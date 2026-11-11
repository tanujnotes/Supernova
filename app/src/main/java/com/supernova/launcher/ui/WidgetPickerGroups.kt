package com.supernova.launcher.ui

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import android.os.UserManager
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
    val shortcuts: List<WidgetPickerEntry.Shortcut>,
    // Tells a work profile's copy of an app from the personal one.
    val key: String = packageName
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
        val key: String,
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
        val spanY: Int,
        // Its app's icon, standing in for a preview the provider has none of.
        val appIcon: Drawable? = null
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

// How the sheet's icons are drawn. The launcher's own: every app icon as it
// shows on the home screen and in the drawer (pack, shape, glass, Edit), and
// any other icon through the same shape and glass. The default leaves them
// as the system hands them over.
class WidgetPickerIcons(
    // An app's icon, by package and profile serial, badged for its profile.
    val app: (packageName: String, userSerial: Long) -> Drawable? = { _, _ -> null },
    // Anything else: a shortcut's own icon, a launcher action tile.
    val render: (Drawable) -> Drawable = { it }
) {
    companion object {
        fun of(catalog: com.supernova.launcher.data.AppCatalog) =
            WidgetPickerIcons(catalog::appIconFor, catalog::renderActionTile)
    }
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
        filter: ((android.appwidget.AppWidgetProviderInfo) -> Boolean)? = null,
        icons: WidgetPickerIcons = WidgetPickerIcons()
    ): List<WidgetPickerGroup> {
        val pm = context.packageManager
        val me = Process.myUserHandle()
        val mySerial = runCatching {
            context.getSystemService(UserManager::class.java)?.getSerialNumberForUser(me)
        }.getOrNull() ?: 0L
        val grid = CellGrid(cellWidthPx, cellHeightPx, cols, rows)
        val providers = AppWidgetManager.getInstance(context)
            .getInstalledProvidersForProfile(me)
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
        val personal = packages.mapNotNull { packageName ->
            val appLabel = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            }.getOrNull() ?: return@mapNotNull null
            val icon = icons.app(packageName, mySerial)
                ?: runCatching { pm.getApplicationIcon(packageName) }.getOrNull()

            val widgets = widgetEntries(context, widgetsByPackage[packageName].orEmpty(), appLabel, icon, grid)

            val shortcuts = shortcutsByPackage[packageName].orEmpty().map { resolve ->
                val activity = resolve.activityInfo
                // A shortcut with no icon of its own wears its app's, as the
                // app's row does; one with its own gets the same shape and
                // glass.
                val own = if (activity.icon != 0) {
                    runCatching { resolve.loadIcon(pm) }.getOrNull()?.let(icons.render)
                } else {
                    null
                }
                WidgetPickerEntry.Shortcut(
                    packageName = packageName,
                    label = runCatching { resolve.loadLabel(pm).toString() }.getOrNull().orEmpty().ifBlank { appLabel },
                    icon = own ?: icon,
                    target = ShortcutTarget.Create(activity.packageName, activity.name)
                )
            }.sortedWith(com.supernova.launcher.data.LabelOrder.by { it.label })

            WidgetPickerGroup(packageName, appLabel, icon, widgets, shortcuts)
        }
        return (personal + workGroups(context, filterPackage, filter, grid, icons))
            .sortedWith(com.supernova.launcher.data.LabelOrder.by { it.label })
    }

    // Widgets from work profiles, with the work badge on the app's name and
    // icon. Paused profiles offer none, and the private space stays out.
    private fun workGroups(
        context: Context,
        filterPackage: String?,
        filter: ((AppWidgetProviderInfo) -> Boolean)?,
        grid: CellGrid,
        icons: WidgetPickerIcons
    ): List<WidgetPickerGroup> {
        val pm = context.packageManager
        val userManager = context.getSystemService(UserManager::class.java)
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        if (userManager == null || launcherApps == null) return emptyList()
        val me = Process.myUserHandle()
        val profiles = runCatching { userManager.userProfiles }.getOrDefault(emptyList()).filter { user ->
            user != me && !isPrivateSpace(launcherApps, user) &&
                !runCatching { userManager.isQuietModeEnabled(user) }.getOrDefault(true)
        }
        return profiles.flatMap { user ->
            val serial = userManager.getSerialNumberForUser(user)
            runCatching { AppWidgetManager.getInstance(context).getInstalledProvidersForProfile(user) }
                .getOrDefault(emptyList())
                .filter { filterPackage == null || it.provider.packageName == filterPackage }
                .filter { filter == null || filter(it) }
                .groupBy { it.provider.packageName }
                .mapNotNull { (packageName, infos) ->
                    val app = runCatching { launcherApps.getApplicationInfo(packageName, 0, user) }.getOrNull()
                        ?: return@mapNotNull null
                    val appLabel = pm.getUserBadgedLabel(app.loadLabel(pm), user).toString()
                    val icon = icons.app(packageName, serial)
                        ?: runCatching { pm.getUserBadgedIcon(app.loadIcon(pm), user) }.getOrNull()
                    val widgets = widgetEntries(context, infos, appLabel, icon, grid)
                    WidgetPickerGroup(packageName, appLabel, icon, widgets, emptyList(), key = "$serial/$packageName")
                }
        }
    }

    private fun isPrivateSpace(launcherApps: LauncherApps, user: android.os.UserHandle): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && runCatching {
            launcherApps.getLauncherUserInfo(user)?.userType == UserManager.USER_TYPE_PROFILE_PRIVATE
        }.getOrDefault(false)

    // The cell size and grid a widget's starting spans are worked out on.
    private class CellGrid(val cellWidthPx: Int, val cellHeightPx: Int, val cols: Int, val rows: Int)

    private fun widgetEntries(
        context: Context,
        infos: List<AppWidgetProviderInfo>,
        appLabel: String,
        appIcon: Drawable?,
        grid: CellGrid
    ): List<WidgetPickerEntry.Widget> = infos.map { info ->
        val spans = WidgetSizing.defaultSpans(context, info, grid.cellWidthPx, grid.cellHeightPx, grid.cols, grid.rows)
        WidgetPickerEntry.Widget(
            info = info,
            label = runCatching { info.loadLabel(context.packageManager) }.getOrNull().orEmpty().ifBlank { appLabel },
            appLabel = appLabel,
            spanX = spans.first,
            spanY = spans.second,
            appIcon = appIcon
        )
    }.sortedWith(com.supernova.launcher.data.LabelOrder.by { it.label })

    // The launcher's own row, listed first: every launcher action plus the Activities picker, as shortcuts.
    fun launcherGroup(context: Context, icons: WidgetPickerIcons): WidgetPickerGroup {
        val renderTile = icons.render
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val hasPrivateSpace = launcherApps != null && runCatching {
            launcherApps.profiles.any { isPrivateSpace(launcherApps, it) }
        }.getOrDefault(false)
        val actions = ActionShortcut.offered(ActionShortcut.choices, hasPrivateSpace).map { action ->
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
            icon = context.getDrawable(R.drawable.ic_action_drawer)?.let(renderTile),
            target = ShortcutTarget.Activities
        )
        return WidgetPickerGroup(
            packageName = context.packageName,
            label = context.getString(R.string.app_name),
            icon = context.getDrawable(R.mipmap.ic_launcher)?.let(renderTile),
            widgets = emptyList(),
            shortcuts = actions + activities
        )
    }
}
