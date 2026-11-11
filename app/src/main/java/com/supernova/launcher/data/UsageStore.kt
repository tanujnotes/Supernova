package com.supernova.launcher.data

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

// Usage data in a file of its own. Launch counts and recents are written on
// every app launch, the dock page on every dock swipe, and a store write
// rewrites its whole file: kept in the main store, each of those rewrote the
// home screen layout and its inline icons, and re-emitted every setting.
//
// Device-local, like the gate flags left in the main store: not part of
// LauncherPrefs, never backed up, untouched by a reset.
val Context.supernovaUsageStore by preferencesDataStore(
    name = "supernova_usage",
    produceMigrations = { context -> listOf(UsageMigration { context.supernovaStore }) }
)

object UsageKeys {
    // "userSerial/pkg/cls=count" entries, for most-used sort.
    val launchCounts: Preferences.Key<Set<String>> = stringSetPreferencesKey("launch_counts")

    // "key=epochMillis" for the most recently opened apps (the drawer's
    // recents row).
    val recentLaunches: Preferences.Key<Set<String>> = stringSetPreferencesKey("recent_launches")

    // The last shown dock page for dockRememberPosition. Positional state,
    // not configuration.
    val dockPageState: Preferences.Key<Int> = intPreferencesKey("dock_page_state")

    // Recent search queries, newest first, '\n'-joined (a string set would
    // lose recency order).
    val searchRecents: Preferences.Key<String> = stringPreferencesKey("search_recents")

    val all: List<Preferences.Key<*>> = listOf(launchCounts, recentLaunches, dockPageState, searchRecents)
}

// An install from before the split holds these keys in the main store: they
// move across on the first read, and leave the main store once they have
// landed. A key this store already holds is the newer one and is kept.
internal class UsageMigration(
    private val mainStore: () -> DataStore<Preferences>
) : DataMigration<Preferences> {

    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val main = mainStore().data.first()
        return UsageKeys.all.any { it in main }
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val main = mainStore().data.first()
        val moved = currentData.toMutablePreferences()
        UsageKeys.all.forEach { moved.takeFrom(main, it) }
        return moved
    }

    override suspend fun cleanUp() {
        mainStore().edit { main -> UsageKeys.all.forEach { main.remove(it) } }
    }

    private fun <T> MutablePreferences.takeFrom(source: Preferences, key: Preferences.Key<T>) {
        val value = source[key] ?: return
        if (key !in this) this[key] = value
    }
}
