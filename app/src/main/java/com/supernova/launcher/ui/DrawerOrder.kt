package com.supernova.launcher.ui

import com.supernova.launcher.data.LabelOrder
import com.supernova.launcher.data.LaunchableApp

// What the drawer knows about how its apps get used: launch counts and the
// recently opened apps (newest first), both by app key.
class AppUsage(
    val launchCounts: Map<String, Int>,
    val recent: List<String>,
    val keyOf: (LaunchableApp) -> String
) {
    fun count(app: LaunchableApp): Int = launchCounts[keyOf(app)] ?: 0
}

// The drawer's orders: the app list by DrawerSort, the top row by
// TopRowSort. Apps level on the measure keep name order (LabelOrder), so a
// list never shuffles between two opens.
object DrawerOrder {

    fun sorted(apps: List<LaunchableApp>, sort: DrawerSort, usage: AppUsage): List<LaunchableApp> =
        when (sort) {
            DrawerSort.ALPHA -> byName(apps)
            DrawerSort.ALPHA_REVERSE -> LabelOrder.sorted(apps, descending = true) { it.label }
            DrawerSort.INSTALL_DATE -> byName(apps).sortedByDescending { it.installTime }
            DrawerSort.MOST_USED -> byName(apps).sortedByDescending(usage::count)
            DrawerSort.RECENTLY_USED -> recentlyUsed(apps, usage)
        }

    // Up to `limit` apps for the row over the list.
    fun topRow(apps: List<LaunchableApp>, sort: TopRowSort, usage: AppUsage, limit: Int): List<LaunchableApp> =
        when (sort) {
            TopRowSort.RECENTLY_USED -> recentRow(apps, usage, limit)
            TopRowSort.MOST_USED -> mostUsedRow(apps, usage, limit)
            TopRowSort.INSTALL_DATE -> byName(apps).sortedByDescending { it.installTime }.take(limit)
            TopRowSort.ALPHA -> byName(recentRow(apps, usage, limit))
        }

    // The apps opened most recently. Until there is any recency to go on,
    // the most launched stand in.
    fun recentRow(apps: List<LaunchableApp>, usage: AppUsage, limit: Int): List<LaunchableApp> {
        if (usage.recent.isNotEmpty()) {
            val byKey = apps.associateBy(usage.keyOf)
            val recent = usage.recent.mapNotNull { byKey[it] }
            if (recent.isNotEmpty()) return recent.take(limit)
        }
        return mostUsedRow(apps, usage, limit)
    }

    // Only apps opened at least once: a never-opened app is not "most used".
    private fun mostUsedRow(apps: List<LaunchableApp>, usage: AppUsage, limit: Int): List<LaunchableApp> =
        byName(apps.filter { usage.count(it) > 0 }).sortedByDescending(usage::count).take(limit)

    // The recently opened apps newest first, then the rest by how often they
    // were opened (the recents only go back so far), then by name.
    private fun recentlyUsed(apps: List<LaunchableApp>, usage: AppUsage): List<LaunchableApp> {
        val rank = usage.recent.withIndex().associate { (index, key) -> key to index }
        return byName(apps).sortedWith(
            compareBy<LaunchableApp> { rank[usage.keyOf(it)] ?: Int.MAX_VALUE }
                .thenByDescending(usage::count)
        )
    }

    private fun byName(apps: List<LaunchableApp>): List<LaunchableApp> = LabelOrder.sorted(apps) { it.label }
}
