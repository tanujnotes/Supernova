package com.supernova.launcher.ui.settings

import androidx.core.graphics.toColorInt
import android.content.Context
import com.supernova.launcher.R
import com.supernova.launcher.SupernovaApp
import com.supernova.launcher.data.IconGlass
import com.supernova.launcher.data.IconShape
import com.supernova.launcher.data.LauncherPrefs
import com.supernova.launcher.data.LockMethod
import com.supernova.launcher.data.Pref
import com.supernova.launcher.data.SearchWindowMode
import kotlin.math.roundToInt
import com.supernova.launcher.ui.BadgePosition
import com.supernova.launcher.ui.BadgeStyle
import com.supernova.launcher.ui.DockBgStyle
import com.supernova.launcher.ui.FolderPreviewStyle
import androidx.annotation.StringRes
import com.supernova.launcher.gesture.Gesture
import com.supernova.launcher.gesture.GestureAction
import com.supernova.launcher.gesture.GestureDefaults
import com.supernova.launcher.gesture.HomeButtonAction
import com.supernova.launcher.search.WebProvider
import com.supernova.launcher.ui.DrawerSort
import com.supernova.launcher.ui.DrawerTabAlign
import com.supernova.launcher.ui.DrawerTabBar
import com.supernova.launcher.ui.DrawerTabContent
import com.supernova.launcher.ui.DrawerTabStyle
import com.supernova.launcher.ui.FolderMode
import com.supernova.launcher.ui.FolderScroll
import com.supernova.launcher.ui.DesktopSearchBarConfig
import com.supernova.launcher.ui.DrawerBarPlacement
import com.supernova.launcher.ui.DrawerSearchBarConfig
import com.supernova.launcher.ui.SearchBarButtons
import com.supernova.launcher.ui.SearchBarChip
import com.supernova.launcher.ui.SearchBarConfig
import com.supernova.launcher.ui.SearchBarPlacement
import com.supernova.launcher.ui.DrawerStyle
import com.supernova.launcher.ui.IndicatorStyle
import com.supernova.launcher.ui.LabelFont
import com.supernova.launcher.ui.LabelConfig
import com.supernova.launcher.ui.LabelContext
import com.supernova.launcher.ui.LabelOverride
import com.supernova.launcher.ui.LabelStyler
import com.supernova.launcher.ui.LaunchAnimation
import com.supernova.launcher.ui.StatusBarIcons
import com.supernova.launcher.ui.TopRowSort
import com.supernova.launcher.ui.theme.AccentColor
import com.supernova.launcher.ui.theme.PaletteSourceMode
import com.supernova.launcher.ui.theme.SurfaceId
import com.supernova.launcher.ui.theme.ThemeMode
import com.supernova.launcher.ui.LauncherViewModel
import com.supernova.launcher.ui.settings.groups.GroupListBuilder
import com.supernova.launcher.ui.ScrollEffect

// The single registry of every setting, grouped into sections. Adding a
// setting means adding one entry here once its pref field and setter exist.
object SettingsCatalogue {

    // Dialogs and system flows live in SettingsActivity; the catalogue only
    // needs to trigger them. Every hook defaults to a no-op so the settings
    // search index can instantiate the catalogue purely for its titles.
    class Actions(
        // Every plain single-choice setting: the catalogue builds the Choice
        // (its values, labels and setter) and the activity only shows it.
        val pickChoice: (Choice) -> Unit = {},
        val pickIconPack: () -> Unit = {},
        val pickIconShape: () -> Unit = {},
        val resetCustomIcons: () -> Unit = {},
        val resetHiddenLabels: () -> Unit = {},
        val clearIconCache: () -> Unit = {},
        val exportIcons: () -> Unit = {},
        val managePermission: (LabsPermission) -> Unit = {},
        val pickDesktopGrid: () -> Unit = {},
        val editThemeSchedule: () -> Unit = {},
        val showLicense: () -> Unit = {},
        val showWhatsNew: () -> Unit = {},
        val editGesture: (Gesture) -> Unit = {},
        val pickLockMethod: () -> Unit = {},
        val editHiddenApps: () -> Unit = {},
        val editSearchChips: () -> Unit = {},
        val pickDrawerGrid: () -> Unit = {},
        val editFolderIcon: () -> Unit = {},
        val editBadgedApps: () -> Unit = {},
        val postTestBadge: () -> Unit = {},
        val openPaletteDialog: () -> Unit = {},
        val openStylePicker: (SurfaceId) -> Unit = {},
        val showAutoBackups: () -> Unit = {},
        val exportBackup: () -> Unit = {},
        val importBackup: () -> Unit = {},
        // Phase 8 backup extras: ACTION_SEND a fresh export, factory-reset
        // the layout and prefs, re-run the onboarding wizard, and the
        // exit-and-relaunch process restart.
        val shareBackup: () -> Unit = {},
        val resetToDefaults: () -> Unit = {},
        val quickStart: () -> Unit = {},
        val restartLauncher: () -> Unit = {},
        // About > Crash log: share the crash handler's log, or clear it.
        val showCrashLog: () -> Unit = {},
        // Fired only on a genuine user enable, so the activity can route to
        // the notification-access screen when permission is missing.
        val onBadgesEnabled: () -> Unit = {},
        val onPopupNotificationsEnabled: () -> Unit = {},
        // Gestures that need the accessibility service, and the device-admin
        // lock method: why, then the system screen.
        val explainAccessibility: () -> Unit = {},
        val requestDeviceAdmin: () -> Unit = {},
        // Fired on a genuine user enable, so the activity can run the
        // READ_CONTACTS runtime request when it isn't granted yet.
        val onSearchContactsEnabled: () -> Unit = {},
        val editPopupRows: () -> Unit = {},
        // Feed page: contextual permission requests on genuine enables (the
        // contacts-search pattern), the custom-cards manager, and the
        // notification-access deep link for the media card note row.
        val onFeedCalendarEnabled: () -> Unit = {},
        val onFeedContactsEnabled: () -> Unit = {},
        // null = create a new card.
        val editCard: (com.supernova.launcher.feed.FeedCard?) -> Unit = {},
        val openCards: () -> Unit = {},
        val openNotificationAccess: () -> Unit = {},
        // Cards page renderings: a custom card through the feed's own binder,
        // a built-in card with placeholder content.
        val cardPreview: (com.supernova.launcher.feed.FeedCard, android.view.ViewGroup) -> android.view.View =
            { _, parent -> android.view.View(parent.context) },
        val builtInPreview: (BuiltInCard, android.view.ViewGroup) -> android.view.View =
            { _, parent -> android.view.View(parent.context) },
        // Opens a section by title (a built-in card's own settings page,
        // the Desktop search bar page).
        val openSection: (Int) -> Unit = {}
    )

    // Builds the rows whose tap offers one value out of a list. The pick
    // itself is the activity's (Actions.pickChoice), shown as a menu under
    // the row; what the row offers, what it shows as current and what a
    // pick writes all live here with the row.
    private class Rows(
        private val context: Context,
        private val viewModel: LauncherViewModel,
        private val actions: Actions
    ) {
        // Under a switch that needs notification access: shown while the
        // switch is on and the access is missing, and a tap goes to grant it.
        fun needsNotificationAccess(
            @StringRes summary: Int,
            on: (LauncherPrefs) -> Boolean
        ): SettingItem.Row =
            warningRow(context, R.string.feed_media_needs_access, summary, actions.openNotificationAccess) {
                on(it) && !com.supernova.launcher.notification.SupernovaNotificationListener.isEnabled(context)
            }

        // One of `values`, each named by a string resource; the subtitle is
        // the current value's name unless given.
        fun <T> choice(
            @StringRes title: Int,
            values: List<T>,
            current: (LauncherPrefs) -> T,
            label: (T) -> Int,
            subtitle: (LauncherPrefs) -> CharSequence? = { context.getString(label(current(it))) },
            visibleIf: (LauncherPrefs) -> Boolean = { true },
            onPick: (T) -> Unit
        ): SettingItem.Row = choiceText(title, values, current, { context.getString(label(it)) }, subtitle, visibleIf, onPick)

        // As choice, for values that name themselves.
        fun <T> choiceText(
            @StringRes title: Int,
            values: List<T>,
            current: (LauncherPrefs) -> T,
            label: (T) -> CharSequence,
            subtitle: (LauncherPrefs) -> CharSequence? = { label(current(it)) },
            visibleIf: (LauncherPrefs) -> Boolean = { true },
            onPick: (T) -> Unit
        ): SettingItem.Row = SettingItem.Row(
            title,
            subtitle = subtitle,
            onClick = { pick(title, values, values.indexOf(current(viewModel.state.value.prefs)), label, onPick) },
            visibleIf = visibleIf
        )

        // Curated swatches for v1; a free hex picker arrives with theming. An
        // unknown stored color (from a backup) reads as Default.
        // The row shows the colour in force, its dot and its name.
        fun color(
            @StringRes title: Int,
            current: (LauncherPrefs) -> String,
            subtitle: (LauncherPrefs) -> CharSequence? = { context.getString(curatedFor(current(it)).first) },
            visibleIf: (LauncherPrefs) -> Boolean = { true },
            onPick: (String) -> Unit
        ): SettingItem.Row = SettingItem.Row(
            title,
            subtitle = subtitle,
            onClick = {
                val now = current(viewModel.state.value.prefs)
                actions.pickChoice(
                    Choice(
                        title, CURATED_COLORS.map { context.getString(it.first) },
                        CURATED_COLORS.indexOf(curatedFor(now)), { onPick(CURATED_COLORS[it].second) },
                        swatches = CURATED_COLORS.map { swatchOf(it.second) }
                    )
                )
            },
            visibleIf = visibleIf,
            look = SettingItem.RowLook(swatch = { swatchOf(current(it)) })
        )


        // Shows the choice now: for rows built by hand and for controls that
        // are not rows (the Label card's font).
        fun <T> pick(
            @StringRes title: Int,
            values: List<T>,
            selected: Int,
            label: (T) -> CharSequence,
            onPick: (T) -> Unit
        ) {
            actions.pickChoice(Choice(title, values.map(label), selected, { onPick(values[it]) }))
        }

        // A gesture's row: its name, what it does now (a launch shows its
        // target), and the action picker on tap.
        fun gestureRow(gesture: Gesture): SettingItem.Row = SettingItem.Row(
            EnumLabels.of(gesture),
            subtitle = { prefs ->
                val binding = GestureDefaults.decode(prefs.gestureBindings)[gesture]
                    ?: com.supernova.launcher.gesture.GestureBinding(GestureAction.NONE)
                binding.label ?: context.getString(EnumLabels.of(binding.action))
            },
            onClick = { actions.editGesture(gesture) }
        )

        // Every row on a search bar's page edits one field of its slice of
        // the searchBarConfig JSON (SearchBarPrefs), written back whole.
        fun editDesktopBar(change: (DesktopSearchBarConfig) -> DesktopSearchBarConfig) {
            viewModel.set(
                Pref.searchBarConfig,
                SearchBarPrefs.withDesktopBar(viewModel.state.value.prefs, change).searchBarConfig
            )
        }

        fun editDrawerBar(change: (DrawerSearchBarConfig) -> DrawerSearchBarConfig) {
            viewModel.set(
                Pref.searchBarConfig,
                SearchBarPrefs.withDrawerBar(viewModel.state.value.prefs, change).searchBarConfig
            )
        }

        // A button on the bar. Turning one on adds it after the others, so
        // they show in the order they were turned on.
        fun barChip(chip: SearchBarChip) = SettingItem.Toggle(
            SearchBarButtons.label(chip),
            get = { prefs -> SearchBarPrefs.desktopBar(prefs).chips.any { SearchBarChip.fromName(it) == chip } },
            set = { on ->
                editDesktopBar { bar ->
                    val without = bar.chips.filterNot { SearchBarChip.fromName(it) == chip }
                    bar.copy(chips = if (on) without + chip.name else without)
                }
            }
        )

        fun fontPick(current: LabelFont, onPick: (LabelFont) -> Unit) = pick(
            R.string.label_font, LabelFont.entries, LabelFont.entries.indexOf(current),
            { context.getString(EnumLabels.of(it)) }, onPick
        )
    }

