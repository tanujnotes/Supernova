package com.supernova.launcher.ui

import com.supernova.launcher.data.ActivityApp
import com.supernova.launcher.data.ActivityCatalog
import com.supernova.launcher.data.ActivityChoice

// The Activities picker's list, apart from its views: an app card for every
// app, each open one followed by its activities.
internal object ActivityRows {

    sealed interface Row {
        val app: ActivityApp

        data class App(override val app: ActivityApp, val expanded: Boolean) : Row
        data class Activity(override val app: ActivityApp, val choice: ActivityChoice) : Row
    }

    fun flatten(apps: List<ActivityApp>, expanded: Set<String>): List<Row> = buildList {
        for (app in apps) {
            val open = app.packageName in expanded
            add(Row.App(app, open))
            if (open) app.activities.forEach { add(Row.Activity(app, it)) }
        }
    }

    // What a search leaves, the way the widget sheet's search does: an app
    // whose name matches keeps all its activities, any other keeps only the
    // ones whose name or class (as its row shows it) matches, and an app left
    // with none drops out. A blank query leaves the list as it is.
    //
    // Every name and class is lowercased once, when the list arrives, so a
    // keystroke over a few thousand activities is a plain substring check.
    class Search(private val apps: List<ActivityApp>) {

        private val appKeys = apps.map { it.label.lowercase() }

        // An activity's name and its class in one key; the NUL between them
        // keeps a query from matching across the two.
        private val activityKeys = apps.map { app ->
            app.activities.map { choice ->
                (choice.label + KEY_SEPARATOR + ActivityCatalog.shortClassName(app.packageName, choice.className))
                    .lowercase()
            }
        }

        fun matching(query: String): List<ActivityApp> {
            val q = query.trim().lowercase()
            if (q.isEmpty()) return apps
            return apps.indices.mapNotNull { i ->
                val app = apps[i]
                if (q in appKeys[i]) return@mapNotNull app
                val keys = activityKeys[i]
                val hits = app.activities.filterIndexed { j, _ -> q in keys[j] }
                when {
                    hits.isEmpty() -> null
                    hits.size == app.activities.size -> app
                    else -> app.copy(activities = hits)
                }
            }
        }
    }

    private const val KEY_SEPARATOR = '\u0000'

    // The fast scrollbar's letters: one per app card, at its position, the
    // way the drawer gives one per app.
    fun sections(rows: List<Row>, letter: (String) -> String): List<FastScrollMath.Section> =
        FastScrollMath.sections(
            rows.mapIndexedNotNull { position, row -> (row as? Row.App)?.let { position to letter(it.app.label) } }
        )
}
