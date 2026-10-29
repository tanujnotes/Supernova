package com.supernova.launcher.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class LauncherPreferences(private val context: Context) {

    // A plain setting is one key written. The setters that read first or
    // write several keys keep their own edit block.
    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        context.supernovaStore.edit { it[key] = value }
    }

    companion object {
        private const val RECENT_LAUNCHES_MAX = 12
        const val MAX_RECENT_SEARCHES = 8

        // The write-side twin of `flow`: every LauncherPrefs field maps to its
        // key here, so a pref that reads must also restore. homePromptShown is
        // device-local state, not a preference, and stays out of both.
        fun writeAll(target: MutablePreferences, prefs: LauncherPrefs) {
            target[StoreKeys.gridRows] = prefs.gridRows.coerceIn(2, GridLimits.DESKTOP_BIG)
            target[StoreKeys.gridCols] = prefs.gridCols.coerceIn(2, GridLimits.DESKTOP_BIG)
            target[StoreKeys.dockCount] = prefs.dockCount.coerceIn(3, GridLimits.DOCK_BIG)
            target[StoreKeys.iconScale] = prefs.iconScale.coerceIn(0.7f, 1.6f)
            target[StoreKeys.showLabels] = prefs.showLabels
            target[StoreKeys.wallpaperScroll] = prefs.wallpaperScroll
            target[StoreKeys.wallpaperScrollMode] = prefs.wallpaperScrollMode
            target[StoreKeys.iconPack] = prefs.iconPack
            target[StoreKeys.scrollEffect] = prefs.scrollEffect
            target[StoreKeys.iconShape] = prefs.iconShape
            target[StoreKeys.iconShapeCustom] = prefs.iconShapeCustom
            target[StoreKeys.subgrid] = prefs.subgrid
            target[StoreKeys.badges] = prefs.badges
            target[StoreKeys.badgeCounts] = prefs.badgeCounts
            target[StoreKeys.badgeStyle] = prefs.badgeStyle
            target[StoreKeys.badgeApps] = prefs.badgeApps
            target[StoreKeys.hiddenApps] = prefs.hiddenApps
            target[StoreKeys.gestureBindings] = prefs.gestureBindings
            target[StoreKeys.drawerGroups] = prefs.drawerGroups
            target[StoreKeys.pageIndicatorStyle] = prefs.pageIndicatorStyle
            target[StoreKeys.pageIndicatorAutoHide] = prefs.pageIndicatorAutoHide
            target[StoreKeys.pageIndicatorColor] = prefs.pageIndicatorColor
            target[StoreKeys.infiniteScroll] = prefs.infiniteScroll
            target[StoreKeys.removeEmptyPages] = prefs.removeEmptyPages
            target[StoreKeys.desktopPaddingH] = prefs.desktopPaddingH.coerceIn(0, 48)
            target[StoreKeys.desktopPaddingV] = prefs.desktopPaddingV.coerceIn(0, 48)
            target[StoreKeys.desktopLocked] = prefs.desktopLocked
            target[StoreKeys.dockEnabled] = prefs.dockEnabled
            target[StoreKeys.dockPages] = prefs.dockPages.coerceIn(1, 3)
            target[StoreKeys.dockBgStyle] = prefs.dockBgStyle
            target[StoreKeys.dockBgOpacity] = prefs.dockBgOpacity.coerceIn(0f, 1f)
            target[StoreKeys.dockBgBehindNav] = prefs.dockBgBehindNav
            target[StoreKeys.dockBgPadding] = prefs.dockBgPadding
            target[StoreKeys.dockLabels] = prefs.dockLabels
            target[StoreKeys.dockIconScale] = prefs.dockIconScale.coerceIn(0.7f, 1.6f)
            target[StoreKeys.widgetOverlap] = prefs.widgetOverlap
            target[StoreKeys.overlapWhenPlacing] = prefs.overlapWhenPlacing
            target[StoreKeys.widgetPadding] = prefs.widgetPadding.coerceIn(0, 24)
            target[StoreKeys.drawerStyle] = prefs.drawerStyle
            target[StoreKeys.drawerColumns] = prefs.drawerColumns.coerceIn(0, GridLimits.DRAWER_COLUMNS_BIG)
            target[StoreKeys.drawerRows] = prefs.drawerRows.coerceIn(0, GridLimits.DRAWER_ROWS_BIG)
            target[StoreKeys.drawerAutoKeyboard] = prefs.drawerAutoKeyboard
            target[StoreKeys.drawerTabBar] = prefs.drawerTabBar
            target[StoreKeys.drawerTabStyle] = prefs.drawerTabStyle
            target[StoreKeys.drawerTabsNoSwipe] = prefs.drawerTabsNoSwipe
            target[StoreKeys.drawerFrequentRow] = prefs.drawerFrequentRow
            target[StoreKeys.drawerTransition] = prefs.drawerTransition
            target[StoreKeys.folderShadow] = prefs.folderShadow
            target[StoreKeys.transparentStatus] = prefs.transparentStatus
            target[StoreKeys.iconWiggle] = prefs.iconWiggle
            target[StoreKeys.homeButtonOnMainPage] = prefs.homeButtonOnMainPage
            target[StoreKeys.searchChips] = prefs.searchChips
            target[StoreKeys.searchBarWidgetId] = prefs.searchBarWidgetId
            target[StoreKeys.searchBarWidgetProvider] = prefs.searchBarWidgetProvider
            target[StoreKeys.searchQueryShortcuts] = prefs.searchQueryShortcuts
            target[StoreKeys.drawerInfiniteScroll] = prefs.drawerInfiniteScroll
            target[StoreKeys.drawerPageEffect] = prefs.drawerPageEffect
            target[StoreKeys.drawerRememberPosition] = prefs.drawerRememberPosition
            target[StoreKeys.drawerAutoClose] = prefs.drawerAutoClose
            target[StoreKeys.drawerFoldersFirst] = prefs.drawerFoldersFirst
            target[StoreKeys.smartFolders] = prefs.smartFolders
            target[StoreKeys.smartFoldersDismissed] = prefs.smartFoldersDismissed
            target[StoreKeys.drawerIconScale] = prefs.drawerIconScale.coerceIn(0.7f, 1.6f)
            target[StoreKeys.drawerMatchDesktop] = prefs.drawerMatchDesktop
            target[StoreKeys.drawerBgOpacity] = prefs.drawerBgOpacity.coerceIn(0.2f, 1f)
            target[StoreKeys.drawerFastScroller] = prefs.drawerFastScroller
            target[StoreKeys.drawerPullToSearch] = prefs.drawerPullToSearch
            target[StoreKeys.drawerSwipeIndicator] = prefs.drawerSwipeIndicator
            target[StoreKeys.drawerSwipeHaptic] = prefs.drawerSwipeHaptic
            target[StoreKeys.swipeDownHaptic] = prefs.swipeDownHaptic
            target[StoreKeys.drawerScrubberColor] = prefs.drawerScrubberColor
            target[StoreKeys.drawerActionButtons] = prefs.drawerActionButtons
            target[StoreKeys.drawerIsolateTabs] = prefs.drawerIsolateTabs
            target[StoreKeys.drawerSort] = prefs.drawerSort
            target[StoreKeys.drawerWorkTab] = prefs.drawerWorkTab
            target[StoreKeys.drawerDefaultTab] = prefs.drawerDefaultTab
            target[StoreKeys.drawerHideAllTab] = prefs.drawerHideAllTab
            target[StoreKeys.labelSize] = prefs.labelSize.coerceIn(9, 18)
            target[StoreKeys.labelConfig] = prefs.labelConfig
            target[StoreKeys.labelColor] = prefs.labelColor
            target[StoreKeys.labelShadow] = prefs.labelShadow
            target[StoreKeys.drawerLabels] = prefs.drawerLabels
            target[StoreKeys.folderLabels] = prefs.folderLabels
            target[StoreKeys.iconNormalize] = prefs.iconNormalize
            target[StoreKeys.reshapeLegacy] = prefs.reshapeLegacy
            target[StoreKeys.iconShadow] = prefs.iconShadow
            target[StoreKeys.iconGlass] = prefs.iconGlass
            target[StoreKeys.iconSwipeIndicator] = prefs.iconSwipeIndicator
            target[StoreKeys.themedIcons] = prefs.themedIcons
            target[StoreKeys.themedIconsAutogen] = prefs.themedIconsAutogen
            target[StoreKeys.lockMethod] = prefs.lockMethod
            target[StoreKeys.badgeColor] = prefs.badgeColor
            target[StoreKeys.badgePosition] = prefs.badgePosition
            target[StoreKeys.badgeScale] = prefs.badgeScale.coerceIn(0.6f, 1.6f)
            target[StoreKeys.badgeTextColor] = prefs.badgeTextColor
            target[StoreKeys.badgeCornerRadius] = prefs.badgeCornerRadius.coerceIn(0, BadgeLimits.CORNER_ROUND)
            target[StoreKeys.badgeOutline] = prefs.badgeOutline
            target[StoreKeys.folderPreviewStyle] = prefs.folderPreviewStyle
            target[StoreKeys.folderPrioritizeFirst] = prefs.folderPrioritizeFirst
            target[StoreKeys.folderColumns] = prefs.folderColumns.coerceIn(0, GridLimits.FOLDER_COLUMNS_BIG)
            target[StoreKeys.folderSortAz] = prefs.folderSortAz
            target[StoreKeys.folderCloseOnLaunch] = prefs.folderCloseOnLaunch
            target[StoreKeys.folderIconScale] = prefs.folderIconScale.coerceIn(0.7f, 1.6f)
            target[StoreKeys.folderMatchDesktop] = prefs.folderMatchDesktop
            target[StoreKeys.folderShowName] = prefs.folderShowName
            target[StoreKeys.folderShowCount] = prefs.folderShowCount
            target[StoreKeys.folderMode] = prefs.folderMode
            target[StoreKeys.folderScroll] = prefs.folderScroll
            target[StoreKeys.folderIconShape] = prefs.folderIconShape
            target[StoreKeys.folderIconOutline] = prefs.folderIconOutline
            target[StoreKeys.folderClipToBg] = prefs.folderClipToBg
            target[StoreKeys.folderMatchWindowColor] = prefs.folderMatchWindowColor
            target[StoreKeys.folderBadges] = prefs.folderBadges
            target[StoreKeys.themeMode] = prefs.themeMode
            target[StoreKeys.themeScheduleStart] = prefs.themeScheduleStart.coerceIn(0, 1439)
            target[StoreKeys.themeScheduleEnd] = prefs.themeScheduleEnd.coerceIn(0, 1439)
            target[StoreKeys.accentColor] = prefs.accentColor
            target[StoreKeys.drawerBgColor] = prefs.drawerBgColor
            target[StoreKeys.folderBgColor] = prefs.folderBgColor
            target[StoreKeys.paletteSource] = prefs.paletteSource
            target[StoreKeys.paletteSeed] = prefs.paletteSeed
            target[StoreKeys.styleConfig] = prefs.styleConfig
            target[StoreKeys.widgetCornerRadius] = prefs.widgetCornerRadius.coerceIn(0, 32)
            target[StoreKeys.showScrims] = prefs.showScrims
            target[StoreKeys.wallpaperZoom] = prefs.wallpaperZoom
            target[StoreKeys.autoAddApps] = prefs.autoAddApps
            target[StoreKeys.labelFont] = prefs.labelFont
            target[StoreKeys.labelSingleLine] = prefs.labelSingleLine
            target[StoreKeys.dockInfiniteScroll] = prefs.dockInfiniteScroll
            target[StoreKeys.dockMatchDesktop] = prefs.dockMatchDesktop
            target[StoreKeys.dockOverlay] = prefs.dockOverlay
            target[StoreKeys.dockAutoClose] = prefs.dockAutoClose
            target[StoreKeys.dockRememberPosition] = prefs.dockRememberPosition
            target[StoreKeys.dockPaddingH] = prefs.dockPaddingH
            target[StoreKeys.dockPaddingV] = prefs.dockPaddingV
            target[StoreKeys.searchWebProvider] = prefs.searchWebProvider
            target[StoreKeys.searchContacts] = prefs.searchContacts
            target[StoreKeys.searchShortcuts] = prefs.searchShortcuts
            target[StoreKeys.searchFolders] = prefs.searchFolders
            target[StoreKeys.searchWindowMode] = prefs.searchWindowMode
            target[StoreKeys.searchAppsOneRow] = prefs.searchAppsOneRow
            target[StoreKeys.searchContactCards] = prefs.searchContactCards
            target[StoreKeys.searchCards] = prefs.searchCards
            target[StoreKeys.searchCalculator] = prefs.searchCalculator
            target[StoreKeys.searchHiddenApps] = prefs.searchHiddenApps
            target[StoreKeys.searchHistory] = prefs.searchHistory
            target[StoreKeys.searchPrivateKeyboard] = prefs.searchPrivateKeyboard
            target[StoreKeys.homeSearch] = prefs.homeSearch
            target[StoreKeys.homeButtonAction] = prefs.homeButtonAction
            target[StoreKeys.searchBarConfig] = prefs.searchBarConfig
            target[StoreKeys.popupNotifications] = prefs.popupNotifications
            target[StoreKeys.popupCardBg] = prefs.popupCardBg
            target[StoreKeys.popupGroupShortcuts] = prefs.popupGroupShortcuts
            target[StoreKeys.popupActionIcons] = prefs.popupActionIcons
            target[StoreKeys.popupStyle] = prefs.popupStyle
            target[StoreKeys.popupItemsHome] = prefs.popupItemsHome
            target[StoreKeys.popupItemsDrawer] = prefs.popupItemsDrawer
            target[StoreKeys.dropTargets] = prefs.dropTargets
            target[StoreKeys.undoRemove] = prefs.undoRemove
            target[StoreKeys.disableAnimations] = prefs.disableAnimations
            target[StoreKeys.animationSpeed] = prefs.animationSpeed
            target[StoreKeys.launchAnimation] = prefs.launchAnimation
            target[StoreKeys.hideStatusBar] = prefs.hideStatusBar
            target[StoreKeys.darkStatusIcons] = prefs.darkStatusIcons
            target[StoreKeys.transparentNav] = prefs.transparentNav
            target[StoreKeys.homeRotation] = prefs.homeRotation
            target[StoreKeys.homeOrientation] = prefs.homeOrientation
            target[StoreKeys.feedEnabled] = prefs.feedEnabled
            target[StoreKeys.feedEdgeSwipe] = prefs.feedEdgeSwipe
            target[StoreKeys.feedShowSearch] = prefs.feedShowSearch
            target[StoreKeys.feedShowFrequent] = prefs.feedShowFrequent
            target[StoreKeys.feedShowShortcuts] = prefs.feedShowShortcuts
            target[StoreKeys.feedShowMedia] = prefs.feedShowMedia
            target[StoreKeys.feedShowCalendar] = prefs.feedShowCalendar
            target[StoreKeys.feedShowContacts] = prefs.feedShowContacts
            target[StoreKeys.feedShortcutRows] = prefs.feedShortcutRows
            target[StoreKeys.labsEnabled] = prefs.labsEnabled
            target[StoreKeys.labsShowComponent] = prefs.labsShowComponent
            target[StoreKeys.labsExportPng] = prefs.labsExportPng
            target[StoreKeys.labsBigGrid] = prefs.labsBigGrid
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

    // distinctUntilChanged: unrelated store writes (the model document,
    // launch counts) otherwise re-emit an equal snapshot and re-render the UI.
    val flow: Flow<LauncherPrefs> = context.supernovaStore.data.map { values ->
        LauncherPrefs(
            gridRows = (values[StoreKeys.gridRows] ?: 6).coerceIn(2, GridLimits.DESKTOP_BIG),
            gridCols = (values[StoreKeys.gridCols] ?: 5).coerceIn(2, GridLimits.DESKTOP_BIG),
            dockCount = (values[StoreKeys.dockCount] ?: 5).coerceIn(3, GridLimits.DOCK_BIG),
            iconScale = (values[StoreKeys.iconScale] ?: 1f).coerceIn(0.7f, 1.6f),
            showLabels = values[StoreKeys.showLabels] ?: true,
            wallpaperScroll = values[StoreKeys.wallpaperScroll] ?: true,
            wallpaperScrollMode = values[StoreKeys.wallpaperScrollMode].orEmpty(),
            iconPack = values[StoreKeys.iconPack].orEmpty(),
            scrollEffect = values[StoreKeys.scrollEffect] ?: "NONE",
            iconShape = values[StoreKeys.iconShape] ?: "SYSTEM",
            iconShapeCustom = values[StoreKeys.iconShapeCustom].orEmpty(),
            subgrid = values[StoreKeys.subgrid] ?: false,
            badges = values[StoreKeys.badges] ?: false,
            badgeCounts = values[StoreKeys.badgeCounts] ?: false,
            badgeStyle = values[StoreKeys.badgeStyle].orEmpty(),
            badgeApps = values[StoreKeys.badgeApps].orEmpty(),
            hiddenApps = values[StoreKeys.hiddenApps].orEmpty(),
            gestureBindings = values[StoreKeys.gestureBindings].orEmpty(),
            drawerGroups = values[StoreKeys.drawerGroups].orEmpty(),
            pageIndicatorStyle = values[StoreKeys.pageIndicatorStyle] ?: "LINE",
            pageIndicatorAutoHide = values[StoreKeys.pageIndicatorAutoHide] ?: true,
            pageIndicatorColor = values[StoreKeys.pageIndicatorColor].orEmpty(),
            infiniteScroll = values[StoreKeys.infiniteScroll] ?: false,
            removeEmptyPages = values[StoreKeys.removeEmptyPages] ?: true,
            desktopPaddingH = (values[StoreKeys.desktopPaddingH] ?: 0).coerceIn(0, 48),
            desktopPaddingV = (values[StoreKeys.desktopPaddingV] ?: 12).coerceIn(0, 48),
            desktopLocked = values[StoreKeys.desktopLocked] ?: false,
            dockEnabled = values[StoreKeys.dockEnabled] ?: true,
            dockPages = (values[StoreKeys.dockPages] ?: 1).coerceIn(1, 3),
            dockBgStyle = values[StoreKeys.dockBgStyle] ?: "NONE",
            dockBgOpacity = (values[StoreKeys.dockBgOpacity] ?: 0.3f).coerceIn(0f, 1f),
            dockBgBehindNav = values[StoreKeys.dockBgBehindNav] ?: false,
            dockBgPadding = values[StoreKeys.dockBgPadding] ?: false,
            dockLabels = values[StoreKeys.dockLabels] ?: false,
            dockIconScale = (values[StoreKeys.dockIconScale] ?: 1f).coerceIn(0.7f, 1.6f),
            widgetOverlap = values[StoreKeys.widgetOverlap] ?: false,
            overlapWhenPlacing = values[StoreKeys.overlapWhenPlacing] ?: false,
            widgetPadding = (values[StoreKeys.widgetPadding] ?: 0).coerceIn(0, 24),
            drawerStyle = values[StoreKeys.drawerStyle] ?: "VERTICAL_GRID",
            drawerColumns = (values[StoreKeys.drawerColumns] ?: 0).coerceIn(0, GridLimits.DRAWER_COLUMNS_BIG),
            drawerRows = (values[StoreKeys.drawerRows] ?: 0).coerceIn(0, GridLimits.DRAWER_ROWS_BIG),
            drawerAutoKeyboard = values[StoreKeys.drawerAutoKeyboard] ?: true,
            drawerTabBar = values[StoreKeys.drawerTabBar] ?: "TOP",
            drawerTabStyle = values[StoreKeys.drawerTabStyle] ?: "CLASSIC",
            drawerTabsNoSwipe = values[StoreKeys.drawerTabsNoSwipe] ?: false,
            drawerFrequentRow = values[StoreKeys.drawerFrequentRow] ?: true,
            drawerTransition = values[StoreKeys.drawerTransition] ?: "SLIDE_UP",
            folderShadow = values[StoreKeys.folderShadow] ?: true,
            transparentStatus = values[StoreKeys.transparentStatus] ?: true,
            iconWiggle = values[StoreKeys.iconWiggle] ?: true,
            homeButtonOnMainPage = values[StoreKeys.homeButtonOnMainPage] ?: true,
            searchChips = values[StoreKeys.searchChips] ?: "",
            searchBarWidgetId = values[StoreKeys.searchBarWidgetId] ?: 0,
            searchBarWidgetProvider = values[StoreKeys.searchBarWidgetProvider] ?: "",
            searchQueryShortcuts = values[StoreKeys.searchQueryShortcuts] ?: true,
            drawerInfiniteScroll = values[StoreKeys.drawerInfiniteScroll] ?: false,
            drawerPageEffect = values[StoreKeys.drawerPageEffect] ?: "NONE",
            drawerRememberPosition = values[StoreKeys.drawerRememberPosition] ?: false,
            drawerAutoClose = values[StoreKeys.drawerAutoClose] ?: false,
            drawerFoldersFirst = values[StoreKeys.drawerFoldersFirst] ?: true,
            smartFolders = values[StoreKeys.smartFolders] ?: true,
            smartFoldersDismissed = values[StoreKeys.smartFoldersDismissed] ?: emptySet(),
            drawerIconScale = (values[StoreKeys.drawerIconScale] ?: 1f).coerceIn(0.7f, 1.6f),
            drawerMatchDesktop = values[StoreKeys.drawerMatchDesktop] ?: true,
            drawerBgOpacity = (values[StoreKeys.drawerBgOpacity] ?: 1f).coerceIn(0.2f, 1f),
            drawerFastScroller = values[StoreKeys.drawerFastScroller] ?: true,
            drawerPullToSearch = values[StoreKeys.drawerPullToSearch] ?: false,
            drawerSwipeIndicator = values[StoreKeys.drawerSwipeIndicator] ?: false,
            drawerSwipeHaptic = values[StoreKeys.drawerSwipeHaptic] ?: true,
            swipeDownHaptic = values[StoreKeys.swipeDownHaptic] ?: true,
            drawerScrubberColor = values[StoreKeys.drawerScrubberColor].orEmpty(),
            drawerActionButtons = values[StoreKeys.drawerActionButtons].orEmpty(),
            drawerIsolateTabs = values[StoreKeys.drawerIsolateTabs] ?: false,
            drawerSort = values[StoreKeys.drawerSort] ?: "ALPHA",
            drawerWorkTab = values[StoreKeys.drawerWorkTab] ?: true,
            drawerDefaultTab = values[StoreKeys.drawerDefaultTab].orEmpty(),
            drawerHideAllTab = values[StoreKeys.drawerHideAllTab] ?: false,
            labelSize = (values[StoreKeys.labelSize] ?: 12).coerceIn(9, 18),
            labelConfig = values[StoreKeys.labelConfig].orEmpty(),
            labelColor = values[StoreKeys.labelColor].orEmpty(),
            labelShadow = values[StoreKeys.labelShadow] ?: false,
            drawerLabels = values[StoreKeys.drawerLabels] ?: true,
            folderLabels = values[StoreKeys.folderLabels] ?: true,
            iconNormalize = values[StoreKeys.iconNormalize] ?: false,
            reshapeLegacy = values[StoreKeys.reshapeLegacy] ?: "ON",
            iconShadow = values[StoreKeys.iconShadow] ?: true,
            iconGlass = values[StoreKeys.iconGlass] ?: "OFF",
            iconSwipeIndicator = values[StoreKeys.iconSwipeIndicator] ?: true,
            themedIcons = values[StoreKeys.themedIcons] ?: false,
            themedIconsAutogen = values[StoreKeys.themedIconsAutogen] ?: false,
            lockMethod = values[StoreKeys.lockMethod] ?: "ACCESSIBILITY",
            badgeColor = values[StoreKeys.badgeColor].orEmpty(),
            badgePosition = values[StoreKeys.badgePosition] ?: "TOP_RIGHT",
            badgeScale = (values[StoreKeys.badgeScale] ?: 1f).coerceIn(0.6f, 1.6f),
            badgeTextColor = values[StoreKeys.badgeTextColor].orEmpty(),
            badgeCornerRadius = (values[StoreKeys.badgeCornerRadius] ?: BadgeLimits.CORNER_ROUND)
                .coerceIn(0, BadgeLimits.CORNER_ROUND),
            badgeOutline = values[StoreKeys.badgeOutline] ?: true,
            folderPreviewStyle = values[StoreKeys.folderPreviewStyle] ?: "GRID",
            folderPrioritizeFirst = values[StoreKeys.folderPrioritizeFirst] ?: false,
            folderColumns = (values[StoreKeys.folderColumns] ?: 0).coerceIn(0, GridLimits.FOLDER_COLUMNS_BIG),
            folderSortAz = values[StoreKeys.folderSortAz] ?: false,
            folderCloseOnLaunch = values[StoreKeys.folderCloseOnLaunch] ?: true,
            folderIconScale = (values[StoreKeys.folderIconScale] ?: 1f).coerceIn(0.7f, 1.6f),
            folderMatchDesktop = values[StoreKeys.folderMatchDesktop] ?: false,
            folderShowName = values[StoreKeys.folderShowName] ?: true,
            folderShowCount = values[StoreKeys.folderShowCount] ?: false,
            folderMode = values[StoreKeys.folderMode] ?: "WINDOW",
            folderScroll = values[StoreKeys.folderScroll] ?: "HORIZONTAL",
            folderIconShape = values[StoreKeys.folderIconShape].orEmpty(),
            folderIconOutline = values[StoreKeys.folderIconOutline] ?: false,
            folderClipToBg = values[StoreKeys.folderClipToBg] ?: true,
            folderMatchWindowColor = values[StoreKeys.folderMatchWindowColor] ?: false,
            folderBadges = values[StoreKeys.folderBadges] ?: false,
            themeMode = values[StoreKeys.themeMode] ?: "SYSTEM",
            themeScheduleStart = (values[StoreKeys.themeScheduleStart] ?: 1140).coerceIn(0, 1439),
            themeScheduleEnd = (values[StoreKeys.themeScheduleEnd] ?: 420).coerceIn(0, 1439),
            accentColor = values[StoreKeys.accentColor] ?: "BLUE",
            drawerBgColor = values[StoreKeys.drawerBgColor].orEmpty(),
            folderBgColor = values[StoreKeys.folderBgColor].orEmpty(),
            paletteSource = values[StoreKeys.paletteSource] ?: "SYSTEM",
            paletteSeed = values[StoreKeys.paletteSeed].orEmpty(),
            styleConfig = values[StoreKeys.styleConfig].orEmpty(),
            widgetCornerRadius = (values[StoreKeys.widgetCornerRadius] ?: 16).coerceIn(0, 32),
            showScrims = values[StoreKeys.showScrims] ?: false,
            wallpaperZoom = values[StoreKeys.wallpaperZoom] ?: true,
            autoAddApps = values[StoreKeys.autoAddApps] ?: false,
            labelFont = values[StoreKeys.labelFont] ?: "DEFAULT",
            labelSingleLine = values[StoreKeys.labelSingleLine] ?: true,
            dockInfiniteScroll = values[StoreKeys.dockInfiniteScroll] ?: false,
            dockMatchDesktop = values[StoreKeys.dockMatchDesktop] ?: true,
            dockOverlay = values[StoreKeys.dockOverlay] ?: false,
            dockAutoClose = values[StoreKeys.dockAutoClose] ?: false,
            dockRememberPosition = values[StoreKeys.dockRememberPosition] ?: false,
            dockPaddingH = values[StoreKeys.dockPaddingH] ?: 0,
            dockPaddingV = values[StoreKeys.dockPaddingV] ?: 0,
            searchWebProvider = values[StoreKeys.searchWebProvider] ?: "GOOGLE",
            searchContacts = values[StoreKeys.searchContacts] ?: false,
            searchShortcuts = values[StoreKeys.searchShortcuts] ?: true,
            searchFolders = values[StoreKeys.searchFolders] ?: false,
            searchWindowMode = values[StoreKeys.searchWindowMode] ?: "IMMERSIVE",
            searchAppsOneRow = values[StoreKeys.searchAppsOneRow] ?: true,
            searchContactCards = values[StoreKeys.searchContactCards] ?: true,
            searchCards = values[StoreKeys.searchCards] ?: true,
            searchCalculator = values[StoreKeys.searchCalculator] ?: true,
            searchHiddenApps = values[StoreKeys.searchHiddenApps] ?: false,
            searchHistory = values[StoreKeys.searchHistory] ?: true,
            searchPrivateKeyboard = values[StoreKeys.searchPrivateKeyboard] ?: true,
            homeSearch = values[StoreKeys.homeSearch] ?: false,
            homeButtonAction = values[StoreKeys.homeButtonAction] ?: "OPEN_DRAWER",
            searchBarConfig = values[StoreKeys.searchBarConfig].orEmpty(),
            popupNotifications = values[StoreKeys.popupNotifications] ?: true,
            popupCardBg = values[StoreKeys.popupCardBg] ?: true,
            popupGroupShortcuts = values[StoreKeys.popupGroupShortcuts] ?: false,
            popupActionIcons = values[StoreKeys.popupActionIcons] ?: false,
            popupStyle = values[StoreKeys.popupStyle] ?: "BLOCK",
            popupItemsHome = values[StoreKeys.popupItemsHome].orEmpty(),
            popupItemsDrawer = values[StoreKeys.popupItemsDrawer].orEmpty(),
            dropTargets = values[StoreKeys.dropTargets] ?: false,
            undoRemove = values[StoreKeys.undoRemove] ?: true,
            disableAnimations = values[StoreKeys.disableAnimations] ?: false,
            animationSpeed = values[StoreKeys.animationSpeed] ?: "DEFAULT",
            launchAnimation = values[StoreKeys.launchAnimation] ?: "ADAPTIVE_ICON",
            hideStatusBar = values[StoreKeys.hideStatusBar] ?: false,
            darkStatusIcons = values[StoreKeys.darkStatusIcons] ?: "AUTO",
            transparentNav = values[StoreKeys.transparentNav] ?: true,
            homeRotation = values[StoreKeys.homeRotation] ?: false,
            homeOrientation = values[StoreKeys.homeOrientation].orEmpty(),
            feedEnabled = values[StoreKeys.feedEnabled] ?: true,
            feedEdgeSwipe = values[StoreKeys.feedEdgeSwipe] ?: true,
            feedShowSearch = values[StoreKeys.feedShowSearch] ?: true,
            feedShowFrequent = values[StoreKeys.feedShowFrequent] ?: true,
            feedShowShortcuts = values[StoreKeys.feedShowShortcuts] ?: true,
            feedShowMedia = values[StoreKeys.feedShowMedia] ?: true,
            feedShowCalendar = values[StoreKeys.feedShowCalendar] ?: false,
            feedShowContacts = values[StoreKeys.feedShowContacts] ?: false,
            feedShortcutRows = (values[StoreKeys.feedShortcutRows] ?: 1).coerceIn(1, 3),
            labsEnabled = values[StoreKeys.labsEnabled] ?: false,
            labsShowComponent = values[StoreKeys.labsShowComponent] ?: false,
            labsExportPng = values[StoreKeys.labsExportPng] ?: false,
            labsBigGrid = values[StoreKeys.labsBigGrid] ?: false
        )
    }.distinctUntilChanged()

    suspend fun setGridRows(value: Int) = put(StoreKeys.gridRows, value.coerceIn(2, GridLimits.DESKTOP_BIG))

    suspend fun setGridCols(value: Int) = put(StoreKeys.gridCols, value.coerceIn(2, GridLimits.DESKTOP_BIG))

    suspend fun setDockCount(value: Int) = put(StoreKeys.dockCount, value.coerceIn(3, GridLimits.DOCK_BIG))

    suspend fun setIconScale(value: Float) = put(StoreKeys.iconScale, value.coerceIn(0.7f, 1.6f))

    suspend fun setShowLabels(value: Boolean) = put(StoreKeys.showLabels, value)

    suspend fun saveDrawerGroup(group: DrawerGroup) {
        context.supernovaStore.edit { prefs ->
            val others = prefs[StoreKeys.drawerGroups].orEmpty()
                .filterNot { DrawerGroup.decode(it)?.name == group.name }
                .toSet()
            // Empty groups are kept: a group is created before its apps are
            // picked, and only explicit deletion removes one.
            prefs[StoreKeys.drawerGroups] = others + group.encode()
        }
    }

    // Every group edit as one read-modify-write of the stored set. Callers
    // used to save a whole group copied from the list the screen was drawn
    // from, which trails the store by a write: tick two apps in a group's
    // checklist quickly and the second save, built on a copy without the
    // first app, took it back out. Entries that don't decode are kept.
    suspend fun updateDrawerGroups(transform: (List<DrawerGroup>) -> List<DrawerGroup>) {
        context.supernovaStore.edit { prefs ->
            val stored = prefs[StoreKeys.drawerGroups].orEmpty()
            val undecodable = stored.filter { DrawerGroup.decode(it) == null }
            val groups = stored.mapNotNull { DrawerGroup.decode(it) }
            prefs[StoreKeys.drawerGroups] = (undecodable + transform(groups).map { it.encode() }).toSet()
        }
    }

    suspend fun removeDrawerGroup(name: String) {
        context.supernovaStore.edit { prefs ->
            prefs[StoreKeys.drawerGroups] = prefs[StoreKeys.drawerGroups].orEmpty()
                .filterNot { DrawerGroup.decode(it)?.name == name }
                .toSet()
        }
    }

    suspend fun setBadges(value: Boolean) = put(StoreKeys.badges, value)

    suspend fun setBadgeCounts(value: Boolean) = put(StoreKeys.badgeCounts, value)

    suspend fun setBadgeStyle(value: String) = put(StoreKeys.badgeStyle, value)

    suspend fun setBadgeApps(value: Set<String>) = put(StoreKeys.badgeApps, value)

    suspend fun setGestureBinding(gesture: String, encoded: String) {
        context.supernovaStore.edit { prefs ->
            val current = prefs[StoreKeys.gestureBindings].orEmpty()
                .filterNot { it.startsWith("$gesture:") }
                .toSet()
            prefs[StoreKeys.gestureBindings] = current + encoded
        }
    }

    suspend fun setSubgrid(value: Boolean) = put(StoreKeys.subgrid, value)

    suspend fun setIconShape(value: String) = put(StoreKeys.iconShape, value)

    suspend fun setIconShapeCustom(value: String) = put(StoreKeys.iconShapeCustom, value)

    suspend fun setScrollEffect(value: String) = put(StoreKeys.scrollEffect, value)

    suspend fun setAppHidden(key: String, hidden: Boolean) {
        context.supernovaStore.edit { prefs ->
            val current = prefs[StoreKeys.hiddenApps].orEmpty()
            prefs[StoreKeys.hiddenApps] = if (hidden) current + key else current - key
        }
    }

    suspend fun setIconPack(value: String) = put(StoreKeys.iconPack, value)

    suspend fun setWallpaperScroll(value: Boolean) = put(StoreKeys.wallpaperScroll, value)

    suspend fun setWallpaperScrollMode(value: String) = put(StoreKeys.wallpaperScrollMode, value)

    suspend fun setPageIndicatorStyle(value: String) = put(StoreKeys.pageIndicatorStyle, value)

    suspend fun setPageIndicatorAutoHide(value: Boolean) = put(StoreKeys.pageIndicatorAutoHide, value)

    suspend fun setPageIndicatorColor(value: String) = put(StoreKeys.pageIndicatorColor, value)

    suspend fun setInfiniteScroll(value: Boolean) = put(StoreKeys.infiniteScroll, value)

    suspend fun setRemoveEmptyPages(value: Boolean) = put(StoreKeys.removeEmptyPages, value)

    suspend fun setDesktopPadding(horizontal: Int, vertical: Int) {
        context.supernovaStore.edit {
            it[StoreKeys.desktopPaddingH] = horizontal.coerceIn(0, 48)
            it[StoreKeys.desktopPaddingV] = vertical.coerceIn(0, 48)
        }
    }

    suspend fun setDesktopLocked(value: Boolean) = put(StoreKeys.desktopLocked, value)

    suspend fun setDockEnabled(value: Boolean) = put(StoreKeys.dockEnabled, value)

    suspend fun setDockPages(value: Int) = put(StoreKeys.dockPages, value.coerceIn(1, 3))

    suspend fun setDockBgStyle(value: String) = put(StoreKeys.dockBgStyle, value)

    suspend fun setDockBgOpacity(value: Float) = put(StoreKeys.dockBgOpacity, value.coerceIn(0f, 1f))

    suspend fun setDockBgBehindNav(value: Boolean) = put(StoreKeys.dockBgBehindNav, value)

    suspend fun setSearchQueryShortcuts(value: Boolean) = put(StoreKeys.searchQueryShortcuts, value)

    suspend fun setSearchBarWidget(id: Int, provider: String) {
        context.supernovaStore.edit {
            it[StoreKeys.searchBarWidgetId] = id
            it[StoreKeys.searchBarWidgetProvider] = provider
        }
    }

    suspend fun setSearchChips(value: String) = put(StoreKeys.searchChips, value)

    suspend fun setHomeButtonOnMainPage(value: Boolean) = put(StoreKeys.homeButtonOnMainPage, value)

    suspend fun setIconWiggle(value: Boolean) = put(StoreKeys.iconWiggle, value)

    suspend fun setTransparentStatus(value: Boolean) = put(StoreKeys.transparentStatus, value)

    suspend fun setFolderShadow(value: Boolean) = put(StoreKeys.folderShadow, value)

    suspend fun setDrawerInfiniteScroll(value: Boolean) = put(StoreKeys.drawerInfiniteScroll, value)

    suspend fun setDrawerPageEffect(value: String) = put(StoreKeys.drawerPageEffect, value)

    suspend fun setDrawerTransition(value: String) = put(StoreKeys.drawerTransition, value)

    suspend fun setDockBgPadding(value: Boolean) = put(StoreKeys.dockBgPadding, value)

    suspend fun setDockLabels(value: Boolean) = put(StoreKeys.dockLabels, value)

    suspend fun setDockIconScale(value: Float) = put(StoreKeys.dockIconScale, value.coerceIn(0.7f, 1.6f))

    suspend fun setWidgetOverlap(value: Boolean) = put(StoreKeys.widgetOverlap, value)

    suspend fun setOverlapWhenPlacing(value: Boolean) = put(StoreKeys.overlapWhenPlacing, value)

    suspend fun setWidgetPadding(value: Int) = put(StoreKeys.widgetPadding, value.coerceIn(0, 24))

    suspend fun setDrawerStyle(value: String) = put(StoreKeys.drawerStyle, value)

    suspend fun setDrawerColumns(value: Int) = put(StoreKeys.drawerColumns, value.coerceIn(0, GridLimits.DRAWER_COLUMNS_BIG))

    suspend fun setDrawerRows(value: Int) = put(StoreKeys.drawerRows, value.coerceIn(0, GridLimits.DRAWER_ROWS_BIG))

    suspend fun setDrawerAutoKeyboard(value: Boolean) = put(StoreKeys.drawerAutoKeyboard, value)

    suspend fun setDrawerTabBar(value: String) = put(StoreKeys.drawerTabBar, value)

    suspend fun setDrawerTabStyle(value: String) = put(StoreKeys.drawerTabStyle, value)

    suspend fun setDrawerTabsNoSwipe(value: Boolean) = put(StoreKeys.drawerTabsNoSwipe, value)

    suspend fun setDrawerFrequentRow(value: Boolean) = put(StoreKeys.drawerFrequentRow, value)

    suspend fun setDrawerRememberPosition(value: Boolean) = put(StoreKeys.drawerRememberPosition, value)

    suspend fun setDrawerAutoClose(value: Boolean) = put(StoreKeys.drawerAutoClose, value)

    suspend fun setDrawerFoldersFirst(value: Boolean) = put(StoreKeys.drawerFoldersFirst, value)

    suspend fun setSmartFolders(value: Boolean) = put(StoreKeys.smartFolders, value)

    // Smart Folders bookkeeping: one atomic edit replaces every smart-marked
    // group so a regeneration can never interleave with a user edit. The
    // dismissed names are read (and [dismiss] joins them) in that same edit:
    // on a fresh install the launcher's own first pass can land after the
    // wizard's, and must still leave out the folders that one left out.
    suspend fun replaceSmartGroups(groups: List<DrawerGroup>, dismiss: Set<String> = emptySet()) {
        context.supernovaStore.edit { prefs ->
            val dismissed = prefs[StoreKeys.smartFoldersDismissed].orEmpty() + dismiss
            if (dismiss.isNotEmpty()) prefs[StoreKeys.smartFoldersDismissed] = dismissed
            prefs[StoreKeys.drawerGroups] =
                withSmartGroups(prefs[StoreKeys.drawerGroups].orEmpty(), groups, dismissed)
        }
    }

    suspend fun dismissSmartGroup(name: String) {
        context.supernovaStore.edit {
            it[StoreKeys.smartFoldersDismissed] = it[StoreKeys.smartFoldersDismissed].orEmpty() + name
        }
    }

    suspend fun clearDismissedSmartGroups() {
        context.supernovaStore.edit { it.remove(StoreKeys.smartFoldersDismissed) }
    }

    suspend fun removeSmartGroups() {
        context.supernovaStore.edit { prefs ->
            prefs[StoreKeys.drawerGroups] = prefs[StoreKeys.drawerGroups].orEmpty()
                .filterNot { DrawerGroup.decode(it)?.smart == true }
                .toSet()
        }
    }

    suspend fun setDrawerIconScale(value: Float) = put(StoreKeys.drawerIconScale, value.coerceIn(0.7f, 1.6f))

    suspend fun setDrawerMatchDesktop(value: Boolean) = put(StoreKeys.drawerMatchDesktop, value)

    suspend fun setDrawerBgOpacity(value: Float) = put(StoreKeys.drawerBgOpacity, value.coerceIn(0.2f, 1f))

    suspend fun setDrawerFastScroller(value: Boolean) = put(StoreKeys.drawerFastScroller, value)

    suspend fun setDrawerPullToSearch(value: Boolean) = put(StoreKeys.drawerPullToSearch, value)

    suspend fun setDrawerSwipeIndicator(value: Boolean) = put(StoreKeys.drawerSwipeIndicator, value)

    suspend fun setDrawerSwipeHaptic(value: Boolean) = put(StoreKeys.drawerSwipeHaptic, value)

    suspend fun setSwipeDownHaptic(value: Boolean) = put(StoreKeys.swipeDownHaptic, value)

    suspend fun setDrawerScrubberColor(value: String) = put(StoreKeys.drawerScrubberColor, value)

    suspend fun setDrawerActionButtons(value: String) = put(StoreKeys.drawerActionButtons, value)

    suspend fun setDrawerSort(value: String) = put(StoreKeys.drawerSort, value)

    suspend fun setDrawerWorkTab(value: Boolean) = put(StoreKeys.drawerWorkTab, value)

    suspend fun setDrawerDefaultTab(value: String) = put(StoreKeys.drawerDefaultTab, value)

    suspend fun setDrawerHideAllTab(value: Boolean) = put(StoreKeys.drawerHideAllTab, value)

    suspend fun setLabelSize(value: Int) = put(StoreKeys.labelSize, value.coerceIn(9, 18))

    suspend fun setLabelConfig(value: String) = put(StoreKeys.labelConfig, value)

    suspend fun setLabelColor(value: String) = put(StoreKeys.labelColor, value)

    suspend fun setLabelShadow(value: Boolean) = put(StoreKeys.labelShadow, value)

    suspend fun setDrawerLabels(value: Boolean) = put(StoreKeys.drawerLabels, value)

    suspend fun setFolderLabels(value: Boolean) = put(StoreKeys.folderLabels, value)

    suspend fun setIconNormalize(value: Boolean) = put(StoreKeys.iconNormalize, value)

    suspend fun setReshapeLegacy(value: String) = put(StoreKeys.reshapeLegacy, value)

    suspend fun setIconShadow(value: Boolean) = put(StoreKeys.iconShadow, value)

    suspend fun setIconGlass(value: String) = put(StoreKeys.iconGlass, value)

    suspend fun setIconSwipeIndicator(value: Boolean) = put(StoreKeys.iconSwipeIndicator, value)

    suspend fun setThemedIcons(value: Boolean) = put(StoreKeys.themedIcons, value)

    suspend fun setThemedIconsAutogen(value: Boolean) = put(StoreKeys.themedIconsAutogen, value)

    suspend fun setLockMethod(value: String) = put(StoreKeys.lockMethod, value)

    suspend fun setAnimationSpeed(value: String) = put(StoreKeys.animationSpeed, value)

    suspend fun setDockPaddingH(value: Int) = put(StoreKeys.dockPaddingH, value)

    suspend fun setDockPaddingV(value: Int) = put(StoreKeys.dockPaddingV, value)

    suspend fun setBadgeColor(value: String) = put(StoreKeys.badgeColor, value)

    suspend fun setBadgePosition(value: String) = put(StoreKeys.badgePosition, value)

    suspend fun setBadgeScale(value: Float) = put(StoreKeys.badgeScale, value.coerceIn(0.6f, 1.6f))

    suspend fun setBadgeTextColor(value: String) = put(StoreKeys.badgeTextColor, value)

    suspend fun setBadgeCornerRadius(value: Int) =
        put(StoreKeys.badgeCornerRadius, value.coerceIn(0, BadgeLimits.CORNER_ROUND))

    suspend fun setBadgeOutline(value: Boolean) = put(StoreKeys.badgeOutline, value)

    suspend fun setFolderPreviewStyle(value: String) = put(StoreKeys.folderPreviewStyle, value)

    suspend fun setFolderPrioritizeFirst(value: Boolean) = put(StoreKeys.folderPrioritizeFirst, value)

    suspend fun setFolderColumns(value: Int) = put(StoreKeys.folderColumns, value.coerceIn(0, GridLimits.FOLDER_COLUMNS_BIG))

    suspend fun setFolderSortAz(value: Boolean) = put(StoreKeys.folderSortAz, value)

    suspend fun setFolderCloseOnLaunch(value: Boolean) = put(StoreKeys.folderCloseOnLaunch, value)

    suspend fun setFolderIconScale(value: Float) = put(StoreKeys.folderIconScale, value.coerceIn(0.7f, 1.6f))

    suspend fun setFolderMatchDesktop(value: Boolean) = put(StoreKeys.folderMatchDesktop, value)

    suspend fun setFolderShowName(value: Boolean) = put(StoreKeys.folderShowName, value)
    suspend fun setFolderShowCount(value: Boolean) = put(StoreKeys.folderShowCount, value)

    suspend fun setFolderMode(value: String) = put(StoreKeys.folderMode, value)

    suspend fun setFolderScroll(value: String) = put(StoreKeys.folderScroll, value)

    suspend fun setFolderIconShape(value: String) = put(StoreKeys.folderIconShape, value)

    suspend fun setFolderIconOutline(value: Boolean) = put(StoreKeys.folderIconOutline, value)

    suspend fun setFolderClipToBg(value: Boolean) = put(StoreKeys.folderClipToBg, value)

    suspend fun setFolderMatchWindowColor(value: Boolean) = put(StoreKeys.folderMatchWindowColor, value)

    suspend fun setFolderBadges(value: Boolean) = put(StoreKeys.folderBadges, value)

    suspend fun setThemeMode(value: String) = put(StoreKeys.themeMode, value)

    suspend fun setThemeSchedule(startMinute: Int, endMinute: Int) {
        context.supernovaStore.edit {
            it[StoreKeys.themeScheduleStart] = startMinute.coerceIn(0, 1439)
            it[StoreKeys.themeScheduleEnd] = endMinute.coerceIn(0, 1439)
        }
    }

    suspend fun setAccentColor(value: String) = put(StoreKeys.accentColor, value)

    suspend fun setDrawerBgColor(value: String) = put(StoreKeys.drawerBgColor, value)

    suspend fun setFolderBgColor(value: String) = put(StoreKeys.folderBgColor, value)

    suspend fun setPaletteSource(value: String) = put(StoreKeys.paletteSource, value)

    suspend fun setPaletteSeed(value: String) = put(StoreKeys.paletteSeed, value)

    suspend fun setStyleConfig(value: String) = put(StoreKeys.styleConfig, value)

    suspend fun setWidgetCornerRadius(value: Int) = put(StoreKeys.widgetCornerRadius, value.coerceIn(0, 32))

    suspend fun setShowScrims(value: Boolean) = put(StoreKeys.showScrims, value)

    suspend fun setWallpaperZoom(value: Boolean) = put(StoreKeys.wallpaperZoom, value)

    suspend fun setAutoAddApps(value: Boolean) = put(StoreKeys.autoAddApps, value)

    suspend fun setLabelFont(value: String) = put(StoreKeys.labelFont, value)

    suspend fun setLabelSingleLine(value: Boolean) = put(StoreKeys.labelSingleLine, value)

    suspend fun setDockInfiniteScroll(value: Boolean) = put(StoreKeys.dockInfiniteScroll, value)

    suspend fun setDockMatchDesktop(value: Boolean) = put(StoreKeys.dockMatchDesktop, value)

    suspend fun setDockOverlay(value: Boolean) = put(StoreKeys.dockOverlay, value)

    suspend fun setDockAutoClose(value: Boolean) = put(StoreKeys.dockAutoClose, value)

    suspend fun setDockRememberPosition(value: Boolean) = put(StoreKeys.dockRememberPosition, value)

    suspend fun setSearchWebProvider(value: String) = put(StoreKeys.searchWebProvider, value)

    suspend fun setSearchContacts(value: Boolean) = put(StoreKeys.searchContacts, value)

    suspend fun setSearchShortcuts(value: Boolean) = put(StoreKeys.searchShortcuts, value)

    suspend fun setSearchFolders(value: Boolean) = put(StoreKeys.searchFolders, value)

    suspend fun setSearchWindowMode(value: String) = put(StoreKeys.searchWindowMode, value)

    suspend fun setSearchAppsOneRow(value: Boolean) = put(StoreKeys.searchAppsOneRow, value)

    suspend fun setSearchContactCards(value: Boolean) = put(StoreKeys.searchContactCards, value)

    suspend fun setSearchCards(value: Boolean) = put(StoreKeys.searchCards, value)

    suspend fun setSearchCalculator(value: Boolean) = put(StoreKeys.searchCalculator, value)

    suspend fun setSearchHiddenApps(value: Boolean) = put(StoreKeys.searchHiddenApps, value)

    suspend fun setSearchHistory(value: Boolean) = put(StoreKeys.searchHistory, value)

    suspend fun setSearchPrivateKeyboard(value: Boolean) = put(StoreKeys.searchPrivateKeyboard, value)

    suspend fun setHomeSearch(value: Boolean) = put(StoreKeys.homeSearch, value)

    suspend fun setHomeButtonAction(value: String) = put(StoreKeys.homeButtonAction, value)

    suspend fun setSearchBarConfig(value: String) = put(StoreKeys.searchBarConfig, value)

    suspend fun setPopupNotifications(value: Boolean) = put(StoreKeys.popupNotifications, value)

    suspend fun setPopupCardBg(value: Boolean) = put(StoreKeys.popupCardBg, value)

    suspend fun setPopupGroupShortcuts(value: Boolean) = put(StoreKeys.popupGroupShortcuts, value)
    suspend fun setPopupActionIcons(value: Boolean) = put(StoreKeys.popupActionIcons, value)

    suspend fun setPopupStyle(value: String) = put(StoreKeys.popupStyle, value)

    suspend fun setPopupItemsHome(value: String) = put(StoreKeys.popupItemsHome, value)

    suspend fun setPopupItemsDrawer(value: String) = put(StoreKeys.popupItemsDrawer, value)

    suspend fun setDropTargets(value: Boolean) = put(StoreKeys.dropTargets, value)

    suspend fun setUndoRemove(value: Boolean) = put(StoreKeys.undoRemove, value)

    suspend fun setDisableAnimations(value: Boolean) = put(StoreKeys.disableAnimations, value)

    suspend fun setLaunchAnimation(value: String) = put(StoreKeys.launchAnimation, value)

    suspend fun setHideStatusBar(value: Boolean) = put(StoreKeys.hideStatusBar, value)

    suspend fun setDarkStatusIcons(value: String) = put(StoreKeys.darkStatusIcons, value)

    suspend fun setTransparentNav(value: Boolean) = put(StoreKeys.transparentNav, value)

    suspend fun setHomeRotation(value: Boolean) = put(StoreKeys.homeRotation, value)

    suspend fun setHomeOrientation(value: String) = put(StoreKeys.homeOrientation, value)

    suspend fun setFeedEnabled(value: Boolean) = put(StoreKeys.feedEnabled, value)

    suspend fun setFeedEdgeSwipe(value: Boolean) = put(StoreKeys.feedEdgeSwipe, value)

    suspend fun setFeedShowSearch(value: Boolean) = put(StoreKeys.feedShowSearch, value)

    suspend fun setFeedShowFrequent(value: Boolean) = put(StoreKeys.feedShowFrequent, value)

    suspend fun setFeedShowShortcuts(value: Boolean) = put(StoreKeys.feedShowShortcuts, value)

    suspend fun setFeedShowMedia(value: Boolean) = put(StoreKeys.feedShowMedia, value)

    suspend fun setFeedShowCalendar(value: Boolean) = put(StoreKeys.feedShowCalendar, value)

    suspend fun setFeedShowContacts(value: Boolean) = put(StoreKeys.feedShowContacts, value)

    suspend fun setFeedShortcutRows(value: Int) = put(StoreKeys.feedShortcutRows, value.coerceIn(1, 3))

    suspend fun setLabsEnabled(value: Boolean) = put(StoreKeys.labsEnabled, value)

    suspend fun setLabsShowComponent(value: Boolean) = put(StoreKeys.labsShowComponent, value)

    suspend fun setLabsExportPng(value: Boolean) = put(StoreKeys.labsExportPng, value)

    suspend fun setLabsBigGrid(value: Boolean) = put(StoreKeys.labsBigGrid, value)

    // Recent search queries: device-local usage data like launchCounts,
    // newest first, deduplicated case-insensitively, capped at 8.
    suspend fun recentSearches(): List<String> =
        decodeRecents(context.supernovaStore.data.first()[StoreKeys.searchRecents])

    suspend fun recordSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        context.supernovaStore.edit { prefs ->
            val current = decodeRecents(prefs[StoreKeys.searchRecents])
            prefs[StoreKeys.searchRecents] =
                (listOf(trimmed) + current.filterNot { it.equals(trimmed, ignoreCase = true) })
                    .take(MAX_RECENT_SEARCHES)
                    .joinToString("\n")
        }
    }

    suspend fun removeRecentSearch(query: String) {
        context.supernovaStore.edit { prefs ->
            prefs[StoreKeys.searchRecents] = decodeRecents(prefs[StoreKeys.searchRecents])
                .filterNot { it == query }
                .joinToString("\n")
        }
    }

    private fun decodeRecents(raw: String?): List<String> =
        raw.orEmpty().split('\n').filter { it.isNotBlank() }

    // Device-local dock page state for dockRememberPosition.
    suspend fun setDockPageState(value: Int) = put(StoreKeys.dockPageState, value.coerceAtLeast(0))

    // 0 unless the remember pref is on, so a stale saved page can't leak.
    suspend fun restoredDockPage(): Int {
        val values = context.supernovaStore.data.first()
        if (values[StoreKeys.dockRememberPosition] != true) return 0
        return (values[StoreKeys.dockPageState] ?: 0).coerceAtLeast(0)
    }

    // Read once at process start so the theme is set before any inflation.
    suspend fun snapshot(): LauncherPrefs = flow.first()

    // Labs' Clear icon cache: every catalog listening drops its icons.
    val iconCacheClearsFlow: Flow<Long> = context.supernovaStore.data
        .map { it[StoreKeys.iconCacheCleared] ?: 0L }
        .distinctUntilChanged()

    suspend fun requestIconCacheClear() = put(StoreKeys.iconCacheCleared, System.currentTimeMillis())

    // Launch-count bookkeeping for most-used sort. Encoded "key=count"; one
    // set rewrite per launch is fine at app-launch frequency.
    val launchCountsFlow: Flow<Map<String, Int>> = context.supernovaStore.data.map { values ->
        values[StoreKeys.launchCounts].orEmpty().mapNotNull { entry ->
            val key = entry.substringBeforeLast('=')
            val count = entry.substringAfterLast('=').toIntOrNull() ?: return@mapNotNull null
            key to count
        }.toMap()
    }

    // App keys, most recently opened first.
    val recentLaunchesFlow: Flow<List<String>> = context.supernovaStore.data.map { values ->
        values[StoreKeys.recentLaunches].orEmpty()
            .sortedByDescending { it.substringAfterLast('=').toLongOrNull() ?: 0L }
            .map { it.substringBeforeLast('=') }
    }

    suspend fun recordLaunch(key: String) {
        context.supernovaStore.edit { prefs ->
            val current = prefs[StoreKeys.launchCounts].orEmpty()
            val existing = current.firstOrNull { it.substringBeforeLast('=') == key }
            val count = existing?.substringAfterLast('=')?.toIntOrNull() ?: 0
            prefs[StoreKeys.launchCounts] = (current - setOfNotNull(existing)) + "$key=${count + 1}"
            val recents = prefs[StoreKeys.recentLaunches].orEmpty()
                .filterNot { it.substringBeforeLast('=') == key } + "$key=${System.currentTimeMillis()}"
            prefs[StoreKeys.recentLaunches] = recents
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
            if (values[StoreKeys.gridCols] == null) values[StoreKeys.gridCols] = profile.gridCols
            if (values[StoreKeys.gridRows] == null) values[StoreKeys.gridRows] = profile.gridRows
            if (values[StoreKeys.dockCount] == null) values[StoreKeys.dockCount] = profile.dockCount
            listOf(
                StoreKeys.iconScale, StoreKeys.dockIconScale,
                StoreKeys.drawerIconScale, StoreKeys.folderIconScale
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