    // The accent: its dot on the row, and every option's dot in the menu.
    private fun accentRow(context: Context, viewModel: LauncherViewModel, actions: Actions): SettingItem.Row {
        val accents = AccentColor.entries
        return SettingItem.Row(
            R.string.accent_color,
            subtitle = { context.getString(EnumLabels.of(it.accentColor)) },
            onClick = {
                actions.pickChoice(
                    Choice(
                        R.string.accent_color, accents.map { context.getString(EnumLabels.of(it)) },
                        accents.indexOf(viewModel.state.value.prefs.accentColor),
                        { viewModel.set(Pref.accentColor, accents[it]) },
                        swatches = accents.map { context.getColor(it.colorRes) }
                    )
                )
            },
            look = SettingItem.RowLook(
                R.drawable.ic_style_accent, swatch = { context.getColor(it.accentColor.colorRes) }
            )
        )
    }

    // A stored colour's curated entry; one not in the list reads as Default.
    private const val ANDROID_16 = 36

    // The reasons rows on other pages are greyed (SettingItem.blockedWhen).
    private val animationsOff: (LauncherPrefs) -> Int? = {
        R.string.needs_animations.takeIf { _ -> it.disableAnimations }
    }
    private val feedOff: (LauncherPrefs) -> Int? = { R.string.needs_feed.takeUnless { _ -> it.feedEnabled } }

    // The settings rows hide behind (SettingItem.hiddenWhen), shared by
    // every row that waits on them.
    private val animationsHidden: (LauncherPrefs) -> SettingItem.HiddenBy? = {
        needsOff(R.string.disable_animations, it.disableAnimations)
    }
    private val dockHidden: (LauncherPrefs) -> SettingItem.HiddenBy? = {
        needsOn(R.string.dock_enabled, !it.dockEnabled)
    }
    private val dockBgHidden: (LauncherPrefs) -> SettingItem.HiddenBy? = {
        dockHidden(it) ?: needsOn(R.string.dock_bg_style, it.dockBgStyle == DockBgStyle.NONE)
    }
    private val feedHidden: (LauncherPrefs) -> SettingItem.HiddenBy? = {
        needsOn(R.string.feed_enabled, !it.feedEnabled)
    }
    private val badgesHidden: (LauncherPrefs) -> SettingItem.HiddenBy? = { needsOn(R.string.badges, !it.badges) }
    // Home's Infinite scroll, on its own page, stands in for swipe left and right.
    private val infiniteScrollHidden: (LauncherPrefs) -> SettingItem.HiddenBy? = {
        needsOff(R.string.infinite_scroll, it.infiniteScroll, R.string.settings_section_home)
    }
    private val desktopGlassHidden: (LauncherPrefs) -> SettingItem.HiddenBy? = {
        dependsOn(R.string.icon_glass, IconGlass.fromName(SearchBarPrefs.desktopBar(it).glass) != IconGlass.OFF)
    }
    private val drawerGlassHidden: (LauncherPrefs) -> SettingItem.HiddenBy? = {
        dependsOn(R.string.icon_glass, IconGlass.fromName(SearchBarPrefs.drawerBar(it).glass) != IconGlass.OFF)
    }

    private fun curatedFor(stored: String): Pair<Int, String> =
        CURATED_COLORS.firstOrNull { it.second.equals(stored, ignoreCase = true) } ?: CURATED_COLORS.first()

    // Its dot; none for Default.
    private fun swatchOf(hex: String): Int? =
        hex.takeIf { it.isNotEmpty() }?.let { runCatching { it.toColorInt() }.getOrNull() }

    val CURATED_COLORS = listOf(
        R.string.color_default to "",
        R.string.color_white to "#FFFFFFFF",
        R.string.color_black to "#FF000000",
        R.string.color_red to "#FFE53935",
        R.string.color_orange to "#FFFB8C00",
        R.string.color_yellow to "#FFFDD835",
        R.string.color_green to "#FF43A047",
        R.string.color_cyan to "#FF00ACC1",
        R.string.color_blue to "#FF1E88E5",
        R.string.color_purple to "#FF8E24AA"
    )

