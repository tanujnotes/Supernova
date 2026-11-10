package com.supernova.launcher.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule

// A setting as it is stored: its key and, for a number, the range it is held
// to on every read and write. It fills the LauncherPrefs field named after
// the key ("grid_rows" is gridRows), and its default is that field's own.
open class PrefSpec<T : Any> internal constructor(val key: Preferences.Key<T>) {

    // The LauncherPrefs field this setting fills.
    val field: String = key.name.split('_').let { words ->
        words.first() + words.drop(1).joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
    }

    open fun clamped(value: T): T = value

    internal fun stored(values: Preferences): T? = values[key]?.let(::clamped)

    // From LauncherPrefs' own serializer, which hands every field over as
    // Any: a spec of the wrong type for its field fails PrefRegistryTest.
    @Suppress("UNCHECKED_CAST")
    internal fun store(target: MutablePreferences, value: Any) {
        target[key] = clamped(value as T)
    }
}

private class RangedPref<T>(
    key: Preferences.Key<T>,
    private val range: ClosedRange<T>
) : PrefSpec<T>(key) where T : Any, T : Comparable<T> {
    override fun clamped(value: T): T = value.coerceIn(range)
}

// Every setting, once. A new one is a field in LauncherPrefs (with its
// default) and a line here; reading, backup, restore, reset and
// LauncherPreferences.set all follow from the two. The key strings are the
// stored format: renaming one orphans what users have saved under it.
object Pref {
    private const val ICON_SCALE_MIN = 0.7f
    private const val ICON_SCALE_MAX = 2f
    private const val BADGE_SCALE_MIN = 0.6f
    private const val BADGE_SCALE_MAX = 1.6f
    private const val DRAWER_OPACITY_MIN = 0.2f
    private const val DOCK_COUNT_MIN = 3
    private const val DESKTOP_PADDING_MAX = 48
    private const val WIDGET_PADDING_MAX = 24
    private const val WIDGET_RADIUS_MAX = 32
    private const val LABEL_SIZE_MIN = 9
    private const val LABEL_SIZE_MAX = 18
    private const val DAY_LAST_MINUTE = 1439

    // Dock pages, and the feed's rows of shortcuts.
    private const val PAGES_OR_ROWS_MAX = 3

    private val specs = mutableListOf<PrefSpec<*>>()
    val all: List<PrefSpec<*>> get() = specs

    private fun <T : Any> add(spec: PrefSpec<T>): PrefSpec<T> = spec.also(specs::add)

    private fun bool(key: String) = add(PrefSpec(booleanPreferencesKey(key)))
    private fun text(key: String) = add(PrefSpec(stringPreferencesKey(key)))
    private fun textSet(key: String) = add(PrefSpec(stringSetPreferencesKey(key)))
    private fun int(key: String) = add(PrefSpec(intPreferencesKey(key)))
    private fun int(key: String, range: IntRange) = add(RangedPref(intPreferencesKey(key), range))
    private fun float(key: String, range: ClosedFloatingPointRange<Float>) =
        add(RangedPref(floatPreferencesKey(key), range))

