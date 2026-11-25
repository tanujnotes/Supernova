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
// silently to no shortcut results. Each row names its app (#361): a chat
// often comes from several apps under the same name.
class ShortcutSearchProvider(
    context: Context,
    private val matchedApps: (String) -> List<LaunchableApp>,
    private val userForSerial: (Long) -> UserHandle?,
    // The apps search may show: a shortcut found by its own name must belong
    // to one of them, never to a hidden app.
    private val searchableApps: () -> List<LaunchableApp> = { emptyList() },
    // SMS apps other than the phone's SMS app (SmsApps.others): their chats
    // are left out (#361).
    private val otherSmsApps: () -> Set<String> = { emptySet() }
) : SearchProvider {

    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val densityDpi = context.resources.displayMetrics.densityDpi

    override val id: String = ID

    // Every published shortcut by profile, read once per few seconds rather
    // than once per keystroke.
    @Volatile
    private var index: Pair<Long, List<Pair<Long, ShortcutInfo>>>? = null

    @Volatile
    private var smsApps: Pair<Long, Set<String>>? = null

    // What one query may show, read once per query.
    private class Scope(
        val apps: Set<String>,
        val activities: Set<String>,
        val names: Map<String, String>,
        val otherSmsApps: Set<String>
    )

    override fun search(query: String): List<SearchResult> {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || launcherApps == null) return emptyList()

        val results = mutableListOf<SearchResult>()
        val seen = HashSet<String>()
        val searchable = searchableApps()
        val scope = Scope(
            apps = searchable.mapTo(HashSet(), ::appKey),
            activities = searchable.mapTo(HashSet(), ::activityKey),
            names = searchable.reversed().associate { appKey(it) to it.label },
            otherSmsApps = cachedSmsApps()
        )
        for (app in matchedApps(trimmed).take(MAX_APPS)) {
            if (results.size >= MAX_RESULTS) break
            val user = userForSerial(app.userSerial) ?: continue
            val shortcutQuery = LauncherApps.ShortcutQuery()
                .setPackage(app.packageName)
                .setQueryFlags(PUBLISHED)
            val shortcuts = runCatching { launcherApps.getShortcuts(shortcutQuery, user) }
                .getOrNull().orEmpty()
                .filter {
                    isShown(app.userSerial, it, scope.apps + appKey(app), scope.activities + activityKey(app)) &&
                        !hidesChat(it.`package`, it.isDeclaredInManifest, scope.otherSmsApps)
                }
            for (shortcut in shortcuts) {
                if (results.size >= MAX_RESULTS) break
                val label = labelOf(shortcut)
                if (label.isEmpty() || !label.contains(trimmed, ignoreCase = true)) continue
                if (seen.add(keyOf(app.userSerial, shortcut))) {
                    results.add(result(app.userSerial, shortcut, label, app.label))
                }
            }
        }
        if (results.size < MAX_RESULTS && trimmed.length >= MIN_NAME_QUERY) {
            shortcutIndex(scope.apps)
                .filter { (serial, shortcut) ->
                    isShown(serial, shortcut, scope.apps, scope.activities) &&
                        !hidesChat(shortcut.`package`, shortcut.isDeclaredInManifest, scope.otherSmsApps)
                }
                .mapNotNull { (serial, shortcut) ->
                    val label = labelOf(shortcut)
                    val rank = nameRank(label, trimmed) ?: return@mapNotNull null
                    Triple(rank, serial, shortcut)
                }
                .sortedWith(compareBy({ it.first }, { labelOf(it.third).lowercase() }))
                .forEach { (_, serial, shortcut) ->
                    if (results.size < MAX_RESULTS && seen.add(keyOf(serial, shortcut))) {
                        val appName = scope.names["$serial/${shortcut.`package`}"]
                        results.add(result(serial, shortcut, labelOf(shortcut), appName))
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
    private fun result(serial: Long, shortcut: ShortcutInfo, label: String, appName: String?) =
        SearchResult.Shortcut(
            packageName = shortcut.`package`,
            shortcutId = shortcut.id,
            label = label,
            userSerial = serial,
            icon = runCatching { launcherApps?.getShortcutBadgedIconDrawable(shortcut, densityDpi) }.getOrNull(),
            appName = appName
        )

    private fun cachedSmsApps(): Set<String> {
        val now = SystemClock.elapsedRealtime()
        smsApps?.let { (at, set) -> if (now - at < INDEX_TTL_MS) return set }
        return otherSmsApps.invoke().also { smsApps = now to it }
    }

    private fun isShown(serial: Long, shortcut: ShortcutInfo, apps: Set<String>, activities: Set<String>) =
        isShown(serial, shortcut.`package`, shortcut.activity?.className, apps, activities)

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

        private fun appKey(app: LaunchableApp) = "${app.userSerial}/${app.packageName}"

        private fun activityKey(app: LaunchableApp) = "${app.userSerial}/${app.packageName}/${app.className}"

        // Whether a shortcut belongs to an app search may show (apps: serial/
        // package keys), never to a hidden one. A shortcut tied to an activity
        // must be that app's listed activity (activities: serial/package/class
        // keys): apps that change their icon through activity-aliases declare
        // the same shortcuts on every alias, and YouTube came up four times
        // for "Search" (#361).
        fun isShown(
            serial: Long,
            packageName: String,
            activityClass: String?,
            apps: Set<String>,
            activities: Set<String>
        ): Boolean = if (activityClass == null) {
            "$serial/$packageName" in apps
        } else {
            "$serial/$packageName/$activityClass" in activities
        }

        // Whether a shortcut is a chat from an SMS app that isn't the phone's
        // SMS app (otherSmsApps). Every SMS app installed publishes its own
        // shortcut for the same chat, and an unused one's is stale: it opens
        // an old copy of the thread, then drops to its list (#361). The
        // shortcuts the app declares in its manifest ("New conversation")
        // stay.
        fun hidesChat(packageName: String, manifest: Boolean, otherSmsApps: Set<String>): Boolean =
            !manifest && packageName in otherSmsApps

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