    fun sections(
        context: Context,
        viewModel: LauncherViewModel,
        actions: Actions
    ): List<SettingsSection> {
        val rows = Rows(context, viewModel, actions)
        return listOf(
        SettingsSection(
            R.string.settings_section_home,
            listOfNotNull(
                SettingItem.Preview(PreviewKind.HOME),
                SettingItem.Header(R.string.group_layout),
                // The Home screen's Layout group opens with the desktop's
                // icon size and its Label card. The dock, drawer and folders
                // follow both unless they are given their own.
                SettingItem.Slider(
                    R.string.icon_size,
                    min = 70, max = 200,
                    step = 5,
                    get = { (it.iconScale * 100f).roundToInt() },
                    set = { viewModel.set(Pref.iconScale, it / 100f) },
                    display = { context.getString(R.string.opacity_percent, it) },
                    preview = { p, v -> p.copy(iconScale = v / 100f) }
                ),
                SettingItem.LabelGroup(
                    R.string.label_group,
                    shown = { it.showLabels },
                    setShown = { viewModel.set(Pref.showLabels, it) },
                    sizeMin = 9, sizeMax = 18,
                    size = { it.labelSize },
                    setSize = { viewModel.set(Pref.labelSize, it) },
                    sizeDisplay = { context.getString(R.string.label_size_value, it) },
                    font = { context.getString(EnumLabels.of(it.labelFont)) },
                    pickFont = {
                        rows.fontPick(viewModel.state.value.prefs.labelFont) {
                            viewModel.set(Pref.labelFont, it)
                        }
                    },
                    shadow = { it.labelShadow },
                    setShadow = { viewModel.set(Pref.labelShadow, it) },
                    singleLine = { it.labelSingleLine },
                    setSingleLine = { viewModel.set(Pref.labelSingleLine, it) },
                    previewSize = { p, v -> p.copy(labelSize = v) },
                    searchTitles = listOf(
                        R.string.show_labels, R.string.label_size, R.string.label_font,
                        R.string.label_shadow, R.string.label_single_line
                    )
                ),
                SettingItem.Row(
                    R.string.desktop_grid,
                    subtitle = { context.getString(R.string.grid_value, it.gridRows, it.gridCols) },
                    onClick = actions.pickDesktopGrid
                ),
                rows.choice(
                    R.string.wallpaper_scroll, com.supernova.launcher.ui.WallpaperScrollMode.entries,
                    { com.supernova.launcher.ui.WallpaperScrollMode.fromPrefs(it) }, { EnumLabels.of(it) }
                ) { viewModel.set(Pref.wallpaperScrollMode, it.name) },
                // Left out, not hidden, where the phone blocks the zoom call:
                // that never changes, so search shouldn't find it either.
                SettingItem.Toggle(
                    R.string.wallpaper_zoom,
                    get = { it.wallpaperZoom },
                    set = { viewModel.set(Pref.wallpaperZoom, it) }
                ).takeIf { com.supernova.launcher.ui.WallpaperZoom.supported },
                SettingItem.Toggle(
                    R.string.show_scrims,
                    get = { it.showScrims },
                    set = { viewModel.set(Pref.showScrims, it) },
                    subtitle = R.string.show_scrims_summary
                ),
                SettingItem.Toggle(
                    R.string.auto_add_apps,
                    get = { it.autoAddApps },
                    set = { viewModel.set(Pref.autoAddApps, it) }
                ),
                SettingItem.Header(R.string.group_animations),
                // The switch first, with what it turns off right under it:
                // none of the three does anything while animations are off.
                SettingItem.Toggle(
                    R.string.disable_animations,
                    get = { it.disableAnimations },
                    set = { viewModel.set(Pref.disableAnimations, it) }
                ),
                rows.choice(
                    R.string.animation_speed, com.supernova.launcher.ui.AnimationSpeed.entries,
                    { it.animationSpeed }, { EnumLabels.of(it) }
                ) { viewModel.set(Pref.animationSpeed, it) }.hiddenWhen(animationsHidden),
                rows.choice(
                    R.string.scroll_effect, ScrollEffect.entries,
                    { it.scrollEffect }, { EnumLabels.of(it) }
                ) { viewModel.setScrollEffect(it) }.hiddenWhen(animationsHidden),
                rows.choice(
                    R.string.launch_animation, LaunchAnimation.entries,
                    { it.launchAnimation }, { EnumLabels.of(it) },
                    onPick = { viewModel.set(Pref.launchAnimation, it) }
                ).hiddenWhen(animationsHidden),
                SettingItem.Toggle(
                    R.string.drop_targets,
                    get = { it.dropTargets },
                    set = { viewModel.set(Pref.dropTargets, it) },
                    subtitle = R.string.drop_targets_summary
                ),
                SettingItem.Toggle(
                    R.string.undo_remove,
                    get = { it.undoRemove },
                    set = { viewModel.set(Pref.undoRemove, it) }
                ),
                SettingItem.Header(R.string.group_system),
                // Notification bar / system chrome group (Part E).
                SettingItem.Toggle(
                    R.string.hide_status_bar,
                    get = { it.hideStatusBar },
                    set = { viewModel.set(Pref.hideStatusBar, it) }
                ),
                rows.choice(
                    R.string.status_bar_icons, StatusBarIcons.entries,
                    { it.darkStatusIcons }, { EnumLabels.of(it) }
                ) { viewModel.set(Pref.darkStatusIcons, it) }.hiddenWhen {
                    needsOff(R.string.hide_status_bar, it.hideStatusBar)
                },
                SettingItem.Toggle(
                    R.string.transparent_status,
                    get = { it.transparentStatus },
                    set = { viewModel.set(Pref.transparentStatus, it) }
                ),
                SettingItem.Toggle(
                    R.string.transparent_nav,
                    get = { it.transparentNav },
                    set = { viewModel.set(Pref.transparentNav, it) }
                ),
                rows.choice(
                    R.string.home_orientation, com.supernova.launcher.ui.HomeOrientation.entries,
                    { com.supernova.launcher.ui.HomeOrientation.fromPrefs(it) },
                    // Default is portrait on a phone and rotates on a tablet.
                    { EnumLabels.of(it, com.supernova.launcher.data.DeviceProfile.isWide(context)) }
                ) { viewModel.set(Pref.homeOrientation, it.name) }.blockedWhen {
                    // Android 16 turns a large screen with the system's
                    // rotation and ignores what an app asks for.
                    R.string.orientation_system_owned.takeIf { _ ->
                        android.os.Build.VERSION.SDK_INT >= ANDROID_16 &&
                            com.supernova.launcher.data.DeviceProfile.isWide(context)
                    }
                }.searchableBy(R.string.home_orientation_keywords),
                SettingItem.Header(R.string.group_pages),
                rows.choice(
                    R.string.page_indicator, IndicatorStyle.entries,
                    { it.pageIndicatorStyle }, { EnumLabels.of(it) }
                ) { viewModel.set(Pref.pageIndicatorStyle, it) },
                // Default follows the labels' color.
                rows.color(
                    R.string.page_indicator_color, { it.pageIndicatorColor }
                ) { viewModel.set(Pref.pageIndicatorColor, it) }.hiddenWhen {
                    dependsOn(R.string.page_indicator, it.pageIndicatorStyle == IndicatorStyle.NONE)
                },
                SettingItem.Toggle(
                    R.string.page_indicator_auto_hide,
                    get = { it.pageIndicatorAutoHide },
                    set = { viewModel.set(Pref.pageIndicatorAutoHide, it) },
                    subtitle = R.string.page_indicator_auto_hide_summary
                ).hiddenWhen { dependsOn(R.string.page_indicator, it.pageIndicatorStyle == IndicatorStyle.NONE) },
                SettingItem.Toggle(
                    R.string.infinite_scroll,
                    get = { it.infiniteScroll },
                    set = { viewModel.set(Pref.infiniteScroll, it) },
                    subtitle = R.string.infinite_scroll_summary
                ),
                SettingItem.Toggle(
                    R.string.remove_empty_pages,
                    get = { it.removeEmptyPages },
                    set = { viewModel.set(Pref.removeEmptyPages, it) },
                    subtitle = R.string.remove_empty_pages_summary
                ),
                SettingItem.Slider(
                    R.string.desktop_padding_h,
                    min = 0, max = 48,
                    get = { it.desktopPaddingH },
                    set = { viewModel.setDesktopPadding(it, viewModel.state.value.prefs.desktopPaddingV) },
                    display = { context.getString(R.string.padding_dp_value, it) }
                ),
                SettingItem.Slider(
                    R.string.desktop_padding_v,
                    min = 0, max = 48,
                    get = { it.desktopPaddingV },
                    set = { viewModel.setDesktopPadding(viewModel.state.value.prefs.desktopPaddingH, it) },
                    display = { context.getString(R.string.padding_dp_value, it) }
                ),
                SettingItem.Accordion(R.string.advanced),
                SettingItem.Toggle(
                    R.string.desktop_locked,
                    get = { it.desktopLocked },
                    set = { viewModel.set(Pref.desktopLocked, it) }
                ),
                SettingItem.Toggle(
                    R.string.widget_overlap,
                    get = { it.widgetOverlap },
                    set = { viewModel.set(Pref.widgetOverlap, it) }
                ),
                SettingItem.Toggle(
                    R.string.overlap_when_placing,
                    get = { it.overlapWhenPlacing },
                    set = { viewModel.set(Pref.overlapWhenPlacing, it) },
                    subtitle = R.string.overlap_when_placing_summary
                ),
                SettingItem.Slider(
                    R.string.widget_padding,
                    min = 0, max = 24,
                    get = { it.widgetPadding },
                    set = { viewModel.set(Pref.widgetPadding, it) },
                    display = { context.getString(R.string.padding_dp_value, it) }
                ),
                SettingItem.Slider(
                    R.string.widget_corner_radius,
                    min = 0, max = 32,
                    step = 2,
                    get = { it.widgetCornerRadius },
                    set = { viewModel.set(Pref.widgetCornerRadius, it) },
                    display = { context.getString(R.string.padding_dp_value, it) }
                )
            )
        ),
        SettingsSection(
            R.string.settings_section_dock,
            listOf(
                SettingItem.Preview(PreviewKind.DOCK, visibleIf = { it.dockEnabled }),
                SettingItem.Toggle(
                    R.string.dock_enabled,
                    get = { it.dockEnabled },
                    set = { viewModel.set(Pref.dockEnabled, it) }
                ),
                SettingItem.Slider(
                    R.string.dock_count,
                    min = 3, max = com.supernova.launcher.data.GridLimits.DOCK,
                    bigGridMax = com.supernova.launcher.data.GridLimits.DOCK_BIG,
                    get = { it.dockCount },
                    set = { viewModel.set(Pref.dockCount, it) },
                    display = { it.toString() },
                    preview = { p, v -> p.copy(dockCount = v) }
                ).hiddenWhen(dockHidden),
                SettingItem.Slider(
                    R.string.dock_pages,
                    min = 1, max = 3,
                    get = { it.dockPages },
                    set = { viewModel.set(Pref.dockPages, it) },
                    display = { it.toString() }
                ).hiddenWhen(dockHidden),
                SettingItem.Toggle(
                    R.string.dock_bg_style,
                    get = { it.dockBgStyle != DockBgStyle.NONE },
                    set = { viewModel.set(Pref.dockBgStyle, if (it) DockBgStyle.ROUNDED else DockBgStyle.NONE) }
                ).hiddenWhen(dockHidden),
                SettingItem.Slider(
                    R.string.dock_bg_opacity,
                    min = 0, max = 100,
                    step = 5,
                    get = { SurfaceOpacity.effective(it, SurfaceId.DOCK) },
                    set = { SurfaceOpacity.set(viewModel, SurfaceId.DOCK, it) },
                    display = { context.getString(R.string.opacity_percent, it) },
                    preview = { p, v -> SurfaceOpacity.preview(p, SurfaceId.DOCK, v) }
                ).hiddenWhen(dockBgHidden),
                SettingItem.Slider(
                    R.string.dock_bg_radius,
                    min = 0, max = 24,
                    get = { if (it.dockBgStyle == DockBgStyle.SQUARE) 0 else it.dockBgRadius },
                    // A square dock from before the switch becomes a rounded
                    // one at the radius picked.
                    set = {
                        viewModel.setAll(
                            listOf(
                                com.supernova.launcher.data.PrefWrite(Pref.dockBgRadius, it),
                                com.supernova.launcher.data.PrefWrite(Pref.dockBgStyle, DockBgStyle.ROUNDED)
                            )
                        )
                    },
                    display = { context.getString(R.string.padding_dp_value, it) },
                    preview = { p, v -> p.copy(dockBgRadius = v, dockBgStyle = DockBgStyle.ROUNDED) }
                ).hiddenWhen(dockBgHidden),
                SettingItem.Toggle(
                    R.string.dock_bg_behind_nav,
                    get = { it.dockBgBehindNav },
                    set = { viewModel.set(Pref.dockBgBehindNav, it) }
                ).hiddenWhen(dockBgHidden),
                SettingItem.Toggle(
                    R.string.dock_bg_padding,
                    get = { it.dockBgPadding },
                    set = { viewModel.set(Pref.dockBgPadding, it) },
                    // Behind-nav already forces the slab full-bleed, so the
                    // inset choice only means something without it.
                ).hiddenWhen { dockBgHidden(it) ?: needsOff(R.string.dock_bg_behind_nav, it.dockBgBehindNav) },
                SettingItem.Toggle(
                    R.string.dock_labels,
                    get = { it.dockLabels },
                    set = { viewModel.set(Pref.dockLabels, it) },
                    // Not tied to the desktop's labels: the dock shows its
                    // own whatever those are, and hiding the switch with
                    // them left no way to turn the dock's off.
                ).hiddenWhen(dockHidden),
                *labelOverrideItems(context, viewModel, actions, LabelContext.DOCK) {
                    dockHidden(it) ?: needsOn(R.string.dock_labels, !it.dockLabels)
                }.toTypedArray(),
                SettingItem.Toggle(
                    R.string.dock_match_desktop,
                    get = { it.dockMatchDesktop },
                    set = { viewModel.set(Pref.dockMatchDesktop, it) }
                ).hiddenWhen(dockHidden),
                SettingItem.Slider(
                    R.string.dock_icon_scale,
                    min = 70, max = 200,
                    step = 5,
                    get = { (it.dockIconScale * 100f).roundToInt() },
                    set = { viewModel.set(Pref.dockIconScale, it / 100f) },
                    display = { context.getString(R.string.opacity_percent, it) },
                    preview = { p, v -> p.copy(dockIconScale = v / 100f) }
                ).hiddenWhen { dockHidden(it) ?: needsOff(R.string.dock_match_desktop, it.dockMatchDesktop) },
                SettingItem.Slider(
                    R.string.dock_padding_h,
                    min = 0, max = 48,
                    step = 2,
                    get = { it.dockPaddingH },
                    set = { viewModel.set(Pref.dockPaddingH, it) },
                    display = { context.getString(R.string.padding_dp_value, it) }
                ).hiddenWhen(dockHidden),
                SettingItem.Slider(
                    R.string.dock_padding_v,
                    min = 0, max = 48,
                    step = 2,
                    get = { it.dockPaddingV },
                    set = { viewModel.set(Pref.dockPaddingV, it) },
                    display = { context.getString(R.string.padding_dp_value, it) }
                ).hiddenWhen(dockHidden),
                SettingItem.Accordion(R.string.advanced),
                SettingItem.Toggle(
                    R.string.dock_infinite_scroll,
                    get = { it.dockInfiniteScroll },
                    set = { viewModel.set(Pref.dockInfiniteScroll, it) }
                ).hiddenWhen { dockHidden(it) ?: dependsOn(R.string.dock_pages, it.dockPages <= 1) },
                SettingItem.Toggle(
                    R.string.dock_remember_position,
                    get = { it.dockRememberPosition },
                    set = { viewModel.set(Pref.dockRememberPosition, it) }
                ).hiddenWhen { dockHidden(it) ?: dependsOn(R.string.dock_pages, it.dockPages <= 1) },
                SettingItem.Toggle(
                    R.string.dock_overlay,
                    get = { it.dockOverlay },
                    set = { viewModel.set(Pref.dockOverlay, it) },
                    subtitle = R.string.dock_overlay_summary
                ).hiddenWhen(dockHidden),
                SettingItem.Toggle(
                    R.string.dock_auto_close,
                    get = { it.dockAutoClose },
                    set = { viewModel.set(Pref.dockAutoClose, it) }
                ).hiddenWhen { dockHidden(it) ?: needsOn(R.string.dock_overlay, !it.dockOverlay) }
            )
        ),
        SettingsSection(
            R.string.settings_section_drawer,
            listOf(
                SettingItem.Preview(PreviewKind.DRAWER),
                rows.choice(
                    R.string.drawer_style, DrawerStyle.entries,
                    { it.drawerStyle }, { EnumLabels.of(it) }
                ) { viewModel.set(Pref.drawerStyle, it) },
                rows.choice(
                    R.string.drawer_sort, DrawerSort.entries,
                    { it.drawerSort }, { EnumLabels.of(it) }
                ) { viewModel.set(Pref.drawerSort, it) },
                SettingItem.Toggle(
                    R.string.drawer_sort_buttons,
                    get = { it.drawerSortButtons },
                    set = { viewModel.set(Pref.drawerSortButtons, it) },
                    subtitle = R.string.drawer_sort_buttons_summary
                ),
                rows.choice(
                    R.string.drawer_transition, com.supernova.launcher.ui.DrawerTransition.entries,
                    { it.drawerTransition }, { EnumLabels.of(it) }
                ) { viewModel.set(Pref.drawerTransition, it) }.blockedWhen(animationsOff),
                SettingItem.Row(
                    R.string.drawer_grid,
                    subtitle = { prefs ->
                        val auto = context.getString(R.string.drawer_columns_auto)
                        context.getString(
                            R.string.drawer_grid_value,
                            prefs.drawerRows.takeIf { it > 0 }?.toString() ?: auto,
                            prefs.drawerColumns.takeIf { it > 0 }?.toString() ?: auto
                        )
                    },
                    onClick = actions.pickDrawerGrid
                ).hiddenWhen { dependsOn(R.string.drawer_style, it.drawerStyle == DrawerStyle.VERTICAL_LIST) },
                SettingItem.Toggle(
                    R.string.drawer_auto_keyboard,
                    get = { it.drawerAutoKeyboard },
                    set = { viewModel.set(Pref.drawerAutoKeyboard, it) }
                ).blockedWhen {
                    R.string.needs_drawer_search_bar.takeUnless { _ ->
                        SearchBarConfig.decode(it.searchBarConfig).drawer.enabled
                    }
                },
                // Open App Drawer options, live only while a swipe opens the
                // drawer (and, for the arrow, a dock to draw it on).
                SettingItem.Toggle(
                    R.string.drawer_swipe_indicator,
                    get = { it.drawerSwipeIndicator },
                    set = { viewModel.set(Pref.drawerSwipeIndicator, it) },
                    subtitle = R.string.drawer_swipe_indicator_summary
                ).blockedWhen {
                    when {
                        !GestureDefaults.swipeUpOpensDrawer(it.gestureBindings) -> R.string.needs_swipe_up_drawer
                        !it.dockEnabled -> R.string.needs_dock
                        else -> null
                    }
                },
                SettingItem.Toggle(
                    R.string.drawer_swipe_haptic,
                    get = { it.drawerSwipeHaptic },
                    set = { viewModel.set(Pref.drawerSwipeHaptic, it) },
                    subtitle = R.string.drawer_swipe_haptic_summary
                ).blockedWhen {
                    R.string.needs_swipe_up_drawer.takeUnless { _ ->
                        GestureDefaults.swipeUpOpensDrawer(it.gestureBindings)
                    }
                },
                SettingItem.Toggle(
                    R.string.drawer_frequent_row,
                    get = { it.drawerFrequentRow },
                    set = { viewModel.set(Pref.drawerFrequentRow, it) },
                    subtitle = R.string.drawer_frequent_row_summary
                ),
                rows.choice(
                    R.string.drawer_top_row_sort, TopRowSort.entries,
                    { it.drawerTopRowSort }, { EnumLabels.of(it) }
                ) { viewModel.set(Pref.drawerTopRowSort, it) }.hiddenWhen {
                    needsOn(R.string.drawer_frequent_row, !it.drawerFrequentRow)
                },
                // Horizontal Scroll group: paged style only.
                SettingItem.Toggle(
                    R.string.drawer_infinite_scroll,
                    get = { it.drawerInfiniteScroll },
                    set = { viewModel.set(Pref.drawerInfiniteScroll, it) }
                ).hiddenWhen { dependsOn(R.string.drawer_style, it.drawerStyle != DrawerStyle.PAGED_GRID) },
                rows.choice(
                    R.string.drawer_page_effect, ScrollEffect.entries,
                    { it.drawerPageEffect }, { EnumLabels.of(it) }
                ) { viewModel.set(Pref.drawerPageEffect, it) }.blockedWhen(animationsOff).hiddenWhen {
                    dependsOn(R.string.drawer_style, it.drawerStyle != DrawerStyle.PAGED_GRID)
                },
                SettingItem.Accordion(R.string.advanced),
                SettingItem.Toggle(
                    R.string.drawer_remember_position,
                    get = { it.drawerRememberPosition },
                    set = { viewModel.set(Pref.drawerRememberPosition, it) }
                ),
                SettingItem.Toggle(
                    R.string.drawer_auto_close,
                    get = { it.drawerAutoClose },
                    set = { viewModel.set(Pref.drawerAutoClose, it) }
                ),
                SettingItem.Header(R.string.group_layout),
                SettingItem.Toggle(
                    R.string.dock_match_desktop,
                    get = { it.drawerMatchDesktop },
                    set = { viewModel.set(Pref.drawerMatchDesktop, it) }
                ),
                SettingItem.Slider(
                    R.string.drawer_icon_scale,
                    min = 70, max = 200,
                    step = 5,
                    get = { (it.drawerIconScale * 100f).roundToInt() },
                    set = { viewModel.set(Pref.drawerIconScale, it / 100f) },
                    display = { context.getString(R.string.opacity_percent, it) },
                    preview = { p, v -> p.copy(drawerIconScale = v / 100f) }
                ).hiddenWhen { needsOff(R.string.dock_match_desktop, it.drawerMatchDesktop) },
                SettingItem.Slider(
                    R.string.drawer_padding_h,
                    min = 0, max = 48,
                    step = 2,
                    get = { it.drawerPaddingH },
                    set = { viewModel.set(Pref.drawerPaddingH, it) },
                    display = { context.getString(R.string.padding_dp_value, it) }
                ),
                SettingItem.Slider(
                    R.string.drawer_padding_v,
                    min = 0, max = 48,
                    step = 2,
                    get = { it.drawerPaddingV },
                    set = { viewModel.set(Pref.drawerPaddingV, it) },
                    display = { context.getString(R.string.padding_dp_value, it) }
                ),
                SettingItem.Slider(
                    R.string.drawer_bg_opacity,
                    min = 20, max = 100,
                    step = 5,
                    get = { SurfaceOpacity.effective(it, SurfaceId.DRAWER) },
                    set = { SurfaceOpacity.set(viewModel, SurfaceId.DRAWER, it) },
                    display = { context.getString(R.string.opacity_percent, it) },
                    preview = { p, v -> SurfaceOpacity.preview(p, SurfaceId.DRAWER, v) }
                ),
                SettingItem.Toggle(
                    R.string.drawer_labels,
                    get = { it.drawerLabels },
                    set = { viewModel.set(Pref.drawerLabels, it) }
                ).hiddenWhen { dependsOn(R.string.drawer_style, it.drawerStyle == DrawerStyle.VERTICAL_LIST) },
                *labelOverrideItems(context, viewModel, actions, LabelContext.DRAWER) {
                    needsOn(R.string.drawer_labels, !it.drawerLabels)
                }.toTypedArray(),
                SettingItem.Toggle(
                    R.string.drawer_fast_scroller,
                    get = { it.drawerFastScroller },
                    set = { viewModel.set(Pref.drawerFastScroller, it) }
                ).hiddenWhen { dependsOn(R.string.drawer_style, it.drawerStyle == DrawerStyle.PAGED_GRID) },
                SettingItem.Toggle(
                    R.string.drawer_pull_to_search,
                    get = { it.drawerPullToSearch },
                    set = { viewModel.set(Pref.drawerPullToSearch, it) }
                ),
                rows.color(
                    R.string.drawer_scrubber_color, { it.drawerScrubberColor },
                    // As the scroller's own switch, none in the paged style.
                ) { viewModel.set(Pref.drawerScrubberColor, it) }.hiddenWhen {
                    dependsOn(R.string.drawer_style, it.drawerStyle == DrawerStyle.PAGED_GRID)
                        ?: needsOn(R.string.drawer_fast_scroller, !it.drawerFastScroller)
                },
                SettingItem.Header(R.string.group_tabs),
                rows.choice(
                    R.string.drawer_tab_bar, DrawerTabBar.entries,
                    { it.drawerTabBar }, { EnumLabels.of(it) },
                    // Always listed, as the bar's place can be set before any
                    // tab exists; until one does, the row says where tabs
                    // come from.
                    subtitle = { prefs ->
                        val bar = prefs.drawerTabBar
                        when {
                            viewModel.drawerTabGroups().isEmpty() && !viewModel.hasWorkApps() -> context.getString(
                                R.string.drawer_tab_bar_no_tabs, context.getString(EnumLabels.of(bar))
                            )
                            bar == DrawerTabBar.NONE -> context.getString(R.string.drawer_tab_bar_hidden_note)
                            else -> context.getString(EnumLabels.of(bar))
                        }
                    }
                ) { viewModel.set(Pref.drawerTabBar, it) },
                rows.choice(
                    R.string.drawer_tab_style, DrawerTabStyle.entries,
                    { it.drawerTabStyle }, { EnumLabels.of(it) },
                    visibleIf = { viewModel.drawerTabGroups().isNotEmpty() || viewModel.hasWorkApps() }
                ) { viewModel.set(Pref.drawerTabStyle, it) }.hiddenWhen {
                    dependsOn(R.string.drawer_tab_bar, it.drawerTabBar == DrawerTabBar.NONE)
                },
                rows.choice(
                    R.string.drawer_tab_content, DrawerTabContent.entries,
                    { it.drawerTabContent }, { EnumLabels.of(it) },
                    visibleIf = { viewModel.drawerTabGroups().isNotEmpty() || viewModel.hasWorkApps() }
                ) { viewModel.set(Pref.drawerTabContent, it) }.hiddenWhen {
                    dependsOn(R.string.drawer_tab_bar, it.drawerTabBar == DrawerTabBar.NONE)
                },
                rows.choice(
                    R.string.drawer_tab_align, DrawerTabAlign.entries,
                    { it.drawerTabAlign }, { EnumLabels.of(it) },
                    visibleIf = { viewModel.drawerTabGroups().isNotEmpty() || viewModel.hasWorkApps() }
                ) { viewModel.set(Pref.drawerTabAlign, it) }.hiddenWhen {
                    dependsOn(R.string.drawer_tab_bar, it.drawerTabBar == DrawerTabBar.NONE)
                },
                SettingItem.Toggle(
                    R.string.drawer_work_tab,
                    get = { it.drawerWorkTab },
                    set = { viewModel.set(Pref.drawerWorkTab, it) },
                    visibleIf = { viewModel.hasWorkApps() || it.drawerWorkTab }
                ),
                SettingItem.Row(
                    R.string.drawer_default_tab,
                    subtitle = { prefs ->
                        prefs.drawerDefaultTab.ifEmpty { context.getString(R.string.drawer_all) }
                    },
                    onClick = {
                        // FOLDER-type groups have no tab to default to.
                        val names = listOf("") + viewModel.drawerTabGroups().map { it.name }
                        rows.pick(
                            R.string.drawer_default_tab, names,
                            names.indexOf(viewModel.state.value.prefs.drawerDefaultTab).coerceAtLeast(0),
                            { it.ifEmpty { context.getString(R.string.drawer_all) } }
                        ) { viewModel.set(Pref.drawerDefaultTab, it) }
                    },
                    visibleIf = { viewModel.drawerTabGroups().isNotEmpty() }
                ),
                SettingItem.Toggle(
                    R.string.drawer_hide_all_tab,
                    get = { it.drawerHideAllTab },
                    set = { viewModel.set(Pref.drawerHideAllTab, it) },
                    visibleIf = { viewModel.drawerTabGroups().isNotEmpty() }
                ),
                SettingItem.Row(R.string.hidden_apps, onClick = actions.editHiddenApps),
                // A page of its own (ui/settings/groups); the row counts
                // what it holds.
                SettingItem.Row(
                    R.string.drawer_groups,
                    subtitle = { GroupListBuilder(context, viewModel).summary() },
                    onClick = { actions.openSection(R.string.drawer_groups) }
                ),
                // Isolate tabs. Whether a group's apps also stay in
                // All is the group's own choice now (Keep apps in main app
                // tab); the old switch of that name only still decides it
                // for tabs made before, which never chose.
                SettingItem.Toggle(
                    R.string.drawer_isolate_tabs,
                    get = { it.drawerTabsNoSwipe },
                    set = { viewModel.set(Pref.drawerTabsNoSwipe, it) },
                    visibleIf = { viewModel.drawerTabGroups().isNotEmpty() || viewModel.hasWorkApps() },
                    subtitle = R.string.drawer_isolate_tabs_summary
                ),
                SettingItem.Toggle(
                    R.string.smart_folders,
                    get = { it.smartFolders },
                    set = { viewModel.setSmartFolders(it) },
                    subtitle = R.string.smart_folders_summary
                ),
                SettingItem.Toggle(
                    R.string.drawer_folders_first,
                    get = { it.drawerFoldersFirst },
                    set = { viewModel.set(Pref.drawerFoldersFirst, it) }
                ).hiddenWhen {
                    needsOn(R.string.smart_folders, !it.smartFolders && viewModel.drawerFolderGroups().isEmpty())
                }
            )
        ),
        SettingsSection(
            R.string.settings_section_search,
            listOf(
                SettingItem.Preview(PreviewKind.SEARCH),
                SettingItem.Header(R.string.group_providers),
                rows.choiceText(
                    R.string.search_web_provider, WebProvider.entries,
                    { it.searchWebProvider }, { it.displayName }
                ) { viewModel.set(Pref.searchWebProvider, it) },
                SettingItem.Toggle(
                    R.string.search_contacts,
                    get = { it.searchContacts },
                    set = { enabled ->
                        viewModel.set(Pref.searchContacts, enabled)
                        if (enabled) actions.onSearchContactsEnabled()
                    }
                ),
                SettingItem.Toggle(
                    R.string.search_shortcuts,
                    get = { it.searchShortcuts },
                    set = { viewModel.set(Pref.searchShortcuts, it) }
                ),
                SettingItem.Toggle(
                    R.string.search_folders,
                    get = { it.searchFolders },
                    set = { viewModel.set(Pref.searchFolders, it) }
                ),
                rows.choice(
                    R.string.search_window_mode, SearchWindowMode.entries,
                    { it.searchWindowMode },
                    {
                        if (it == SearchWindowMode.WINDOW) {
                            R.string.search_window_window
                        } else {
                            R.string.search_window_immersive
                        }
                    }
                ) { viewModel.set(Pref.searchWindowMode, it) },
                SettingItem.Toggle(
                    R.string.search_apps_one_row,
                    get = { it.searchAppsOneRow },
                    set = { viewModel.set(Pref.searchAppsOneRow, it) },
                    subtitle = R.string.search_apps_one_row_summary
                ),
                SettingItem.Toggle(
                    R.string.search_contact_cards,
                    get = { it.searchContactCards },
                    set = { viewModel.set(Pref.searchContactCards, it) }
                ).hiddenWhen { needsOn(R.string.search_contacts, !it.searchContacts) },
                SettingItem.Toggle(
                    R.string.search_cards,
                    get = { it.searchCards },
                    set = { viewModel.set(Pref.searchCards, it) }
                ),
                SettingItem.Toggle(
                    R.string.search_calculator,
                    get = { it.searchCalculator },
                    set = { viewModel.set(Pref.searchCalculator, it) }
                ),
                SettingItem.Toggle(
                    R.string.search_hidden_apps,
                    get = { it.searchHiddenApps },
                    set = { viewModel.set(Pref.searchHiddenApps, it) },
                    subtitle = R.string.search_hidden_apps_summary
                ),
                SettingItem.Row(
                    R.string.search_chips,
                    subtitle = {
                        val chips = com.supernova.launcher.search.SearchChip.decode(it.searchChips)
                        if (chips.isEmpty()) {
                            context.getString(R.string.option_none)
                        } else {
                            chips.joinToString(", ") { chip -> context.getString(EnumLabels.of(chip)) }
                        }
                    },
                    onClick = actions.editSearchChips
                ),
                SettingItem.Toggle(
                    R.string.search_private_keyboard,
                    get = { it.searchPrivateKeyboard },
                    set = { viewModel.set(Pref.searchPrivateKeyboard, it) },
                    subtitle = R.string.search_private_keyboard_summary
                ),
                SettingItem.Header(R.string.group_bars),
                SettingItem.Row(
                    R.string.desktop_search_bar,
                    subtitle = {
                        context.getString(
                            EnumLabels.of(
                                SearchBarPlacement.fromName(
                                    SearchBarConfig.decode(it.searchBarConfig).desktop.placement
                                )
                            )
                        )
                    },
                    onClick = { actions.openSection(R.string.desktop_search_bar) }
                ),
                SettingItem.Row(
                    R.string.drawer_search_bar,
                    subtitle = {
                        val config = SearchBarConfig.decode(it.searchBarConfig).drawer
                        context.getString(
                            when (com.supernova.launcher.ui.DrawerBarPlacement.fromConfig(config)) {
                                com.supernova.launcher.ui.DrawerBarPlacement.TOP -> R.string.tab_bar_top
                                com.supernova.launcher.ui.DrawerBarPlacement.BOTTOM -> R.string.tab_bar_bottom
                                com.supernova.launcher.ui.DrawerBarPlacement.HIDDEN -> R.string.placement_hidden
                            }
                        )
                    },
                    onClick = { actions.openSection(R.string.drawer_search_bar) }
                )
            )
        ),
        // The Drawer groups page, opened from the App drawer page's row;
        // back returns there. It has no rows of its own: SettingsActivity
        // shows DrawerGroupsPane in the list's place.
        SettingsSection(R.string.drawer_groups, emptyList()),
        // The Desktop search bar's own page, opened from the Search page's
        // Search bars group (and the bar's long-press menu); back returns there.
        SettingsSection(
            R.string.desktop_search_bar,
            listOf(
                SettingItem.Preview(PreviewKind.SEARCH_BAR),
                rows.choice(
                    R.string.search_bar_placement,
                    listOf(
                        SearchBarPlacement.NONE, SearchBarPlacement.DOCK_ABOVE,
                        SearchBarPlacement.DOCK_BELOW, SearchBarPlacement.PERSISTENT
                    ),
                    { SearchBarPlacement.fromName(SearchBarPrefs.desktopBar(it).placement) }, { EnumLabels.of(it) }
                ) { placement -> rows.editDesktopBar { it.copy(placement = placement.name) } },
                SettingItem.Header(R.string.group_appearance),
                SettingItem.Slider(
                    R.string.style_corner_radius, min = 0, max = DesktopSearchBarConfig.MAX_RADIUS, step = 2,
                    get = { SearchBarPrefs.desktopBar(it).radius.coerceIn(0, DesktopSearchBarConfig.MAX_RADIUS) },
                    set = { v -> rows.editDesktopBar { it.copy(radius = v) } },
                    display = { context.getString(R.string.padding_dp_value, it) },
                    preview = { p, v -> SearchBarPrefs.withDesktopBar(p) { it.copy(radius = v) } }
                ),
                SettingItem.Slider(
                    R.string.style_opacity, min = 0, max = 100, step = 5,
                    get = { (SearchBarPrefs.desktopBar(it).alpha.coerceIn(0f, 1f) * 100f).roundToInt() },
                    set = { v -> rows.editDesktopBar { it.copy(alpha = v / 100f) } },
                    display = { context.getString(R.string.opacity_percent, it) },
                    preview = { p, v -> SearchBarPrefs.withDesktopBar(p) { it.copy(alpha = v / 100f) } }
                ),
                rows.choice(
                    R.string.icon_glass, IconGlass.entries,
                    { IconGlass.fromName(SearchBarPrefs.desktopBar(it).glass) },
                    { EnumLabels.of(it) }
                ) { glass -> rows.editDesktopBar { it.copy(glass = glass.name) } },
                // Glass takes the place of wireframe and shadow; they keep
                // their settings, out of sight, while it is on.
                SettingItem.Toggle(
                    R.string.search_bar_wireframe,
                    get = { SearchBarPrefs.desktopBar(it).wireframe },
                    set = { on -> rows.editDesktopBar { it.copy(wireframe = on) } },
                    subtitle = R.string.search_bar_wireframe_summary
                ).hiddenWhen(desktopGlassHidden),
                SettingItem.Toggle(
                    R.string.search_bar_shadow,
                    get = { SearchBarPrefs.desktopBar(it).shadow },
                    set = { on -> rows.editDesktopBar { it.copy(shadow = on) } }
                ).hiddenWhen(desktopGlassHidden),
                SettingItem.Toggle(
                    R.string.search_bar_hint_text,
                    get = { SearchBarPrefs.desktopBar(it).hint },
                    set = { on -> rows.editDesktopBar { it.copy(hint = on) } }
                ),
                SettingItem.Header(R.string.search_bar_chips)
                // The same buttons as the drawer field's, in the same order.
            ) + SearchBarButtons.ROWS.map(rows::barChip)
        ),
        // The drawer field's own page, opened from the same Search bars group.
        SettingsSection(
            R.string.drawer_search_bar,
            listOf(
                SettingItem.Preview(PreviewKind.DRAWER_SEARCH_BAR),
                rows.choice(
                    R.string.search_bar_placement, DrawerBarPlacement.entries,
                    { DrawerBarPlacement.fromConfig(SearchBarPrefs.drawerBar(it)) },
                    {
                        when (it) {
                            DrawerBarPlacement.TOP -> R.string.tab_bar_top
                            DrawerBarPlacement.BOTTOM -> R.string.tab_bar_bottom
                            DrawerBarPlacement.HIDDEN -> R.string.placement_hidden
                        }
                    }
                ) { placement ->
                    rows.editDrawerBar {
                        it.copy(placement = placement.name, enabled = placement != DrawerBarPlacement.HIDDEN)
                    }
                },
                SettingItem.Toggle(
                    R.string.drawer_search_global,
                    get = { SearchBarPrefs.drawerBar(it).globalOnTap },
                    set = { on -> rows.editDrawerBar { it.copy(globalOnTap = on) } }
                ).hiddenWhen(drawerFieldHidden),
                SettingItem.Header(R.string.group_appearance),
                rows.choice(
                    R.string.icon_glass, IconGlass.entries,
                    { IconGlass.fromName(SearchBarPrefs.drawerBar(it).glass) },
                    { EnumLabels.of(it) }
                ) { glass -> rows.editDrawerBar { it.copy(glass = glass.name) } },
                // Glass takes the place of the filled box and the outline.
                SettingItem.Toggle(
                    R.string.drawer_bar_filled,
                    get = { SearchBarPrefs.drawerBar(it).filled },
                    set = { on -> rows.editDrawerBar { it.copy(filled = on) } }
                ).hiddenWhen(drawerGlassHidden),
                // An unset radius draws as the full pill, so it reads as one.
                SettingItem.Slider(
                    R.string.style_corner_radius, min = 0, max = DrawerSearchBarConfig.MAX_RADIUS, step = 2,
                    get = {
                        (SearchBarPrefs.drawerBar(it).radius ?: DrawerSearchBarConfig.MAX_RADIUS)
                            .coerceIn(0, DrawerSearchBarConfig.MAX_RADIUS)
                    },
                    set = { v -> rows.editDrawerBar { it.copy(radius = v) } },
                    display = { context.getString(R.string.padding_dp_value, it) },
                    preview = { p, v -> SearchBarPrefs.withDrawerBar(p) { it.copy(radius = v) } }
                ),
                // The buttons inside the field, at its right end; gone with it.
                SettingItem.Header(R.string.search_bar_chips, visibleIf = { SearchBarPrefs.drawerFieldShown(it) }),
            ) + SearchBarButtons.ROWS.map { drawerButton(viewModel, it) }
        ),
        // Feed page: the page toggle, then Layout (frequent apps and
        // shortcuts, the rows stepper, the search bar, the edge swipe), and a
        // link to the Cards page, which links back.
        SettingsSection(
            R.string.settings_section_feed,
            listOf(
                SettingItem.Toggle(
                    R.string.feed_enabled,
                    subtitle = R.string.feed_enabled_subtitle,
                    get = { it.feedEnabled },
                    set = { viewModel.set(Pref.feedEnabled, it) }
                ),
                // The cards' color and opacity: the Style tab's Cards row.
                SettingItem.StyleTarget(
                    R.string.feed_card_colors, SurfaceId.CARDS, { actions.openStylePicker(SurfaceId.CARDS) }
                ).hiddenWhen(feedHidden),
                SettingItem.Header(R.string.feed_layout_header, visibleIf = { it.feedEnabled }),
                SettingItem.Toggle(
                    R.string.feed_show_frequent,
                    subtitle = R.string.feed_show_frequent_subtitle,
                    get = { it.feedShowFrequent },
                    set = { viewModel.set(Pref.feedShowFrequent, it) }
                ).hiddenWhen(feedHidden),
                SettingItem.Toggle(
                    R.string.feed_show_shortcuts,
                    subtitle = R.string.feed_show_shortcuts_subtitle,
                    get = { it.feedShowShortcuts },
                    set = { viewModel.set(Pref.feedShowShortcuts, it) }
                ).hiddenWhen(feedHidden),
                // "Suggested shortcuts rows": how many rows of pills.
                SettingItem.Slider(
                    R.string.feed_shortcut_rows,
                    min = 1, max = 3,
                    get = { it.feedShortcutRows },
                    set = { viewModel.set(Pref.feedShortcutRows, it) },
                    display = { context.resources.getQuantityString(R.plurals.feed_shortcut_rows_value, it, it) }
                ).hiddenWhen { feedHidden(it) ?: needsOn(R.string.feed_show_shortcuts, !it.feedShowShortcuts) },
                SettingItem.Toggle(
                    R.string.feed_show_search,
                    get = { it.feedShowSearch },
                    set = { viewModel.set(Pref.feedShowSearch, it) }
                ).hiddenWhen(feedHidden),
                SettingItem.Toggle(
                    R.string.feed_edge_swipe,
                    get = { it.feedEdgeSwipe },
                    set = { viewModel.set(Pref.feedEdgeSwipe, it) }
                ).hiddenWhen(feedHidden),
                // What the page shows lives on the Cards page.
                SettingItem.Header(R.string.settings_section_cards),
                SettingItem.Row(
                    R.string.feed_cards_link,
                    subtitle = { context.getString(R.string.feed_cards_link_summary) },
                    onClick = actions.openCards
                )
            )
        ),
        // Cards page: every card - the user's own and the built-in
        // ones - as a name with a gear over a live rendering; the FAB adds.
        // Rebuilt by SettingsActivity whenever the card list changes, since
        // the rows are the cards themselves.
        SettingsSection(
            R.string.settings_section_cards,
            buildList {
                add(SettingItem.Header(R.string.cards_yours))
                val cards = viewModel.feedCards()
                if (cards.isEmpty()) {
                    add(SettingItem.Row(R.string.cards_none, onClick = { actions.editCard(null) }))
                }
                cards.forEach { card ->
                    add(
                        SettingItem.Card(
                            titleText = card.name.ifBlank { context.getString(R.string.card_untitled) },
                            build = { parent -> actions.cardPreview(card, parent) },
                            onGear = { actions.editCard(card) }
                        )
                    )
                }
                add(
                    SettingItem.Card(
                        titleText = context.getString(R.string.cards_media_title),
                        build = { parent -> actions.builtInPreview(BuiltInCard.MEDIA, parent) },
                        onGear = { actions.openSection(R.string.cards_media_title) }
                    )
                )
                add(
                    SettingItem.Card(
                        titleText = context.getString(R.string.feed_calendar_title),
                        build = { parent -> actions.builtInPreview(BuiltInCard.CALENDAR, parent) },
                        onGear = { actions.openSection(R.string.feed_calendar_title) }
                    )
                )
                add(
                    SettingItem.Card(
                        titleText = context.getString(R.string.feed_contacts_title),
                        build = { parent -> actions.builtInPreview(BuiltInCard.CONTACTS, parent) },
                        onGear = { actions.openSection(R.string.feed_contacts_title) }
                    )
                )
                // Cards show on the feed page; its switch is one tap away.
                add(SettingItem.Header(R.string.settings_section_feed))
                add(
                    SettingItem.Row(
                        R.string.cards_feed_link,
                        subtitle = { prefs ->
                            context.getString(if (prefs.feedEnabled) R.string.mode_on else R.string.mode_off)
                        },
                        onClick = { actions.openSection(R.string.settings_section_feed) }
                    )
                )
            }
        ),
        // Behind each built-in card's gear (reached from the Cards page, not
        // the root list): the rendering, then where the card shows.
        SettingsSection(
            R.string.cards_media_title,
            listOf(
                SettingItem.Card(
                    titleText = context.getString(R.string.cards_media_title),
                    build = { parent -> actions.builtInPreview(BuiltInCard.MEDIA, parent) },
                    onGear = null
                ),
                SettingItem.Header(R.string.card_show_in_feed),
                SettingItem.Toggle(
                    R.string.feed_show_media,
                    get = { it.feedShowMedia },
                    set = { viewModel.set(Pref.feedShowMedia, it) }
                ).blockedWhen(feedOff),
                // Honest note when the media card can't work: notification
                // access gates getActiveSessions, same as badges.
                rows.needsNotificationAccess(R.string.feed_media_needs_access_summary) { it.feedShowMedia }
            )
        ),
        SettingsSection(
            R.string.feed_calendar_title,
            listOf(
                SettingItem.Card(
                    titleText = context.getString(R.string.feed_calendar_title),
                    build = { parent -> actions.builtInPreview(BuiltInCard.CALENDAR, parent) },
                    onGear = null
                ),
                SettingItem.Header(R.string.card_show_in_feed),
                SettingItem.Toggle(
                    R.string.feed_show_calendar,
                    get = { it.feedShowCalendar },
                    set = { enabled ->
                        viewModel.set(Pref.feedShowCalendar, enabled)
                        if (enabled) actions.onFeedCalendarEnabled()
                    }
                ).blockedWhen(feedOff)
            )
        ),
        SettingsSection(
            R.string.feed_contacts_title,
            listOf(
                SettingItem.Card(
                    titleText = context.getString(R.string.feed_contacts_title),
                    build = { parent -> actions.builtInPreview(BuiltInCard.CONTACTS, parent) },
                    onGear = null
                ),
                SettingItem.Header(R.string.card_show_in_feed),
                SettingItem.Toggle(
                    R.string.feed_show_contacts,
                    get = { it.feedShowContacts },
                    set = { enabled ->
                        viewModel.set(Pref.feedShowContacts, enabled)
                        if (enabled) actions.onFeedContactsEnabled()
                    }
                ).blockedWhen(feedOff)
            )
        ),
        SettingsSection(
            R.string.settings_section_folders,
            listOf(
                SettingItem.Preview(PreviewKind.FOLDER),
                rows.choice(
                    R.string.folder_preview_style, FolderPreviewStyle.entries,
                    { it.folderPreviewStyle }, { EnumLabels.of(it) },
                    onPick = { viewModel.set(Pref.folderPreviewStyle, it) }
                ),
                SettingItem.Toggle(
                    R.string.folder_prioritize_first,
                    get = { it.folderPrioritizeFirst },
                    set = { viewModel.set(Pref.folderPrioritizeFirst, it) },
                    subtitle = R.string.folder_prioritize_first_summary
                ),
                SettingItem.Slider(
                    R.string.folder_columns,
                    min = 0, max = com.supernova.launcher.data.GridLimits.FOLDER_COLUMNS,
                    bigGridMax = com.supernova.launcher.data.GridLimits.FOLDER_COLUMNS_BIG,
                    get = { it.folderColumns },
                    set = { viewModel.set(Pref.folderColumns, it) },
                    display = { if (it == 0) context.getString(R.string.folder_columns_auto) else it.toString() }
                ),
                SettingItem.Toggle(
                    R.string.dock_match_desktop,
                    get = { it.folderMatchDesktop },
                    set = { viewModel.set(Pref.folderMatchDesktop, it) }
                ),
                SettingItem.Slider(
                    R.string.folder_icon_scale,
                    min = 70, max = 200,
                    step = 5,
                    get = { (it.folderIconScale * 100f).roundToInt() },
                    set = { viewModel.set(Pref.folderIconScale, it / 100f) },
                    display = { context.getString(R.string.opacity_percent, it) }
                ).hiddenWhen { needsOff(R.string.dock_match_desktop, it.folderMatchDesktop) },
                SettingItem.Toggle(
                    R.string.folder_labels,
                    get = { it.folderLabels },
                    set = { viewModel.set(Pref.folderLabels, it) }
                ),
                *labelOverrideItems(context, viewModel, actions, LabelContext.FOLDER) {
                    needsOn(R.string.folder_labels, !it.folderLabels)
                }.toTypedArray(),
                SettingItem.Toggle(
                    R.string.folder_badges,
                    get = { it.folderBadges },
                    set = { viewModel.set(Pref.folderBadges, it) }
                ).blockedWhen { R.string.needs_badges.takeUnless { _ -> it.badges } },
                SettingItem.Toggle(
                    R.string.folder_close_on_launch,
                    get = { it.folderCloseOnLaunch },
                    set = { viewModel.set(Pref.folderCloseOnLaunch, it) }
                ),
                SettingItem.Toggle(
                    R.string.folder_show_name,
                    get = { it.folderShowName },
                    set = { viewModel.set(Pref.folderShowName, it) }
                ),
                // It sits in the name's row, which goes with the name.
                SettingItem.Toggle(
                    R.string.folder_show_count,
                    get = { it.folderShowCount },
                    set = { viewModel.setFolderShowCount(it) }
                ).hiddenWhen { needsOn(R.string.folder_show_name, !it.folderShowName) },
                SettingItem.Slider(
                    R.string.folder_window_radius,
                    min = 0, max = 32,
                    step = 2,
                    get = {
                        com.supernova.launcher.ui.theme.StyleConfig.decode(it.styleConfig)
                            .styleFor(com.supernova.launcher.ui.theme.SurfaceId.FOLDER)
                            .cornerRadii?.firstOrNull()?.roundToInt() ?: 24
                    },
                    set = { v ->
                        val prefs = viewModel.state.value.prefs
                        val config = com.supernova.launcher.ui.theme.StyleConfig.decode(prefs.styleConfig)
                        val style = config.styleFor(com.supernova.launcher.ui.theme.SurfaceId.FOLDER)
                        viewModel.set(
                            Pref.styleConfig,
                            config.with(
                                com.supernova.launcher.ui.theme.SurfaceId.FOLDER,
                                style.copy(cornerRadii = List(4) { v.toFloat() })
                            ).encode()
                        )
                    },
                    display = { context.getString(R.string.padding_dp_value, it) }
                ),
                rows.choice(
                    R.string.folder_mode, FolderMode.entries,
                    { it.folderMode }, { EnumLabels.of(it) }
                ) { viewModel.set(Pref.folderMode, it) },
                SettingItem.Toggle(
                    R.string.folder_shadow,
                    get = { it.folderShadow },
                    set = { viewModel.set(Pref.folderShadow, it) }
                ),
                rows.choice(
                    R.string.folder_scroll, FolderScroll.entries,
                    { it.folderScroll }, { EnumLabels.of(it) }
                ) { viewModel.set(Pref.folderScroll, it) },
                SettingItem.Row(
                    R.string.folder_icon_appearance,
                    onClick = actions.editFolderIcon
                )
            )
        ),
        SettingsSection(
            R.string.settings_section_icons,
            listOf(
                SettingItem.Preview(PreviewKind.ICON_ROW),
                // The desktop's icon size and label look are the Home screen
                // page's; this page holds what applies
                // to every icon wherever it is.
                SettingItem.Header(R.string.group_icons),
                rows.choice(
                    R.string.reshape_legacy, com.supernova.launcher.data.ReshapeLegacy.entries,
                    { com.supernova.launcher.data.ReshapeLegacy.fromPrefs(it) }, { EnumLabels.of(it) },
                    onPick = { viewModel.set(Pref.reshapeLegacy, it.name) }
                ),
                SettingItem.Toggle(
                    R.string.icon_wiggle,
                    get = { it.iconWiggle },
                    set = { viewModel.set(Pref.iconWiggle, it) }
                ),
                SettingItem.Toggle(
                    R.string.icon_shadow,
                    get = { it.iconShadow },
                    set = { viewModel.set(Pref.iconShadow, it) }
                ),
                SettingItem.Toggle(
                    R.string.icon_swipe_indicator,
                    get = { it.iconSwipeIndicator },
                    set = { viewModel.set(Pref.iconSwipeIndicator, it) }
                ),
                SettingItem.Toggle(
                    R.string.themed_icons,
                    get = { it.themedIcons },
                    set = { viewModel.set(Pref.themedIcons, it) },
                    // System monochrome layers exist only on Android 13+.
                    visibleIf = { android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU }
                ),
                SettingItem.Toggle(
                    R.string.themed_icons_autogen,
                    get = { it.themedIconsAutogen },
                    set = { viewModel.set(Pref.themedIconsAutogen, it) },
                    visibleIf = { android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU }
                ).hiddenWhen { needsOn(R.string.themed_icons, !it.themedIcons) },
                SettingItem.Row(
                    R.string.icon_pack,
                    // The pack's own label, not the package name the pref
                    // stores - "Simply Minimal Icons", not
                    // "com.italiano.simplyminimal".
                    subtitle = { prefs ->
                        prefs.iconPack.ifEmpty { null }
                            ?.let { viewModel.appCatalog().iconPackLabel(it) }
                            ?: context.getString(R.string.icon_pack_system)
                    },
                    onClick = actions.pickIconPack
                ),
                SettingItem.Row(
                    R.string.icon_shape,
                    subtitle = { context.getString(EnumLabels.of(it.iconShape)) },
                    onClick = actions.pickIconShape
                ),
                rows.choice(
                    R.string.icon_glass, com.supernova.launcher.data.IconGlass.entries,
                    { it.iconGlass }, { EnumLabels.of(it) },
                    onPick = { viewModel.set(Pref.iconGlass, it) }
                ),
                // Bulk undo for per-app and per-folder icon picks.
                SettingItem.Row(
                    R.string.reset_custom_icons,
                    subtitle = { _ ->
                        val count = viewModel.customIcons()
                        if (count.total == 0) {
                            context.getString(R.string.reset_custom_icons_none)
                        } else {
                            listOfNotNull(
                                count.apps.takeIf { it > 0 }?.let {
                                    context.resources.getQuantityString(R.plurals.custom_icon_apps, it, it)
                                },
                                count.folders.takeIf { it > 0 }?.let {
                                    context.resources.getQuantityString(R.plurals.custom_icon_folders, it, it)
                                },
                                count.shortcuts.takeIf { it > 0 }?.let {
                                    context.resources.getQuantityString(R.plurals.custom_icon_shortcuts, it, it)
                                }
                            ).joinToString(", ")
                        }
                    },
                    onClick = actions.resetCustomIcons
                ),
                SettingItem.Header(R.string.group_label_rows),
                rows.color(R.string.label_color, { it.labelColor }) { viewModel.set(Pref.labelColor, it) },
                // Bulk undo for labels hidden one app or shortcut at a time.
                SettingItem.Row(
                    R.string.reset_hidden_labels,
                    subtitle = { _ ->
                        val count = viewModel.hiddenLabels()
                        if (count.total == 0) {
                            context.getString(R.string.reset_hidden_labels_none)
                        } else {
                            listOfNotNull(
                                count.apps.takeIf { it > 0 }?.let {
                                    context.resources.getQuantityString(R.plurals.custom_icon_apps, it, it)
                                },
                                count.shortcuts.takeIf { it > 0 }?.let {
                                    context.resources.getQuantityString(R.plurals.custom_icon_shortcuts, it, it)
                                }
                            ).joinToString(", ")
                        }
                    },
                    onClick = actions.resetHiddenLabels
                ),
                SettingItem.Header(R.string.group_badges),
                SettingItem.Toggle(
                    R.string.badges,
                    get = { it.badges },
                    set = { enabled ->
                        viewModel.set(Pref.badges, enabled)
                        if (enabled) actions.onBadgesEnabled()
                    }
                ),
                rows.needsNotificationAccess(R.string.badges_need_access_summary) { it.badges },
                rows.choice(
                    R.string.badge_style, listOf(BadgeStyle.DYNAMIC, BadgeStyle.DOTS, BadgeStyle.NUMERIC),
                    { BadgeStyle.effective(it) }, { EnumLabels.of(it) }
                ) { style ->
                    viewModel.set(Pref.badgeStyle, style.name)
                    // Keep the legacy pref in sync so old builds and backups still
                    // derive the same style.
                    viewModel.set(Pref.badgeCounts, style == BadgeStyle.NUMERIC)
                }.hiddenWhen(badgesHidden),
                SettingItem.Row(
                    R.string.badged_apps,
                    onClick = actions.editBadgedApps
                ).hiddenWhen(badgesHidden),
                rows.color(
                    R.string.badge_color, { it.badgeColor },
                    // Unset, Dots take the app icon's color.
                    subtitle = { prefs ->
                        if (prefs.badgeColor.isBlank() && BadgeStyle.effective(prefs) == BadgeStyle.DOTS) {
                            context.getString(R.string.badge_color_icon)
                        } else {
                            context.getString(curatedFor(prefs.badgeColor).first)
                        }
                    }
                ) { viewModel.set(Pref.badgeColor, it) }.hiddenWhen {
                    badgesHidden(it)
                        ?: dependsOn(R.string.badge_style, BadgeStyle.effective(it) == BadgeStyle.DYNAMIC)
                },
                // Numeric dots' own look, laid out under the style.
                rows.color(
                    R.string.badge_text_color, { it.badgeTextColor }
                ) { viewModel.set(Pref.badgeTextColor, it) }.hiddenWhen {
                    badgesHidden(it)
                        ?: dependsOn(R.string.badge_style, BadgeStyle.effective(it) != BadgeStyle.NUMERIC)
                },
                SettingItem.Slider(
                    R.string.badge_corner_radius,
                    min = 0, max = com.supernova.launcher.data.BadgeLimits.CORNER_ROUND,
                    step = 5,
                    get = { it.badgeCornerRadius },
                    set = { viewModel.set(Pref.badgeCornerRadius, it) },
                    display = { context.getString(R.string.opacity_percent, it) },
                    preview = { prefs, value -> prefs.copy(badgeCornerRadius = value) }
                ).hiddenWhen {
                    badgesHidden(it)
                        ?: dependsOn(R.string.badge_style, BadgeStyle.effective(it) != BadgeStyle.NUMERIC)
                },
                SettingItem.Toggle(
                    R.string.badge_outline,
                    get = { it.badgeOutline },
                    set = { viewModel.set(Pref.badgeOutline, it) }
                ).hiddenWhen(badgesHidden),
                rows.choice(
                    R.string.badge_position, BadgePosition.entries,
                    { it.badgePosition }, { EnumLabels.of(it) }
                ) { viewModel.set(Pref.badgePosition, it) }.hiddenWhen(badgesHidden),
                SettingItem.Slider(
                    R.string.badge_size,
                    min = 60, max = 160,
                    step = 5,
                    get = { (it.badgeScale * 100f).roundToInt() },
                    set = { viewModel.set(Pref.badgeScale, it / 100f) },
                    display = { context.getString(R.string.opacity_percent, it) }
                ).hiddenWhen(badgesHidden),
                // Test notification badge: a badge to look at without
                // waiting for a message.
                SettingItem.Row(
                    R.string.badge_test,
                    subtitle = { context.getString(R.string.badge_test_summary) },
                    onClick = actions.postTestBadge
                ),
                // Popup menu (Part A): row configuration dialog + the
                // notification-lines toggle. Lives here because the popup is
                // an icon-level surface; its colors live in the Style tab.
                SettingItem.Header(R.string.group_popup),
                SettingItem.Row(
                    R.string.popup_menu_rows,
                    onClick = actions.editPopupRows
                ),
                SettingItem.Toggle(
                    R.string.popup_action_icons,
                    get = { it.popupActionIcons },
                    set = { viewModel.setPopupActionIcons(it) },
                    subtitle = R.string.popup_action_icons_summary
                ),
                SettingItem.Toggle(
                    R.string.popup_notifications,
                    get = { it.popupNotifications },
                    set = { enabled ->
                        viewModel.set(Pref.popupNotifications, enabled)
                        if (enabled) actions.onPopupNotificationsEnabled()
                    }
                ),
                rows.needsNotificationAccess(R.string.popup_needs_access_summary) { it.popupNotifications },
                rows.choice(
                    R.string.popup_style, com.supernova.launcher.ui.PopupMenuStyle.entries,
                    { it.popupStyle }, { EnumLabels.of(it) },
                    onPick = { viewModel.set(Pref.popupStyle, it) }
                ),
                SettingItem.Toggle(
                    R.string.popup_group_shortcuts,
                    get = { it.popupGroupShortcuts },
                    set = { viewModel.set(Pref.popupGroupShortcuts, it) },
                    subtitle = R.string.popup_group_shortcuts_summary
                ).hiddenWhen { dependsOn(R.string.popup_style, !it.popupStyle.canGroup) },
                SettingItem.Toggle(
                    R.string.popup_card_bg,
                    get = { it.popupCardBg },
                    set = { viewModel.set(Pref.popupCardBg, it) },
                    // Grouping draws the shortcuts on tiles of their own.
                ).hiddenWhen {
                    needsOff(R.string.popup_group_shortcuts, it.popupGroupShortcuts && it.popupStyle.canGroup)
                }
            )
        ),
        SettingsSection(
            R.string.gestures,
            listOf(
                // One row per gesture, its action as the subtitle: a
                // single "Gestures" row opening a list of
                // "gesture - action" lines read as a menu, not settings.
                warningRow(
                    context,
                    R.string.gestures_need_accessibility,
                    R.string.gestures_need_accessibility_summary,
                    actions.explainAccessibility
                ) {
                    com.supernova.launcher.ui.PermissionPrompts.gesturesNeedAccessibility(it) &&
                        !com.supernova.launcher.SupernovaAccessibilityService.isEnabled(context)
                },
                SettingItem.Header(R.string.group_one_finger),
                rows.gestureRow(Gesture.SWIPE_UP),
                rows.gestureRow(Gesture.SWIPE_DOWN),
                // Gear by Swipe down: shown while it opens the shade.
                SettingItem.Toggle(
                    R.string.swipe_down_haptic,
                    get = { it.swipeDownHaptic },
                    set = { viewModel.set(Pref.swipeDownHaptic, it) },
                    subtitle = R.string.swipe_down_haptic_summary
                ).hiddenWhen {
                    val down = GestureDefaults.decode(it.gestureBindings)[Gesture.SWIPE_DOWN]?.action
                    dependsOn(R.string.gesture_swipe_down, down != GestureAction.EXPAND_NOTIFICATIONS)
                },
                // Every sideways swipe turns the page with infinite scroll
                // on, so these never fire: one notice stands in for both
                // rows (#122). Their bindings stay for when it's off.
                rows.gestureRow(Gesture.SWIPE_LEFT).hiddenWhen(infiniteScrollHidden),
                rows.gestureRow(Gesture.SWIPE_RIGHT).hiddenWhen(infiniteScrollHidden),
                SettingItem.Row(
                    0,
                    subtitle = { context.getString(R.string.gesture_left_right_conflict_summary) },
                    onClick = {},
                    visibleIf = { it.infiniteScroll },
                    titleText = context.getString(R.string.gesture_left_right_conflict),
                    look = SettingItem.RowLook(tappable = false)
                ),
                rows.gestureRow(Gesture.DOUBLE_TAP),
                rows.gestureRow(Gesture.DOUBLE_TAP_SWIPE_UP),
                rows.gestureRow(Gesture.DOUBLE_TAP_SWIPE_DOWN),
                SettingItem.Header(R.string.group_two_fingers),
                rows.gestureRow(Gesture.PINCH_IN),
                rows.gestureRow(Gesture.PINCH_OUT),
                rows.gestureRow(Gesture.TWO_FINGER_SWIPE_UP),
                rows.gestureRow(Gesture.TWO_FINGER_SWIPE_DOWN),
                rows.gestureRow(Gesture.TWO_FINGER_ROTATE_CW),
                rows.gestureRow(Gesture.TWO_FINGER_ROTATE_CCW),
                // The Home button pressed while home shows.
                SettingItem.Header(R.string.group_home_button),
                // Supersedes the legacy homeSearch toggle; the pref remains
                // underneath as the "" fallback.
                rows.choice(
                    R.string.home_button_action, HomeButtonAction.choices,
                    { HomeButtonAction.effective(it.homeButtonAction, it.homeSearch) }, { EnumLabels.of(it) }
                ) { action ->
                    viewModel.set(Pref.homeButtonAction, action.name)
                    // Mirror the legacy pref so old builds and backups derive the
                    // same behavior, like badgeStyle keeps badgeCounts in sync.
                    viewModel.set(Pref.homeSearch, action == GestureAction.OPEN_SEARCH)
                },
                SettingItem.Toggle(
                    R.string.home_button_main_page_only,
                    get = { it.homeButtonOnMainPage },
                    set = { viewModel.set(Pref.homeButtonOnMainPage, it) }
                ).hiddenWhen {
                    val action = HomeButtonAction.effective(it.homeButtonAction, it.homeSearch)
                    dependsOn(R.string.home_button_action, action == GestureAction.NONE)
                },
                SettingItem.Header(R.string.action_lock_screen),
                SettingItem.Row(
                    R.string.lock_method,
                    subtitle = {
                        context.getString(
                            if (it.lockMethod == LockMethod.DEVICE_ADMIN)
                                R.string.lock_method_device_admin
                            else R.string.lock_method_accessibility
                        )
                    },
                    onClick = actions.pickLockMethod
                ),
                warningRow(
                    context,
                    R.string.device_admin_inactive,
                    R.string.device_admin_inactive_summary,
                    actions.requestDeviceAdmin
                ) {
                    it.lockMethod == LockMethod.DEVICE_ADMIN &&
                        !com.supernova.launcher.SupernovaDeviceAdmin.isActive(context)
                }
            )
        ),
        SettingsSection(
            R.string.settings_section_backup,
            listOf(
                SettingItem.Row(R.string.export_backup, onClick = actions.exportBackup),
                SettingItem.Row(
                    R.string.import_backup,
                    subtitle = { context.getString(R.string.import_backup_subtitle) },
                    onClick = actions.importBackup
                ),
                SettingItem.Row(R.string.share_backup, onClick = actions.shareBackup),
                SettingItem.Row(R.string.auto_backups, onClick = actions.showAutoBackups),
                SettingItem.Row(
                    R.string.reset_to_default,
                    subtitle = { context.getString(R.string.reset_to_default_subtitle) },
                    onClick = actions.resetToDefaults
                ),
                // Maintenance rows kept in Backup rather than a one-off
                // "Advanced" section: they're recovery/setup tools like the
                // rows above, and a two-row section wouldn't earn its header.
                SettingItem.Row(
                    R.string.quick_start,
                    subtitle = { context.getString(R.string.quick_start_subtitle) },
                    onClick = actions.quickStart
                ),
                SettingItem.Row(R.string.restart_supernova, onClick = actions.restartLauncher)
            )
        ),
        SettingsSection(
            R.string.about,
            listOf(
                SettingItem.Row(
                    R.string.about_version,
                    subtitle = { SupernovaApp.versionLabel(context) },
                    onClick = {},
                    // Information only: no ripple, no chevron.
                    look = SettingItem.RowLook(tappable = false)
                ),
                SettingItem.Row(
                    R.string.about_whats_new,
                    subtitle = { context.getString(R.string.about_whats_new_summary) },
                    onClick = actions.showWhatsNew
                ),
                SettingItem.Row(
                    R.string.about_license,
                    subtitle = { context.getString(R.string.about_license_name) },
                    onClick = actions.showLicense
                ),
                SettingItem.Row(
                    R.string.about_source,
                    subtitle = { context.getString(R.string.about_source_url).removePrefix("https://") },
                    onClick = { AboutLinks.open(context, context.getString(R.string.about_source_url)) }
                ),
                SettingItem.Row(
                    R.string.about_report_issue,
                    subtitle = { context.getString(R.string.about_report_issue_summary) },
                    onClick = { AboutLinks.open(context, AboutLinks.newIssueUrl(context)) }
                ),
                SettingItem.Row(
                    R.string.crash_log,
                    subtitle = {
                        val count = SupernovaApp.crashCount(context)
                        if (count == 0) {
                            context.getString(R.string.crash_log_none)
                        } else {
                            context.resources.getQuantityString(R.plurals.crash_log_count, count, count)
                        }
                    },
                    onClick = actions.showCrashLog
                )
            )
        ),
        // Labs: switches for unusual setups and icon theme makers.
        SettingsSection(
            R.string.labs,
            listOf(
                SettingItem.Toggle(
                    R.string.labs_show_component,
                    get = { it.labsShowComponent },
                    set = { viewModel.set(Pref.labsShowComponent, it) },
                    subtitle = R.string.labs_show_component_summary
                ),
                SettingItem.Toggle(
                    R.string.labs_big_grid,
                    get = { it.labsBigGrid },
                    set = { viewModel.set(Pref.labsBigGrid, it) },
                    subtitle = R.string.labs_big_grid_summary
                ),
                SettingItem.Toggle(
                    R.string.labs_export_png,
                    get = { it.labsExportPng },
                    set = { viewModel.set(Pref.labsExportPng, it) },
                    subtitle = R.string.labs_export_png_summary
                ),
                // Both run on the tap, so neither leads anywhere.
                SettingItem.Row(
                    R.string.labs_export_icons,
                    subtitle = { context.getString(R.string.labs_export_icons_summary) },
                    onClick = actions.exportIcons,
                    look = SettingItem.RowLook(chevron = false)
                ),
                SettingItem.Row(
                    R.string.labs_clear_icon_cache,
                    subtitle = { context.getString(R.string.labs_clear_icon_cache_summary) },
                    onClick = actions.clearIconCache,
                    look = SettingItem.RowLook(chevron = false)
                )
            )
        ),
        // Every access Supernova can ask for, each with its state; a tap
        // grants it or opens the system screen that does.
        SettingsSection(
            R.string.permissions,
            LabsPermission.entries.filter { it.applies }.map { permission ->
                SettingItem.Row(
                    permission.title,
                    subtitle = { permission.status(context) },
                    onClick = { actions.managePermission(permission) },
                    look = SettingItem.RowLook(positive = { permission.granted(context) })
                )
            }
        )
    )
    }

