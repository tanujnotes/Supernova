package com.supernova.launcher.ui

import android.graphics.drawable.Drawable
import com.supernova.launcher.search.AppSearchProvider

// "Suggested shortcuts": the first shortcuts of the most-launched
// apps, shown as icon+label pills on the feed page and in the search window.
// Launchers are left out: trying one out racks up launches, and its
// shortcuts ("Launcher settings", "Set launcher") are no use from inside
// another home app.
object SuggestedShortcuts {

    class Pill(
        val label: String,
        val icon: Drawable?,
        val packageName: String,
        val shortcutId: String,
        val userSerial: Long
    ) {
        // The gesture LAUNCH_SHORTCUT payload encoding.
        val target: String get() = "$packageName|$shortcutId@$userSerial"
    }

    fun pills(viewModel: LauncherViewModel, max: Int = MAX_PILLS): List<Pill> {
        val counts = viewModel.launchCounts()
        val catalog = viewModel.appCatalog()
        val launchers = catalog.homePackages()
        val topApps = viewModel.visibleApps()
            .filterNot { it.packageName in launchers }
            .sortedByDescending { counts[AppSearchProvider.countKey(it)] ?: 0 }
            .take(8)
        return buildList {
            for (app in topApps) {
                if (size >= max) break
                catalog.shortcutsFor(app.packageName, app.userSerial).take(2).forEach { shortcut ->
                    if (size < max) {
                        add(
                            Pill(
                                label = (shortcut.shortLabel ?: shortcut.longLabel ?: app.label).toString(),
                                icon = catalog.shortcutIcon(shortcut) ?: catalog.iconFor(app),
                                packageName = app.packageName,
                                shortcutId = shortcut.id,
                                userSerial = app.userSerial
                            )
                        )
                    }
                }
            }
        }
    }

    private const val MAX_PILLS = 12
}
