package com.supernova.launcher.ui

import android.appwidget.AppWidgetProviderInfo
import android.graphics.drawable.Drawable
import com.supernova.launcher.data.LaunchableApp
import com.supernova.launcher.data.LauncherItem

// What a drag carries: an item already on the home screen, or something
// new to it from the drawer, search, a drawer folder or the widget sheet.
sealed interface DragPayload {
    // An item that isn't on the home screen yet, picked up from the
    // drawer, search or a drawer folder: it has no place to fly back to,
    // and its drag closes what it was picked from.
    val isNew: Boolean get() = false

    data class ExistingItem(val itemId: String) : DragPayload
    data class DrawerApp(val app: LaunchableApp) : DragPayload {
        override val isNew: Boolean get() = true
    }
    // An app shortcut from search.
    data class DrawerShortcut(
        val packageName: String,
        val shortcutId: String,
        val userSerial: Long,
        val label: String,
        val icon: Drawable?
    ) : DragPayload {
        override val isNew: Boolean get() = true
    }
    // A drawer folder: it lands as a home folder of its own, named and
    // filled as the group is.
    data class DrawerFolder(val name: String, val apps: List<LaunchableApp>) : DragPayload {
        override val isNew: Boolean get() = true
    }
    // A widget dragged out of the add sheet, not yet bound; the spans are
    // the provider's defaults on the current grid.
    data class NewWidget(val info: AppWidgetProviderInfo, val spanX: Int, val spanY: Int) : DragPayload
}

internal fun LauncherItem.toLaunchableApp(): LaunchableApp? {
    val pkg = packageName ?: return null
    val cls = className ?: return null
    return LaunchableApp(pkg, cls, label, userSerial)
}