    // The Style bottom tab. Theme and accent moved here from Look & feel; the
    // legacy drawer/folder background color rows are superseded by the
    // per-surface pickers (their prefs still back the resolver's defaults).
    fun styleSection(
        context: Context,
        viewModel: LauncherViewModel,
        actions: Actions
    ): SettingsSection {
        val rows = Rows(context, viewModel, actions)
        return SettingsSection(
        R.string.style_tab,
        listOf(
            // A grouped page: Theme and Surfaces cards, each under its label.
            SettingItem.Header(R.string.style_group_theme),
            SettingItem.Row(
                R.string.color_palette,
                subtitle = {
                    context.getString(EnumLabels.of(it.paletteSource))
                },
                onClick = actions.openPaletteDialog,
                look = SettingItem.RowLook(R.drawable.ic_tab_style)
            ),
            rows.choice(
                R.string.theme_mode, ThemeMode.entries,
                { it.themeMode }, { EnumLabels.of(it) }
            ) { viewModel.set(Pref.themeMode, it) }.withLook(SettingItem.RowLook(R.drawable.ic_style_theme)),
            SettingItem.Row(
                R.string.theme_schedule_times,
                subtitle = {
                    context.getString(
                        R.string.theme_schedule_range,
                        formatMinute(it.themeScheduleStart), formatMinute(it.themeScheduleEnd)
                    )
                },
                onClick = actions.editThemeSchedule,
                look = SettingItem.RowLook(R.drawable.ic_style_schedule)
            ).hiddenWhen { dependsOn(R.string.theme_mode, it.themeMode != ThemeMode.SCHEDULE) },
            accentRow(context, viewModel, actions),
            SettingItem.Header(R.string.style_group_surfaces),
            SettingItem.StyleTarget(
                R.string.settings_section_home, SurfaceId.HOME, { actions.openStylePicker(SurfaceId.HOME) }
            ),
            SettingItem.StyleTarget(R.string.settings_section_drawer, SurfaceId.DRAWER, { actions.openStylePicker(SurfaceId.DRAWER) }),
            SettingItem.StyleTarget(R.string.settings_section_dock, SurfaceId.DOCK, { actions.openStylePicker(SurfaceId.DOCK) }),
            SettingItem.StyleTarget(R.string.settings_section_folders, SurfaceId.FOLDER, { actions.openStylePicker(SurfaceId.FOLDER) }),
            SettingItem.StyleTarget(R.string.style_popup, SurfaceId.POPUP, { actions.openStylePicker(SurfaceId.POPUP) }),
            SettingItem.StyleTarget(
                R.string.style_cards, SurfaceId.CARDS, { actions.openStylePicker(SurfaceId.CARDS) }
            ),
            SettingItem.StyleTarget(
                R.string.desktop_search_bar, SurfaceId.SEARCH_BAR,
                { actions.openStylePicker(SurfaceId.SEARCH_BAR) }
            ),
            SettingItem.StyleTarget(
                R.string.drawer_search_bar, SurfaceId.DRAWER_SEARCH_BAR,
                { actions.openStylePicker(SurfaceId.DRAWER_SEARCH_BAR) }
            ),
            SettingItem.StyleTarget(R.string.settings, SurfaceId.SETTINGS, { actions.openStylePicker(SurfaceId.SETTINGS) })
        )
    )
    }

