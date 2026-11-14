package com.supernova.launcher.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.supernova.launcher.search.WebProvider
import com.supernova.launcher.ui.AnimationSpeed
import com.supernova.launcher.ui.BadgePosition
import com.supernova.launcher.ui.DockBgStyle
import com.supernova.launcher.ui.DrawerSort
import com.supernova.launcher.ui.DrawerStyle
import com.supernova.launcher.ui.DrawerTabAlign
import com.supernova.launcher.ui.DrawerTabBar
import com.supernova.launcher.ui.DrawerTabContent
import com.supernova.launcher.ui.DrawerTabStyle
import com.supernova.launcher.ui.DrawerTransition
import com.supernova.launcher.ui.FolderMode
import com.supernova.launcher.ui.FolderPreviewStyle
import com.supernova.launcher.ui.FolderScroll
import com.supernova.launcher.ui.IndicatorStyle
import com.supernova.launcher.ui.LabelFont
import com.supernova.launcher.ui.LaunchAnimation
import com.supernova.launcher.ui.PopupMenuStyle
import com.supernova.launcher.ui.ScrollEffect
import com.supernova.launcher.ui.StatusBarIcons
import com.supernova.launcher.ui.TopRowSort
import com.supernova.launcher.ui.theme.AccentColor
import com.supernova.launcher.ui.theme.PaletteSourceMode
import com.supernova.launcher.ui.theme.ThemeMode
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule

// One setting's new value, for writing several in a single store edit
// (LauncherPreferences.setAll): one emission, so the icons re-render once.
class PrefWrite<T : Any>(val spec: PrefSpec<T>, val value: T) {
    internal fun into(target: MutablePreferences) = spec.write(target, value)
}

// A setting as it is stored. It fills the LauncherPrefs field named after
// its key ("grid_rows" is gridRows), and its default is that field's own.
sealed class PrefSpec<T : Any> {

    abstract val key: Preferences.Key<*>

    // The LauncherPrefs field this setting fills.
    val field: String by lazy {
        key.name.split('_').let { words ->
            words.first() + words.drop(1).joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
        }
    }

    // The value held to its range, for a number that has one.
    open fun clamped(value: T): T = value

    // A value of the setting's own type, for LauncherPreferences.set.
    internal abstract fun write(target: MutablePreferences, value: T)

    // The value as the store holds it, for LauncherPrefs' serializer
    // (PrefStoreFormat): null when nothing is stored.
    internal abstract fun stored(values: Preferences): Any?

    internal abstract fun store(target: MutablePreferences, raw: Any)
}

// A setting stored as what it is: a flag, a number, a string, a set of
// strings.
open class StoredPref<T : Any> internal constructor(override val key: Preferences.Key<T>) : PrefSpec<T>() {

    override fun write(target: MutablePreferences, value: T) {
        target[key] = clamped(value)
    }

    override fun stored(values: Preferences): Any? = values[key]?.let(::clamped)

    // From LauncherPrefs' own serializer, which hands every field over as
    // Any: a spec of the wrong type for its field fails PrefRegistryTest.
    @Suppress("UNCHECKED_CAST")
    override fun store(target: MutablePreferences, raw: Any) = write(target, raw as T)
}

private class RangedPref<T>(
    key: Preferences.Key<T>,
    private val range: ClosedRange<T>
) : StoredPref<T>(key) where T : Any, T : Comparable<T> {
    override fun clamped(value: T): T = value.coerceIn(range)
}

