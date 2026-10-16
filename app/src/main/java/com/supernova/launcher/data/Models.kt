package com.supernova.launcher.data

import kotlinx.serialization.Serializable

@Serializable
enum class ItemType {
    APP,
    FOLDER,
    WIDGET,
    SHORTCUT
}

@Serializable
enum class ContainerType {
    WORKSPACE,
    DOCK,
    FOLDER
}

@Serializable
data class LauncherItem(
    val id: String,
    val itemType: ItemType,
    val container: ContainerType = ContainerType.WORKSPACE,
    val page: Int = 0,
    val cellX: Int = 0,
    val cellY: Int = 0,
    val workspaceX: Float? = null,
    val workspaceY: Float? = null,
    val spanX: Int = 1,
    val spanY: Int = 1,
    val rank: Int = 0,
    val label: String = "",
    val packageName: String? = null,
    val className: String? = null,
    val folderId: String? = null,
    val appWidgetId: Int? = null,
    val widgetProvider: String? = null,
    val shortcutId: String? = null,
    // UserManager serial; 0 = primary user, so legacy persisted items resolve correctly
    val userSerial: Long = 0L,
    // Swipe-up action bound to this icon, as "pkg/cls" of the app to launch
    val swipeAction: String? = null,
    val swipeLabel: String? = null,
    // Swipe-down twin of swipeAction/swipeLabel; same encoding.
    val swipeActionDown: String? = null,
    val swipeLabelDown: String? = null,
    // Folders only, "Swipe to open folder": a tap launches the first
    // app in the folder and a swipe up opens it. A swipe-up action the folder
    // holds is kept, unused, until this goes off again.
    val swipeToOpen: Boolean = false,
    // Widgets only, per-widget padding: this widget's inset in dp
    // inside its cells. Null follows the Widget padding setting.
    val widgetPadding: Int? = null,
    // Custom icon override for folders: a drawable inside an icon pack.
    val customIconPack: String? = null,
    val customIconName: String? = null,
    // Set on SHORTCUT items that are not app shortcuts: a launcher action
    // ("supernova://action/..." - see gesture/ActionShortcut) or an explicit
    // intent to fire (Intent.toUri(URI_INTENT_SCHEME), e.g. an activity or a
    // legacy created shortcut). Default-supplied so older items and backups
    // decode unchanged; launchItem routes on it before the app-shortcut path.
    val intentUri: String? = null,
    // Inline base64 PNG icon (GalleryIconCodec) for items with no app to draw
    // an icon from - activities and legacy shortcuts snapshot theirs here so
    // backups stay self-contained. Launcher actions use a built-in vector.
    val iconBase64: String? = null
)

// Per-app customization, keyed "userSerial/pkg/cls" in LauncherModel so one
// override reaches the drawer entry and every pinned copy of the app.
@Serializable
data class AppOverride(
    val customLabel: String? = null,
    val hideLabel: Boolean = false,
    // IconShape name; null = follow the global shape.
    val iconShape: String? = null,
    // A drawable inside an installed icon pack; both or neither.
    val iconPackPackage: String? = null,
    val iconDrawableName: String? = null,
    // A user-picked gallery image, center-cropped and stored as base64 PNG so
    // backups carry it. Mutually exclusive with the pack icon fields.
    val iconBase64: String? = null,
    // Per-app icon variant when no explicit icon is set: THEMED (system
    // monochrome) or ORIGINAL (the app's own icon, unshaped); null = default.
    val iconVariant: String? = null
) {
    fun isEmpty(): Boolean =
        customLabel == null && !hideLabel && iconShape == null &&
            iconPackPackage == null && iconDrawableName == null && iconBase64 == null &&
            iconVariant == null
}

const val CURRENT_MODEL_VERSION = 5

