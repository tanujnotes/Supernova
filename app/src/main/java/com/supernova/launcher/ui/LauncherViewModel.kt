package com.supernova.launcher.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.supernova.launcher.AppGraph
import com.supernova.launcher.data.AppCatalog
import com.supernova.launcher.data.AppChange
import com.supernova.launcher.data.AppOverride
import com.supernova.launcher.data.ContainerType
import com.supernova.launcher.data.CustomIcons
import com.supernova.launcher.data.CustomShapeSpec
import com.supernova.launcher.data.DeviceProfile
import com.supernova.launcher.data.DrawerGroup
import com.supernova.launcher.data.DrawerGroupType
import com.supernova.launcher.data.IconPack
import com.supernova.launcher.data.IconShape
import com.supernova.launcher.data.ItemType
import com.supernova.launcher.data.LaunchableApp
import com.supernova.launcher.data.LauncherItem
import com.supernova.launcher.data.LauncherPreferences
import com.supernova.launcher.data.LauncherPrefs
import com.supernova.launcher.data.LauncherRepository
import com.supernova.launcher.data.WorkspaceGeometry
import com.supernova.launcher.gesture.Gesture
import com.supernova.launcher.gesture.GestureAction
import com.supernova.launcher.gesture.GestureBinding
import com.supernova.launcher.gesture.GestureDefaults
import com.supernova.launcher.onboarding.DefaultLayoutBuilder
import com.supernova.launcher.ui.ScrollEffect
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LauncherViewModel(
    application: Application,
    private val repository: LauncherRepository,
    private val preferences: LauncherPreferences,
    private val catalog: AppCatalog
) : AndroidViewModel(application) {

    // Each activity keeps its own view model, but all of them share the
    // app's one repository, preferences and catalog (and so its one icon
    // cache): see AppGraph.
    class Factory(private val graph: AppGraph) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            LauncherViewModel(graph.application, graph.repository, graph.preferences, graph.catalog) as T
    }

    private val appsFlow = MutableStateFlow(emptyList<LaunchableApp>())
    private val currentPageFlow = MutableStateFlow(0)
    // Launch counts for most-used sort; device-local, collected eagerly.
    private var launchCounts: Map<String, Int> = emptyMap()
    private var recentLaunches: List<String> = emptyList()
    // Bumped whenever either of those moves. Launches stay out of state (each
    // would re-render every screen), so the rows that list them - the
    // drawer's frequent row, the feed's - watch this to catch up while open.
    private val launches = MutableStateFlow(0)
    val launchGeneration: StateFlow<Int> = launches

    // Bumped when the icon pack changes so the workspace redraws cached icons.
    private val iconGeneration = MutableStateFlow(0)

    // Rescan requests. The Boolean is "run it now": an icon-config change is
    // one the user is waiting to see, while package callbacks can wait for
    // their burst to settle. Only the newest pending ask is kept
    // (DROP_OLDEST), which is what coalesces a burst; replay = 1 so an ask
    // made before the collector below has subscribed still lands.
    private val refreshRequests = MutableSharedFlow<Boolean>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val state = combine(
        repository.modelFlow,
        preferences.flow,
        appsFlow,
        currentPageFlow,
        iconGeneration
    ) { model, prefs, apps, page, generation ->
        LauncherUiState(
            model = model,
            prefs = prefs,
            apps = apps,
            currentPage = page,
            iconGeneration = generation,
            loaded = true,
            // 0 means the icon configuration has not been applied yet, so
            // nothing has been warmed; see the startup scan in init.
            iconsReady = generation > 0
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LauncherUiState())

    // A setting's write: off the main thread, fire and forget. Every plain
    // setter below is one of these.
    private fun persist(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private var appliedIconPack: String? = null
    private var appliedIconShape: String? = null
    private var appliedIconShapeCustom: String? = null
    private var appliedReshape: com.supernova.launcher.data.ReshapeLegacy? = null
    private var appliedShadow: Boolean? = null
    private var appliedGlass: String? = null
    // Themed-icon colors depend on the whole palette, so track a signature.
    private var appliedThemedSig: String? = null
    private var autoBackupWritten = false

    // Whether each of the two icon-configuration collectors has applied its
    // first emission. The startup scan waits for both: a warm that runs
    // before applyIconPack / applyIconShape / applyOverrides is thrown away
    // by their evictAll, and the workspace would draw the stock icons it
    // produced only to replace them a moment later.
    private var prefsApplied = false
    private var overridesApplied = false
    private val configApplied = CompletableDeferred<Unit>()
    // Held so onCleared can remove exactly this view model's listener from
    // the shared catalog.
    private val appChangeListener: (AppChange) -> Unit

    init {
        // The one place the app list is enumerated. A single collector makes
        // the scans single-file and ordered: before this, every package
        // callback launched its own coroutine, so a Play Store batch ran a
        // full enumeration per app and an older scan finishing last could
        // overwrite a newer list.
        viewModelScope.launch(Dispatchers.Default) {
            refreshRequests.collect { immediate ->
                // Package changes arrive in bursts, so let the burst settle
                // and enumerate once over the result. Deliberately a plain
                // delay rather than Flow.debounce: debounce is still
                // @FlowPreview in coroutines 1.8.1 and this codebase opts in
                // to nothing. It also starves the rescan while a long burst
                // keeps arriving, where this scans every COALESCE_MS.
                if (!immediate) delay(REFRESH_COALESCE_MS)
                doRefresh()
            }
        }
        viewModelScope.launch {
            preferences.flow.collect { prefs ->
                WorkspaceGeometry.allowWidgetOverlap = prefs.widgetOverlap
                WorkspaceGeometry.allowPlacementOverlap = prefs.overlapWhenPlacing
                val packChanged = prefs.iconPack != appliedIconPack
                val shapeChanged = prefs.iconShape != appliedIconShape ||
                    prefs.iconShapeCustom != appliedIconShapeCustom
                val reshape = com.supernova.launcher.data.ReshapeLegacy.fromPrefs(prefs)
                val normalizeChanged = reshape != appliedReshape
                val shadowChanged = prefs.iconShadow != appliedShadow
                val glassChanged = prefs.iconGlass != appliedGlass
                val themedSig = themedIconSignature(prefs)
                val themedChanged = themedSig != appliedThemedSig
                val resolution = iconResolution(prefs)
                val resolutionChanged = resolution != appliedResolution
                val changes = listOf(
                    packChanged, shapeChanged, normalizeChanged, shadowChanged,
                    glassChanged, themedChanged, resolutionChanged
                )
                if (changes.none { it }) return@collect
                appliedResolution = resolution
                appliedIconPack = prefs.iconPack
                appliedIconShape = prefs.iconShape
                appliedIconShapeCustom = prefs.iconShapeCustom
                appliedReshape = reshape
                appliedShadow = prefs.iconShadow
                appliedGlass = prefs.iconGlass
                appliedThemedSig = themedSig
                withContext(Dispatchers.Default) {
                    if (packChanged) catalog.applyIconPack(prefs.iconPack)
                    if (shapeChanged) {
                        catalog.applyIconShape(
                            IconShape.fromName(prefs.iconShape),
                            CustomShapeSpec.decode(prefs.iconShapeCustom)
                        )
                    }
                    if (normalizeChanged) catalog.applyReshapeLegacy(reshape)
                    if (shadowChanged) catalog.applyIconShadow(prefs.iconShadow)
                    if (glassChanged) {
                        catalog.applyIconGlass(com.supernova.launcher.data.IconGlass.fromName(prefs.iconGlass))
                    }
                    if (resolutionChanged) catalog.applyIconResolution(resolution)
                    if (themedChanged) {
                        val (bg, fg) = themedIconColors(prefs)
                        catalog.applyThemedIcons(prefs.themedIcons, bg, fg, prefs.themedIconsAutogen)
                    }
                }
                // The first emission is the startup configuration: it applies
                // it and stops there, leaving the enumeration, the warm and
                // the single generation bump to the startup scan. Only a
                // LATER change rescans and redraws on its own - and the user
                // is watching for that one, so it skips the coalescing wait.
                val firstConfig = !prefsApplied
                prefsApplied = true
                signalConfigAppliedIfReady()
                if (!firstConfig) {
                    requestRefresh(immediate = true)
                    iconGeneration.value += 1
                }
            }
        }
        // Both flows re-emit on every store write; only a real move counts.
        viewModelScope.launch {
            preferences.launchCountsFlow.distinctUntilChanged().collect {
                launchCounts = it
                launches.value += 1
            }
        }
        viewModelScope.launch {
            // Labs' Clear icon cache, asked for from whichever activity:
            // the first value is only where the store stands.
            preferences.iconCacheClearsFlow.drop(1).collect {
                withContext(Dispatchers.Default) { catalog.clearIconCache() }
                requestRefresh(immediate = true)
                iconGeneration.value += 1
            }
        }
        viewModelScope.launch {
            preferences.recentLaunchesFlow.distinctUntilChanged().collect {
                recentLaunches = it
                launches.value += 1
            }
        }
        // Smart folders are on by default, so the category folders are made
        // automatically; the toggle and the install hook regenerate them, so
        // this first pass only covers a store that has none yet.
        viewModelScope.launch {
            val ready = state.first { it.loaded && it.apps.isNotEmpty() }
            if (ready.prefs.smartFolders && drawerGroups().none { it.smart }) regenerateSmartFolders()
        }
        viewModelScope.launch {
            repository.modelFlow.map { it.appOverrides }.distinctUntilChanged().collect { overrides ->
                withContext(Dispatchers.Default) { catalog.applyOverrides(overrides) }
                // As above: the first emission only settles the overrides.
                val firstConfig = !overridesApplied
                overridesApplied = true
                signalConfigAppliedIfReady()
                if (!firstConfig) iconGeneration.value += 1
            }
        }
        appChangeListener = { change ->
            // Only the enumeration is coalesced. Everything below stays
            // immediate: an uninstall has to clear its items now, and a new
            // install's auto-add and smart-folder placement both run their
            // own fresh lookups rather than waiting on appsFlow.
            requestRefresh(immediate = false)
            when (change) {
                is AppChange.Removed -> {
                    viewModelScope.launch(Dispatchers.Default) { catalog.invalidatePackages(listOf(change.packageName)) }
                    viewModelScope.launch { repository.removeItemsForPackage(change.packageName, change.userSerial) }
                }
                is AppChange.Added -> {
                    maybeAutoAddToHome(change.packageName, change.userSerial)
                    // New installs slot into their smart folder while enabled.
                    if (state.value.prefs.smartFolders) {
                        viewModelScope.launch { regenerateSmartFolders() }
                    }
                }
                is AppChange.Updated -> {
                    // The screens draw an updated app's new icon once the
                    // rescan has it: nothing else about the app changed, so
                    // no list or signature would have moved. In that order:
                    // the old icons (and, for the icon pack in use, the old
                    // pack) go first, then a rescan is asked for, whose
                    // redraw so always follows them.
                    viewModelScope.launch(Dispatchers.Default) {
                        catalog.invalidatePackages(change.packageNames)
                        catalog.onPackagesUpdated(change.packageNames)
                        iconsStale = true
                        requestRefresh(immediate = false)
                    }
                }
            }
        }
        catalog.registerAppChangeListener(appChangeListener)
        // Startup: one enumeration and one warm, run once both icon-config
        // collectors have applied their first emission. This is the only
        // thing that moves the generation off 0, so it is also what lets the
        // workspace bind at all.
        viewModelScope.launch {
            configApplied.await()
            withContext(Dispatchers.Default) { runStartupScan() }
        }
        // Safety valve for a warm that takes too long (a big icon pack, a
        // large work profile): let the workspace draw anyway.
        //
        // It waits for the configuration first, deliberately. A main-thread
        // icon render takes AppCatalog's renderLock, and applyIconPack holds
        // that lock for the whole appfilter parse - so flipping early during
        // a slow pack would park the main thread on the lock rather than
        // produce an earlier frame. The second wait is the last resort: if
        // the configuration never arrives at all, a workspace drawn with
        // stock icons still beats one that never draws.
        viewModelScope.launch {
            delay(STARTUP_VALVE_MS)
            withTimeoutOrNull(STARTUP_VALVE_GIVE_UP_MS) { configApplied.await() }
            iconGeneration.compareAndSet(0, 1)
        }
    }

    private fun signalConfigAppliedIfReady() {
        if (prefsApplied && overridesApplied) configApplied.complete(Unit)
    }

    // The icons the first frame actually draws: the dock, the default page,
    // and the members of any folder on either - a folder's icon is drawn from
    // its members, so they are first-frame work too.
    //
    // Built straight from the model, exactly as the workspace builds them
    // (LauncherActivity.viewForItem turns each item into its own
    // LaunchableApp), so warming these needs no app-list enumeration.
    private fun firstFrameApps(model: com.supernova.launcher.data.LauncherModel): List<LaunchableApp> {
        val front = model.items.filter {
            it.container == ContainerType.DOCK ||
                (it.container == ContainerType.WORKSPACE && it.page == model.defaultPage)
        }
        val folderIds = front.filter { it.itemType == ItemType.FOLDER }.map { it.id }.toSet()
        val members = model.items.filter {
            it.container == ContainerType.FOLDER && it.folderId in folderIds
        }
        return (front + members).mapNotNull { item ->
            val pkg = item.packageName ?: return@mapNotNull null
            val cls = item.className ?: return@mapNotNull null
            LaunchableApp(pkg, cls, item.label, item.userSerial)
        }
    }

    // The single enumeration the first frame waits for. What the first frame
    // shows is warmed BEFORE the workspace is allowed to bind, so those binds
    // are cache hits; the rest of the drawer warms behind the drawn frame.
    private suspend fun runStartupScan() {
        // What the first frame shows, warmed first and on its own. The full
        // enumeration below is the drawer's, and the workspace must not wait
        // on it: it costs ~350-750 ms on a 58-app device, and the home screen
        // needs a handful of icons it can already name from the model.
        catalog.warmIcons(firstFrameApps(repository.modelFlow.first()))
        iconGeneration.compareAndSet(0, 1)

        // Behind the drawn frame: the list the drawer needs, and every other
        // icon. The first-frame icons are cache hits here.
        val apps = catalog.listLaunchableApps()
        appsFlow.value = apps
        catalog.warmIcons(apps)
    }

    // Auto-add: a newly installed primary-profile app lands on the first page
    // with a free cell when the pref is on.
    private fun maybeAutoAddToHome(packageName: String, userSerial: Long) {
        if (userSerial != 0L) return
        viewModelScope.launch(Dispatchers.Default) {
            val prefs = preferences.flow.first()
            if (!prefs.autoAddApps) return@launch
            val app = catalog.mainActivityFor(packageName, userSerial) ?: return@launch
            repository.autoAddApp(app, prefs.gridCols, prefs.gridRows)
        }
    }

    override fun onCleared() {
        catalog.unregisterAppChangeListener(appChangeListener)
        super.onCleared()
    }

    fun appCatalog(): AppCatalog = catalog

    fun setCurrentPage(page: Int) {
        currentPageFlow.value = page
    }

    // Ask for a rescan; the collector in init decides when it runs.
    private fun requestRefresh(immediate: Boolean) {
        refreshRequests.tryEmit(immediate)
    }

    // The enumeration itself. Only ever called from the single collector, so
    // two scans can never be in flight at once and a slow one can't finish
    // last and overwrite a newer list.
    private suspend fun doRefresh() {
        val stale = iconsStale
        iconsStale = false
        val apps = catalog.listLaunchableApps()
        appsFlow.value = apps
        // Pre-warm off the main thread so binds become cache hits; the
        // render lock keeps this safe alongside main-thread misses.
        catalog.warmIcons(apps)
        if (stale) iconGeneration.value += 1
    }

    // An app was updated since the last scan: its icon may have changed.
    @Volatile private var iconsStale = false

    fun workspaceItems(page: Int): List<LauncherItem> {
        return state.value.model.items.filter { it.container == ContainerType.WORKSPACE && it.page == page }
    }

    fun dockItems(): List<LauncherItem> {
        return state.value.model.items.filter { it.container == ContainerType.DOCK }.sortedBy { it.rank }
    }

    private var appliedResolution = 0f

    // The largest icon size any surface draws at, rounded up to a fifth so a
    // size slider's drag re-renders at a few steps rather than every tick.
    private fun iconResolution(prefs: com.supernova.launcher.data.LauncherPrefs): Float {
        val scales = listOf(
            prefs.iconScale,
            if (prefs.dockMatchDesktop) prefs.iconScale else prefs.dockIconScale,
            if (prefs.drawerMatchDesktop) prefs.iconScale else prefs.drawerIconScale,
            if (prefs.folderMatchDesktop) prefs.iconScale else prefs.folderIconScale
        )
        val largest = scales.maxOrNull()?.coerceIn(1f, MAX_ICON_RESOLUTION) ?: 1f
        return kotlin.math.ceil(largest * 5f - 0.001f) / 5f
    }

    // Called as the launcher's activity is created: a Display size change
    // recreates it (the view model survives), and icons rendered for the
    // old density are drawn again.
    fun onDisplayDensity(densityDpi: Int) {
        viewModelScope.launch(Dispatchers.Default) {
            if (catalog.applyDensity(densityDpi)) {
                requestRefresh(immediate = true)
                iconGeneration.value += 1
            }
        }
    }

    // Signature over everything themed-icon colors depend on, so a palette or
    // theme change re-tints monochrome icons even though no icon-shape pref moved.
    private fun themedIconSignature(prefs: com.supernova.launcher.data.LauncherPrefs): String {
        val night = isNightMode(prefs)
        return "${prefs.themedIcons}|${prefs.themedIconsAutogen}|${prefs.paletteSource}|${prefs.paletteSeed}|" +
            "${prefs.accentColor}|${prefs.themeMode}|$night"
    }

    // The launcher's own dark or light, Schedule's and Auto's windows
    // included, not the system's.
    private fun isNightMode(prefs: com.supernova.launcher.data.LauncherPrefs): Boolean =
        com.supernova.launcher.ui.theme.ThemeEngine.isNight(getApplication(), prefs)

    // The launcher can turn dark or light with no pref changing: the
    // system's dark mode, or Schedule's and Auto's boundaries. Each of those
    // recreates the activity, which calls this so themed icons re-tint.
    fun refreshThemedIcons() {
        if (!prefsApplied) return
        viewModelScope.launch {
            val prefs = state.value.prefs
            val signature = themedIconSignature(prefs)
            if (signature == appliedThemedSig) return@launch
            appliedThemedSig = signature
            withContext(Dispatchers.Default) {
                val (bg, fg) = themedIconColors(prefs)
                catalog.applyThemedIcons(prefs.themedIcons, bg, fg, prefs.themedIconsAutogen)
            }
            iconGeneration.value += 1
        }
    }

    // Android-style themed-icon colors from the palette: a tonal plate with a
    // contrasting tinted glyph, flipped for dark mode.
    private fun themedIconColors(prefs: com.supernova.launcher.data.LauncherPrefs): Pair<Int, Int> {
        val app = getApplication<Application>()
        val palette = com.supernova.launcher.ui.theme.PaletteFactory.resolve(
            app,
            com.supernova.launcher.ui.theme.PaletteSourceMode.fromName(prefs.paletteSource),
            prefs.paletteSeed,
            fallbackSeed = app.getColor(com.supernova.launcher.R.color.accent)
        )
        val night = isNightMode(prefs)
        val bg = palette.tone(
            com.supernova.launcher.ui.theme.PaletteRole.NEUTRAL, if (night) 20 else 90
        )
        val fg = palette.tone(
            com.supernova.launcher.ui.theme.PaletteRole.PRIMARY, if (night) 80 else 40
        )
        return bg to fg
    }

    fun folderMembers(folderId: String): List<LauncherItem> {
        return state.value.model.items.filter { it.container == ContainerType.FOLDER && it.folderId == folderId }.sortedBy { it.rank }
    }

    // The app a swipe-to-open folder's tap launches: the first one its window
    // shows, which is A-Z when the folder sort says so.
    fun folderFirstMember(folderId: String): LauncherItem? {
        val members = folderMembers(folderId)
        return if (state.value.prefs.folderSortAz) {
            members.minByOrNull { it.label.lowercase() }
        } else {
            members.firstOrNull()
        }
    }

    fun setFolderSwipeToOpen(folderId: String, on: Boolean) = persist { repository.setFolderSwipeToOpen(folderId, on) }

    // What overlaps a placed item from below and above on its page.
    fun stacking(item: LauncherItem): WorkspaceGeometry.Stacking {
        val current = state.value
        return WorkspaceGeometry.stacking(current.model, item, current.prefs.gridCols, current.prefs.gridRows)
    }

    fun restackItem(itemId: String, toFront: Boolean) = persist { repository.restackItem(itemId, toFront) }

    // A widget's padding in dp: its own, or the Widget padding setting's.
    fun widgetPaddingDp(item: LauncherItem): Int = item.widgetPadding ?: state.value.prefs.widgetPadding

    // Null goes back to following the setting.
    fun setWidgetPadding(itemId: String, paddingDp: Int?) = persist { repository.setWidgetPadding(itemId, paddingDp) }

    fun addAppToWorkspace(
        app: LaunchableApp,
        page: Int,
        cellX: Int,
        cellY: Int,
        workspaceX: Float? = null,
        workspaceY: Float? = null,
        displace: List<WorkspaceGeometry.ReorderMove> = emptyList(),
        mergeFolders: Boolean = true,
        mergeTargetId: String? = null
    ) {
        val prefs = state.value.prefs
        viewModelScope.launch {
            repository.addAppToWorkspace(
                app, page, cellX, cellY, prefs.gridCols, prefs.gridRows,
                workspaceX, workspaceY, displace, mergeFolders, mergeTargetId
            )
        }
    }

    fun addShortcutToWorkspace(
        app: LaunchableApp,
        shortcutId: String,
        label: String,
        page: Int,
        cellX: Int,
        cellY: Int,
        workspaceX: Float? = null,
        workspaceY: Float? = null,
        iconBase64: String? = null,
        displace: List<WorkspaceGeometry.ReorderMove> = emptyList(),
        mergeFolders: Boolean = true,
        mergeTargetId: String? = null
    ) {
        val prefs = state.value.prefs
        viewModelScope.launch {
            repository.addShortcutToWorkspace(
                app, shortcutId, label, page, cellX, cellY, prefs.gridCols, prefs.gridRows,
                workspaceX, workspaceY, iconBase64, displace, mergeFolders, mergeTargetId
            )
        }
    }

    fun addShortcutToDock(
        app: LaunchableApp,
        shortcutId: String,
        label: String,
        iconBase64: String?,
        rank: Int,
        preferredPage: Int = 0
    ) {
        val prefs = state.value.prefs
        viewModelScope.launch {
            repository.addShortcutToDock(
                app, shortcutId, label, iconBase64, rank, prefs.gridCols, prefs.gridRows, preferredPage
            )
        }
    }

    fun addFolderToWorkspace(
        label: String,
        apps: List<LaunchableApp>,
        page: Int,
        cellX: Int,
        cellY: Int,
        workspaceX: Float? = null,
        workspaceY: Float? = null,
        displace: List<WorkspaceGeometry.ReorderMove> = emptyList()
    ) {
        val prefs = state.value.prefs
        viewModelScope.launch {
            repository.addFolderToWorkspace(
                label, apps, page, cellX, cellY, prefs.gridCols, prefs.gridRows, workspaceX, workspaceY, displace
            )
        }
    }

    fun addFolderToDock(label: String, apps: List<LaunchableApp>, rank: Int, preferredPage: Int = 0) {
        val prefs = state.value.prefs
        viewModelScope.launch {
            repository.addFolderToDock(label, apps, rank, prefs.gridCols, prefs.gridRows, preferredPage)
        }
    }

    fun addIntentItemToWorkspace(
        intentUri: String,
        label: String,
        iconBase64: String?,
        page: Int,
        cellX: Int,
        cellY: Int,
        workspaceX: Float? = null,
        workspaceY: Float? = null,
        displace: List<WorkspaceGeometry.ReorderMove> = emptyList()
    ) {
        val prefs = state.value.prefs
        viewModelScope.launch {
            repository.addIntentItemToWorkspace(
                intentUri, label, iconBase64, page, cellX, cellY,
                prefs.gridCols, prefs.gridRows, workspaceX, workspaceY, displace
            )
        }
    }

    fun addAppToDock(app: LaunchableApp, rank: Int, preferredPage: Int = 0) {
        val prefs = state.value.prefs
        viewModelScope.launch { repository.addAppToDock(app, rank, prefs.gridCols, prefs.gridRows, preferredPage) }
    }

    // A placed widget onto `rect` (in cells, half ones included).
    fun moveWidget(
        itemId: String,
        page: Int,
        rect: WorkspaceGeometry.CellRect,
        displace: List<WorkspaceGeometry.ReorderMove>
    ) {
        val prefs = state.value.prefs
        viewModelScope.launch { repository.moveWidget(itemId, page, rect, prefs.gridCols, prefs.gridRows, displace) }
    }

    fun moveItemToWorkspace(
        itemId: String,
        page: Int,
        cellX: Int,
        cellY: Int,
        workspaceX: Float? = null,
        workspaceY: Float? = null,
        displace: List<WorkspaceGeometry.ReorderMove> = emptyList(),
        mergeFolders: Boolean = true,
        mergeTargetId: String? = null
    ) {
        val prefs = state.value.prefs
        viewModelScope.launch {
            repository.moveItemToWorkspace(
                itemId, page, cellX, cellY, prefs.gridCols, prefs.gridRows,
                workspaceX, workspaceY, displace, mergeFolders, mergeTargetId
            )
        }
    }

    fun moveItemToDock(itemId: String, rank: Int) = persist { repository.moveItemToDock(itemId, rank) }

    fun mergeIntoDock(item: LauncherItem, rank: Int) = persist { repository.mergeIntoDock(item, rank) }

    fun moveIntoFolder(itemId: String, folderId: String) = persist { repository.moveItemIntoFolder(itemId, folderId) }

    fun addAppToFolder(app: LaunchableApp, folderId: String) = persist { repository.addAppToFolder(app, folderId) }

    fun removeItem(itemId: String) = persist { repository.removeItem(itemId) }

    fun renameFolder(folderId: String, name: String) = persist { repository.renameFolder(folderId, name) }

    fun removeFolderMember(memberId: String, page: Int, cellX: Int, cellY: Int) = persist { repository.removeFolderMember(memberId, page, cellX, cellY) }

    fun reorderFolderMember(folderId: String, memberId: String, direction: Int) = persist { repository.reorderFolderMember(folderId, memberId, direction) }

    fun addWidget(
        page: Int,
        cellX: Int,
        cellY: Int,
        appWidgetId: Int,
        provider: String,
        label: String,
        spanX: Int,
        spanY: Int,
        displace: List<WorkspaceGeometry.ReorderMove> = emptyList()
    ) {
        val prefs = state.value.prefs
        viewModelScope.launch {
            repository.addWidget(
                page, cellX, cellY, prefs.gridCols, prefs.gridRows, appWidgetId, provider, label, spanX, spanY,
                displace
            )
        }
    }

    // Every host id the model still references: workspace/dock items plus
    // feed-card featured widgets. sweepOrphanWidgetIds reclaims the rest, so
    // omitting a source here would delete its live widgets.
    // Every host id something still points at: widgets on the pages, widget
    // cards, and the search bar's Replace widget, which lives in a pref. The
    // orphan sweep deletes whatever this leaves out, so it must be complete,
    // and it is null while the layout can't be read: then nobody knows.
    suspend fun persistedWidgetIds(): Set<Int>? {
        if (!repository.modelReadable()) return null
        val model = repository.modelFlow.first()
        val searchBarWidget = preferences.snapshot().searchBarWidgetId
        return (model.items.mapNotNull { it.appWidgetId } +
            model.feedCards.mapNotNull { it.appWidgetId } +
            listOfNotNull(searchBarWidget.takeIf { it != 0 })).toSet()
    }

    fun resizeWidget(itemId: String, rect: WorkspaceGeometry.CellRect, onRefused: () -> Unit = {}) {
        val prefs = state.value.prefs
        viewModelScope.launch {
            if (!repository.resizeWidget(itemId, rect, prefs.gridCols, prefs.gridRows)) onRefused()
        }
    }

    fun setGridRows(value: Int) = persist { preferences.setGridRows(value) }

    fun setGridCols(value: Int) = persist { preferences.setGridCols(value) }

    fun setDockCount(value: Int) = persist { preferences.setDockCount(value) }

    fun setIconScale(value: Float) = persist { preferences.setIconScale(value) }

    fun setShowLabels(value: Boolean) = persist { preferences.setShowLabels(value) }

    fun setWallpaperScroll(value: Boolean) = persist { preferences.setWallpaperScroll(value) }

    fun setWallpaperScrollMode(value: String) = persist { preferences.setWallpaperScrollMode(value) }

    fun setPageIndicatorStyle(style: String) = persist { preferences.setPageIndicatorStyle(style) }

    fun setPageIndicatorAutoHide(value: Boolean) = persist { preferences.setPageIndicatorAutoHide(value) }

    fun setPageIndicatorColor(value: String) = persist { preferences.setPageIndicatorColor(value) }

    fun setInfiniteScroll(value: Boolean) = persist { preferences.setInfiniteScroll(value) }

    fun setRemoveEmptyPages(value: Boolean) = persist { preferences.setRemoveEmptyPages(value) }

    fun setDesktopPadding(horizontal: Int, vertical: Int) = persist { preferences.setDesktopPadding(horizontal, vertical) }

    fun setDesktopLocked(value: Boolean) = persist { preferences.setDesktopLocked(value) }

    fun setDockEnabled(value: Boolean) = persist { preferences.setDockEnabled(value) }

    fun setDockPages(value: Int) = persist { preferences.setDockPages(value) }

    fun setDockBgStyle(style: String) = persist { preferences.setDockBgStyle(style) }

    fun setDockBgOpacity(value: Float) = persist { preferences.setDockBgOpacity(value) }

    fun setDockBgBehindNav(value: Boolean) = persist { preferences.setDockBgBehindNav(value) }

    fun setSearchQueryShortcuts(value: Boolean) = persist { preferences.setSearchQueryShortcuts(value) }

    fun setSearchBarWidget(id: Int, provider: String) = persist { preferences.setSearchBarWidget(id, provider) }

    fun setSearchChips(value: String) = persist { preferences.setSearchChips(value) }

    fun setHomeButtonOnMainPage(value: Boolean) = persist { preferences.setHomeButtonOnMainPage(value) }

    fun setIconWiggle(value: Boolean) = persist { preferences.setIconWiggle(value) }

    fun setTransparentStatus(value: Boolean) = persist { preferences.setTransparentStatus(value) }

    fun setFolderShadow(value: Boolean) = persist { preferences.setFolderShadow(value) }

    fun setDrawerInfiniteScroll(value: Boolean) = persist { preferences.setDrawerInfiniteScroll(value) }

    fun setDrawerPageEffect(value: String) = persist { preferences.setDrawerPageEffect(value) }

    fun setDrawerTransition(value: String) = persist { preferences.setDrawerTransition(value) }

    fun setDockBgPadding(value: Boolean) = persist { preferences.setDockBgPadding(value) }

    fun setDockLabels(value: Boolean) = persist { preferences.setDockLabels(value) }

    fun setDockIconScale(value: Float) = persist { preferences.setDockIconScale(value) }

    fun setWidgetOverlap(value: Boolean) = persist { preferences.setWidgetOverlap(value) }

    fun setOverlapWhenPlacing(value: Boolean) = persist { preferences.setOverlapWhenPlacing(value) }

    fun setWidgetPadding(value: Int) = persist { preferences.setWidgetPadding(value) }

    fun setDrawerStyle(style: String) = persist { preferences.setDrawerStyle(style) }

    fun setDrawerColumns(value: Int) = persist { preferences.setDrawerColumns(value) }

    fun setDrawerRows(value: Int) = persist { preferences.setDrawerRows(value) }

    fun setDrawerAutoKeyboard(value: Boolean) = persist { preferences.setDrawerAutoKeyboard(value) }

    fun setDrawerTabBar(value: String) = persist { preferences.setDrawerTabBar(value) }

    fun setDrawerTabStyle(value: String) = persist { preferences.setDrawerTabStyle(value) }

    fun setDrawerTabsNoSwipe(value: Boolean) = persist { preferences.setDrawerTabsNoSwipe(value) }

    fun setDrawerFrequentRow(value: Boolean) = persist { preferences.setDrawerFrequentRow(value) }

    fun setDrawerRememberPosition(value: Boolean) = persist { preferences.setDrawerRememberPosition(value) }

    fun setDrawerAutoClose(value: Boolean) = persist { preferences.setDrawerAutoClose(value) }

    fun setDrawerFoldersFirst(value: Boolean) = persist { preferences.setDrawerFoldersFirst(value) }

    fun setDrawerIconScale(value: Float) = persist { preferences.setDrawerIconScale(value) }

    fun setDrawerMatchDesktop(value: Boolean) = persist { preferences.setDrawerMatchDesktop(value) }

    fun setDrawerBgOpacity(value: Float) = persist { preferences.setDrawerBgOpacity(value) }

    fun setDrawerFastScroller(value: Boolean) = persist { preferences.setDrawerFastScroller(value) }

    fun setDrawerPullToSearch(value: Boolean) = persist { preferences.setDrawerPullToSearch(value) }

    fun setDrawerSwipeIndicator(value: Boolean) = persist { preferences.setDrawerSwipeIndicator(value) }

    fun setDrawerSwipeHaptic(value: Boolean) = persist { preferences.setDrawerSwipeHaptic(value) }

    fun setSwipeDownHaptic(value: Boolean) = persist { preferences.setSwipeDownHaptic(value) }

    fun setDrawerScrubberColor(value: String) = persist { preferences.setDrawerScrubberColor(value) }

    fun setDrawerActionButtons(value: String) = persist { preferences.setDrawerActionButtons(value) }

    fun setDrawerSort(value: String) = persist { preferences.setDrawerSort(value) }

    fun setDrawerWorkTab(value: Boolean) = persist { preferences.setDrawerWorkTab(value) }

    fun setDrawerDefaultTab(value: String) = persist { preferences.setDrawerDefaultTab(value) }

    fun setDrawerHideAllTab(value: Boolean) = persist { preferences.setDrawerHideAllTab(value) }

    fun setLabelSize(value: Int) = persist { preferences.setLabelSize(value) }

    // Rewrites one context's slice of the labelConfig document. The transform
    // sees the current override; returning an all-null override goes back to
    // inheriting the desktop labels.
    fun updateLabelOverride(context: LabelContext, transform: (LabelOverride) -> LabelOverride) {
        val prefs = state.value.prefs
        val config = LabelConfig.decode(prefs.labelConfig)
        val updated = config.with(context, transform(config.of(context)))
        viewModelScope.launch { preferences.setLabelConfig(updated.encode()) }
    }

    fun setLabelColor(value: String) = persist { preferences.setLabelColor(value) }

    fun setLabelShadow(value: Boolean) = persist { preferences.setLabelShadow(value) }

    fun setDrawerLabels(value: Boolean) = persist { preferences.setDrawerLabels(value) }

    fun setFolderLabels(value: Boolean) = persist { preferences.setFolderLabels(value) }

    fun setIconNormalize(value: Boolean) = persist { preferences.setIconNormalize(value) }

    fun setReshapeLegacy(value: String) = persist { preferences.setReshapeLegacy(value) }

    fun setIconShadow(value: Boolean) = persist { preferences.setIconShadow(value) }

    fun setIconGlass(value: String) = persist { preferences.setIconGlass(value) }

    fun setIconSwipeIndicator(value: Boolean) = persist { preferences.setIconSwipeIndicator(value) }

    fun setThemedIconsAutogen(value: Boolean) = persist { preferences.setThemedIconsAutogen(value) }

    fun setThemedIcons(value: Boolean) = persist { preferences.setThemedIcons(value) }

    fun setLockMethod(value: String) = persist { preferences.setLockMethod(value) }

    fun setAnimationSpeed(value: String) = persist { preferences.setAnimationSpeed(value) }

    fun setDockPaddingH(value: Int) = persist { preferences.setDockPaddingH(value) }

    fun setDockPaddingV(value: Int) = persist { preferences.setDockPaddingV(value) }

    fun setBadgeColor(value: String) = persist { preferences.setBadgeColor(value) }

    fun setBadgePosition(value: String) = persist { preferences.setBadgePosition(value) }

    fun setBadgeScale(value: Float) = persist { preferences.setBadgeScale(value) }

    fun setBadgeTextColor(value: String) = persist { preferences.setBadgeTextColor(value) }

    fun setBadgeCornerRadius(value: Int) = persist { preferences.setBadgeCornerRadius(value) }

    fun setBadgeOutline(value: Boolean) = persist { preferences.setBadgeOutline(value) }

    fun setFolderPreviewStyle(value: String) = persist { preferences.setFolderPreviewStyle(value) }

    fun setFolderPrioritizeFirst(value: Boolean) = persist { preferences.setFolderPrioritizeFirst(value) }

    fun setFolderColumns(value: Int) = persist { preferences.setFolderColumns(value) }

    fun setFolderSortAz(value: Boolean) = persist { preferences.setFolderSortAz(value) }

    fun setFolderCloseOnLaunch(value: Boolean) = persist { preferences.setFolderCloseOnLaunch(value) }

    fun setFolderIconScale(value: Float) = persist { preferences.setFolderIconScale(value) }

    fun setFolderMatchDesktop(value: Boolean) = persist { preferences.setFolderMatchDesktop(value) }

    fun setFolderShowName(value: Boolean) = persist { preferences.setFolderShowName(value) }

    fun setFolderMode(value: String) = persist { preferences.setFolderMode(value) }

    fun setFolderScroll(value: String) = persist { preferences.setFolderScroll(value) }

    fun setFolderIconShape(value: String) = persist { preferences.setFolderIconShape(value) }

    fun setFolderIconOutline(value: Boolean) = persist { preferences.setFolderIconOutline(value) }

    fun setFolderClipToBg(value: Boolean) = persist { preferences.setFolderClipToBg(value) }

    fun setFolderMatchWindowColor(value: Boolean) = persist { preferences.setFolderMatchWindowColor(value) }

    fun setFolderBadges(value: Boolean) = persist { preferences.setFolderBadges(value) }

    fun setThemeMode(value: String) = persist { preferences.setThemeMode(value) }

    fun setThemeSchedule(startMinute: Int, endMinute: Int) = persist { preferences.setThemeSchedule(startMinute, endMinute) }

    fun setAccentColor(value: String) = persist { preferences.setAccentColor(value) }

    fun setDrawerBgColor(value: String) = persist { preferences.setDrawerBgColor(value) }

    fun setPaletteSource(value: String) = persist { preferences.setPaletteSource(value) }

    fun setPaletteSeed(value: String) = persist { preferences.setPaletteSeed(value) }

    fun setStyleConfig(value: String) = persist { preferences.setStyleConfig(value) }

    fun setWidgetCornerRadius(value: Int) = persist { preferences.setWidgetCornerRadius(value) }

    fun setShowScrims(value: Boolean) = persist { preferences.setShowScrims(value) }

    fun setWallpaperZoom(value: Boolean) = persist { preferences.setWallpaperZoom(value) }

    fun setAutoAddApps(value: Boolean) = persist { preferences.setAutoAddApps(value) }

    fun setLabelFont(value: String) = persist { preferences.setLabelFont(value) }

    fun setLabelSingleLine(value: Boolean) = persist { preferences.setLabelSingleLine(value) }

    fun setDockInfiniteScroll(value: Boolean) = persist { preferences.setDockInfiniteScroll(value) }

    fun setDockMatchDesktop(value: Boolean) = persist { preferences.setDockMatchDesktop(value) }

    fun setDockOverlay(value: Boolean) = persist { preferences.setDockOverlay(value) }

    fun setDockAutoClose(value: Boolean) = persist { preferences.setDockAutoClose(value) }

    fun setDockRememberPosition(value: Boolean) = persist { preferences.setDockRememberPosition(value) }

    fun setSearchWebProvider(value: String) = persist { preferences.setSearchWebProvider(value) }

    fun setSearchContacts(value: Boolean) = persist { preferences.setSearchContacts(value) }

    fun setSearchShortcuts(value: Boolean) = persist { preferences.setSearchShortcuts(value) }

    fun setSearchFolders(value: Boolean) = persist { preferences.setSearchFolders(value) }

    fun setSearchWindowMode(value: String) = persist { preferences.setSearchWindowMode(value) }

    fun setSearchAppsOneRow(value: Boolean) = persist { preferences.setSearchAppsOneRow(value) }

    fun setSearchContactCards(value: Boolean) = persist { preferences.setSearchContactCards(value) }

    fun setSearchCards(value: Boolean) = persist { preferences.setSearchCards(value) }

    fun setSearchCalculator(value: Boolean) = persist { preferences.setSearchCalculator(value) }

    fun setSearchHiddenApps(value: Boolean) = persist { preferences.setSearchHiddenApps(value) }

    fun setSearchHistory(value: Boolean) = persist { preferences.setSearchHistory(value) }

    fun setSearchPrivateKeyboard(value: Boolean) = persist { preferences.setSearchPrivateKeyboard(value) }

    fun setHomeSearch(value: Boolean) = persist { preferences.setHomeSearch(value) }

    fun setHomeButtonAction(value: String) = persist { preferences.setHomeButtonAction(value) }

    fun setSearchBarConfig(value: String) = persist { preferences.setSearchBarConfig(value) }

    fun setPopupNotifications(value: Boolean) = persist { preferences.setPopupNotifications(value) }

    fun setPopupCardBg(value: Boolean) = persist { preferences.setPopupCardBg(value) }

    fun setPopupGroupShortcuts(value: Boolean) = persist { preferences.setPopupGroupShortcuts(value) }

    fun setPopupStyle(value: String) = persist { preferences.setPopupStyle(value) }

    fun setPopupItemsHome(value: String) = persist { preferences.setPopupItemsHome(value) }

    fun setPopupItemsDrawer(value: String) = persist { preferences.setPopupItemsDrawer(value) }

    fun setDropTargets(value: Boolean) = persist { preferences.setDropTargets(value) }

    fun setUndoRemove(value: Boolean) = persist { preferences.setUndoRemove(value) }

    fun setDisableAnimations(value: Boolean) = persist { preferences.setDisableAnimations(value) }

    fun setLaunchAnimation(value: String) = persist { preferences.setLaunchAnimation(value) }

    fun setHideStatusBar(value: Boolean) = persist { preferences.setHideStatusBar(value) }

    fun setDarkStatusIcons(value: String) = persist { preferences.setDarkStatusIcons(value) }

    fun setTransparentNav(value: Boolean) = persist { preferences.setTransparentNav(value) }

    fun setHomeRotation(value: Boolean) = persist { preferences.setHomeRotation(value) }

    fun setHomeOrientation(value: String) = persist { preferences.setHomeOrientation(value) }

    fun setFeedEnabled(value: Boolean) = persist { preferences.setFeedEnabled(value) }

    fun setFeedEdgeSwipe(value: Boolean) = persist { preferences.setFeedEdgeSwipe(value) }

    fun setFeedShowSearch(value: Boolean) = persist { preferences.setFeedShowSearch(value) }

    fun setFeedShowFrequent(value: Boolean) = persist { preferences.setFeedShowFrequent(value) }

    fun setFeedShowShortcuts(value: Boolean) = persist { preferences.setFeedShowShortcuts(value) }

    fun setFeedShowMedia(value: Boolean) = persist { preferences.setFeedShowMedia(value) }

    fun setFeedShowCalendar(value: Boolean) = persist { preferences.setFeedShowCalendar(value) }

    fun setFeedShowContacts(value: Boolean) = persist { preferences.setFeedShowContacts(value) }

    fun setFeedShortcutRows(value: Int) = persist { preferences.setFeedShortcutRows(value) }

    fun setLabsEnabled(value: Boolean) = persist { preferences.setLabsEnabled(value) }

    fun setLabsShowComponent(value: Boolean) = persist { preferences.setLabsShowComponent(value) }

    fun setLabsExportPng(value: Boolean) = persist { preferences.setLabsExportPng(value) }

    fun setLabsBigGrid(value: Boolean) = persist { preferences.setLabsBigGrid(value) }

    fun clearIconCache() = persist { preferences.requestIconCacheClear() }

    // Labs' Export PNG: the drawn item, held across a recreate while the
    // file picker is open (LauncherActivity.exportPngLauncher).
    var pendingExportPng: android.graphics.Bitmap? = null

    // A widget part way through being added (LauncherActivity's
    // PendingWidget), held across a recreate while the system's bind or the
    // widget's own setup screen is up.
    var pendingWidgetAdd: Any? = null

    fun feedCards(): List<com.supernova.launcher.feed.FeedCard> = state.value.model.feedCards

    fun upsertFeedCard(card: com.supernova.launcher.feed.FeedCard) = persist { repository.upsertFeedCard(card) }

    fun removeFeedCard(cardId: String) = persist { repository.removeFeedCard(cardId) }

    // "Pin": flips the stored flag; the feed re-sorts on the emission.
    fun setFeedCardPinned(cardId: String, pinned: Boolean) {
        val card = feedCards().firstOrNull { it.id == cardId } ?: return
        if (card.pinned == pinned) return
        viewModelScope.launch { repository.upsertFeedCard(card.copy(pinned = pinned)) }
    }

    fun setCardWidget(cardId: String, appWidgetId: Int, provider: String) = persist { repository.setCardWidget(cardId, appWidgetId, provider) }

    // Undo-remove: puts the captured item (plus folder members) back.
    fun restoreItems(items: List<LauncherItem>) {
        val prefs = state.value.prefs
        viewModelScope.launch {
            repository.restoreItems(items, prefs.gridCols, prefs.gridRows, prefs.dockCount * prefs.dockPages.coerceAtLeast(1))
        }
    }

    // Snapshot for search ranking and the frequent-apps strip.
    fun launchCounts(): Map<String, Int> = launchCounts

    suspend fun recentSearches(): List<String> = preferences.recentSearches()

    fun recordSearch(query: String) = persist { preferences.recordSearch(query) }

    suspend fun removeRecentSearch(query: String) = preferences.removeRecentSearch(query)

    fun persistDockPage(page: Int) = persist { preferences.setDockPageState(page) }

    suspend fun restoredDockPage(): Int = preferences.restoredDockPage()

    fun setFolderBgColor(value: String) = persist { preferences.setFolderBgColor(value) }

    fun setIconPack(packageName: String) = persist { preferences.setIconPack(packageName) }

    fun setBadges(value: Boolean) = persist { preferences.setBadges(value) }

    fun setBadgeCounts(value: Boolean) = persist { preferences.setBadgeCounts(value) }

    fun setBadgeStyle(value: String) = persist { preferences.setBadgeStyle(value) }

    fun setBadgeApps(value: Set<String>) = persist { preferences.setBadgeApps(value) }

    fun setGestureBinding(gesture: Gesture, binding: GestureBinding) {
        viewModelScope.launch {
            preferences.setGestureBinding(gesture.name, GestureDefaults.encode(gesture, binding))
        }
    }

    fun gestureBindings(): Map<Gesture, GestureBinding> =
        GestureDefaults.decode(state.value.prefs.gestureBindings)

    fun shortcutsFor(packageName: String, userSerial: Long): List<android.content.pm.ShortcutInfo> =
        catalog.shortcutsFor(packageName, userSerial)

    fun setSubgrid(value: Boolean) = persist { preferences.setSubgrid(value) }

    fun setIconShape(shape: IconShape) = persist { preferences.setIconShape(shape.name) }

    fun setIconShapeCustom(value: String) = persist { preferences.setIconShapeCustom(value) }

    fun setScrollEffect(effect: ScrollEffect) = persist { preferences.setScrollEffect(effect.name) }

    fun setAppHidden(app: LaunchableApp, hidden: Boolean) = persist { preferences.setAppHidden(hiddenKey(app), hidden) }

    fun isAppHidden(app: LaunchableApp): Boolean = hiddenKey(app) in state.value.prefs.hiddenApps

    // Drawer visibility is per launchable activity, not per package: some apps
    // ship several entries and users hide them individually.
    fun hiddenKey(app: LaunchableApp): String = "${app.userSerial}/${app.packageName}/${app.className}"

    fun visibleApps(): List<LaunchableApp> {
        val hidden = state.value.prefs.hiddenApps
        return sortedApps(withOverrides(state.value.apps.filterNot { hiddenKey(it) in hidden }))
    }

    // The apps search matches: the drawer's, plus the hidden ones when the
    // user wants them found.
    fun searchableApps(): List<LaunchableApp> =
        if (state.value.prefs.searchHiddenApps) appsForPicking() else visibleApps()

    // Every app, hidden ones too, under the names the user gave them: what a
    // gesture or icon swipe can open.
    fun appsForPicking(): List<LaunchableApp> = sortedApps(withOverrides(state.value.apps))

    private fun withOverrides(apps: List<LaunchableApp>): List<LaunchableApp> {
        val overrides = state.value.model.appOverrides
        return apps.map { app ->
            val override = overrides[hiddenKey(app)] ?: return@map app
            app.copy(
                label = override.customLabel ?: app.label,
                hideLabel = override.hideLabel
            )
        }
    }

    fun overrideFor(item: LauncherItem): AppOverride? {
        // An app's rename and hidden label are the app icon's. A pinned
        // shortcut carries the same package and class, and took the app's
        // new name with them.
        if (item.itemType != ItemType.APP) return null
        val pkg = item.packageName ?: return null
        val cls = item.className ?: return null
        return state.value.model.appOverrides["${item.userSerial}/$pkg/$cls"]
    }

    fun overrideFor(app: LaunchableApp): AppOverride =
        state.value.model.appOverrides[hiddenKey(app)] ?: AppOverride()

    // The catalog's label, ignoring any rename override.
    fun rawLabelOf(app: LaunchableApp): String =
        state.value.apps.firstOrNull {
            it.packageName == app.packageName && it.className == app.className &&
                it.userSerial == app.userSerial
        }?.label ?: app.label

    fun renameShortcut(itemId: String, label: String) = persist { repository.renameShortcut(itemId, label) }

    fun setAppOverride(app: LaunchableApp, transform: (AppOverride) -> AppOverride) {
        val key = hiddenKey(app)
        viewModelScope.launch {
            val before = state.value.model.appOverrides[key] ?: AppOverride()
            val after = transform(before)
            repository.setAppOverride(key) { after }
            // A cleared rename restores the catalog label on pinned copies.
            if (before.customLabel != null && after.customLabel == null) {
                val raw = state.value.apps.firstOrNull {
                    it.packageName == app.packageName && it.className == app.className &&
                        it.userSerial == app.userSerial
                }?.label
                if (raw != null) {
                    repository.relabelItems(app.packageName, app.className, app.userSerial, raw)
                }
            }
        }
    }

    fun setFolderIcon(folderId: String, pack: String?, drawableName: String?, base64: String? = null) = persist { repository.setFolderIcon(folderId, pack, drawableName, base64) }

    fun customIcons(): CustomIcons.Count = CustomIcons.count(state.value.model)

    fun resetCustomIcons() = persist { repository.resetCustomIcons() }

    suspend fun packDrawableNames(packPackage: String): List<String> =
        withContext(Dispatchers.Default) { catalog.listPackDrawables(packPackage) }

    // By the label the drawer shows. The catalog comes A-Z by the apps' own
    // labels, which left a renamed app filed under its old name - and out
    // of the A-Z rail's reach.
    private fun sortedApps(apps: List<LaunchableApp>): List<LaunchableApp> =
        when (DrawerSort.fromName(state.value.prefs.drawerSort)) {
            DrawerSort.ALPHA -> byLabel(apps, descending = false)
            DrawerSort.ALPHA_REVERSE -> byLabel(apps, descending = true)
            DrawerSort.INSTALL_DATE -> apps.sortedByDescending { it.installTime }
            DrawerSort.MOST_USED -> apps.sortedWith(
                compareByDescending<LaunchableApp> { launchCounts[hiddenKey(it)] ?: 0 }
                    .thenBy { it.label.lowercase() }
            )
        }

    // Each label lowered once, not once per comparison: this runs on the
    // main thread for every list the drawer builds.
    private fun byLabel(apps: List<LaunchableApp>, descending: Boolean): List<LaunchableApp> {
        val keyed = apps.map { it.label.lowercase() to it }
        val sorted = if (descending) keyed.sortedByDescending { it.first } else keyed.sortedBy { it.first }
        return sorted.map { it.second }
    }

    // The row over the app list: the apps opened most recently. Until
    // there is any recency to go on, the most-launched stand in.
    fun recentApps(limit: Int): List<LaunchableApp> {
        val visible = visibleApps()
        if (recentLaunches.isNotEmpty()) {
            val byKey = visible.associateBy(::hiddenKey)
            val recent = recentLaunches.mapNotNull { byKey[it] }
            if (recent.isNotEmpty()) return recent.take(limit)
        }
        return visible.filter { (launchCounts[hiddenKey(it)] ?: 0) > 0 }
            .sortedByDescending { launchCounts[hiddenKey(it)] ?: 0 }
            .take(limit)
    }

    fun noteLaunch(app: LaunchableApp) = persist { preferences.recordLaunch(hiddenKey(app)) }

    fun personalApps(): List<LaunchableApp> = visibleApps().filter { it.userSerial == 0L }

    fun workApps(): List<LaunchableApp> = visibleApps().filter { it.userSerial != 0L }

    fun hasWorkApps(): Boolean = state.value.apps.any { it.userSerial != 0L }

    fun drawerGroups(): List<DrawerGroup> =
        state.value.prefs.drawerGroups.mapNotNull(DrawerGroup::decode)
            .sortedWith(compareBy({ it.order }, { it.name.lowercase() }))

    // TAB groups drive the tab bar; FOLDER groups render as drawer folders.
    fun drawerTabGroups(): List<DrawerGroup> =
        drawerGroups().filter { it.type == DrawerGroupType.TAB }

    fun drawerFolderGroups(): List<DrawerGroup> =
        drawerGroups().filter { it.type == DrawerGroupType.FOLDER }

    // Whether a group's apps leave the All/Personal list: the group's own
    // Keep apps in main app tab choice, else the old rule - a folder's apps
    // always left, a tab's only with the old global Isolate tabs switch on.
    fun leavesMainList(group: DrawerGroup): Boolean =
        group.keepInMain?.not() ?: when (group.type) {
            DrawerGroupType.FOLDER -> true
            DrawerGroupType.TAB -> state.value.prefs.drawerIsolateTabs
        }

    // Every key a TAB-type group takes out of the All/Personal list.
    fun isolatedTabKeys(): Set<String> =
        drawerGroups()
            .filter { it.type == DrawerGroupType.TAB && leavesMainList(it) }
            .flatMapTo(mutableSetOf()) { it.members }

    // Like any edit, it makes a smart folder the user's: regenerating the
    // smart ones would otherwise put the folder back without the choice.
    fun setGroupKeepInMain(group: DrawerGroup, keep: Boolean) {
        editGroup(group.name) { it.copy(keepInMain = keep, smart = false) }
    }

    fun setGroupType(group: DrawerGroup, type: DrawerGroupType) {
        // A user retype of a smart group takes ownership of it, so the smart
        // toggle can no longer silently delete their customization. A group
        // that never chose whether its apps stay in the main list keeps what
        // it did before, which its old type decided.
        editGroup(group.name) {
            it.copy(type = type, smart = false, keepInMain = it.keepInMain ?: !leavesMainList(it))
        }
    }

    // One group changed against what is stored, not against the caller's
    // copy of it; see LauncherPreferences.updateDrawerGroups.
    private fun editGroup(name: String, change: (DrawerGroup) -> DrawerGroup) {
        viewModelScope.launch {
            preferences.updateDrawerGroups { groups -> groups.map { if (it.name == name) change(it) else it } }
        }
    }

    // A group's name is its identity in storage, where '|' separates its
    // fields; a name holding one would lose the group's apps.
    private fun groupName(raw: String): String = raw.trim().replace('|', '/')

    // The name the group goes by afterwards (unchanged for an empty or
    // same name), or null, and nothing renamed, when another group has the
    // name: saving it would replace that group.
    fun renameGroup(group: DrawerGroup, newName: String): String? {
        val name = groupName(newName)
        if (name.isEmpty() || name == group.name) return group.name
        if (drawerGroups().any { it.name == name }) return null
        viewModelScope.launch {
            // One edit, so the group is never briefly missing (the drawer's
            // tabs would rebuild without it), and checked again inside it
            // for a group that took the name meanwhile.
            preferences.updateDrawerGroups { groups ->
                if (groups.any { it.name == name }) {
                    groups
                } else {
                    // A renamed smart folder is the user's now, and
                    // regenerating the smart ones leaves it be.
                    groups.map { if (it.name == group.name) it.copy(name = name, smart = false) else it }
                }
            }
            if (state.value.prefs.drawerDefaultTab == group.name) {
                preferences.setDrawerDefaultTab(name)
            }
        }
        return name
    }

    // Swaps the group with the next one of its own type in that direction,
    // then reassigns sequential orders: tabs and folders are separate lists
    // (the tab bar, the drawer's folders, the Drawer groups page), and a move
    // across one of the other type changed nothing anyone could see.
    fun moveGroup(name: String, delta: Int) {
        viewModelScope.launch {
            preferences.updateDrawerGroups { stored ->
                val groups = stored.sortedWith(compareBy({ it.order }, { it.name.lowercase() })).toMutableList()
                val index = groups.indexOfFirst { it.name == name }
                val target = if (index < 0) null else generateSequence(index + delta) { it + delta }
                    .takeWhile { it in groups.indices }
                    .firstOrNull { groups[it].type == groups[index].type }
                if (target == null) return@updateDrawerGroups stored
                groups[index] = groups[target].also { groups[target] = groups[index] }
                groups.mapIndexed { position, group -> group.copy(order = position) }
            }
        }
    }

    fun appsInGroup(group: DrawerGroup?): List<LaunchableApp> {
        val visible = visibleApps()
        if (group == null) return visible
        return visible.filter { hiddenKey(it) in group.members }
    }

    fun isInGroup(app: LaunchableApp, group: DrawerGroup): Boolean = hiddenKey(app) in group.members

    fun setGroupMembership(group: DrawerGroup, app: LaunchableApp, member: Boolean) {
        val key = hiddenKey(app)
        // Picking a smart folder's apps makes it the user's, as a retype does.
        editGroup(group.name) {
            it.copy(members = if (member) it.members + key else it.members - key, smart = false)
        }
    }

    // The name the group was stored under ('|' can't be in one), or null,
    // and nothing created, when the name is empty or already taken.
    fun createGroup(name: String, type: DrawerGroupType = DrawerGroupType.TAB, keepInMain: Boolean = false): String? {
        val clean = groupName(name)
        if (clean.isEmpty() || drawerGroups().any { it.name == clean }) return null
        viewModelScope.launch {
            preferences.saveDrawerGroup(DrawerGroup(clean, emptySet(), type = type, keepInMain = keepInMain))
        }
        return clean
    }

    fun removeGroup(name: String) {
        val smart = drawerGroups().firstOrNull { it.name == name }?.smart == true
        viewModelScope.launch {
            preferences.removeDrawerGroup(name)
            // A smart folder the user deleted stays deleted as apps install.
            if (smart) preferences.dismissSmartGroup(name)
        }
    }

    // Smart Folders: FOLDER groups generated from on-device categories. The
    // toggle owns the whole lifecycle - on generates, off removes only groups
    // carrying the smart marker. Turning them on again brings back the ones
    // the user deleted, too.
    fun setSmartFolders(value: Boolean) {
        viewModelScope.launch {
            preferences.setSmartFolders(value)
            if (value) {
                preferences.clearDismissedSmartGroups()
                regenerateSmartFolders()
            } else {
                preferences.removeSmartGroups()
            }
        }
    }

    // freshInstall: the one pass a new install makes as the wizard opens (see
    // seedDefaultLayout). It creates only the folders that start small; the
    // bigger categories are dismissed, as if the user had deleted them, so
    // no later pass brings them back. After that the folders are the user's:
    // they grow as apps install, and turning Smart Folders on again brings
    // the big ones back too.
    private suspend fun regenerateSmartFolders(freshInstall: Boolean = false) {
        val generated = withContext(Dispatchers.Default) {
            val hidden = state.value.prefs.hiddenApps
            // Fresh enumeration: the install hook fires before appsFlow updates.
            val apps = catalog.listLaunchableApps().filterNot { hiddenKey(it) in hidden }
            // Apps the user already placed in a folder group stay theirs.
            val claimed = drawerGroups()
                .filter { it.type == DrawerGroupType.FOLDER && !it.smart }
                .flatMap { it.members }
                .toSet()
            val byCategory = linkedMapOf<com.supernova.launcher.data.SmartCategory, MutableSet<String>>()
            apps.forEach { app ->
                val key = hiddenKey(app)
                if (key in claimed) return@forEach
                val category = com.supernova.launcher.data.AppCategories.categoryOf(
                    app.packageName, catalog.appCategory(app.packageName)
                ) ?: return@forEach
                byCategory.getOrPut(category) { mutableSetOf() }.add(key)
            }
            byCategory.filterValues { it.size >= SMART_FOLDER_MIN_APPS }.map { (category, members) ->
                DrawerGroup(
                    name = getApplication<Application>()
                        .getString(com.supernova.launcher.ui.settings.EnumLabels.of(category)),
                    members = members,
                    type = DrawerGroupType.FOLDER,
                    smart = true
                )
            }
        }
        val (kept, tooBig) = generated.partition {
            !freshInstall || it.members.size <= SMART_FOLDER_FRESH_INSTALL_MAX_APPS
        }
        // The store drops the dismissed names itself, in the same edit.
        preferences.replaceSmartGroups(kept, dismiss = tooBig.mapTo(mutableSetOf()) { it.name })
    }

    suspend fun availableIconPacks(): List<IconPack> =
        withContext(Dispatchers.Default) { catalog.availableIconPacks() }

    fun setSwipeAction(itemId: String, component: String?, label: String?, down: Boolean = false) = persist { repository.setSwipeAction(itemId, component, label, down) }

    fun addPage() = persist { repository.addPage() }

    fun removePage(page: Int, onRemoved: (() -> Unit)? = null) {
        viewModelScope.launch { if (repository.removePage(page)) onRemoved?.invoke() }
    }

    // onRemoved hears the pages that went (old numbers), when any did.
    fun removeEmptyPages(keep: Set<Int>, onRemoved: (List<Int>) -> Unit) {
        viewModelScope.launch {
            val removed = repository.removeEmptyPages(keep)
            if (removed.isNotEmpty()) onRemoved(removed)
        }
    }

    fun movePage(from: Int, to: Int) = persist { repository.movePage(from, to) }

    fun setDefaultPage(page: Int) = persist { repository.setDefaultPage(page) }

    suspend fun homePromptShown(): Boolean = preferences.homePromptShown()

    suspend fun markHomePromptShown() = preferences.markHomePromptShown()

    // Onboarding gate (device-local, like homePromptShown).
    suspend fun onboardingDone(): Boolean = preferences.onboardingDone()

    suspend fun markOnboardingDone() = preferences.markOnboardingDone()

    suspend fun markOnboardingComplete() = preferences.markOnboardingComplete()

    // Grandfathering check for the onboarding gate: any persisted layout item
    // marks this install as pre-onboarding. Reads the store directly because
    // the state snapshot may not have loaded yet at activity onCreate.
    suspend fun hasModelItems(): Boolean = repository.modelFlow.first().items.isNotEmpty()

    // The wizard plants the fresh-install layout as it opens; items it
    // planted don't make an install older than onboarding.
    suspend fun defaultLayoutSeeded(): Boolean = preferences.defaultLayoutSeeded()

    // The home screen a fresh install starts with (see
    // DefaultLayoutBuilder): on a phone, the device's phone, messages, maps,
    // browser and camera apps in the dock with the search bar under them,
    // and a few apps and Supernova Settings on the first page; on a tablet,
    // the default tablet dock. The first-run wizard plants it as it opens. Planted once per install, and only over a layout that
    // is still empty: an import brought its own arrangement. The drawer's
    // first smart folders come with it, the small ones only.
    suspend fun seedDefaultLayout() {
        if (!preferences.claimDefaultLayoutSeed()) return
        if (repository.modelFlow.first().items.isNotEmpty()) return
        val prefs = preferences.snapshot()
        val items = withContext(Dispatchers.Default) {
            DefaultLayoutBuilder.buildItems(getApplication(), catalog, prefs.gridCols, prefs.gridRows, prefs.dockCount)
        }
        repository.seedDefaultLayout(items)
        placeDefaultSearchBar()
        if (prefs.smartFolders) regenerateSmartFolders(freshInstall = true)
    }

    // The default home-screen bar: the pill under the dock
    // icons, in the fresh style (DesktopSearchBarConfig.FRESH). Never touches a
    // bar the user has already placed. Reads the store rather than the state
    // snapshot, which may not have loaded yet on the wizard's first frame.
    suspend fun placeDefaultSearchBar() {
        val config = SearchBarConfig.decode(preferences.snapshot().searchBarConfig)
        if (SearchBarPlacement.fromName(config.desktop.placement) != SearchBarPlacement.NONE) return
        preferences.setSearchBarConfig(config.copy(desktop = DesktopSearchBarConfig.FRESH).encode())
    }

    // Back to what a fresh install on this screen starts with: its grid and
    // the default search bar, over an empty home screen.
    suspend fun resetToDefaults() = repository.resetToDefaults(
        preferences.snapshot(),
        DeviceProfile.forDevice(getApplication()).applyTo(LauncherPrefs()).copy(
            searchBarConfig = SearchBarConfig(desktop = DesktopSearchBarConfig.FRESH).encode()
        )
    )

    suspend fun exportBackup(): String = repository.exportBackup(state.value.prefs)

    fun autoBackups(): List<java.io.File> = repository.autoBackups()

    suspend fun restoreAutoBackup(file: java.io.File): LauncherRepository.ImportResult =
        importBackup(file.readText())

    // One snapshot per session, taken once the real layout has loaded.
    fun writeAutoBackupOnce() {
        if (autoBackupWritten) return
        autoBackupWritten = true
        viewModelScope.launch {
            val prefs = state.value.prefs
            withContext(Dispatchers.Default) { repository.writeAutoBackup(prefs) }
        }
    }

    // The prefs for the snapshot come from the store: onboarding can import
    // before this view model's state has loaded them.
    // Off the main thread: the decode, an install check per item and the
    // snapshot write are no work for it.
    suspend fun importBackup(raw: String): LauncherRepository.ImportResult = withContext(Dispatchers.IO) {
        repository.importBackup(raw, preferences.snapshot()) { pkg, userSerial -> catalog.isInstalled(pkg, userSerial) }
    }

    // Null when the page has no free area of the requested size.
    fun firstFreeWorkspaceCell(page: Int, spanX: Int = 1, spanY: Int = 1): Pair<Int, Int>? {
        val current = state.value
        return WorkspaceGeometry.findFreeCell(
            model = current.model,
            page = page,
            spanX = spanX,
            spanY = spanY,
            cols = current.prefs.gridCols,
            rows = current.prefs.gridRows
        )
    }

    private companion object {
        // The icon size settings' top.
        const val MAX_ICON_RESOLUTION = 1.6f
        // How long a package-callback rescan waits for its burst to settle.
        const val REFRESH_COALESCE_MS = 300L
        // How long the first frame may wait for the startup warm before the
        // workspace is allowed to draw regardless.
        const val STARTUP_VALVE_MS = 1500L
        // ...and how much longer it then waits for the icon configuration
        // itself, before drawing with whatever the catalog has.
        const val STARTUP_VALVE_GIVE_UP_MS = 3500L
        // Categories with fewer apps than this don't earn a smart folder.
        const val SMART_FOLDER_MIN_APPS = 3
        // ...and on a fresh install, nor do ones with more than this.
        const val SMART_FOLDER_FRESH_INSTALL_MAX_APPS = 4
    }
}
