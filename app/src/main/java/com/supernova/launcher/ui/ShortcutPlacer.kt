package com.supernova.launcher.ui

import android.app.Activity
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.os.UserManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.supernova.launcher.R
import com.supernova.launcher.data.GalleryIconCodec
import com.supernova.launcher.data.LaunchableApp
import com.supernova.launcher.data.WorkspaceGeometry
import com.supernova.launcher.gesture.ActionShortcut
import com.supernova.launcher.runLogged
import com.supernova.launcher.ui.settings.EnumLabels

// A shortcut picked from the add sheet, placed as an icon: one an app makes
// on its own shortcut screen, a launcher action, or any exported activity of
// an app. A tapped row's icon goes on the first free cell of the page in
// view, a dragged row's on the cell it was dropped on. A field of the
// activity, so the shortcut screen's result is registered before it starts.
class ShortcutPlacer(
    private val activity: ComponentActivity,
    private val viewModel: () -> LauncherViewModel
) {
    // Where a row's result lands: the cell a drag dropped it on (with
    // subgrid, the exact spot) and the neighbours the drop moved aside, or
    // for a tap the page's first free cell.
    data class DropCell(
        val page: Int,
        val cellX: Int,
        val cellY: Int,
        val workspaceX: Float? = null,
        val workspaceY: Float? = null,
        val displace: List<WorkspaceGeometry.ReorderMove> = emptyList()
    )

    private val createShortcutLauncher =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            // The cell goes with the screen it was kept for, answered or not.
            val cell = viewModel().pendingShortcutCell
            viewModel().pendingShortcutCell = null
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.let { handleCreateShortcutResult(it, cell) }
            }
        }

    // A shortcut row from the add sheet: an app's CREATE_SHORTCUT flow, a
    // launcher action placed as an icon, or the any-activity picker. cell:
    // where a dragged row was dropped; null for a tap.
    fun pick(target: ShortcutTarget, cell: DropCell? = null) {
        when (target) {
            is ShortcutTarget.Create -> {
                val intent = Intent(Intent.ACTION_CREATE_SHORTCUT)
                    .setClassName(target.packageName, target.activityName)
                // Kept in the view model: a recreate while the app's screen is
                // up still places its answer where the row was dropped.
                viewModel().pendingShortcutCell = cell
                runLogged("open shortcut creator") { createShortcutLauncher.launch(intent) }
                    .onFailure {
                        viewModel().pendingShortcutCell = null
                        Toast.makeText(activity, R.string.launch_failed, Toast.LENGTH_SHORT).show()
                    }
            }
            is ShortcutTarget.Action -> addIntentItem(
                ActionShortcut.encode(target.action),
                activity.getString(EnumLabels.of(target.action)),
                iconBase64 = null,
                cell
            )
            ShortcutTarget.Activities -> showActivitiesPicker(cell)
        }
    }

    // The dropped cell, or for a tap the first free cell of the page in view;
    // null, with a toast, when that page is full.
    private fun landing(cell: DropCell?): DropCell? {
        if (cell != null) return cell
        val page = viewModel().state.value.currentPage
        val free = viewModel().firstFreeWorkspaceCell(page, 1, 1)
        if (free == null) Toast.makeText(activity, R.string.no_room_on_page, Toast.LENGTH_SHORT).show()
        return free?.let { DropCell(page, it.first, it.second) }
    }

    private fun addIntentItem(intentUri: String, label: String, iconBase64: String?, cell: DropCell?) {
        val at = landing(cell) ?: return
        viewModel().addIntentItemToWorkspace(
            intentUri, label, iconBase64, at.page, at.cellX, at.cellY, at.workspaceX, at.workspaceY, at.displace
        )
    }

    // Any exported activity of any installed app as an icon: the launch
    // intent is stored as an intentUri and the icon is snapshotted.
    private fun showActivitiesPicker(cell: DropCell?) {
        ActivitiesPickerSheet.show(activity, WidgetPickerIcons.of(viewModel().appCatalog())) { pick ->
            val intent = Intent()
                .setComponent(pick.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val icon = runCatching { activity.packageManager.getActivityIcon(pick.component) }.getOrNull()
            addIntentItem(
                intent.toUri(Intent.URI_INTENT_SCHEME), pick.label, icon?.let { GalleryIconCodec.encode(it) }, cell
            )
        }
    }

    private fun handleCreateShortcutResult(data: Intent, cell: DropCell?) {
        // Newer apps return a pinnable shortcut request; accepting it pins the
        // shortcut to us, so it launches reliably through startShortcut.
        val launcherApps = activity.getSystemService(LauncherApps::class.java)
        val pin = runCatching { launcherApps?.getPinItemRequest(data) }.getOrNull()
        if (pin != null && pin.isValid &&
            pin.requestType == LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT
        ) {
            val shortcut = pin.shortcutInfo
            if (shortcut != null && pin.accept()) {
                val label = (shortcut.shortLabel ?: shortcut.longLabel ?: shortcut.`package`).toString()
                val icon = runCatching {
                    launcherApps.getShortcutIconDrawable(shortcut, activity.resources.displayMetrics.densityDpi)
                }.getOrNull()
                val icon64 = icon?.let { GalleryIconCodec.encode(it) }
                val serial = activity.getSystemService(UserManager::class.java)
                    ?.getSerialNumberForUser(shortcut.userHandle) ?: 0L
                val owner = LaunchableApp(shortcut.`package`, shortcut.activity?.className.orEmpty(), label, serial)
                addPinnedShortcut(owner, shortcut.id, label, icon64, cell)
            }
            return
        }

        // Legacy result: an intent to fire plus a name and an optional icon.
        @Suppress("DEPRECATION")
        val shortcutIntent = data.getParcelableExtra<Intent>(Intent.EXTRA_SHORTCUT_INTENT) ?: return
        val name = data.getStringExtra(Intent.EXTRA_SHORTCUT_NAME).orEmpty()
        addIntentItem(
            shortcutIntent.toUri(Intent.URI_INTENT_SCHEME), name, extractLegacyShortcutIcon(data), cell
        )
    }

    private fun addPinnedShortcut(
        owner: LaunchableApp,
        shortcutId: String,
        label: String,
        iconBase64: String?,
        cell: DropCell?
    ) {
        val at = landing(cell) ?: return
        viewModel().addShortcutToWorkspace(
            owner, shortcutId, label, at.page, at.cellX, at.cellY, at.workspaceX, at.workspaceY,
            iconBase64 = iconBase64, displace = at.displace, mergeFolders = false
        )
    }

    // The other app names its icon; there is no id of ours to look it up by.
    @android.annotation.SuppressLint("DiscouragedApi")
    @Suppress("DEPRECATION")
    private fun extractLegacyShortcutIcon(data: Intent): String? {
        data.getParcelableExtra<Bitmap>(Intent.EXTRA_SHORTCUT_ICON)?.let {
            return GalleryIconCodec.encode(it)
        }
        val iconRes = data.getParcelableExtra<Intent.ShortcutIconResource>(Intent.EXTRA_SHORTCUT_ICON_RESOURCE)
            ?: return null
        val drawable = runCatching {
            val res = activity.packageManager.getResourcesForApplication(iconRes.packageName)
            val id = res.getIdentifier(iconRes.resourceName, null, null)
            if (id != 0) androidx.core.content.res.ResourcesCompat.getDrawable(res, id, null) else null
        }.getOrNull() ?: return null
        return GalleryIconCodec.encode(drawable)
    }
}