    val gridRows = int("grid_rows", 2..GridLimits.DESKTOP_BIG)
    val gridCols = int("grid_cols", 2..GridLimits.DESKTOP_BIG)
    val dockCount = int("dock_count", DOCK_COUNT_MIN..GridLimits.DOCK_BIG)
    val iconScale = float("icon_scale", ICON_SCALE_MIN..ICON_SCALE_MAX)
    val showLabels = bool("show_labels")
    val wallpaperScroll = bool("wallpaper_scroll")
    val wallpaperScrollMode = text("wallpaper_scroll_mode")
    val iconPack = text("icon_pack")
    val scrollEffect = text("scroll_effect")
    val iconShape = text("icon_shape")
    val iconShapeCustom = text("icon_shape_custom")
    val subgrid = bool("subgrid")
    val badges = bool("badges")
    val badgeCounts = bool("badge_counts")
    val badgeStyle = text("badge_style")
    val badgeApps = textSet("badge_apps")
    val hiddenApps = textSet("hidden_apps")
    val gestureBindings = textSet("gesture_bindings")
    val drawerGroups = textSet("drawer_groups")
    val pageIndicatorStyle = text("page_indicator_style")
    val pageIndicatorAutoHide = bool("page_indicator_auto_hide")
    val pageIndicatorColor = text("page_indicator_color")
    val infiniteScroll = bool("infinite_scroll")
    val removeEmptyPages = bool("remove_empty_pages")
    val desktopPaddingH = int("desktop_padding_h", 0..DESKTOP_PADDING_MAX)
    val desktopPaddingV = int("desktop_padding_v", 0..DESKTOP_PADDING_MAX)
    val desktopLocked = bool("desktop_locked")
    val dockEnabled = bool("dock_enabled")
    val dockPages = int("dock_pages", 1..PAGES_OR_ROWS_MAX)
    val dockBgStyle = text("dock_bg_style")
    val dockBgOpacity = float("dock_bg_opacity", 0f..1f)
    val dockBgBehindNav = bool("dock_bg_behind_nav")
    val dockBgPadding = bool("dock_bg_padding")
    val dockLabels = bool("dock_labels")
    val dockIconScale = float("dock_icon_scale", ICON_SCALE_MIN..ICON_SCALE_MAX)
    val widgetOverlap = bool("widget_overlap")
    val overlapWhenPlacing = bool("overlap_when_placing")
    val widgetPadding = int("widget_padding", 0..WIDGET_PADDING_MAX)
    val drawerStyle = text("drawer_style")
    val drawerColumns = int("drawer_columns", 0..GridLimits.DRAWER_COLUMNS_BIG)
    val drawerRows = int("drawer_rows", 0..GridLimits.DRAWER_ROWS_BIG)
    val drawerAutoKeyboard = bool("drawer_auto_keyboard")
    val drawerInfiniteScroll = bool("drawer_infinite_scroll")
    val drawerPageEffect = text("drawer_page_effect")
    val drawerTransition = text("drawer_transition")
    val drawerTabBar = text("drawer_tab_bar")
    val drawerTabStyle = text("drawer_tab_style")
    val drawerTabsNoSwipe = bool("drawer_tabs_no_swipe")
    val drawerFrequentRow = bool("drawer_frequent_row")
    val drawerRememberPosition = bool("drawer_remember_position")
    val drawerAutoClose = bool("drawer_auto_close")
    val drawerFoldersFirst = bool("drawer_folders_first")
    val smartFolders = bool("smart_folders")
    val smartFoldersDismissed = textSet("smart_folders_dismissed")
    val drawerIconScale = float("drawer_icon_scale", ICON_SCALE_MIN..ICON_SCALE_MAX)
    val drawerMatchDesktop = bool("drawer_match_desktop")
    val drawerBgOpacity = float("drawer_bg_opacity", DRAWER_OPACITY_MIN..1f)
    val drawerFastScroller = bool("drawer_fast_scroller")
    val drawerPullToSearch = bool("drawer_pull_to_search")
    val drawerSwipeIndicator = bool("drawer_swipe_indicator")
    val drawerSwipeHaptic = bool("drawer_swipe_haptic")
    val swipeDownHaptic = bool("swipe_down_haptic")
    val drawerScrubberColor = text("drawer_scrubber_color")
    val drawerActionButtons = text("drawer_action_buttons")
    val drawerIsolateTabs = bool("drawer_isolate_tabs")
    val drawerSort = text("drawer_sort")
    val drawerWorkTab = bool("drawer_work_tab")
    val drawerDefaultTab = text("drawer_default_tab")
    val drawerHideAllTab = bool("drawer_hide_all_tab")
    val labelSize = int("label_size", LABEL_SIZE_MIN..LABEL_SIZE_MAX)
    val labelConfig = text("label_config")
    val labelColor = text("label_color")
    val labelShadow = bool("label_shadow")
    val drawerLabels = bool("drawer_labels")
    val folderLabels = bool("folder_labels")
    val iconNormalize = bool("icon_normalize")
    val reshapeLegacy = text("reshape_legacy")
    val iconShadow = bool("icon_shadow")
    val iconGlass = text("icon_glass")
    val iconSwipeIndicator = bool("icon_swipe_indicator")
    val themedIcons = bool("themed_icons")
    val themedIconsAutogen = bool("themed_icons_autogen")
    val lockMethod = text("lock_method")
    val badgeColor = text("badge_color")
    val badgePosition = text("badge_position")
    val badgeScale = float("badge_scale", BADGE_SCALE_MIN..BADGE_SCALE_MAX)
    val badgeTextColor = text("badge_text_color")
    val badgeCornerRadius = int("badge_corner_radius", 0..BadgeLimits.CORNER_ROUND)
    val badgeOutline = bool("badge_outline")
    val folderPreviewStyle = text("folder_preview_style")
    val folderPrioritizeFirst = bool("folder_prioritize_first")
    val folderColumns = int("folder_columns", 0..GridLimits.FOLDER_COLUMNS_BIG)
    val folderSortAz = bool("folder_sort_az")
    val folderCloseOnLaunch = bool("folder_close_on_launch")
    val folderIconScale = float("folder_icon_scale", ICON_SCALE_MIN..ICON_SCALE_MAX)
    val folderMatchDesktop = bool("folder_match_desktop")
    val folderShowName = bool("folder_show_name")
    val folderShowCount = bool("folder_show_count")
    val folderMode = text("folder_mode")
    val folderShadow = bool("folder_shadow")
    val folderScroll = text("folder_scroll")
    val folderIconShape = text("folder_icon_shape")
    val folderIconOutline = bool("folder_icon_outline")
    val folderClipToBg = bool("folder_clip_to_bg")
    val folderMatchWindowColor = bool("folder_match_window_color")
    val folderPlateColor = text("folder_plate_color")
    val folderBadges = bool("folder_badges")
    val themeMode = text("theme_mode")
    val themeScheduleStart = int("theme_schedule_start", 0..DAY_LAST_MINUTE)
    val themeScheduleEnd = int("theme_schedule_end", 0..DAY_LAST_MINUTE)
    val accentColor = text("accent_color")
    val drawerBgColor = text("drawer_bg_color")
    val folderBgColor = text("folder_bg_color")
    val paletteSource = text("palette_source")
    val paletteSeed = text("palette_seed")
    val styleConfig = text("style_config")
    val widgetCornerRadius = int("widget_corner_radius", 0..WIDGET_RADIUS_MAX)
    val showScrims = bool("show_scrims")
    val wallpaperZoom = bool("wallpaper_zoom")
    val autoAddApps = bool("auto_add_apps")
    val labelFont = text("label_font")
    val labelSingleLine = bool("label_single_line")
    val dockInfiniteScroll = bool("dock_infinite_scroll")
    val dockMatchDesktop = bool("dock_match_desktop")
    val dockOverlay = bool("dock_overlay")
    val dockAutoClose = bool("dock_auto_close")
    val dockRememberPosition = bool("dock_remember_position")
    val dockPaddingH = int("dock_padding_h")
    val dockPaddingV = int("dock_padding_v")
    val searchWebProvider = text("search_web_provider")
    val searchContacts = bool("search_contacts")
    val searchShortcuts = bool("search_shortcuts")
    val searchFolders = bool("search_folders")
    val searchWindowMode = text("search_window_mode")
    val searchAppsOneRow = bool("search_apps_one_row")
    val searchContactCards = bool("search_contact_cards")
    val searchCards = bool("search_cards")
    val searchCalculator = bool("search_calculator")
    val searchHiddenApps = bool("search_hidden_apps")
    val searchHistory = bool("search_history")
    val searchPrivateKeyboard = bool("search_private_keyboard")
    val searchQueryShortcuts = bool("search_query_shortcuts")
    val searchBarWidgetId = int("search_bar_widget_id")
    val searchBarWidgetProvider = text("search_bar_widget_provider")
    val searchChips = text("search_chips")
    val homeSearch = bool("home_search")
    val homeButtonAction = text("home_button_action")
    val homeButtonOnMainPage = bool("home_button_on_main_page")
    val searchBarConfig = text("search_bar_config")
    val popupNotifications = bool("popup_notifications")
    val popupCardBg = bool("popup_card_bg")
    val popupGroupShortcuts = bool("popup_group_shortcuts")
    val popupActionIcons = bool("popup_action_icons")
    val popupStyle = text("popup_style")
    val popupItemsHome = text("popup_items_home")
    val popupItemsDrawer = text("popup_items_drawer")
    val dropTargets = bool("drop_targets")
    val undoRemove = bool("undo_remove")
    val disableAnimations = bool("disable_animations")
    val animationSpeed = text("animation_speed")
    val launchAnimation = text("launch_animation")
    val hideStatusBar = bool("hide_status_bar")
    val darkStatusIcons = text("dark_status_icons")
    val transparentNav = bool("transparent_nav")
    val iconWiggle = bool("icon_wiggle")
    val transparentStatus = bool("transparent_status")
    val homeRotation = bool("home_rotation")
    val homeOrientation = text("home_orientation")
    val feedEnabled = bool("feed_enabled")
    val feedEdgeSwipe = bool("feed_edge_swipe")
    val feedShowSearch = bool("feed_show_search")
    val feedShowFrequent = bool("feed_show_frequent")
    val feedShowShortcuts = bool("feed_show_shortcuts")
    val feedShowMedia = bool("feed_show_media")
    val feedShowCalendar = bool("feed_show_calendar")
    val feedShowContacts = bool("feed_show_contacts")
    val feedShortcutRows = int("feed_shortcut_rows", 1..PAGES_OR_ROWS_MAX)
    val labsEnabled = bool("labs_enabled")
    val labsShowComponent = bool("labs_show_component")
    val labsExportPng = bool("labs_export_png")
    val labsBigGrid = bool("labs_big_grid")