    // A row saying what a setting still needs (a permission, a service):
    // shown while it is missing, and a tap goes to grant it.
    private fun warningRow(
        context: Context,
        @StringRes title: Int,
        @StringRes summary: Int,
        onClick: () -> Unit,
        visibleIf: (LauncherPrefs) -> Boolean
    ): SettingItem.Row = SettingItem.Row(
        title,
        subtitle = { context.getString(summary) },
        onClick = onClick,
        look = SettingItem.RowLook(icon = R.drawable.ic_settings_warn, warn = true),
        visibleIf = visibleIf
    )

    private fun formatMinute(minute: Int): String =
        "%d:%02d".format(minute / 60, minute % 60)

    // The per-context label block (B3/B4): a customize toggle plus the four
    // override fields, spliced into the Dock / App drawer / Folders sections.
    // Enabling seeds the override from the current desktop values so nothing
    // visibly changes until a field is edited; disabling goes back to inherit.
    private fun labelOverrideItems(
        context: Context,
        viewModel: LauncherViewModel,
        actions: Actions,
        labelContext: LabelContext,
        sectionHidden: (LauncherPrefs) -> SettingItem.HiddenBy?
    ): List<SettingItem> {
        fun override(prefs: LauncherPrefs): LabelOverride =
            LabelConfig.decode(prefs.labelConfig).of(labelContext)

        fun customized(prefs: LauncherPrefs): Boolean = !override(prefs).isEmpty()
        val rows = Rows(context, viewModel, actions)

        return listOf(
            SettingItem.Toggle(
                R.string.label_customize,
                get = ::customized,
                set = { on ->
                    viewModel.updateLabelOverride(labelContext) {
                        if (on) {
                            val p = viewModel.state.value.prefs
                            LabelOverride(p.labelSize, p.labelFont.name, p.labelShadow, p.labelSingleLine)
                        } else {
                            LabelOverride()
                        }
                    }
                }
            ).hiddenWhen(sectionHidden),
            SettingItem.Slider(
                R.string.label_size,
                min = 9, max = 18,
                get = { override(it).size ?: it.labelSize },
                set = { v -> viewModel.updateLabelOverride(labelContext) { it.copy(size = v) } },
                display = { context.getString(R.string.label_size_value, it) }
            ).hiddenWhen { sectionHidden(it) ?: needsOn(R.string.label_customize, !customized(it)) },
            SettingItem.Row(
                R.string.label_font,
                subtitle = {
                    context.getString(EnumLabels.of(override(it).font?.let(LabelFont::fromName) ?: it.labelFont))
                },
                onClick = {
                    val prefs = viewModel.state.value.prefs
                    rows.fontPick(override(prefs).font?.let(LabelFont::fromName) ?: prefs.labelFont) { font ->
                        viewModel.updateLabelOverride(labelContext) { it.copy(font = font.name) }
                    }
                }
            ).hiddenWhen { sectionHidden(it) ?: needsOn(R.string.label_customize, !customized(it)) },
            SettingItem.Toggle(
                R.string.label_shadow,
                get = { override(it).shadow ?: it.labelShadow },
                set = { v -> viewModel.updateLabelOverride(labelContext) { it.copy(shadow = v) } }
            ).hiddenWhen { sectionHidden(it) ?: needsOn(R.string.label_customize, !customized(it)) },
            SettingItem.Toggle(
                R.string.label_single_line,
                get = { LabelStyler.singleLineFor(labelContext, it) },
                set = { v -> viewModel.updateLabelOverride(labelContext) { it.copy(singleLine = v) } }
            ).hiddenWhen { sectionHidden(it) ?: needsOn(R.string.label_customize, !customized(it)) }
        )
    }