@Serializable
data class LauncherModel(
    // Default stays 1: legacy persisted JSON omits the field entirely, so an
    // absent version must decode as v1 for migrations to run.
    val version: Int = 1,
    val items: List<LauncherItem> = emptyList(),
    val pageCount: Int = 1,
    val defaultPage: Int = 0,
    val appOverrides: Map<String, AppOverride> = emptyMap(),
    // v5: custom feed-page cards (see feed/FeedCards.kt).
    val feedCards: List<com.supernova.launcher.feed.FeedCard> = emptyList()
)

@Serializable
data class LauncherPrefs(
    // Phone grid, 5 columns by 6 rows (see DeviceProfile).
    val gridRows: Int = 6,
    val gridCols: Int = 5,
    val dockCount: Int = 5,
    val iconScale: Float = 1f,
    val showLabels: Boolean = true,
    val wallpaperScroll: Boolean = true,
    // WallpaperScrollMode name adding REVERSE; "" derives from the legacy
    // wallpaperScroll flag (true = ON) so old configs and backups keep their
    // meaning without a migration.
    val wallpaperScrollMode: String = "",
    val iconPack: String = "",
    val scrollEffect: String = "NONE",
    val iconShape: String = "SYSTEM",
    // JSON CustomShapeSpec backing IconShape.CUSTOM; "" = default 25% ROUND.
    val iconShapeCustom: String = "",
    val subgrid: Boolean = false,
    val badges: Boolean = false,
    val badgeCounts: Boolean = false,
    // "" = legacy-derive from badgeCounts; else DOTS/NUMERIC/DYNAMIC.
    val badgeStyle: String = "",
    // Empty = badges on every app; else only these "userSerial/pkg/cls" keys.
    val badgeApps: Set<String> = emptySet(),
    val hiddenApps: Set<String> = emptySet(),
    val gestureBindings: Set<String> = emptySet(),
    // Drawer tabs as "Name|key1,key2,..." entries; the All tab is implicit.
    val drawerGroups: Set<String> = emptySet(),
    // Line by default.
    val pageIndicatorStyle: String = "LINE",
    // The indicator only shows while the page moves; off keeps it up.
    val pageIndicatorAutoHide: Boolean = true,
    // Indicator color; "" draws it in the labels' color, as the swipe
    // arrow is.
    val pageIndicatorColor: String = "",
    val infiniteScroll: Boolean = false,
    // A page left with nothing on it goes once a drag ends or the pager
    // leaves it; the home page and the page in view always stay.
    val removeEmptyPages: Boolean = true,
    // Workspace page padding in dp per axis. None across: the
    // columns span the whole width, 216px apiece on a 1080px phone.
    val desktopPaddingH: Int = 0,
    val desktopPaddingV: Int = 12,
    val desktopLocked: Boolean = false,
    val dockEnabled: Boolean = true,
    // Dock rank space is page * dockCount + slot; extra pages swipe in place.
    val dockPages: Int = 1,
    val dockBgStyle: String = "NONE",
    val dockBgOpacity: Float = 0.3f,
    // Dock background runs flush to the window's bottom edge (no side or
    // bottom margins, square bottom corners), meeting the transparent nav
    // bar the way a draw-behind-navigation option reads. The window
    // itself is not edge-to-edge, so this is visual, not literal.
    val dockBgBehindNav: Boolean = false,
    // The dock background's "Padding": on, the background slab is inset from
    // the screen edges; off, it spans the full width and sits flush on the
    // navigation bar (square bottom corners), without reaching under it -
    // that is what dockBgBehindNav adds on top. Off by default.
    val dockBgPadding: Boolean = false,
    // Off by default: dock icons go unlabelled.
    val dockLabels: Boolean = false,
    val dockIconScale: Float = 1f,
    val widgetOverlap: Boolean = false,
    // Placement drops may land on occupied cells (grid bounds still apply);
    // auto-placement (new apps, pins) keeps looking for free cells.
    val overlapWhenPlacing: Boolean = false,
    // Inset in dp applied inside each widget's cell-derived frame.
    val widgetPadding: Int = 0,
    val drawerStyle: String = "VERTICAL_GRID",
    // 0 = derive from screen width (the pre-setting behavior).
    val drawerColumns: Int = 0,
    // Rows for the paged drawer style; 0 = derive from the sheet height.
    val drawerRows: Int = 0,
    // Opening the drawer focuses search and shows the IME; the keyboard goes
    // back down with the sheet on close.
    val drawerAutoKeyboard: Boolean = true,
    // Paged drawer style only, the Horizontal Scroll group:
    // wrap past the last page, and the effect used between pages.
    val drawerInfiniteScroll: Boolean = false,
    val drawerPageEffect: String = "NONE",
    // How the drawer arrives: SLIDE_UP/ZOOM/FADE (DrawerTransition names).
    val drawerTransition: String = "SLIDE_UP",
    // Where the group tab bar renders: TOP/BOTTOM/NONE (DrawerTabBar names).
    // NONE hides tabs entirely, so the drawer always shows the All list.
    val drawerTabBar: String = "TOP",
    // Tab type: CLASSIC (a bar under the label) or BUBBLE (a pill
    // behind it); DrawerTabStyle names.
    val drawerTabStyle: String = "CLASSIC",
    // Isolate tabs: a sideways swipe on the list stays on the tab.
    val drawerTabsNoSwipe: Boolean = false,
    // Horizontal most-launched strip pinned above the drawer list.
    val drawerFrequentRow: Boolean = true,
    // Keep the scroll position across close/reopen instead of resetting to top.
    val drawerRememberPosition: Boolean = false,
    // Hide the drawer when an app is launched from it; false keeps it open
    // behind the launched app, which is the default.
    val drawerAutoClose: Boolean = false,
    // FOLDER-type groups render before apps in the drawer grid; false merges
    // them into the list alphabetically.
    val drawerFoldersFirst: Boolean = true,
    // Auto-generate FOLDER groups from on-device app categories.
    val smartFolders: Boolean = true,
    // Smart folders the user deleted, or a fresh install left out as too big,
    // by name: regenerating on an install leaves them out. Turning Smart
    // Folders on again clears it.
    val smartFoldersDismissed: Set<String> = emptySet(),
    val drawerIconScale: Float = 1f,
    // When on, the drawer follows iconScale and its scale slider hides. On
    // by default.
    val drawerMatchDesktop: Boolean = true,
    val drawerBgOpacity: Float = 1f,
    // On by default.
    val drawerFastScroller: Boolean = true,
    // Overscrolling past the bottom of the drawer list opens global search.
    val drawerPullToSearch: Boolean = false,
    // Open App Drawer options, for a swipe up bound to the drawer: a
    // small arrow above the dock that hints at the swipe, and a haptic tick
    // when the swipe starts pulling the drawer in. The arrow starts off
    // and the tick on.
    val drawerSwipeIndicator: Boolean = false,
    val drawerSwipeHaptic: Boolean = true,
    // Expand notifications option (the gear by Swipe down): a tick as
    // a swipe down pulls the shade. On by default.
    val swipeDownHaptic: Boolean = true,
    // Fast-scroller active-letter color; "" follows the drawer accent.
    val drawerScrubberColor: String = "",
    // Header action buttons beside the drawer search field (comma-joined
    // DrawerActionButton names); "" = none.
    val drawerActionButtons: String = "",
    // Apps living in TAB-type drawer groups leave the All tab (tab
    // isolation); folder-type groups always did.
    val drawerIsolateTabs: Boolean = false,
    val drawerSort: String = "ALPHA",
    // On by default; it only shows with a work profile.
    val drawerWorkTab: Boolean = true,
    // Name of the group tab selected on open ("" = All), and whether the
    // implicit All tab is suppressed once groups exist.
    val drawerDefaultTab: String = "",
    val drawerHideAllTab: Boolean = false,
    val labelSize: Int = 12,
    // Per-context (dock/drawer/folder) label overrides as one JSON document
    // (see ui/LabelConfig); "" = every context inherits the desktop labels.
    val labelConfig: String = "",
    // "" = default white; else #AARRGGBB.
    val labelColor: String = "",
    val labelShadow: Boolean = false,
    val drawerLabels: Boolean = true,
    val folderLabels: Boolean = true,
    val iconNormalize: Boolean = false,
    // ReshapeLegacy name (OFF/AUTO/ON); "" derives from the legacy
    // iconNormalize flag (true = AUTO, the old rescale-only behavior).
    // ON additionally bakes a plate behind raw legacy icons
    // ("Reshape legacy icons"), on out of the box.
    val reshapeLegacy: String = "ON",
    // On by default. The shadow falls outside the icon, which keeps
    // its size (ShadowedIconDrawable).
    val iconShadow: Boolean = true,
    // IconGlass name (OFF/FROSTED/CLEAR/SMOKED): icon backgrounds as glass.
    val iconGlass: String = "OFF",
    // A short bar under any icon that has a swipe action bound. An icon swipe
    // is invisible without it, so it stays on by default; it only ever draws
    // on icons that actually carry one.
    val iconSwipeIndicator: Boolean = true,
    // Draw adaptive icons' monochrome layer, palette-tinted (API 33+).
    val themedIcons: Boolean = false,
    // Synthesize a monochrome layer for apps that ship none (D8).
    val themedIconsAutogen: Boolean = false,
    // Screen-lock mechanism: ACCESSIBILITY (default) or DEVICE_ADMIN.
    val lockMethod: String = "ACCESSIBILITY",
    val badgeColor: String = "",
    val badgePosition: String = "TOP_RIGHT",
    val badgeScale: Float = 1f,
    // Numeric dots' number color; "" = white.
    val badgeTextColor: String = "",
    // Numeric dots' corners, 0 = square .. 100 = round (Corner radius).
    val badgeCornerRadius: Int = BadgeLimits.CORNER_ROUND,
    // A thin dark ring around every badge (Outline).
    val badgeOutline: Boolean = true,
    val folderPreviewStyle: String = "GRID",
    // Draw the first member large in the preview, remaining members small.
    val folderPrioritizeFirst: Boolean = false,
    // 0 = derive from member count.
    val folderColumns: Int = 0,
    val folderSortAz: Boolean = false,
    val folderCloseOnLaunch: Boolean = true,
    // Scale of member icons inside the open folder window; matchDesktop
    // follows iconScale instead and hides the slider.
    val folderIconScale: Float = 1f,
    val folderMatchDesktop: Boolean = false,
    // Title text in the open folder window (the edit button stays).
    val folderShowName: Boolean = true,
    // WINDOW = anchored popup card; IMMERSIVE = full-screen folder (FolderMode).
    val folderMode: String = "WINDOW",
    // Drop shadow under the folder card. Off flattens it onto the wallpaper,
    // which is what a translucent folder surface usually wants.
    val folderShadow: Boolean = true,
    // VERTICAL scrolls the folder grid; HORIZONTAL, the default, pages it
    // (FolderScroll).
    val folderScroll: String = "HORIZONTAL",
    // IconShape name clipping the folder preview plate; "" = the default
    // rounded plate.
    val folderIconShape: String = "",
    // 2dp FOLDER-accent stroke around the plate.
    val folderIconOutline: Boolean = false,
    // Member icons clipped inside the plate vs allowed to overflow it.
    val folderClipToBg: Boolean = true,
    // Plate uses the resolved FOLDER window surface instead of the
    // translucent default plate.
    val folderMatchWindowColor: Boolean = false,
    // Badge folder icons with the summed unread count of their members.
    val folderBadges: Boolean = false,
    val themeMode: String = "SYSTEM",
    // SCHEDULE mode's dark window, minutes since midnight; end < start wraps
    // past midnight (defaults: 19:00-07:00).
    val themeScheduleStart: Int = 1140,
    val themeScheduleEnd: Int = 420,
    // Auto's Location enhancement: sunsets from the phone's last known fix
    // rather than a time-zone estimate (NightWindow).
    val themeAutoLocation: Boolean = false,
    val accentColor: String = "BLUE",
    // "" = follow the theme; else #AARRGGBB.
    val drawerBgColor: String = "",
    val folderBgColor: String = "",
    // Style engine: where tonal palettes come from (SYSTEM/WALLPAPER/CUSTOM),
    // the CUSTOM seed as #RRGGBB, and per-surface overrides as one JSON
    // document (see ui/theme/StyleConfig).
    val paletteSource: String = "SYSTEM",
    val paletteSeed: String = "",
    val styleConfig: String = "",
    // Corner clip radius for widget host views in dp; 0 = no clip.
    val widgetCornerRadius: Int = 16,
    val showScrims: Boolean = false,
    val wallpaperZoom: Boolean = true,
    val autoAddApps: Boolean = false,
    val labelFont: String = "DEFAULT",
    // One ellipsized line everywhere by default: a wrapped name reads as a
    // broken word ("OpenWeathe" over "r"). Per-surface overrides in
    // labelConfig can still allow two lines.
    val labelSingleLine: Boolean = true,
    val dockInfiniteScroll: Boolean = false,
    // When on, the dock follows iconScale and the dock scale slider hides.
    // On by default.
    val dockMatchDesktop: Boolean = true,
    // The dock draws over the workspace instead of reserving bottom space.
    val dockOverlay: Boolean = false,
    val dockAutoClose: Boolean = false,
    val dockRememberPosition: Boolean = false,
    // Extra padding inside the dock bar, in dp per axis.
    val dockPaddingH: Int = 0,
    val dockPaddingV: Int = 0,
    // Global search: web engine (WebProvider name) and per-provider toggles.
    val searchWebProvider: String = "GOOGLE",
    val searchContacts: Boolean = false,
    val searchShortcuts: Boolean = true,
    // Folder names (workspace and drawer groups) as search results. Off by
    // default.
    val searchFolders: Boolean = false,
    // Search window presentation: IMMERSIVE (full-screen, the original) or
    // WINDOW (floating rounded card).
    val searchWindowMode: String = "IMMERSIVE",
    // App results as one horizontal strip instead of vertical rows. On by
    // default.
    val searchAppsOneRow: Boolean = true,
    // Contacts as cards with call/message actions instead of plain rows. On
    // by default; it only matters once contact search is on.
    val searchContactCards: Boolean = true,
    // Custom cards match by name and render as full cards in the results.
    val searchCards: Boolean = true,
    val searchCalculator: Boolean = true,
    val searchHistory: Boolean = true,
    // Sets IME_FLAG_NO_PERSONALIZED_LEARNING on the search field. On by
    // default.
    val searchPrivateKeyboard: Boolean = true,
    // Long-pressing a recent search offers to keep it as a home-screen
    // shortcut that re-runs the query.
    val searchQueryShortcuts: Boolean = true,
    // Replace (the search-bar menu): an app widget standing in for the
    // built-in desktop bar. 0 = the built-in bar. The id dies with the host,
    // so a restore keeps the provider and re-binds.
    val searchBarWidgetId: Int = 0,
    val searchBarWidgetProvider: String = "",
    // Which alternate-target chips the results row offers, in order
    // (SearchChip names); "" = the built-in four, "NONE" = no row.
    val searchChips: String = "",
    // Pressing home while already on the default page opens search. Kept for
    // compat: homeButtonAction supersedes it ("" derives from this flag).
    val homeSearch: Boolean = false,
    // Home re-press action as a GestureAction name; "" = derive from homeSearch.
    // The app drawer by default.
    val homeButtonAction: String = "OPEN_DRAWER",
    // On: the first home press returns to the main page and only a second one
    // runs the action (the behavior before this pref existed). Off runs the
    // action straight away, wherever the workspace was.
    val homeButtonOnMainPage: Boolean = true,
    // Desktop/drawer search bar configuration as one JSON document (see
    // ui/SearchBarConfig); "" = defaults (no desktop bar, local drawer filter).
    val searchBarConfig: String = "",
    // Notification title/text lines at the top of the item popup menu.
    val popupNotifications: Boolean = true,
    // App-shortcut rows render inside a rounded inner card (C3). On by
    // default.
    val popupCardBg: Boolean = true,
    // "Visually group shortcuts": the shortcut and notification rows
    // stand apart from the menu as tiles of their own.
    val popupGroupShortcuts: Boolean = false,
    // Long-press menu layout (ui/PopupMenuStyle name): BLOCK/PILLS/ANDROID12.
    val popupStyle: String = "BLOCK",
    // Optional popup rows (ui/SupernovaPopupMenu PopupRows tokens, comma
    // joined). "" = defaults; "NONE" = explicitly none.
    val popupItemsHome: String = "",
    val popupItemsDrawer: String = "",
    // Top drop-target bar (Remove / App info / Uninstall) during item drags.
    // Top drop-target bar (Remove / App info / Uninstall) during item drags.
    // Off by default: it was removed once by choice, so it returns opt-in.
    val dropTargets: Boolean = false,
    // Undo snackbar after a workspace item removal.
    val undoRemove: Boolean = true,
    // Master animation kill switch consulted via ui/Animations.enabled.
    val disableAnimations: Boolean = false,
    // Playback speed multiplier (ui/AnimationSpeed): SLOW/DEFAULT/FAST/FASTER.
    val animationSpeed: String = "DEFAULT",
    // App-launch transition style (ui/LaunchAnimation name). REVEAL is the
    // pre-phase-6 clip-reveal behavior; Adaptive icon is the default.
    val launchAnimation: String = "ADAPTIVE_ICON",
    // System chrome: status bar hidden on the launcher, forced icon tint
    // (ui/StatusBarIcons name; AUTO follows the theme), nav bar transparency.
    val hideStatusBar: Boolean = false,
    val darkStatusIcons: String = "AUTO",
    // Hide the status bar clock via the SystemUI icon_blacklist secure
    // setting (HideClock name). Needs WRITE_SECURE_SETTINGS, which only an
    // adb grant can give; without it the pref is stored but inert.
    val hideClock: String = "NEVER",
    val transparentNav: Boolean = true,
    // Workspace icons rock back and forth while a drag is live, on out
    // of the box. Off keeps the grid still.
    val iconWiggle: Boolean = true,
    // The status bar's twin of transparentNav: off paints it black over the
    // wallpaper instead of letting the wallpaper through.
    val transparentStatus: Boolean = true,
    // Allow the launcher activity to rotate to landscape.
    val homeRotation: Boolean = false,
    // HomeOrientation name (DEFAULT/AUTO_ROTATE/PORTRAIT/LANDSCAPE); ""
    // derives from the legacy homeRotation flag (true = AUTO_ROTATE).
    val homeOrientation: String = "",
    // Feed page (Phase 7): master toggle, the left-edge-swipe open path, and
    // which built-in sections/cards render. Calendar/contacts stay off by
    // default because they are permission-gated. The page itself starts on.
    val feedEnabled: Boolean = true,
    val feedEdgeSwipe: Boolean = true,
    val feedShowSearch: Boolean = true,
    val feedShowFrequent: Boolean = true,
    // A strip of app shortcuts pulled from the most-launched apps (D7).
    val feedShowShortcuts: Boolean = true,
    val feedShowMedia: Boolean = true,
    val feedShowCalendar: Boolean = false,
    val feedShowContacts: Boolean = false,
    // How many rows of suggested-shortcut pills the feed shows
    // ("Suggested shortcuts rows"), 1..3.
    val feedShortcutRows: Int = 1,
    // A hidden Labs menu, shown by holding volume down on the settings
    // root, and its switches.
    val labsEnabled: Boolean = false,
    // The app's component name in its edit sheet, for icon theme makers.
    val labsShowComponent: Boolean = false,
    // Export PNG in a desktop item's long-press menu.
    val labsExportPng: Boolean = false,
    // The grid pickers offer GridLimits' big sizes.
    val labsBigGrid: Boolean = false
)

