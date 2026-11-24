package com.supernova.launcher.search

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.os.SystemClock
import android.os.UserHandle
import com.supernova.launcher.data.LaunchableApp

// App-shortcut results for the async tier. First, for each of the top matched
// apps, its shortcuts whose label contains the query. Then any searchable
// app's shortcut found by its own name, so a shortcut the empty window
// suggests ("Alice", a chat app's contact) can be typed for too (#324).
// Only published shortcuts (dynamic + manifest): pinned-only ones are home
// items, see AppCatalog.appShortcuts. Shortcut queries are only allowed for
// the default home app; the SecurityException thrown otherwise degrades
// silently to no shortcut results.
class ShortcutSearchProvider(
    context: Context,
    private val matchedApps: (String) -> List<LaunchableApp>,
    private val userForSerial: (Long) -> UserHandle?,
    // The apps search may show: a shortcut found by its own name must belong
    // to one of them, never to a hidden app.
    private val searchableApps: () -> List<LaunchableApp> = { emptyList() }
) : SearchProvider {

    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val densityDpi = context.resources.displayMetrics.densityDpi

    override val id: String = ID

    // Every published shortcut by profile, read once per few seconds rather
    // than once per keystroke.
    @Volatile
    private var index: Pair<Long, List<Pair<Long, ShortcutInfo>>>? = null

    override fun search(query: String): List<SearchResult> {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || launcherApps == null) return emptyList()

        val results = mutableListOf<SearchResult>()
        val seen = HashSet<String>()
        for (app in matchedApps(trimmed).take(MAX_APPS)) {
            if (results.size >= MAX_RESULTS) break
            val user = userForSerial(app.userSerial) ?: continue
            val shortcutQuery = LauncherApps.ShortcutQuery()
                .setPackage(app.packageName)
                .setQueryFlags(PUBLISHED)
            val shortcuts = runCatching { launcherApps.getShortcuts(shortcutQuery, user) }
                .getOrNull().orEmpty()
            for (shortcut in shortcuts) {
                if (results.size >= MAX_RESULTS) break
                val label = labelOf(shortcut)
                if (label.isEmpty() || !label.contains(trimmed, ignoreCase = true)) continue
                if (seen.add(keyOf(app.userSerial, shortcut))) results.add(result(app.userSerial, shortcut, label))
            }
        }
        if (results.size < MAX_RESULTS && trimmed.length >= MIN_NAME_QUERY) {
            val apps = searchableApps().mapTo(HashSet()) { "${it.userSerial}/${it.packageName}" }
            shortcutIndex(apps)
                .filter { (serial, shortcut) -> "$serial/${shortcut.`package`}" in apps }
                .mapNotNull { (serial, shortcut) ->
                    val label = labelOf(shortcut)
                    val rank = nameRank(label, trimmed) ?: return@mapNotNull null
                    Triple(rank, serial, shortcut)
                }
                .sortedWith(compareBy({ it.first }, { labelOf(it.third).lowercase() }))
                .forEach { (_, serial, shortcut) ->
                    if (results.size < MAX_RESULTS && seen.add(keyOf(serial, shortcut))) {
                        results.add(result(serial, shortcut, labelOf(shortcut)))
                    }
                }
        }
        return results
    }

    private fun shortcutIndex(apps: Set<String>): List<Pair<Long, ShortcutInfo>> {
        val now = SystemClock.elapsedRealtime()
        index?.let { (at, list) -> if (now - at < INDEX_TTL_MS) return list }
        val serials = apps.mapTo(LinkedHashSet()) { it.substringBefore('/').toLong() }
        val list = serials.flatMap { serial ->
            val user = userForSerial(serial) ?: return@flatMap emptyList()
            val all = LauncherApps.ShortcutQuery().setQueryFlags(PUBLISHED)
            runCatching { launcherApps?.getShortcuts(all, user) }.getOrNull().orEmpty()
                .map { serial to it }
        }
        index = now to list
        return list
    }

    // Icon load happens here, off the main thread in the async tier, so
    // binds only ever draw an already-resolved drawable.
    private fun result(serial: Long, shortcut: ShortcutInfo, label: String) = SearchResult.Shortcut(
        packageName = shortcut.`package`,
        shortcutId = shortcut.id,
        label = label,
        userSerial = serial,
        icon = runCatching { launcherApps?.getShortcutBadgedIconDrawable(shortcut, densityDpi) }.getOrNull()
    )

    private fun labelOf(shortcut: ShortcutInfo): String =
        (shortcut.shortLabel ?: shortcut.longLabel)?.toString().orEmpty()

    private fun keyOf(serial: Long, shortcut: ShortcutInfo) = "$serial/${shortcut.`package`}/${shortcut.id}"

    companion object {
        const val ID = "shortcuts"
        private const val MAX_APPS = 3
        private const val MAX_RESULTS = 6
        private const val MIN_NAME_QUERY = 2
        private const val INDEX_TTL_MS = 10_000L
        private const val PUBLISHED =
            LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST

        // A shortcut found by its own name: 0 when the label starts with the
        // query, 1 when one of its words does, null otherwise. Mid-word hits
        // are left to the app's own shortcuts, or every "a" would match.
        fun nameRank(label: String, query: String): Int? {
            val words = label.split(' ', '-', '_', '.', ',', '(', '/')
            return when {
                label.isEmpty() || query.isEmpty() -> null
                label.startsWith(query, ignoreCase = true) -> 0
                words.any { it.startsWith(query, ignoreCase = true) } -> 1
                else -> null
            }
        }
    }
}
