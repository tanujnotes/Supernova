package com.supernova.launcher.data

import com.supernova.launcher.search.AppMatcher

// The icon picker's search field. A pack icon is found by its drawable
// name read as words ("folder_sports" is "folder sports"), anywhere in
// the name, so "sport" finds bbcsport, bt_sport and folder_sports. The
// App icons grid is searched by app name, the way the drawer is.
object IconSearch {

    // Indices of the names (or labels, when the grid has them) the query
    // keeps, in grid order; every index for a blank query.
    fun filter(names: List<String>, labels: List<String>?, query: String): List<Int> {
        val q = words(query)
        return when {
            q.isEmpty() -> names.indices.toList()
            labels != null -> AppMatcher.filter(labels.indices.toList(), query) { labels[it] }
            else -> names.indices.filter { words(names[it]).contains(q) }
        }
    }

    // Lower case, with underscores, dashes and runs of spaces as one space.
    fun words(text: String): String = buildString(text.length) {
        for (c in text.trim().lowercase()) {
            val ch = if (c == '_' || c == '-' || c.isWhitespace()) ' ' else c
            if (ch != ' ' || (isNotEmpty() && last() != ' ')) append(ch)
        }
    }.trimEnd()
}