object BadgeLimits {
    // badgeCornerRadius at its roundest: a circle, the look before the
    // setting existed.
    const val CORNER_ROUND = 100
}

// How a drawer group renders: a filter tab (the original behavior) or a
// folder icon inside the drawer grid.
enum class DrawerGroupType {
    TAB,
    FOLDER;

    companion object {
        fun fromName(name: String?): DrawerGroupType =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: TAB
    }
}

data class DrawerGroup(
    val name: String,
    val members: Set<String>,
    val order: Int = Int.MAX_VALUE,
    val type: DrawerGroupType = DrawerGroupType.TAB,
    // Set on groups generated by Smart Folders so disabling the feature can
    // remove exactly those and leave user-created groups alone.
    val smart: Boolean = false,
    // "Keep apps in main app tab": true leaves the group's apps in the
    // All list as well, false takes them out of it. Null is a group from
    // before the choice existed, which keeps its old behaviour: a folder's
    // apps leave the list, a tab's follow the old global Isolate tabs switch
    // (see LauncherViewModel.leavesMainList).
    val keepInMain: Boolean? = null
) {

    // "Name|key1,key2|order|type|smart|keep" - the order segment is absent in
    // pre-v2 entries and decodes as MAX_VALUE, which sorts after explicitly
    // ordered groups. The type (v5) and smart (v5) segments are absent in
    // older entries and decode as TAB/user-created; old builds split('|') and
    // read parts 0..2, so a FOLDER group degrades to a TAB there. The keep
    // segment is "keep", "move" or absent (null).
    fun encode(): String {
        val base = "$name|${members.joinToString(",")}|$order|${type.name}|${if (smart) SMART_MARKER else ""}"
        return when (keepInMain) {
            null -> base
            true -> "$base|$KEEP_MARKER"
            false -> "$base|$MOVE_MARKER"
        }
    }

    companion object {
        const val SMART_MARKER = "smart"
        private const val KEEP_FIELD = 5
        private const val KEEP_MARKER = "keep"
        private const val MOVE_MARKER = "move"

        fun decode(raw: String): DrawerGroup? {
            val parts = raw.split('|')
            val name = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return null
            val members = parts.getOrNull(1).orEmpty()
                .split(',')
                .filter { it.isNotBlank() }
                .toSet()
            val order = parts.getOrNull(2)?.toIntOrNull() ?: Int.MAX_VALUE
            val type = DrawerGroupType.fromName(parts.getOrNull(3))
            val smart = parts.getOrNull(4) == SMART_MARKER
            val keepInMain = when (parts.getOrNull(KEEP_FIELD)) {
                KEEP_MARKER -> true
                MOVE_MARKER -> false
                else -> null
            }
            return DrawerGroup(name, members, order, type, smart, keepInMain)
        }
    }
}

@Serializable
data class BackupPayload(
    val version: Int = 1,
    val exportedAtEpochMs: Long,
    val model: LauncherModel,
    val prefs: LauncherPrefs
)

data class LaunchableApp(
    val packageName: String,
    val className: String,
    val label: String,
    val userSerial: Long = 0L,
    val installTime: Long = 0L,
    // Resolved from the app's override so list surfaces can honor it.
    val hideLabel: Boolean = false
)
