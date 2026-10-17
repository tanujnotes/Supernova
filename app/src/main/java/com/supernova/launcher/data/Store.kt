package com.supernova.launcher.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

val Context.supernovaStore by preferencesDataStore(name = "supernova_store")

object StoreKeys {
    val model: Preferences.Key<String> = stringPreferencesKey("launcher_model")
    val gridRows: Preferences.Key<Int> = intPreferencesKey("grid_rows")
    val gridCols: Preferences.Key<Int> = intPreferencesKey("grid_cols")
    val dockCount: Preferences.Key<Int> = intPreferencesKey("dock_count")
    // Set once the first-run grid has been chosen for this screen; see DeviceProfile.
    val deviceProfileApplied: Preferences.Key<Boolean> = booleanPreferencesKey("device_profile_applied")
    val iconScale: Preferences.Key<Float> = floatPreferencesKey("icon_scale")
    val iconSwipeIndicator: Preferences.Key<Boolean> = booleanPreferencesKey("icon_swipe_indicator")
    val showLabels: Preferences.Key<Boolean> = booleanPreferencesKey("show_labels")
    val homePromptShown: Preferences.Key<Boolean> = booleanPreferencesKey("home_prompt_shown")
    val wallpaperScroll: Preferences.Key<Boolean> = booleanPreferencesKey("wallpaper_scroll")
    val wallpaperScrollMode: Preferences.Key<String> = stringPreferencesKey("wallpaper_scroll_mode")
    val iconPack: Preferences.Key<String> = stringPreferencesKey("icon_pack")
    val scrollEffect: Preferences.Key<String> = stringPreferencesKey("scroll_effect")
    val iconShape: Preferences.Key<String> = stringPreferencesKey("icon_shape")
    val iconShapeCustom: Preferences.Key<String> = stringPreferencesKey("icon_shape_custom")
    val subgrid: Preferences.Key<Boolean> = booleanPreferencesKey("subgrid")
    val badges: Preferences.Key<Boolean> = booleanPreferencesKey("badges")
    val badgeCounts: Preferences.Key<Boolean> = booleanPreferencesKey("badge_counts")
    val badgeStyle: Preferences.Key<String> = stringPreferencesKey("badge_style")
    val badgeApps: Preferences.Key<Set<String>> = stringSetPreferencesKey("badge_apps")
    val hiddenApps: Preferences.Key<Set<String>> = stringSetPreferencesKey("hidden_apps")
    val gestureBindings: Preferences.Key<Set<String>> = stringSetPreferencesKey("gesture_bindings")
    val drawerGroups: Preferences.Key<Set<String>> = stringSetPreferencesKey("drawer_groups")
    val pageIndicatorStyle: Preferences.Key<String> = stringPreferencesKey("page_indicator_style")
    val pageIndicatorAutoHide: Preferences.Key<Boolean> = booleanPreferencesKey("page_indicator_auto_hide")
    val pageIndicatorColor: Preferences.Key<String> = stringPreferencesKey("page_indicator_color")
    val infiniteScroll: Preferences.Key<Boolean> = booleanPreferencesKey("infinite_scroll")
    val desktopPaddingH: Preferences.Key<Int> = intPreferencesKey("desktop_padding_h")
    val desktopPaddingV: Preferences.Key<Int> = intPreferencesKey("desktop_padding_v")
    val desktopLocked: Preferences.Key<Boolean> = booleanPreferencesKey("desktop_locked")
    val dockEnabled: Preferences.Key<Boolean> = booleanPreferencesKey("dock_enabled")
    val dockPages: Preferences.Key<Int> = intPreferencesKey("dock_pages")
    val dockBgStyle: Preferences.Key<String> = stringPreferencesKey("dock_bg_style")
    val dockBgOpacity: Preferences.Key<Float> = floatPreferencesKey("dock_bg_opacity")
    val dockBgBehindNav: Preferences.Key<Boolean> = booleanPreferencesKey("dock_bg_behind_nav")
    val dockBgPadding: Preferences.Key<Boolean> = booleanPreferencesKey("dock_bg_padding")
    val dockLabels: Preferences.Key<Boolean> = booleanPreferencesKey("dock_labels")
    val dockIconScale: Preferences.Key<Float> = floatPreferencesKey("dock_icon_scale")
    val widgetOverlap: Preferences.Key<Boolean> = booleanPreferencesKey("widget_overlap")
    val overlapWhenPlacing: Preferences.Key<Boolean> = booleanPreferencesKey("overlap_when_placing")
    val widgetPadding: Preferences.Key<Int> = intPreferencesKey("widget_padding")
    val drawerStyle: Preferences.Key<String> = stringPreferencesKey("drawer_style")
    val drawerColumns: Preferences.Key<Int> = intPreferencesKey("drawer_columns")
    val drawerRows: Preferences.Key<Int> = intPreferencesKey("drawer_rows")
    val drawerAutoKeyboard: Preferences.Key<Boolean> = booleanPreferencesKey("drawer_auto_keyboard")
    val drawerTabBar: Preferences.Key<String> = stringPreferencesKey("drawer_tab_bar")
    val drawerTabStyle: Preferences.Key<String> = stringPreferencesKey("drawer_tab_style")
    val drawerTabsNoSwipe: Preferences.Key<Boolean> = booleanPreferencesKey("drawer_tabs_no_swipe")
    val drawerFrequentRow: Preferences.Key<Boolean> = booleanPreferencesKey("drawer_frequent_row")
    val drawerTransition: Preferences.Key<String> = stringPreferencesKey("drawer_transition")
    val folderShadow: Preferences.Key<Boolean> = booleanPreferencesKey("folder_shadow")
    val transparentStatus: Preferences.Key<Boolean> = booleanPreferencesKey("transparent_status")
    val iconWiggle: Preferences.Key<Boolean> = booleanPreferencesKey("icon_wiggle")
    val homeButtonOnMainPage: Preferences.Key<Boolean> =
        booleanPreferencesKey("home_button_on_main_page")
    val searchChips: Preferences.Key<String> = stringPreferencesKey("search_chips")
    val searchBarWidgetId: Preferences.Key<Int> = intPreferencesKey("search_bar_widget_id")
    val searchBarWidgetProvider: Preferences.Key<String> =
        stringPreferencesKey("search_bar_widget_provider")
    val searchQueryShortcuts: Preferences.Key<Boolean> =
        booleanPreferencesKey("search_query_shortcuts")
    val drawerInfiniteScroll: Preferences.Key<Boolean> =
        booleanPreferencesKey("drawer_infinite_scroll")
    val drawerPageEffect: Preferences.Key<String> = stringPreferencesKey("drawer_page_effect")
    val drawerRememberPosition: Preferences.Key<Boolean> = booleanPreferencesKey("drawer_remember_position")
    val drawerAutoClose: Preferences.Key<Boolean> = booleanPreferencesKey("drawer_auto_close")
    val drawerFoldersFirst: Preferences.Key<Boolean> = booleanPreferencesKey("drawer_folders_first")
    val smartFolders: Preferences.Key<Boolean> = booleanPreferencesKey("smart_folders")
    val smartFoldersDismissed: Preferences.Key<Set<String>> = stringSetPreferencesKey("smart_folders_dismissed")
    val drawerIconScale: Preferences.Key<Float> = floatPreferencesKey("drawer_icon_scale")
    val drawerMatchDesktop: Preferences.Key<Boolean> = booleanPreferencesKey("drawer_match_desktop")
    val drawerBgOpacity: Preferences.Key<Float> = floatPreferencesKey("drawer_bg_opacity")
    val drawerFastScroller: Preferences.Key<Boolean> = booleanPreferencesKey("drawer_fast_scroller")
    val drawerPullToSearch: Preferences.Key<Boolean> = booleanPreferencesKey("drawer_pull_to_search")
    val drawerSwipeIndicator: Preferences.Key<Boolean> = booleanPreferencesKey("drawer_swipe_indicator")
    val drawerSwipeHaptic: Preferences.Key<Boolean> = booleanPreferencesKey("drawer_swipe_haptic")
    val swipeDownHaptic: Preferences.Key<Boolean> = booleanPreferencesKey("swipe_down_haptic")
    val drawerScrubberColor: Preferences.Key<String> = stringPreferencesKey("drawer_scrubber_color")
    val drawerActionButtons: Preferences.Key<String> = stringPreferencesKey("drawer_action_buttons")
    val drawerIsolateTabs: Preferences.Key<Boolean> = booleanPreferencesKey("drawer_isolate_tabs")
    val drawerSort: Preferences.Key<String> = stringPreferencesKey("drawer_sort")
    val drawerWorkTab: Preferences.Key<Boolean> = booleanPreferencesKey("drawer_work_tab")
    val drawerDefaultTab: Preferences.Key<String> = stringPreferencesKey("drawer_default_tab")
    val drawerHideAllTab: Preferences.Key<Boolean> = booleanPreferencesKey("drawer_hide_all_tab")
    val labelSize: Preferences.Key<Int> = intPreferencesKey("label_size")
    val labelConfig: Preferences.Key<String> = stringPreferencesKey("label_config")
    val labelColor: Preferences.Key<String> = stringPreferencesKey("label_color")
    val labelShadow: Preferences.Key<Boolean> = booleanPreferencesKey("label_shadow")
    val drawerLabels: Preferences.Key<Boolean> = booleanPreferencesKey("drawer_labels")
    val folderLabels: Preferences.Key<Boolean> = booleanPreferencesKey("folder_labels")
    val iconNormalize: Preferences.Key<Boolean> = booleanPreferencesKey("icon_normalize")
    val reshapeLegacy: Preferences.Key<String> = stringPreferencesKey("reshape_legacy")
    val iconShadow: Preferences.Key<Boolean> = booleanPreferencesKey("icon_shadow")
    val themedIcons: Preferences.Key<Boolean> = booleanPreferencesKey("themed_icons")
    val themedIconsAutogen: Preferences.Key<Boolean> = booleanPreferencesKey("themed_icons_autogen")
    val lockMethod: Preferences.Key<String> = stringPreferencesKey("lock_method")
    val animationSpeed: Preferences.Key<String> = stringPreferencesKey("animation_speed")
    val dockPaddingH: Preferences.Key<Int> = intPreferencesKey("dock_padding_h")
    val dockPaddingV: Preferences.Key<Int> = intPreferencesKey("dock_padding_v")
    val badgeColor: Preferences.Key<String> = stringPreferencesKey("badge_color")
    val badgePosition: Preferences.Key<String> = stringPreferencesKey("badge_position")
    val badgeScale: Preferences.Key<Float> = floatPreferencesKey("badge_scale")
    val badgeTextColor: Preferences.Key<String> = stringPreferencesKey("badge_text_color")
    val badgeCornerRadius: Preferences.Key<Int> = intPreferencesKey("badge_corner_radius")
    val badgeOutline: Preferences.Key<Boolean> = booleanPreferencesKey("badge_outline")
    val folderPreviewStyle: Preferences.Key<String> = stringPreferencesKey("folder_preview_style")
    val folderPrioritizeFirst: Preferences.Key<Boolean> = booleanPreferencesKey("folder_prioritize_first")
    val folderColumns: Preferences.Key<Int> = intPreferencesKey("folder_columns")
    val folderSortAz: Preferences.Key<Boolean> = booleanPreferencesKey("folder_sort_az")
    val folderCloseOnLaunch: Preferences.Key<Boolean> = booleanPreferencesKey("folder_close_on_launch")
    val folderIconScale: Preferences.Key<Float> = floatPreferencesKey("folder_icon_scale")
    val folderMatchDesktop: Preferences.Key<Boolean> = booleanPreferencesKey("folder_match_desktop")
    val folderShowName: Preferences.Key<Boolean> = booleanPreferencesKey("folder_show_name")
    val folderMode: Preferences.Key<String> = stringPreferencesKey("folder_mode")
    val folderScroll: Preferences.Key<String> = stringPreferencesKey("folder_scroll")
    val folderIconShape: Preferences.Key<String> = stringPreferencesKey("folder_icon_shape")
    val folderIconOutline: Preferences.Key<Boolean> = booleanPreferencesKey("folder_icon_outline")
    val folderClipToBg: Preferences.Key<Boolean> = booleanPreferencesKey("folder_clip_to_bg")
    val folderMatchWindowColor: Preferences.Key<Boolean> = booleanPreferencesKey("folder_match_window_color")
    val folderBadges: Preferences.Key<Boolean> = booleanPreferencesKey("folder_badges")
    val themeMode: Preferences.Key<String> = stringPreferencesKey("theme_mode")
    val themeScheduleStart: Preferences.Key<Int> = intPreferencesKey("theme_schedule_start")
    val themeScheduleEnd: Preferences.Key<Int> = intPreferencesKey("theme_schedule_end")
    val themeAutoLocation: Preferences.Key<Boolean> = booleanPreferencesKey("theme_auto_location")
    val accentColor: Preferences.Key<String> = stringPreferencesKey("accent_color")
    val drawerBgColor: Preferences.Key<String> = stringPreferencesKey("drawer_bg_color")
    val folderBgColor: Preferences.Key<String> = stringPreferencesKey("folder_bg_color")
    val paletteSource: Preferences.Key<String> = stringPreferencesKey("palette_source")
    val paletteSeed: Preferences.Key<String> = stringPreferencesKey("palette_seed")
    val styleConfig: Preferences.Key<String> = stringPreferencesKey("style_config")
    val widgetCornerRadius: Preferences.Key<Int> = intPreferencesKey("widget_corner_radius")
    val showScrims: Preferences.Key<Boolean> = booleanPreferencesKey("show_scrims")
    val wallpaperZoom: Preferences.Key<Boolean> = booleanPreferencesKey("wallpaper_zoom")
    val autoAddApps: Preferences.Key<Boolean> = booleanPreferencesKey("auto_add_apps")
    val labelFont: Preferences.Key<String> = stringPreferencesKey("label_font")
    val labelSingleLine: Preferences.Key<Boolean> = booleanPreferencesKey("label_single_line")
    val dockInfiniteScroll: Preferences.Key<Boolean> = booleanPreferencesKey("dock_infinite_scroll")
    val dockMatchDesktop: Preferences.Key<Boolean> = booleanPreferencesKey("dock_match_desktop")
    val dockOverlay: Preferences.Key<Boolean> = booleanPreferencesKey("dock_overlay")
    val dockAutoClose: Preferences.Key<Boolean> = booleanPreferencesKey("dock_auto_close")
    val dockRememberPosition: Preferences.Key<Boolean> = booleanPreferencesKey("dock_remember_position")
    val searchWebProvider: Preferences.Key<String> = stringPreferencesKey("search_web_provider")
    val searchContacts: Preferences.Key<Boolean> = booleanPreferencesKey("search_contacts")
    val searchShortcuts: Preferences.Key<Boolean> = booleanPreferencesKey("search_shortcuts")
    val searchFolders: Preferences.Key<Boolean> = booleanPreferencesKey("search_folders")
    val searchWindowMode: Preferences.Key<String> = stringPreferencesKey("search_window_mode")
    val searchAppsOneRow: Preferences.Key<Boolean> = booleanPreferencesKey("search_apps_one_row")
    val searchContactCards: Preferences.Key<Boolean> = booleanPreferencesKey("search_contact_cards")
    val searchCards: Preferences.Key<Boolean> = booleanPreferencesKey("search_cards")
    val searchCalculator: Preferences.Key<Boolean> = booleanPreferencesKey("search_calculator")
    val searchHistory: Preferences.Key<Boolean> = booleanPreferencesKey("search_history")
    val searchPrivateKeyboard: Preferences.Key<Boolean> = booleanPreferencesKey("search_private_keyboard")
    val homeSearch: Preferences.Key<Boolean> = booleanPreferencesKey("home_search")
    val homeButtonAction: Preferences.Key<String> = stringPreferencesKey("home_button_action")
    val searchBarConfig: Preferences.Key<String> = stringPreferencesKey("search_bar_config")
    val popupNotifications: Preferences.Key<Boolean> = booleanPreferencesKey("popup_notifications")
    val popupCardBg: Preferences.Key<Boolean> = booleanPreferencesKey("popup_card_bg")
    val popupGroupShortcuts: Preferences.Key<Boolean> = booleanPreferencesKey("popup_group_shortcuts")
    val popupStyle: Preferences.Key<String> = stringPreferencesKey("popup_style")
    val popupItemsHome: Preferences.Key<String> = stringPreferencesKey("popup_items_home")
    val popupItemsDrawer: Preferences.Key<String> = stringPreferencesKey("popup_items_drawer")
    val dropTargets: Preferences.Key<Boolean> = booleanPreferencesKey("drop_targets")
    val undoRemove: Preferences.Key<Boolean> = booleanPreferencesKey("undo_remove")
    val disableAnimations: Preferences.Key<Boolean> = booleanPreferencesKey("disable_animations")
    val launchAnimation: Preferences.Key<String> = stringPreferencesKey("launch_animation")
    val hideStatusBar: Preferences.Key<Boolean> = booleanPreferencesKey("hide_status_bar")
    val darkStatusIcons: Preferences.Key<String> = stringPreferencesKey("dark_status_icons")
    val transparentNav: Preferences.Key<Boolean> = booleanPreferencesKey("transparent_nav")
    val homeRotation: Preferences.Key<Boolean> = booleanPreferencesKey("home_rotation")
    val homeOrientation: Preferences.Key<String> = stringPreferencesKey("home_orientation")
    val feedEnabled: Preferences.Key<Boolean> = booleanPreferencesKey("feed_enabled")
    val feedEdgeSwipe: Preferences.Key<Boolean> = booleanPreferencesKey("feed_edge_swipe")
    val feedShowSearch: Preferences.Key<Boolean> = booleanPreferencesKey("feed_show_search")
    val feedShowFrequent: Preferences.Key<Boolean> = booleanPreferencesKey("feed_show_frequent")
    val feedShowShortcuts: Preferences.Key<Boolean> = booleanPreferencesKey("feed_show_shortcuts")
    val feedShowMedia: Preferences.Key<Boolean> = booleanPreferencesKey("feed_show_media")
    val feedShowCalendar: Preferences.Key<Boolean> = booleanPreferencesKey("feed_show_calendar")
    val feedShowContacts: Preferences.Key<Boolean> = booleanPreferencesKey("feed_show_contacts")
    val feedShortcutRows: Preferences.Key<Int> = intPreferencesKey("feed_shortcut_rows")
    val labsEnabled: Preferences.Key<Boolean> = booleanPreferencesKey("labs_enabled")
    val labsShowComponent: Preferences.Key<Boolean> = booleanPreferencesKey("labs_show_component")
    val labsExportPng: Preferences.Key<Boolean> = booleanPreferencesKey("labs_export_png")
    val labsBigGrid: Preferences.Key<Boolean> = booleanPreferencesKey("labs_big_grid")

    // Device-local usage data (not part of LauncherPrefs, not backed up):
    // launch counts as "userSerial/pkg/cls=count" entries, for most-used sort.
    val launchCounts: Preferences.Key<Set<String>> = stringSetPreferencesKey("launch_counts")
    // "key=epochMillis" for the most recently opened apps (the drawer's
    // recents row). Usage data like launchCounts: device-local, not a backup item.
    val recentLaunches: Preferences.Key<Set<String>> = stringSetPreferencesKey("recent_launches")
    // When Labs' Clear icon cache last ran. Every open activity's catalog
    // keeps its own cache, so the request travels through the store.
    // A signal, not a setting: not a backup item.
    val iconCacheCleared: Preferences.Key<Long> = longPreferencesKey("icon_cache_cleared")

    // The last shown dock page for dockRememberPosition. Positional state,
    // not configuration, so it stays out of LauncherPrefs and backups.
    val dockPageState: Preferences.Key<Int> = intPreferencesKey("dock_page_state")

    // Recent search queries, newest first, '\n'-joined (a string set would
    // lose recency order). Usage data like launchCounts: device-local, not a
    // preference, never backed up.
    val searchRecents: Preferences.Key<String> = stringPreferencesKey("search_recents")

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
}
