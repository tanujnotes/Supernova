package com.supernova.launcher.ui

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Process
import com.supernova.launcher.R
import com.supernova.launcher.gesture.ActionShortcut
import com.supernova.launcher.gesture.GestureAction
import com.supernova.launcher.ui.settings.EnumLabels
import com.supernova.launcher.widget.WidgetSizing

// One app's worth of addable things: its widgets and its
// CREATE_SHORTCUT shortcuts together under one collapsible row.
data class WidgetPickerGroup(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val widgets: List<WidgetPickerEntry.Widget>,
    val shortcuts: List<WidgetPickerEntry.Shortcut>
)

// What tapping a shortcut row does.
sealed interface ShortcutTarget {
    // An app's CREATE_SHORTCUT activity (direct dial, a settings page, ...).
    data class Create(val packageName: String, val activityName: String) : ShortcutTarget

    // One of the launcher's own actions placed as a home icon.
    data class Action(val action: GestureAction) : ShortcutTarget

    // Opens the any-activity picker.
    object Activities : ShortcutTarget
}

sealed interface WidgetPickerEntry {
    // A collapsible app row: icon, name, "N widgets, M shortcuts".
    data class Header(
        val packageName: String,
        val label: String,
        val icon: Drawable?,
        val widgetCount: Int,
        val shortcutCount: Int,
        val expanded: Boolean
    ) : WidgetPickerEntry

    data class Widget(
        val info: AppWidgetProviderInfo,
        val label: String,
        val appLabel: String,
        val spanX: Int,
        val spanY: Int
    ) : WidgetPickerEntry

    // Two widgets side by side - a two-column preview grid.
    data class WidgetRow(val first: Widget, val second: Widget?) : WidgetPickerEntry

    data class Shortcut(
        val packageName: String,
        val label: String,
        val icon: Drawable?,
        val target: ShortcutTarget
    ) : WidgetPickerEntry
}

object WidgetPickerGroups {

    // Every installed app that offers widgets and/or shortcuts, grouped by
    // package and sorted by label. Runs off the main thread (binder + icons).
    fun build(
        context: Context,
        cellWidthPx: Int,
        cellHeightPx: Int,
        cols: Int,
        rows: Int,
        filterPackage: String? = null,
        filter: ((android.appwidget.AppWidgetProviderInfo) -> Boolean)? = null
    ): List<WidgetPickerGroup> {
        val pm = context.packageManager
        val providers = AppWidgetManager.getInstance(context)
            .getInstalledProvidersForProfile(Process.myUserHandle())
            .filter { filterPackage == null || it.provider.packageName == filterPackage }
            .filter { filter == null || filter(it) }

        val widgetsByPackage = providers.groupBy { it.provider.packageName }

        val shortcutsByPackage = runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_CREATE_SHORTCUT), 0)
        }.getOrDefault(emptyList())
            .filter { filterPackage == null || it.activityInfo.packageName == filterPackage }
            .filter { filter == null }
            .groupBy { it.activityInfo.packageName }

        val packages = (widgetsByPackage.keys + shortcutsByPackage.keys).distinct()
        return packages.mapNotNull { packageName ->
            val appLabel = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            }.getOrNull() ?: return@mapNotNull null
            val icon = runCatching { pm.getApplicationIcon(packageName) }.getOrNull()

            val widgets = widgetsByPackage[packageName].orEmpty().map { info ->
                val spans = WidgetSizing.defaultSpans(context, info, cellWidthPx, cellHeightPx, cols, rows)
                WidgetPickerEntry.Widget(
                    info = info,
                    label = runCatching { info.loadLabel(pm) }.getOrNull().orEmpty().ifBlank { appLabel },
                    appLabel = appLabel,
                    spanX = spans.first,
                    spanY = spans.second
                )
            }.sortedWith(com.supernova.launcher.data.LabelOrder.by { it.label })

            val shortcuts = shortcutsByPackage[packageName].orEmpty().map { resolve ->
                val activity = resolve.activityInfo
                WidgetPickerEntry.Shortcut(
                    packageName = packageName,
                    label = runCatching { resolve.loadLabel(pm).toString() }.getOrNull().orEmpty().ifBlank { appLabel },
                    icon = runCatching { resolve.loadIcon(pm) }.getOrNull() ?: icon,
                    target = ShortcutTarget.Create(activity.packageName, activity.name)
                )
            }.sortedWith(com.supernova.launcher.data.LabelOrder.by { it.label })

            WidgetPickerGroup(packageName, appLabel, icon, widgets, shortcuts)
        }.sortedWith(com.supernova.launcher.data.LabelOrder.by { it.label })
    }

    // The launcher's own row, listed first: every launcher action plus the Activities picker, as shortcuts.
    fun launcherGroup(context: Context, renderTile: (Drawable) -> Drawable): WidgetPickerGroup {
        val actions = ActionShortcut.choices.map { action ->
            WidgetPickerEntry.Shortcut(
                packageName = context.packageName,
                label = context.getString(EnumLabels.of(action)),
                icon = context.getDrawable(ActionIcons.of(action))?.let(renderTile),
                target = ShortcutTarget.Action(action)
            )
        }
        val activities = WidgetPickerEntry.Shortcut(
            packageName = context.packageName,
            label = context.getString(R.string.add_category_activities),
            icon = context.getDrawable(R.drawable.ic_action_drawer),
            target = ShortcutTarget.Activities
        )
        return WidgetPickerGroup(
            packageName = context.packageName,
            label = context.getString(R.string.app_name),
            icon = context.getDrawable(R.drawable.ic_supernova),
            widgets = emptyList(),
            shortcuts = actions + activities
        )
    }
}