// A setting that is one of an enum's values, stored as the value's name. The
// name is turned back into the value by the enum's own serializer
// (SettingEnumSerializer), the same way in the store and in a backup.
class ChoicePref<E : Enum<E>> internal constructor(
    override val key: Preferences.Key<String>,
    val choices: List<E>
) : PrefSpec<E>() {

    override fun write(target: MutablePreferences, value: E) {
        target[key] = value.name
    }

    override fun stored(values: Preferences): Any? = values[key]

    override fun store(target: MutablePreferences, raw: Any) {
        target[key] = raw as String
    }
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
    private const val DOCK_BG_RADIUS_MAX = 24
    private const val LABEL_SIZE_MIN = 9
    private const val LABEL_SIZE_MAX = 18
    private const val DAY_LAST_MINUTE = 1439

    // Dock pages, and the feed's rows of shortcuts.
    private const val PAGES_OR_ROWS_MAX = 3

    private val specs = mutableListOf<PrefSpec<*>>()
    val all: List<PrefSpec<*>> get() = specs

    private fun <T : Any> add(spec: StoredPref<T>): StoredPref<T> = spec.also(specs::add)

    private fun bool(key: String) = add(StoredPref(booleanPreferencesKey(key)))
    private fun text(key: String) = add(StoredPref(stringPreferencesKey(key)))
    private fun textSet(key: String) = add(StoredPref(stringSetPreferencesKey(key)))
    private fun int(key: String) = add(StoredPref(intPreferencesKey(key)))
    private fun int(key: String, range: IntRange) = add(RangedPref(intPreferencesKey(key), range))
    private fun float(key: String, range: ClosedFloatingPointRange<Float>) =
        add(RangedPref(floatPreferencesKey(key), range))

    // One of an enum's values, stored by name.
    private fun <E : Enum<E>> choice(key: String, choices: List<E>) =
        ChoicePref(stringPreferencesKey(key), choices).also(specs::add)

    val gridRows = int("grid_rows", 2..GridLimits.DESKTOP_BIG)
    val gridCols = int("grid_cols", 2..GridLimits.DESKTOP_BIG)
    val dockCount = int("dock_count", DOCK_COUNT_MIN..GridLimits.DOCK_BIG)
    val iconScale = float("icon_scale", ICON_SCALE_MIN..ICON_SCALE_MAX)
    val showLabels = bool("show_labels")
    val wallpaperScroll = bool("wallpaper_scroll")
    val wallpaperScrollMode = text("wallpaper_scroll_mode")
    val iconPack = text("icon_pack")
    val scrollEffect = choice("scroll_effect", ScrollEffect.entries)
    val iconShape = choice("icon_shape", IconShape.entries)
    val iconShapeCustom = text("icon_shape_custom")
    val subgrid = bool("subgrid")
    val badges = bool("badges")
    val badgeCounts = bool("badge_counts")
    val badgeStyle = text("badge_style")
    val badgeApps = textSet("badge_apps")
    val hiddenApps = textSet("hidden_apps")
    val gestureBindings = textSet("gesture_bindings")
    val drawerGroups = textSet("drawer_groups")
    val pageIndicatorStyle = choice("page_indicator_style", IndicatorStyle.entries)
    val pageIndicatorAutoHide = bool("page_indicator_auto_hide")
    val pageIndicatorColor = text("page_indicator_color")
    val infiniteScroll = bool("infinite_scroll")
    val removeEmptyPages = bool("remove_empty_pages")
    val desktopPaddingH = int("desktop_padding_h", 0..DESKTOP_PADDING_MAX)
    val desktopPaddingV = int("desktop_padding_v", 0..DESKTOP_PADDING_MAX)
    val desktopLocked = bool("desktop_locked")
    val dockEnabled = bool("dock_enabled")
    val dockPages = int("dock_pages", 1..PAGES_OR_ROWS_MAX)
    val dockBgStyle = choice("dock_bg_style", DockBgStyle.entries)
    val dockBgOpacity = float("dock_bg_opacity", 0f..1f)
    val dockBgRadius = int("dock_bg_radius", 0..DOCK_BG_RADIUS_MAX)
    val dockBgBehindNav = bool("dock_bg_behind_nav")
    val dockBgPadding = bool("dock_bg_padding")
    val dockLabels = bool("dock_labels")
    val dockIconScale = float("dock_icon_scale", ICON_SCALE_MIN..ICON_SCALE_MAX)
    val widgetOverlap = bool("widget_overlap")
    val overlapWhenPlacing = bool("overlap_when_placing")
    val widgetPadding = int("widget_padding", 0..WIDGET_PADDING_MAX)
    val drawerStyle = choice("drawer_style", DrawerStyle.entries)
    val drawerColumns = int("drawer_columns", 0..GridLimits.DRAWER_COLUMNS_BIG)
    val drawerRows = int("drawer_rows", 0..GridLimits.DRAWER_ROWS_BIG)
    val drawerAutoKeyboard = bool("drawer_auto_keyboard")
    val drawerInfiniteScroll = bool("drawer_infinite_scroll")
    val drawerPageEffect = choice("drawer_page_effect", ScrollEffect.entries)
    val drawerTransition = choice("drawer_transition", DrawerTransition.entries)
    val drawerTabBar = choice("drawer_tab_bar", DrawerTabBar.entries)
    val drawerTabStyle = choice("drawer_tab_style", DrawerTabStyle.entries)
    val drawerTabContent = choice("drawer_tab_content", DrawerTabContent.entries)
    val drawerTabAlign = choice("drawer_tab_align", DrawerTabAlign.entries)
    val drawerTabsNoSwipe = bool("drawer_tabs_no_swipe")
    val drawerFrequentRow = bool("drawer_frequent_row")
    val drawerRememberPosition = bool("drawer_remember_position")
    val drawerAutoClose = bool("drawer_auto_close")
    val drawerFoldersFirst = bool("drawer_folders_first")
    val smartFolders = bool("smart_folders")
    val smartFoldersDismissed = textSet("smart_folders_dismissed")
    val drawerIconScale = float("drawer_icon_scale", ICON_SCALE_MIN..ICON_SCALE_MAX)
    val drawerPaddingH = int("drawer_padding_h", 0..DESKTOP_PADDING_MAX)
    val drawerPaddingV = int("drawer_padding_v", 0..DESKTOP_PADDING_MAX)
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
    val drawerSort = choice("drawer_sort", DrawerSort.entries)
    val drawerTopRowSort = choice("drawer_top_row_sort", TopRowSort.entries)
    val drawerSortButtons = bool("drawer_sort_buttons")
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
    val iconGlass = choice("icon_glass", IconGlass.entries)
    val iconSwipeIndicator = bool("icon_swipe_indicator")
    val themedIcons = bool("themed_icons")
    val themedIconsAutogen = bool("themed_icons_autogen")
    val lockMethod = choice("lock_method", LockMethod.entries)
    val badgeColor = text("badge_color")
    val badgePosition = choice("badge_position", BadgePosition.entries)
    val badgeScale = float("badge_scale", BADGE_SCALE_MIN..BADGE_SCALE_MAX)
    val badgeTextColor = text("badge_text_color")
    val badgeCornerRadius = int("badge_corner_radius", 0..BadgeLimits.CORNER_ROUND)
    val badgeOutline = bool("badge_outline")
    val folderPreviewStyle = choice("folder_preview_style", FolderPreviewStyle.entries)
    val folderPrioritizeFirst = bool("folder_prioritize_first")
    val folderColumns = int("folder_columns", 0..GridLimits.FOLDER_COLUMNS_BIG)
    val folderCloseOnLaunch = bool("folder_close_on_launch")
    val folderIconScale = float("folder_icon_scale", ICON_SCALE_MIN..ICON_SCALE_MAX)
    val folderMatchDesktop = bool("folder_match_desktop")
    val folderShowName = bool("folder_show_name")
    val folderShowCount = bool("folder_show_count")
    val folderMode = choice("folder_mode", FolderMode.entries)
    val folderShadow = bool("folder_shadow")
    val folderScroll = choice("folder_scroll", FolderScroll.entries)
    val folderIconShape = text("folder_icon_shape")
    val folderIconOutline = bool("folder_icon_outline")
    val folderMatchWindowColor = bool("folder_match_window_color")
    val folderPlateColor = text("folder_plate_color")
    val folderBadges = bool("folder_badges")
    val themeMode = choice("theme_mode", ThemeMode.entries)
    val themeScheduleStart = int("theme_schedule_start", 0..DAY_LAST_MINUTE)
    val themeScheduleEnd = int("theme_schedule_end", 0..DAY_LAST_MINUTE)
    val accentColor = choice("accent_color", AccentColor.entries)
    val drawerBgColor = text("drawer_bg_color")
    val folderBgColor = text("folder_bg_color")
    val paletteSource = choice("palette_source", PaletteSourceMode.entries)
    val paletteSeed = text("palette_seed")
    val styleConfig = text("style_config")
    val widgetCornerRadius = int("widget_corner_radius", 0..WIDGET_RADIUS_MAX)
    val showScrims = bool("show_scrims")
    val wallpaperZoom = bool("wallpaper_zoom")
    val autoAddApps = bool("auto_add_apps")
    val labelFont = choice("label_font", LabelFont.entries)
    val labelSingleLine = bool("label_single_line")
    val dockInfiniteScroll = bool("dock_infinite_scroll")
    val dockMatchDesktop = bool("dock_match_desktop")
    val dockOverlay = bool("dock_overlay")
    val dockAutoClose = bool("dock_auto_close")
    val dockRememberPosition = bool("dock_remember_position")
    val dockPaddingH = int("dock_padding_h")
    val dockPaddingV = int("dock_padding_v")
    val searchWebProvider = choice("search_web_provider", WebProvider.entries)
    val searchContacts = bool("search_contacts")
    val searchShortcuts = bool("search_shortcuts")
    val searchFolders = bool("search_folders")
    val searchWindowMode = choice("search_window_mode", SearchWindowMode.entries)
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
    val popupStyle = choice("popup_style", PopupMenuStyle.entries)
    val popupItemsHome = text("popup_items_home")
    val popupItemsDrawer = text("popup_items_drawer")
    val dropTargets = bool("drop_targets")
    val undoRemove = bool("undo_remove")
    val disableAnimations = bool("disable_animations")
    val animationSpeed = choice("animation_speed", AnimationSpeed.entries)
    val launchAnimation = choice("launch_animation", LaunchAnimation.entries)
    val hideStatusBar = bool("hide_status_bar")
    val darkStatusIcons = choice("dark_status_icons", StatusBarIcons.entries)
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

        // A set of strings is held by the store as it is. An enum is held
        // as its name, which its own serializer reads back through
        // decodeString.
        @Suppress("UNCHECKED_CAST")
        override fun <T> decodeSerializableValue(deserializer: DeserializationStrategy<T>): T {
            val value = decodeValue()
            return if (value is Set<*>) value as T else deserializer.deserialize(this)
        }
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

        // As read: a set goes in whole, an enum through its serializer,
        // which hands its name to encodeString.
        override fun <T> encodeSerializableValue(serializer: SerializationStrategy<T>, value: T) {
            if (value is Set<*>) encodeValue(value) else serializer.serialize(this, value)
        }
    }
}
