package com.supernova.launcher.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

val Context.supernovaStore by preferencesDataStore(name = "supernova_store")

object StoreKeys {
    val model: Preferences.Key<String> = stringPreferencesKey("launcher_model")
    // Set once the first-run grid has been chosen for this screen; see DeviceProfile.
    val deviceProfileApplied: Preferences.Key<Boolean> = booleanPreferencesKey("device_profile_applied")
    val homePromptShown: Preferences.Key<Boolean> = booleanPreferencesKey("home_prompt_shown")

    // When Labs' Clear icon cache last ran. Every open activity's catalog
    // keeps its own cache, so the request travels through the store.
    // A signal, not a setting: not a backup item.
    val iconCacheCleared: Preferences.Key<Long> = longPreferencesKey("icon_cache_cleared")

    // Onboarding gate: set once the wizard finishes or is skipped, or written
    // silently for pre-onboarding installs (grandfathering). Device-local
    // state like homePromptShown, not a LauncherPrefs field: it stays out of
    // writeAll and backups so a restored backup on a fresh device still
    // onboards that device.
    val onboardingDone: Preferences.Key<Boolean> = booleanPreferencesKey("onboarding_done")

    // Set once the fresh-install layout - the dock and first page's apps and
    // the search bar under the dock - has been planted, or its one shot
    // spent on a layout that wasn't empty. Device-local like the onboarding
    // gate, so the starting layout is a property of this install and a
    // restored backup never brings it back. See DefaultLayoutBuilder.
    val defaultLayoutSeeded: Preferences.Key<Boolean> = booleanPreferencesKey("default_layout_seeded")

    // Settings > Folders "Sort apps A-Z", retired (#156): it showed every
    // folder A-Z without changing its order, and kept apps from being
    // dragged into an order of their own. Read only to sort the folders of
    // a store or backup that had it on (FolderSort.allSorted), then dropped.
    val retiredFolderSortAz: Preferences.Key<Boolean> = booleanPreferencesKey("folder_sort_az")
}
