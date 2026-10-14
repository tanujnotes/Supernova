package com.supernova.launcher.search

import android.content.Context
import android.content.pm.LauncherApps
import android.os.UserHandle
import com.supernova.launcher.data.LaunchableApp

// App-shortcut results for the async tier. For each of the top matched apps,
// queries LauncherApps for dynamic + manifest + pinned shortcuts and keeps
// the ones whose label contains the query. Shortcut queries are only allowed
// for the default home app; the SecurityException thrown otherwise degrades
// silently to no shortcut results.
class ShortcutSearchProvider(
    context: Context,
    private val matchedApps: (String) -> List<LaunchableApp>,
    private val userForSerial: (Long) -> UserHandle?
) : SearchProvider {

    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val densityDpi = context.resources.displayMetrics.densityDpi

    override val id: String = ID

    override fun search(query: String): List<SearchResult> {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || launcherApps == null) return emptyList()

        val results = mutableListOf<SearchResult>()
        for (app in matchedApps(trimmed).take(MAX_APPS)) {
            if (results.size >= MAX_RESULTS) break
            val user = userForSerial(app.userSerial) ?: continue
            val shortcutQuery = LauncherApps.ShortcutQuery()
                .setPackage(app.packageName)
                .setQueryFlags(
                    LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                        LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                        LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
                )
            val shortcuts = runCatching { launcherApps.getShortcuts(shortcutQuery, user) }
                .getOrNull().orEmpty()
            for (shortcut in shortcuts) {
                if (results.size >= MAX_RESULTS) break
                val label = (shortcut.shortLabel ?: shortcut.longLabel)?.toString().orEmpty()
                if (label.isEmpty() || !label.contains(trimmed, ignoreCase = true)) continue
                // Icon load happens here, off the main thread in the async
                // tier, so binds only ever draw an already-resolved drawable.
                val icon = runCatching {
                    launcherApps.getShortcutBadgedIconDrawable(shortcut, densityDpi)
                }.getOrNull()
                results.add(
                    SearchResult.Shortcut(
                        packageName = app.packageName,
                        shortcutId = shortcut.id,
                        label = label,
                        userSerial = app.userSerial,
                        icon = icon
                    )
                )
            }
        }
        return results
    }

    companion object {
        const val ID = "shortcuts"
        private const val MAX_APPS = 3
        private const val MAX_RESULTS = 6
    }
}
