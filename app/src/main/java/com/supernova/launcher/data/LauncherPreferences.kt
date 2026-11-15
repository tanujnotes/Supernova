package com.supernova.launcher.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

class LauncherPreferences(private val context: Context) {

    // A plain setting is one key written. The setters that read first or
    // write several keys keep their own edit block.
    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        context.supernovaStore.edit { it[key] = value }
    }

    // Any one setting, held to its range: LauncherPreferences.set(Pref.gridRows, 7).
    suspend fun <T : Any> set(spec: PrefSpec<T>, value: T) {
        context.supernovaStore.edit { spec.write(it, value) }
    }

    // Several settings in one edit, as the first run's looks write them.
    suspend fun setAll(writes: List<PrefWrite<*>>) {
        if (writes.isEmpty()) return
        context.supernovaStore.edit { target -> writes.forEach { it.into(target) } }
    }

    companion object {
        // Enough to put most of a phone's apps in Recently used order.
        private const val RECENT_LAUNCHES_MAX = 100
        const val MAX_RECENT_SEARCHES = 8

        // Every LauncherPrefs field to its key (see Pref), so a setting that
        // reads also restores. homePromptShown and the other gate flags are
        // device-local state, not settings, and stay out of both.
        fun writeAll(target: MutablePreferences, prefs: LauncherPrefs) = PrefStoreFormat.write(target, prefs)

        // The read-side twin: what the store holds, on LauncherPrefs' defaults.
        fun decode(values: Preferences): LauncherPrefs = PrefStoreFormat.read(values)

        // What the settings come to once `writes` land, worked out the way
        // the store reads them back, so it compares equal to what the store
        // then holds.
        fun applied(prefs: LauncherPrefs, writes: List<PrefWrite<*>>): LauncherPrefs {
            val target = mutablePreferencesOf()
            writeAll(target, prefs)
            writes.forEach { it.into(target) }
            return decode(target)
        }

        // The stored drawer groups once a smart-folder regeneration lands:
        // the user's own groups as they were, plus the generated folders.
        fun withSmartGroups(stored: Set<String>, generated: List<DrawerGroup>, dismissed: Set<String>): Set<String> {
            val userGroups = stored.filterNot { DrawerGroup.decode(it)?.smart == true }.toSet()
            val userNames = userGroups.mapNotNull { DrawerGroup.decode(it)?.name }.toSet()
            // A smart folder moved in the list keeps its place when it's
            // generated again.
            val smartOrder = stored.mapNotNull { DrawerGroup.decode(it) }
                .filter { it.smart }
                .associate { it.name to it.order }
            return userGroups + generated
                // Never shadow a user-created group of the same name, or bring
                // back a dismissed one.
                .filterNot { it.name in userNames || it.name in dismissed }
                .map { it.copy(smart = true, order = smartOrder[it.name] ?: it.order).encode() }
        }
    }

    suspend fun writeAll(prefs: LauncherPrefs) {
        context.supernovaStore.edit { writeAll(it, prefs) }
    }

    // distinctUntilChanged: unrelated store writes (the model document, a
    // gate flag) otherwise re-emit an equal snapshot and re-render the UI.
    val flow: Flow<LauncherPrefs> = context.supernovaStore.data.map(::decode).distinctUntilChanged()
        .onEach { latest = it }

    // The settings as last read, for whoever can't wait for the flow: a
    // screen picks its accent color before it inflates anything, and its
    // view model has no state of its own yet. The process reads them once
    // at start (snapshot), so there is always an answer by then.
    @Volatile
    var latest: LauncherPrefs? = null
        private set

    suspend fun saveDrawerGroup(group: DrawerGroup) {
        context.supernovaStore.edit { prefs ->
            val others = prefs[Pref.drawerGroups.key].orEmpty()
                .filterNot { DrawerGroup.decode(it)?.name == group.name }
                .toSet()
            // Empty groups are kept: a group is created before its apps are
            // picked, and only explicit deletion removes one.
            prefs[Pref.drawerGroups.key] = others + group.encode()
        }
    }

    // Every group edit as one read-modify-write of the stored set. Callers
    // used to save a whole group copied from the list the screen was drawn
    // from, which trails the store by a write: tick two apps in a group's
    // checklist quickly and the second save, built on a copy without the
    // first app, took it back out. Entries that don't decode are kept.
    suspend fun updateDrawerGroups(transform: (List<DrawerGroup>) -> List<DrawerGroup>) {
        context.supernovaStore.edit { prefs ->
            val stored = prefs[Pref.drawerGroups.key].orEmpty()
            val undecodable = stored.filter { DrawerGroup.decode(it) == null }
            val groups = stored.mapNotNull { DrawerGroup.decode(it) }
            prefs[Pref.drawerGroups.key] = (undecodable + transform(groups).map { it.encode() }).toSet()
        }
    }

    suspend fun setGestureBinding(gesture: String, encoded: String) {
        context.supernovaStore.edit { prefs ->
            val current = prefs[Pref.gestureBindings.key].orEmpty()
                .filterNot { it.startsWith("$gesture:") }
                .toSet()
            prefs[Pref.gestureBindings.key] = current + encoded
        }
    }

    suspend fun setAppHidden(key: String, hidden: Boolean) {
        context.supernovaStore.edit { prefs ->
            val current = prefs[Pref.hiddenApps.key].orEmpty()
            prefs[Pref.hiddenApps.key] = if (hidden) current + key else current - key
        }
    }

    suspend fun setDesktopPadding(horizontal: Int, vertical: Int) {
        context.supernovaStore.edit {
            it[Pref.desktopPaddingH.key] = Pref.desktopPaddingH.clamped(horizontal)
            it[Pref.desktopPaddingV.key] = Pref.desktopPaddingV.clamped(vertical)
        }
    }

    suspend fun setSearchBarWidget(id: Int, provider: String) {
        context.supernovaStore.edit {
            it[Pref.searchBarWidgetId.key] = id
            it[Pref.searchBarWidgetProvider.key] = provider
        }
    }

    // Smart Folders bookkeeping: one atomic edit replaces every smart-marked
    // group so a regeneration can never interleave with a user edit. The
    // dismissed names are read (and [dismiss] joins them) in that same edit:
    // on a fresh install the launcher's own first pass can land after the
    // wizard's, and must still leave out the folders that one left out. A
    // pass that started before Smart Folders went off (an import turns them
    // off) writes nothing.
    suspend fun replaceSmartGroups(groups: List<DrawerGroup>, dismiss: Set<String> = emptySet()) {
        context.supernovaStore.edit { prefs ->
            if (prefs[Pref.smartFolders.key] == false) return@edit
            val dismissed = prefs[Pref.smartFoldersDismissed.key].orEmpty() + dismiss
            if (dismiss.isNotEmpty()) prefs[Pref.smartFoldersDismissed.key] = dismissed
            prefs[Pref.drawerGroups.key] =
                withSmartGroups(prefs[Pref.drawerGroups.key].orEmpty(), groups, dismissed)
        }
    }

    suspend fun dismissSmartGroup(name: String) {
        context.supernovaStore.edit {
            it[Pref.smartFoldersDismissed.key] = it[Pref.smartFoldersDismissed.key].orEmpty() + name
        }
    }

    suspend fun clearDismissedSmartGroups() {
        context.supernovaStore.edit { it.remove(Pref.smartFoldersDismissed.key) }
    }

    suspend fun removeSmartGroups() {
        context.supernovaStore.edit { prefs ->
            prefs[Pref.drawerGroups.key] = prefs[Pref.drawerGroups.key].orEmpty()
                .filterNot { DrawerGroup.decode(it)?.smart == true }
                .toSet()
        }
    }

    suspend fun setFolderShowCount(value: Boolean) = put(Pref.folderShowCount.key, value)

    suspend fun setThemeSchedule(startMinute: Int, endMinute: Int) {
        context.supernovaStore.edit {
            it[Pref.themeScheduleStart.key] = Pref.themeScheduleStart.clamped(startMinute)
            it[Pref.themeScheduleEnd.key] = Pref.themeScheduleEnd.clamped(endMinute)
        }
    }

    suspend fun setPopupActionIcons(value: Boolean) = put(Pref.popupActionIcons.key, value)

    // Recent search queries: device-local usage data like launchCounts,
    // newest first, deduplicated case-insensitively, capped at 8.
    suspend fun recentSearches(): List<String> =
        decodeRecents(context.supernovaUsageStore.data.first()[UsageKeys.searchRecents])

    suspend fun recordSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        context.supernovaUsageStore.edit { prefs ->
            val current = decodeRecents(prefs[UsageKeys.searchRecents])
            prefs[UsageKeys.searchRecents] =
                (listOf(trimmed) + current.filterNot { it.equals(trimmed, ignoreCase = true) })
                    .take(MAX_RECENT_SEARCHES)
                    .joinToString("\n")
        }
    }

    suspend fun removeRecentSearch(query: String) {
        context.supernovaUsageStore.edit { prefs ->
            prefs[UsageKeys.searchRecents] = decodeRecents(prefs[UsageKeys.searchRecents])
                .filterNot { it == query }
                .joinToString("\n")
        }
    }

    private fun decodeRecents(raw: String?): List<String> =
        raw.orEmpty().split('\n').filter { it.isNotBlank() }

    // Device-local dock page state for dockRememberPosition.
    suspend fun setDockPageState(value: Int) {
        context.supernovaUsageStore.edit { it[UsageKeys.dockPageState] = value.coerceAtLeast(0) }
    }

    // 0 unless the remember pref is on, so a stale saved page can't leak.
    suspend fun restoredDockPage(): Int {
        if (context.supernovaStore.data.first()[Pref.dockRememberPosition.key] != true) return 0
        return (context.supernovaUsageStore.data.first()[UsageKeys.dockPageState] ?: 0).coerceAtLeast(0)
    }

    // Read once at process start so the theme is set before any inflation.
    suspend fun snapshot(): LauncherPrefs = flow.first()

    // Labs' Clear icon cache: every catalog listening drops its icons.
    val iconCacheClearsFlow: Flow<Long> = context.supernovaStore.data
        .map { it[StoreKeys.iconCacheCleared] ?: 0L }
        .distinctUntilChanged()

    suspend fun requestIconCacheClear() = put(StoreKeys.iconCacheCleared, System.currentTimeMillis())

    // Launch-count bookkeeping for most-used sort. Encoded "key=count"; one
    // set rewrite per launch, in the usage store's own small file.
    val launchCountsFlow: Flow<Map<String, Int>> = context.supernovaUsageStore.data.map { values ->
        values[UsageKeys.launchCounts].orEmpty().mapNotNull { entry ->
            val key = entry.substringBeforeLast('=')
            val count = entry.substringAfterLast('=').toIntOrNull() ?: return@mapNotNull null
            key to count
        }.toMap()
    }

    // App keys, most recently opened first.
    val recentLaunchesFlow: Flow<List<String>> = context.supernovaUsageStore.data.map { values ->
        values[UsageKeys.recentLaunches].orEmpty()
            .sortedByDescending { it.substringAfterLast('=').toLongOrNull() ?: 0L }
            .map { it.substringBeforeLast('=') }
    }

    suspend fun recordLaunch(key: String) {
        context.supernovaUsageStore.edit { prefs ->
            val current = prefs[UsageKeys.launchCounts].orEmpty()
            val existing = current.firstOrNull { it.substringBeforeLast('=') == key }
            val count = existing?.substringAfterLast('=')?.toIntOrNull() ?: 0
            prefs[UsageKeys.launchCounts] = (current - setOfNotNull(existing)) + "$key=${count + 1}"
            val recents = prefs[UsageKeys.recentLaunches].orEmpty()
                .filterNot { it.substringBeforeLast('=') == key } + "$key=${System.currentTimeMillis()}"
            prefs[UsageKeys.recentLaunches] = recents
                .sortedByDescending { it.substringAfterLast('=').toLongOrNull() ?: 0L }
                .take(RECENT_LAUNCHES_MAX)
                .toSet()
        }
    }

    suspend fun homePromptShown(): Boolean {
        return context.supernovaStore.data.first()[StoreKeys.homePromptShown] ?: false
    }

    suspend fun markHomePromptShown() = put(StoreKeys.homePromptShown, true)

    // Writes the screen's starting grid into any grid key that has never
    // been set, once per install. Keys the user (or a restored backup) has
    // already written are left alone, and a second call is a no-op, so an
    // upgrade on a phone changes nothing: the phone profile is the default
    // the store already fell back to.
    suspend fun applyDeviceProfileOnce(profile: DeviceProfile) {
        context.supernovaStore.edit { values ->
            if (values[StoreKeys.deviceProfileApplied] == true) return@edit
            if (values[Pref.gridCols.key] == null) values[Pref.gridCols.key] = profile.gridCols
            if (values[Pref.gridRows.key] == null) values[Pref.gridRows.key] = profile.gridRows
            if (values[Pref.dockCount.key] == null) values[Pref.dockCount.key] = profile.dockCount
            listOf(
                Pref.iconScale.key, Pref.dockIconScale.key,
                Pref.drawerIconScale.key, Pref.folderIconScale.key
            ).forEach { key ->
                if (values[key] == null) values[key] = profile.iconScale
            }
            values[StoreKeys.deviceProfileApplied] = true
        }
    }

    // Onboarding gate twin of homePromptShown: device-local, never backed up.
    suspend fun onboardingDone(): Boolean {
        return context.supernovaStore.data.first()[StoreKeys.onboardingDone] ?: false
    }

    suspend fun markOnboardingDone() = put(StoreKeys.onboardingDone, true)

    // Whether the fresh-install home screen has been planted (or its one
    // shot spent).
    suspend fun defaultLayoutSeeded(): Boolean =
        context.supernovaStore.data.first()[StoreKeys.defaultLayoutSeeded] == true

    // One-shot claim on the fresh-install layout, true only for the call that
    // took it. A single DataStore edit does the check and the write, so two
    // racing callers (the wizard opening and the wizard finishing, say) can
    // never both plant a copy.
    suspend fun claimDefaultLayoutSeed(): Boolean {
        var claimed = false
        context.supernovaStore.edit { values ->
            if (values[StoreKeys.defaultLayoutSeeded] == true) return@edit
            values[StoreKeys.defaultLayoutSeeded] = true
            claimed = true
        }
        return claimed
    }

    // Wizard completion writes both gate flags in ONE edit: two sequential
    // writes leave a half-finished gate (onboarding done, home prompt not
    // marked) if the scope is cancelled between them - observed in the wild
    // from an interrupted first run.
    suspend fun markOnboardingComplete() {
        context.supernovaStore.edit {
            it[StoreKeys.onboardingDone] = true
            it[StoreKeys.homePromptShown] = true
        }
    }
}
