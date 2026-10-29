package com.supernova.launcher.ui

import android.appwidget.AppWidgetProviderInfo
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.supernova.launcher.databinding.SheetWidgetPickerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.Build

// The standalone add-widget sheet (the popup menu's per-app "Widgets" row).
// The home edit mode hosts the same adapter in an in-activity sheet.
object WidgetPickerSheet {

    fun show(
        activity: AppCompatActivity,
        cellWidthPx: Int,
        cellHeightPx: Int,
        cols: Int,
        rows: Int,
        // Non-null narrows the sheet to one app (the popup-menu "Widgets"
        // row); the empty state then means "this app has none".
        filterPackage: String? = null,
        // Narrows the list further (Replace offers search widgets only).
        filter: ((AppWidgetProviderInfo) -> Boolean)? = null,
        // Current prefs, for the drawer surface color the sheet wears.
        prefs: com.supernova.launcher.data.LauncherPrefs,
        onPicked: (AppWidgetProviderInfo) -> Unit,
        onPickShortcut: ((ShortcutTarget) -> Unit)? = null
    ) {
        val binding = SheetWidgetPickerBinding.inflate(LayoutInflater.from(activity))
        val dialog = BottomSheetDialog(activity)
        dialog.setContentView(binding.root)
        // Drawer surface on the sheet, and the dialog's nav bar painted to
        // match so the sheet appears to run under it. Opaque, whatever the
        // drawer's transparency: the home screen showing through made the
        // list unreadable.
        val surface = androidx.core.graphics.ColorUtils.setAlphaComponent(
            com.supernova.launcher.ui.theme.Styles.resolve(activity, prefs)
                .of(com.supernova.launcher.ui.theme.SurfaceId.DRAWER).surface,
            OPAQUE
        )
        (binding.root.background?.mutate() as? android.graphics.drawable.GradientDrawable)?.setColor(surface)
        dialog.window?.let { window ->
            // Contrast scrim control is Android 10+; Android 9 keeps its default.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
            window.navigationBarColor = surface
        }

        val adapter = WidgetPickerAdapter(
            onPickWidget = { info ->
                dialog.dismiss()
                onPicked(info)
            },
            onPickShortcut = { target ->
                dialog.dismiss()
                onPickShortcut?.invoke(target)
            }
        )
        binding.widgetPickerRecycler.layoutManager = LinearLayoutManager(activity)
        binding.widgetPickerRecycler.adapter = adapter
        binding.widgetPickerRecycler.itemAnimator = null

        // A single-app picker has nothing to browse; hide search and start
        // expanded. The general picker is searchable and starts collapsed.
        val singleApp = filterPackage != null
        binding.widgetSearch.visibility = if (singleApp) View.GONE else View.VISIBLE
        binding.widgetSearch.doAfterTextChanged { adapter.filter(it?.toString().orEmpty()) }

        activity.lifecycleScope.launch {
            val groups = withContext(Dispatchers.Default) {
                val apps = WidgetPickerGroups.build(
                    activity, cellWidthPx, cellHeightPx, cols, rows, filterPackage, filter
                )
                // The launcher's own actions are not search widgets either.
                if (singleApp || filter != null) apps
                else listOf(WidgetPickerGroups.launcherGroup(activity)) + apps
            }
            if (groups.isEmpty()) {
                binding.widgetPickerEmpty.visibility = View.VISIBLE
                binding.widgetSearch.visibility = View.GONE
            }
            adapter.submit(groups, expandAll = singleApp)
        }

        dialog.show()
    }

    private const val OPAQUE = 0xFF
}
