package com.supernova.launcher

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.LauncherApps.PinItemRequest
import android.os.Bundle
import android.os.UserManager
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.supernova.launcher.widget.SupernovaWidgetHost
import com.supernova.launcher.data.WorkspaceGeometry
import com.supernova.launcher.widget.WidgetSizing
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import com.supernova.launcher.ui.theme.ThemeEngine
import com.supernova.launcher.ui.theme.AccentColor

// Handles "add to home screen" requests from other apps. The system routes
// these here only while Supernova is the default launcher.
class PinRequestActivity : AppCompatActivity() {

    private val repository get() = appGraph.repository
    private val preferences get() = appGraph.preferences
    // The launcher's own host: a widget id allocated under any other would
    // never receive its updates in the launcher.
    private val widgetHost by lazy { AppWidgetHost(applicationContext, SupernovaWidgetHost.HOST_ID) }

    // A widget waiting on the system's bind dialog, and the spans it will
    // take. Kept across a recreate: the dialog's answer can come back to a
    // new instance of this screen.
    private var pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var pendingSpanX = 2
    private var pendingSpanY = 2

    private val bindLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val widgetId = pendingWidgetId
        pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
        val request = pinRequest()
        val info = request?.getAppWidgetProviderInfo(this)
        if (result.resultCode == RESULT_OK && request != null && info != null &&
            widgetId != AppWidgetManager.INVALID_APPWIDGET_ID
        ) {
            acceptWidget(request, info, widgetId, pendingSpanX, pendingSpanY)
        } else {
            if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) widgetHost.deleteAppWidgetId(widgetId)
            finish()
        }
    }

    private fun pinRequest(): PinItemRequest? =
        getSystemService(LauncherApps::class.java)?.getPinItemRequest(intent)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The accent overlay is what sets colorAccent, which the dialog's
        // colours resolve through: without it MaterialAlertDialogBuilder
        // found no theme colours and this screen crashed on open.
        ThemeEngine.applyAccent(this, AccentColor.fromName(runBlocking { preferences.flow.first() }.accentColor))

        if (savedInstanceState != null) {
            pendingWidgetId = savedInstanceState.getInt(KEY_PENDING_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            pendingSpanX = savedInstanceState.getInt(KEY_SPAN_X, 2)
            pendingSpanY = savedInstanceState.getInt(KEY_SPAN_Y, 2)
            // The bind dialog's answer is on its way to bindLauncher.
            if (pendingWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) return
        }

        val request = pinRequest()
        if (request == null || !request.isValid) {
            finish()
            return
        }

        when (request.requestType) {
            PinItemRequest.REQUEST_TYPE_SHORTCUT -> confirmShortcut(request)
            PinItemRequest.REQUEST_TYPE_APPWIDGET -> confirmWidget(request)
            else -> finish()
        }
    }

    private fun confirmShortcut(request: PinItemRequest) {
        val shortcut = request.shortcutInfo
        if (shortcut == null) {
            finish()
            return
        }
        val label = shortcut.shortLabel ?: shortcut.longLabel ?: shortcut.`package`

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.pin_shortcut_title, label))
            .setPositiveButton(R.string.add_to_home) { _, _ ->
                if (request.accept()) {
                    // The shortcut's own picture (a contact's photo, say), as
                    // the create-shortcut path keeps it; without it the icon
                    // fell back to the app's.
                    val icon = runCatching {
                        getSystemService(LauncherApps::class.java)
                            ?.getShortcutIconDrawable(shortcut, resources.displayMetrics.densityDpi)
                    }.getOrNull()
                    val icon64 = icon?.let { com.supernova.launcher.data.GalleryIconCodec.encode(it) }
                    lifecycleScope.launch {
                        val placement = findPlacement(spanX = 1, spanY = 1)
                        repository.addShortcutToWorkspace(
                            app = com.supernova.launcher.data.LaunchableApp(
                                packageName = shortcut.`package`,
                                className = shortcut.activity?.className.orEmpty(),
                                label = label.toString(),
                                // A work app's shortcut opens in the work
                                // profile; saved as personal it failed to.
                                userSerial = getSystemService(UserManager::class.java)
                                    ?.getSerialNumberForUser(shortcut.userHandle) ?: 0L
                            ),
                            shortcutId = shortcut.id,
                            label = label.toString(),
                            page = placement.page,
                            cellX = placement.cellX,
                            cellY = placement.cellY,
                            cols = placement.cols,
                            rows = placement.rows,
                            iconBase64 = icon64,
                            // The cell is free by the items' real positions. A
                            // merge goes by stored cells, stale on a free-form
                            // icon, and folded the new icon into a folder.
                            mergeFolders = false
                        )
                        finish()
                    }
                } else {
                    finish()
                }
            }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun confirmWidget(request: PinItemRequest) {
        val info = request.getAppWidgetProviderInfo(this)
        if (info == null) {
            finish()
            return
        }
        val label = info.loadLabel(packageManager).orEmpty().ifBlank { getString(R.string.widget) }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.pin_widget_title, label))
            .setPositiveButton(R.string.add_to_home) { _, _ -> lifecycleScope.launch { bindWidget(request, info) } }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    // Bind, then accept. Accepting only hands the id back to the app that
    // asked; the widget is only live once bound to its provider, and a
    // pinned widget used to stay an empty placeholder for good. Binding
    // needs the user's say-so once per launcher, through the system's
    // dialog, unless it was given before.
    private suspend fun bindWidget(request: PinItemRequest, info: AppWidgetProviderInfo) {
        val prefs = preferences.flow.first()
        val (cellWidth, cellHeight) = estimatedCellSizePx(prefs.gridCols, prefs.gridRows, prefs.desktopPaddingH, prefs.desktopPaddingV)
        val (spanX, spanY) = WidgetSizing.defaultSpans(this, info, cellWidth, cellHeight, prefs.gridCols, prefs.gridRows)
        val widgetId = widgetHost.allocateAppWidgetId()
        val options = WidgetSizing.initialBindOptions(spanX, spanY, cellWidth, cellHeight, resources.displayMetrics.density)
        val bound = runCatching {
            AppWidgetManager.getInstance(this).bindAppWidgetIdIfAllowed(widgetId, info.profile, info.provider, options)
        }.getOrDefault(false)
        if (bound) {
            acceptWidget(request, info, widgetId, spanX, spanY)
            return
        }
        pendingWidgetId = widgetId
        pendingSpanX = spanX
        pendingSpanY = spanY
        val launched = runCatching {
            bindLauncher.launch(
                Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
                }
            )
        }.isSuccess
        if (!launched) {
            pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
            widgetHost.deleteAppWidgetId(widgetId)
            finish()
        }
    }

    private fun acceptWidget(request: PinItemRequest, info: AppWidgetProviderInfo, widgetId: Int, spanX: Int, spanY: Int) {
        val extras = Bundle().apply { putInt(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId) }
        if (!request.isValid || !runCatching { request.accept(extras) }.getOrDefault(false)) {
            widgetHost.deleteAppWidgetId(widgetId)
            finish()
            return
        }
        val label = info.loadLabel(packageManager).orEmpty().ifBlank { getString(R.string.widget) }
        lifecycleScope.launch {
            val placement = findPlacement(spanX, spanY)
            repository.addWidget(
                page = placement.page,
                cellX = placement.cellX,
                cellY = placement.cellY,
                cols = placement.cols,
                rows = placement.rows,
                appWidgetId = widgetId,
                provider = info.provider?.flattenToString().orEmpty(),
                label = label,
                spanX = spanX,
                spanY = spanY
            )
            finish()
        }
    }

    // This screen has no workspace to measure, so a cell is estimated from
    // the window: the grid across the width, and down the part of the
    // height the pages get (the status bar, dock and search bar take the
    // rest). Only the choice of spans rests on it.
    private fun estimatedCellSizePx(cols: Int, rows: Int, paddingHDp: Int, paddingVDp: Int): Pair<Int, Int> {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels - 2 * paddingHDp * metrics.density
        val height = metrics.heightPixels * PAGE_HEIGHT_SHARE - 2 * paddingVDp * metrics.density
        return (width / cols.coerceAtLeast(1)).toInt().coerceAtLeast(1) to
            (height / rows.coerceAtLeast(1)).toInt().coerceAtLeast(1)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_PENDING_ID, pendingWidgetId)
        outState.putInt(KEY_SPAN_X, pendingSpanX)
        outState.putInt(KEY_SPAN_Y, pendingSpanY)
    }

    private data class Placement(val page: Int, val cellX: Int, val cellY: Int, val cols: Int, val rows: Int)

    // Span-aware free area, searching the default page first and then the
    // rest; the repository relocates again as a backstop if things moved.
    // With every page full a new page takes it: the request is already
    // accepted, and the repository drops an item that has no room.
    private suspend fun findPlacement(spanX: Int, spanY: Int): Placement {
        val model = repository.modelFlow.first()
        val prefs = preferences.flow.first()
        val defaultPage = model.defaultPage.coerceIn(0, (model.pageCount - 1).coerceAtLeast(0))
        val pages = listOf(defaultPage) + (0 until model.pageCount).filterNot { it == defaultPage }

        for (page in pages) {
            val cell = WorkspaceGeometry.findFreeCell(model, page, spanX, spanY, prefs.gridCols, prefs.gridRows)
            if (cell != null) {
                return Placement(page, cell.first, cell.second, prefs.gridCols, prefs.gridRows)
            }
        }
        repository.addPage()
        val newPage = (repository.modelFlow.first().pageCount - 1).coerceAtLeast(0)
        return Placement(newPage, 0, 0, prefs.gridCols, prefs.gridRows)
    }

    private companion object {
        const val KEY_PENDING_ID = "pending_widget_id"
        const val KEY_SPAN_X = "pending_span_x"
        const val KEY_SPAN_Y = "pending_span_y"
        const val PAGE_HEIGHT_SHARE = 0.72f
    }
}