    // One-line description of a custom card for its settings row: pin state,
    // action count, and where it goes (drawer on conditions, or feed+search).
    fun cardSummary(context: Context, card: com.supernova.launcher.feed.FeedCard): CharSequence {
        val parts = ArrayList<String>(4)
        if (!card.enabled) parts.add(context.getString(R.string.feed_card_disabled))
        if (card.pinned) parts.add(context.getString(R.string.card_summary_pinned))
        val actions = card.actions.size
        if (actions > 0) {
            parts.add(context.resources.getQuantityString(R.plurals.card_actions_count, actions, actions))
        }
        parts.add(
            context.getString(
                if (card.hasConditions && card.activationEnabled) R.string.card_summary_conditions
                else R.string.card_summary_no_conditions
            )
        )
        return parts.joinToString(" \u00b7 ")
    }
}

// What brings back a row its page hides, for the settings search's hint
// (SettingItem.hiddenWhen, #133): the setting, which way it needs to go, and
// null while nothing hides the row.
private fun needsOn(@StringRes setting: Int, missing: Boolean, @StringRes page: Int = 0) =
    SettingItem.HiddenBy(setting, SettingItem.HiddenBy.Needs.ON, page).takeIf { missing }

private fun needsOff(@StringRes setting: Int, set: Boolean, @StringRes page: Int = 0) =
    SettingItem.HiddenBy(setting, SettingItem.HiddenBy.Needs.OFF, page).takeIf { set }

