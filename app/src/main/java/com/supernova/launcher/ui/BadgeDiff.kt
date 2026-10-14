package com.supernova.launcher.ui

// Which apps' notification counts actually changed between two emissions of
// SupernovaNotificationListener.badgeCounts.
//
// The listener rebuilds its whole map on every post and removal, so most
// emissions carry the same numbers as the last one - and a StateFlow replays
// its current value to every new collector, so returning to the home screen
// delivers one too. Diffing here is what turns "a notification happened
// somewhere" into "these icons need redrawing".
//
// Keys are the listener's own "$userHash/$packageName".
object BadgeDiff {

    // Absent and zero are the same thing - no badge - so an app dropping out
    // of the map is a change only if it had a count before.
    fun changedKeys(old: Map<String, Int>, new: Map<String, Int>): Set<String> {
        val keys = HashSet<String>(old.size + new.size)
        keys.addAll(old.keys)
        keys.addAll(new.keys)
        return keys.filterTo(mutableSetOf()) { (old[it] ?: 0) != (new[it] ?: 0) }
    }

    // Keys whose value was added, removed or replaced by an unequal one.
    fun <T> changedValues(old: Map<String, T>, new: Map<String, T>): Set<String> {
        val keys = HashSet<String>(old.size + new.size)
        keys.addAll(old.keys)
        keys.addAll(new.keys)
        return keys.filterTo(mutableSetOf()) { old[it] != new[it] }
    }
}
