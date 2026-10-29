package com.supernova.launcher.search

import com.supernova.launcher.data.LaunchableApp

// Matches the drawer's visible apps with the same AppMatcher rules the
// drawer filter uses. Suppliers keep it decoupled from the ViewModel class:
// both are cheap snapshot reads.
class AppSearchProvider(
    private val apps: () -> List<LaunchableApp>,
    private val launchCounts: () -> Map<String, Int> = { emptyMap() }
) : SearchProvider {

    override val id: String = ID

    override fun search(query: String): List<SearchResult> {
        val trimmed = query.trim().lowercase()
        if (trimmed.isEmpty()) return emptyList()
        val counts = launchCounts()
        return apps()
            .mapNotNull { app -> AppMatcher.match(app.label, trimmed)?.let { app to it } }
            // How well the name matches comes first, so a typo-tolerant hit
            // never outranks a real prefix however often it is launched.
            .sortedWith(
                compareBy<Pair<LaunchableApp, AppMatcher.Match>> { it.second.tier }
                    .thenByDescending { counts[countKey(it.first)] ?: 0 }
                    .then(com.supernova.launcher.data.LabelOrder.by { it.first.label })
            )
            .take(MAX_RESULTS)
            .map { (app, match) -> SearchResult.App(app, match.ranges) }
    }

    companion object {
        const val ID = "apps"
        // Two rows of the search window's grid.
        const val MAX_RESULTS = 10

        // Same "userSerial/pkg/cls" key the launch-count store uses.
        fun countKey(app: LaunchableApp): String =
            "${app.userSerial}/${app.packageName}/${app.className}"
    }
}
