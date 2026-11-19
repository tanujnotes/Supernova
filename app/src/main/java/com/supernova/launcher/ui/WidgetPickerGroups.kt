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
import com.supernova.launcher.widget.WidgetFeatures
import com.supernova.launcher.widget.WidgetSizing

// One app's worth of addable things: its widgets and its
// CREATE_SHORTCUT shortcuts together under one collapsible row. The row
// itself is cheap; its contents read every widget's and shortcut's name,
// icon and size out of the app's own files, so they are only worked out
// (off the main thread) once the app is opened or searched.
class WidgetPickerGroup(
    // The app's package; "serial/package" for a work profile's copy.
    val key: String,
    val label: String,
    val icon: Drawable?,
    val widgetCount: Int,
    val shortcutCount: Int,
    // Works the contents out; slow, never call it on the main thread.
    val load: () -> WidgetPickerContents
)

class WidgetPickerContents(
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

    // Whether an app has anything for the picker: a listable widget in any
    // profile, or a shortcut activity. The long-press "Widgets" row shows
    // only then, where it opened a blank sheet before.
    fun offersAny(context: Context, packageName: String): Boolean {
        val widgets = runCatching {
            AppWidgetManager.getInstance(context).getInstalledProvidersForPackage(packageName, null)
        }.getOrDefault(emptyList())
        if (widgets.any(WidgetFeatures::listable)) return true
        return runCatching {
            context.packageManager.queryIntentActivities(
                Intent(Intent.ACTION_CREATE_SHORTCUT).setPackage(packageName), 0
            ).isNotEmpty()
        }.getOrDefault(false)
    }

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
            .filter(WidgetFeatures::listable)
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
            val infos = widgetsByPackage[packageName].orEmpty()
            val resolves = shortcutsByPackage[packageName].orEmpty()
            WidgetPickerGroup(packageName, appLabel, icon, infos.size, resolves.size) {
                WidgetPickerContents(
                    widgetEntries(context, infos, appLabel, icon, grid),
                    shortcutEntries(pm, appLabel, icon, resolves, icons)
                )
            }
        }
        return (personal + workGroups(context, filterPackage, filter, grid, icons))
            .sortedWith(com.supernova.launcher.data.LabelOrder.by { it.label })
    }

    private fun shortcutEntries(
        pm: android.content.pm.PackageManager,
        appLabel: String,
        icon: Drawable?,
        resolves: List<android.content.pm.ResolveInfo>,
        icons: WidgetPickerIcons
    ): List<WidgetPickerEntry.Shortcut> = resolves.map { resolve ->
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
            packageName = activity.packageName,
            label = runCatching { resolve.loadLabel(pm).toString() }.getOrNull().orEmpty().ifBlank { appLabel },
            icon = own ?: icon,
            target = ShortcutTarget.Create(activity.packageName, activity.name)
        )
    }.sortedWith(com.supernova.launcher.data.LabelOrder.by { it.label })

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
                .filter(WidgetFeatures::listable)
                .filter { filterPackage == null || it.provider.packageName == filterPackage }
                .filter { filter == null || filter(it) }
                .groupBy { it.provider.packageName }
                .mapNotNull { (packageName, infos) ->
                    val app = runCatching { launcherApps.getApplicationInfo(packageName, 0, user) }.getOrNull()
                        ?: return@mapNotNull null
                    val appLabel = pm.getUserBadgedLabel(app.loadLabel(pm), user).toString()
                    val icon = icons.app(packageName, serial)
                        ?: runCatching { pm.getUserBadgedIcon(app.loadIcon(pm), user) }.getOrNull()
                    WidgetPickerGroup("$serial/$packageName", appLabel, icon, infos.size, 0) {
                        WidgetPickerContents(widgetEntries(context, infos, appLabel, icon, grid), emptyList())
                    }
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
        val offered = ActionShortcut.offered(ActionShortcut.choices, hasPrivateSpace)
        // Only the row's own icon is drawn up front, so this row is ready
        // to show the moment the sheet opens; the action tiles are drawn
        // when it is opened.
        return WidgetPickerGroup(
            key = context.packageName,
            label = context.getString(R.string.app_name),
            icon = context.getDrawable(R.mipmap.ic_launcher)?.let(renderTile),
            widgetCount = 0,
            shortcutCount = offered.size + 1,
        ) {
            val actions = offered.map { action ->
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
            WidgetPickerContents(emptyList(), actions + activities)
        }
    }
}
