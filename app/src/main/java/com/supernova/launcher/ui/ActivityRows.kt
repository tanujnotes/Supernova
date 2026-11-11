package com.supernova.launcher.ui

import com.supernova.launcher.data.ActivityApp
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

    // The fast scrollbar's letters: one per app card, at its position, the
    // way the drawer gives one per app.
    fun sections(rows: List<Row>, letter: (String) -> String): List<FastScrollMath.Section> =
        FastScrollMath.sections(
            rows.mapIndexedNotNull { position, row -> (row as? Row.App)?.let { position to letter(it.app.label) } }
        )
}