    private val byField: Map<String, PrefSpec<*>> = specs.associateBy { it.field }

    fun forField(name: String): PrefSpec<*>? = byField[name]
}

// LauncherPrefs to and from the store, field by field through its own
// serializer, so no setting is listed again here: a stored key fills its
// field, and a field with nothing stored keeps the default LauncherPrefs
// declares.
@OptIn(ExperimentalSerializationApi::class)
object PrefStoreFormat {

    fun read(values: Preferences): LauncherPrefs =
        LauncherPrefs.serializer().deserialize(Reader(values))

    fun write(target: MutablePreferences, prefs: LauncherPrefs) {
        LauncherPrefs.serializer().serialize(Writer(target), prefs)
    }

    private class Reader(private val values: Preferences) : AbstractDecoder() {
        override val serializersModule: SerializersModule = EmptySerializersModule()
        private var index = -1
        private var current: Any? = null

        // Only the fields the store holds: the rest keep their defaults.
        override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
            while (++index < descriptor.elementsCount) {
                current = Pref.forField(descriptor.getElementName(index))?.stored(values) ?: continue
                return index
            }
            return CompositeDecoder.DECODE_DONE
        }

        override fun decodeValue(): Any = checkNotNull(current)

        // The one field type that isn't a primitive is a set of strings,
        // which the store holds as it is.
        @Suppress("UNCHECKED_CAST")
        override fun <T> decodeSerializableValue(deserializer: DeserializationStrategy<T>): T = decodeValue() as T
    }

    private class Writer(private val target: MutablePreferences) : AbstractEncoder() {
        override val serializersModule: SerializersModule = EmptySerializersModule()
        private var spec: PrefSpec<*>? = null

        // A field at its default is written too: a restore or a reset has
        // to replace whatever the store held.
        override fun shouldEncodeElementDefault(descriptor: SerialDescriptor, index: Int): Boolean = true

        override fun encodeElement(descriptor: SerialDescriptor, index: Int): Boolean {
            spec = checkNotNull(Pref.forField(descriptor.getElementName(index))) {
                "LauncherPrefs.${descriptor.getElementName(index)} has no line in Pref"
            }
            return true
        }

        override fun encodeValue(value: Any) {
            checkNotNull(spec).store(target, value)
        }

        override fun <T> encodeSerializableValue(serializer: SerializationStrategy<T>, value: T) {
            checkNotNull(spec).store(target, value as Any)
        }
    }
}