private fun dependsOn(@StringRes setting: Int, unmet: Boolean) =
    SettingItem.HiddenBy(setting, SettingItem.HiddenBy.Needs.OTHER).takeIf { unmet }

// The drawer field's own rows go with the field.
private val drawerFieldHidden: (LauncherPrefs) -> SettingItem.HiddenBy? = {
    dependsOn(R.string.search_bar_placement, !SearchBarPrefs.drawerFieldShown(it))
}

// One of the drawer field's buttons (drawerActionButtons), on and off. As on
// the home bar, turning one on adds it after the others.
private fun drawerButton(viewModel: LauncherViewModel, button: SearchBarChip) = SettingItem.Toggle(
    SearchBarButtons.label(button),
    get = { button in SearchBarButtons.decode(it.drawerActionButtons) },
    set = { on ->
        val now = SearchBarButtons.decode(viewModel.state.value.prefs.drawerActionButtons)
        viewModel.set(Pref.drawerActionButtons, SearchBarButtons.encode(SearchBarButtons.toggled(now, button, on)))
    }
).hiddenWhen(drawerFieldHidden)

// The two bars' slices of the searchBarConfig JSON, for their pages' rows
// and previews: read one, or a prefs snapshot with one changed.
internal object SearchBarPrefs {
    fun desktopBar(prefs: LauncherPrefs): DesktopSearchBarConfig =
        SearchBarConfig.decode(prefs.searchBarConfig).desktop

    fun withDesktopBar(
        prefs: LauncherPrefs,
        change: (DesktopSearchBarConfig) -> DesktopSearchBarConfig
    ): LauncherPrefs {
        val full = SearchBarConfig.decode(prefs.searchBarConfig)
        return prefs.copy(searchBarConfig = full.copy(desktop = change(full.desktop)).encode())
    }

    fun drawerBar(prefs: LauncherPrefs): DrawerSearchBarConfig =
        SearchBarConfig.decode(prefs.searchBarConfig).drawer

    // Whether the drawer has a field at all (its placement isn't Hidden).
    fun drawerFieldShown(prefs: LauncherPrefs): Boolean =
        DrawerBarPlacement.fromConfig(drawerBar(prefs)) != DrawerBarPlacement.HIDDEN

    fun withDrawerBar(
        prefs: LauncherPrefs,
        change: (DrawerSearchBarConfig) -> DrawerSearchBarConfig
    ): LauncherPrefs {
        val full = SearchBarConfig.decode(prefs.searchBarConfig)
        return prefs.copy(searchBarConfig = full.copy(drawer = change(full.drawer)).encode())
    }
}
