package com.supernova.launcher.data

import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.reflect.full.primaryConstructor

// Guards the bug class where a preference exists but silently falls out of
// backup/restore: the payload must round-trip every field, and writeAll must
// persist one key per LauncherPrefs property.
class BackupRoundTripTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // Every field deliberately non-default so a dropped field can't hide
    // behind a default value.
    private fun nonDefaultPrefs() = LauncherPrefs(
        gridRows = 7,
        gridCols = 6,
        dockCount = 4,
        iconScale = 1.25f,
        showLabels = false,
        wallpaperScroll = false,
        wallpaperScrollMode = "REVERSE",
        iconPack = "com.example.iconpack",
        scrollEffect = "CUBE",
        iconShape = "HEXAGON",
        iconShapeCustom = """{"radii":[10,20,30,40],"cuts":["ROUND","CUT","SQUIRCLE","ROUND"]}""",
        subgrid = true,
        badges = true,
        badgeCounts = true,
        badgeStyle = "DYNAMIC",
        badgeApps = setOf("0/com.a/com.a.Main"),
        iconShadow = true,
        iconSwipeIndicator = false,
        themedIcons = true,
        themedIconsAutogen = true,
        lockMethod = "DEVICE_ADMIN",
        hiddenApps = setOf("0/com.a/com.a.Main"),
        gestureBindings = setOf("SWIPE_UP:OPEN_SETTINGS", "DOUBLE_TAP:LOCK_SCREEN"),
        drawerGroups = setOf("Games|0/com.a/com.a.Main"),
        drawerRows = 4,
        drawerAutoKeyboard = true,
        drawerTabBar = "BOTTOM",
        drawerFrequentRow = true,
        drawerRememberPosition = true,
        drawerAutoClose = false,
        drawerFoldersFirst = false,
        smartFolders = true,
        smartFoldersDismissed = setOf("Games"),
        folderMode = "IMMERSIVE",
        folderScroll = "HORIZONTAL",
        folderIconShape = "CIRCLE",
        folderIconOutline = true,
        folderClipToBg = false,
        folderMatchWindowColor = true,
        folderBadges = true,
        paletteSource = "WALLPAPER",
        paletteSeed = "#336699",
        styleConfig = """{"surfaces":{"DRAWER":{"alpha":0.8}}}""",
        widgetCornerRadius = 8,
        pageIndicatorColor = "#FF1E88E5",
        showScrims = true,
        wallpaperZoom = false,
        autoAddApps = true,
        labelFont = "SERIF",
        labelConfig = """{"dock":{"size":10},"folder":{"shadow":true}}""",
        labelSingleLine = false,
        dockInfiniteScroll = true,
        dockMatchDesktop = true,
        dockOverlay = true,
        dockAutoClose = true,
        dockRememberPosition = true,
        dockPaddingH = 8,
        dockPaddingV = 4,
        searchWebProvider = "DUCKDUCKGO",
        searchContacts = true,
        searchShortcuts = false,
        searchFolders = false,
        searchWindowMode = "WINDOW",
        searchAppsOneRow = true,
        searchContactCards = true,
        searchCards = false,
        searchCalculator = false,
        searchHistory = false,
        searchPrivateKeyboard = true,
        homeSearch = true,
        homeButtonAction = "OPEN_DRAWER",
        searchBarConfig = """{"desktop":{"placement":"DOCK_ABOVE","chips":["VOICE","DATE"]}}""",
        popupNotifications = false,
        popupCardBg = true,
        popupGroupShortcuts = true,
        popupStyle = "PILLS",
        popupItemsHome = "PLAY_STORE,CLOSE_APP",
        popupItemsDrawer = "NONE",
        dropTargets = true,
        undoRemove = false,
        disableAnimations = true,
        animationSpeed = "FAST",
        launchAnimation = "SLIDE_UP",
        hideStatusBar = true,
        darkStatusIcons = "LIGHT",
        transparentNav = false,
        homeRotation = true,
        homeOrientation = "LANDSCAPE",
        themeScheduleStart = 1200,
        themeScheduleEnd = 360,
        themeAutoLocation = true,
        overlapWhenPlacing = true,
        reshapeLegacy = "ON",
        drawerPullToSearch = true,
        drawerSwipeIndicator = true,
        drawerSwipeHaptic = false,
        drawerScrubberColor = "#FF00FF00",
        drawerActionButtons = "SEARCH,SETTINGS",
        drawerIsolateTabs = true,
        folderPrioritizeFirst = true,
        folderIconScale = 1.3f,
        folderMatchDesktop = true,
        folderShowName = false,
        drawerMatchDesktop = true,
        dockBgBehindNav = true,
        feedEnabled = true,
        feedEdgeSwipe = false,
        feedShowSearch = false,
        feedShowFrequent = false,
        feedShowShortcuts = true,
        feedShowMedia = false,
        feedShowCalendar = true,
        feedShowContacts = true,
        feedShortcutRows = 3,
        labsEnabled = true,
        labsShowComponent = true,
        labsExportPng = true,
        labsBigGrid = true
    )

    private fun sampleModel() = LauncherModel(
        version = CURRENT_MODEL_VERSION,
        items = listOf(
            LauncherItem(
                id = "app",
                itemType = ItemType.APP,
                page = 1,
                cellX = 2,
                cellY = 3,
                label = "App",
                packageName = "com.a",
                className = "com.a.Main",
                userSerial = 10L,
                swipeAction = "com.b/com.b.Main",
                swipeLabel = "B",
                swipeActionDown = "com.c/com.c.Main",
                swipeLabelDown = "C"
            ),
            LauncherItem(
                id = "folder",
                itemType = ItemType.FOLDER,
                page = 1,
                cellX = 0,
                cellY = 0,
                label = "Games",
                swipeToOpen = true
            ),
            LauncherItem(
                id = "widget",
                itemType = ItemType.WIDGET,
                spanX = 2,
                spanY = 2,
                appWidgetId = 42,
                widgetProvider = "com.w/com.w.Provider",
                widgetPadding = 12
            ),
            // A launcher-action item and an activity item exercise the new
            // intentUri/iconBase64 fields through the backup round-trip.
            LauncherItem(
                id = "action",
                itemType = ItemType.SHORTCUT,
                page = 0,
                cellX = 0,
                cellY = 0,
                label = "Open drawer",
                intentUri = "supernova://action/OPEN_DRAWER"
            ),
            LauncherItem(
                id = "activity",
                itemType = ItemType.SHORTCUT,
                page = 0,
                cellX = 1,
                cellY = 0,
                label = "Some activity",
                intentUri = "intent:#Intent;component=com.x/.Act;end",
                iconBase64 = "AAAA"
            )
        ),
        pageCount = 3,
        defaultPage = 1
    )

    @Test
    fun backupPayloadRoundTripsEveryField() {
        val payload = BackupPayload(
            exportedAtEpochMs = 1_722_000_000_000,
            model = sampleModel(),
            prefs = nonDefaultPrefs()
        )
        val decoded = json.decodeFromString<BackupPayload>(json.encodeToString(BackupPayload.serializer(), payload))
        assertEquals(payload, decoded)
    }

    // A backup written before this session's fields existed must still decode:
    // omitted prefs fall back to their defaults and omitted item fields to null,
    // so an older export imports without loss or crash.
    @Test
    fun backupOmittingNewFieldsDecodesToDefaults() {
        val raw = """
            {"version":1,"exportedAtEpochMs":1,
             "model":{"version":5,"items":[
               {"id":"a","itemType":"APP","label":"A","packageName":"p","className":"c"}
             ],"pageCount":1,"defaultPage":0},
             "prefs":{"gridRows":5,"gridCols":4}}
        """.trimIndent()
        val decoded = json.decodeFromString<BackupPayload>(raw)
        // New item fields default to null.
        val item = decoded.model.items.single()
        assertEquals(null, item.intentUri)
        assertEquals(null, item.iconBase64)
        // New prefs fall back to their declared defaults.
        assertEquals(false, decoded.prefs.themedIcons)
        assertEquals(false, decoded.prefs.folderBadges)
        assertEquals("ACCESSIBILITY", decoded.prefs.lockMethod)
        assertEquals("DEFAULT", decoded.prefs.animationSpeed)
        assertEquals(0, decoded.prefs.dockPaddingH)
    }

    @Test
    fun writeAllPersistsOneKeyPerPrefsField() {
        val target = mutablePreferencesOf()
        LauncherPreferences.writeAll(target, nonDefaultPrefs())

        val fieldCount = LauncherPrefs::class.primaryConstructor!!.parameters.size
        assertEquals(
            "writeAll must persist exactly one key per LauncherPrefs field; " +
                "a new pref was probably added without a writeAll line",
            fieldCount,
            target.asMap().size
        )
    }

    // A grid set with Labs' Big grid size options on has to come back from
    // a backup or preset as it was: clamped to the normal limits, its items
    // would sit off the grid.
    @Test
    fun writeAllKeepsBigGridSizes() {
        val target = mutablePreferencesOf()
        LauncherPreferences.writeAll(
            target,
            nonDefaultPrefs().copy(
                gridRows = GridLimits.DESKTOP_BIG,
                gridCols = 16,
                dockCount = GridLimits.DOCK_BIG,
                drawerColumns = GridLimits.DRAWER_COLUMNS_BIG,
                drawerRows = GridLimits.DRAWER_ROWS_BIG,
                folderColumns = GridLimits.FOLDER_COLUMNS_BIG
            )
        )
        assertEquals(GridLimits.DESKTOP_BIG, target[StoreKeys.gridRows])
        assertEquals(16, target[StoreKeys.gridCols])
        assertEquals(GridLimits.DOCK_BIG, target[StoreKeys.dockCount])
        assertEquals(GridLimits.DRAWER_COLUMNS_BIG, target[StoreKeys.drawerColumns])
        assertEquals(GridLimits.DRAWER_ROWS_BIG, target[StoreKeys.drawerRows])
        assertEquals(GridLimits.FOLDER_COLUMNS_BIG, target[StoreKeys.folderColumns])
    }

    @Test
    fun writeAllPersistsTheValuesItWasGiven() {
        val target = mutablePreferencesOf()
        val prefs = nonDefaultPrefs()
        LauncherPreferences.writeAll(target, prefs)

        assertEquals(7, target[StoreKeys.gridRows])
        assertEquals(6, target[StoreKeys.gridCols])
        assertEquals(4, target[StoreKeys.dockCount])
        assertEquals(1.25f, target[StoreKeys.iconScale])
        assertEquals(false, target[StoreKeys.showLabels])
        assertEquals(false, target[StoreKeys.wallpaperScroll])
        assertEquals("com.example.iconpack", target[StoreKeys.iconPack])
        assertEquals("CUBE", target[StoreKeys.scrollEffect])
        assertEquals("HEXAGON", target[StoreKeys.iconShape])
        assertEquals(true, target[StoreKeys.subgrid])
        assertEquals(true, target[StoreKeys.badges])
        assertEquals(true, target[StoreKeys.badgeCounts])
        assertEquals(prefs.hiddenApps, target[StoreKeys.hiddenApps])
        assertEquals(prefs.gestureBindings, target[StoreKeys.gestureBindings])
        assertEquals(prefs.drawerGroups, target[StoreKeys.drawerGroups])
    }
}
