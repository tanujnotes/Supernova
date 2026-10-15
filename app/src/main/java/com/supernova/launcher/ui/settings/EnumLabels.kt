package com.supernova.launcher.ui.settings

import androidx.annotation.StringRes
import com.supernova.launcher.R
import com.supernova.launcher.data.CornerCut
import com.supernova.launcher.data.DrawerGroupType
import com.supernova.launcher.data.IconShape
import com.supernova.launcher.data.SmartCategory
import com.supernova.launcher.gesture.Gesture
import com.supernova.launcher.gesture.GestureAction
import com.supernova.launcher.ui.BadgePosition
import com.supernova.launcher.ui.BadgeStyle
import com.supernova.launcher.ui.DockBgStyle
import com.supernova.launcher.ui.DrawerSort
import com.supernova.launcher.ui.DrawerTabBar
import com.supernova.launcher.ui.FolderMode
import com.supernova.launcher.ui.FolderPreviewStyle
import com.supernova.launcher.ui.FolderScroll
import com.supernova.launcher.ui.DrawerStyle
import com.supernova.launcher.ui.HideClock
import com.supernova.launcher.ui.HomeOrientation
import com.supernova.launcher.ui.IndicatorStyle
import com.supernova.launcher.ui.LabelFont
import com.supernova.launcher.ui.AnimationSpeed
import com.supernova.launcher.ui.LaunchAnimation
import com.supernova.launcher.ui.PopupExtraRow
import com.supernova.launcher.ui.ScrollEffect
import com.supernova.launcher.ui.StatusBarIcons
import com.supernova.launcher.ui.WallpaperScrollMode
import com.supernova.launcher.data.IconGlass
import com.supernova.launcher.data.ReshapeLegacy
import com.supernova.launcher.ui.SearchBarPlacement
import com.supernova.launcher.ui.theme.AccentColor
import com.supernova.launcher.ui.theme.PaletteSourceMode
import com.supernova.launcher.ui.theme.SurfaceId
import com.supernova.launcher.ui.theme.ThemeMode

// User-facing labels for data-layer enums. The whens are exhaustive on
// purpose: a new enum constant fails compilation here until it gets a
// localizable string, and the data layer stays resource-free.
object EnumLabels {

    @StringRes
    fun of(gesture: Gesture): Int = when (gesture) {
        Gesture.SWIPE_UP -> R.string.gesture_swipe_up
        Gesture.SWIPE_DOWN -> R.string.gesture_swipe_down
        Gesture.DOUBLE_TAP -> R.string.gesture_double_tap
        Gesture.PINCH_IN -> R.string.gesture_pinch_in
        Gesture.PINCH_OUT -> R.string.gesture_pinch_out
        Gesture.TWO_FINGER_SWIPE_UP -> R.string.gesture_two_finger_swipe_up
        Gesture.TWO_FINGER_SWIPE_DOWN -> R.string.gesture_two_finger_swipe_down
        Gesture.SWIPE_LEFT -> R.string.gesture_swipe_left
        Gesture.SWIPE_RIGHT -> R.string.gesture_swipe_right
        Gesture.DOUBLE_TAP_SWIPE_UP -> R.string.gesture_double_tap_swipe_up
        Gesture.DOUBLE_TAP_SWIPE_DOWN -> R.string.gesture_double_tap_swipe_down
        Gesture.TWO_FINGER_ROTATE_CW -> R.string.gesture_rotate_cw
        Gesture.TWO_FINGER_ROTATE_CCW -> R.string.gesture_rotate_ccw
    }

    @StringRes
    fun of(action: GestureAction): Int = when (action) {
        GestureAction.NONE -> R.string.action_none
        GestureAction.SEARCH_QUERY -> R.string.action_open_search
        GestureAction.OPEN_DRAWER -> R.string.action_open_drawer
        GestureAction.EXPAND_NOTIFICATIONS -> R.string.action_expand_notifications
        GestureAction.OPEN_SETTINGS -> R.string.action_open_settings
        GestureAction.LOCK_SCREEN -> R.string.action_lock_screen
        GestureAction.DEFAULT_PAGE -> R.string.action_default_page
        GestureAction.OPEN_SEARCH -> R.string.action_open_search
        GestureAction.RECENTS -> R.string.action_recents
        GestureAction.QUICK_SETTINGS -> R.string.action_quick_settings
        GestureAction.ASSISTANT -> R.string.action_assistant
        GestureAction.LAUNCH_APP -> R.string.action_launch_app
        GestureAction.LAUNCH_SHORTCUT -> R.string.action_launch_shortcut
        GestureAction.TOGGLE_STATUS_BAR -> R.string.action_toggle_status_bar
        GestureAction.TOGGLE_DOCK -> R.string.action_toggle_dock
        GestureAction.JUMP_TO_PAGE -> R.string.action_jump_to_page
        GestureAction.DESKTOP_PREVIEWS -> R.string.action_desktop_previews
        GestureAction.OPEN_FEED -> R.string.action_open_feed
        GestureAction.VOICE_SEARCH -> R.string.action_voice_search
        GestureAction.LAUNCH_INTENT -> R.string.action_launch_shortcut
    }

    @StringRes
    fun of(speed: AnimationSpeed): Int = when (speed) {
        AnimationSpeed.SLOW -> R.string.anim_speed_slow
        AnimationSpeed.DEFAULT -> R.string.anim_speed_default
        AnimationSpeed.FAST -> R.string.anim_speed_fast
        AnimationSpeed.FASTER -> R.string.anim_speed_faster
    }

    @StringRes
    fun of(animation: LaunchAnimation): Int = when (animation) {
        LaunchAnimation.SYSTEM -> R.string.anim_system
        LaunchAnimation.ADAPTIVE_ICON -> R.string.anim_adaptive_icon
        LaunchAnimation.CIRCLE -> R.string.anim_circle
        LaunchAnimation.REVEAL -> R.string.anim_reveal
        LaunchAnimation.SLIDE_UP -> R.string.anim_slide_up
        LaunchAnimation.ZOOM -> R.string.anim_zoom
        LaunchAnimation.BLINK -> R.string.anim_blink
        LaunchAnimation.SLIDE_LEFT -> R.string.anim_slide_left
    }

    // LIGHT means the light-bar appearance flag (dark icons) and vice versa;
    // labels speak the user's language, not the flag's.
    @StringRes
    fun of(icons: StatusBarIcons): Int = when (icons) {
        StatusBarIcons.AUTO -> R.string.status_icons_auto
        StatusBarIcons.LIGHT -> R.string.status_icons_dark
        StatusBarIcons.DARK -> R.string.status_icons_light
    }

    @StringRes
    fun of(chip: com.supernova.launcher.search.SearchChip): Int = when (chip) {
        com.supernova.launcher.search.SearchChip.PLAY_STORE -> R.string.chip_play_store
        com.supernova.launcher.search.SearchChip.MAPS -> R.string.chip_maps
        com.supernova.launcher.search.SearchChip.YOUTUBE -> R.string.chip_youtube
        com.supernova.launcher.search.SearchChip.WIKIPEDIA -> R.string.chip_wikipedia
        com.supernova.launcher.search.SearchChip.SPOTIFY -> R.string.chip_spotify
        com.supernova.launcher.search.SearchChip.GOOGLE -> R.string.chip_google
    }

    @StringRes
    fun of(row: PopupExtraRow): Int = when (row) {
        PopupExtraRow.HIDE -> R.string.hide_app
        PopupExtraRow.UNINSTALL -> R.string.uninstall
        PopupExtraRow.PLAY_STORE -> R.string.chip_play_store
        PopupExtraRow.CLOSE_APP -> R.string.popup_row_close_app
        PopupExtraRow.RELAUNCH -> R.string.popup_row_relaunch
        PopupExtraRow.SAVE_APK -> R.string.popup_row_save_apk
    }

    @StringRes
    fun of(shape: IconShape): Int = when (shape) {
        IconShape.SYSTEM -> R.string.shape_system
        IconShape.CIRCLE -> R.string.shape_circle
        IconShape.SQUIRCLE -> R.string.shape_squircle
        IconShape.ROUNDED_SQUARE -> R.string.shape_rounded_square
        IconShape.SQUARE -> R.string.shape_square
        IconShape.TEARDROP -> R.string.shape_teardrop
        IconShape.HEXAGON -> R.string.shape_hexagon
        IconShape.PEBBLE -> R.string.shape_pebble
        IconShape.VESSEL -> R.string.shape_vessel
        IconShape.PENTAGON -> R.string.shape_pentagon
        IconShape.HEXAGON_ROUNDED -> R.string.shape_hexagon_rounded
        IconShape.HEPTAGON -> R.string.shape_heptagon
        IconShape.OCTAGON -> R.string.shape_octagon
        IconShape.CROP -> R.string.shape_crop
        IconShape.FLOWER -> R.string.shape_flower
        IconShape.PLATFORM -> R.string.shape_platform
        IconShape.CUSTOM -> R.string.shape_custom
    }

    @StringRes
    fun of(cut: CornerCut): Int = when (cut) {
        CornerCut.ROUND -> R.string.corner_round
        CornerCut.SQUIRCLE -> R.string.corner_squircle
        CornerCut.CUT -> R.string.corner_cut
    }

    @StringRes
    fun of(style: BadgeStyle): Int = when (style) {
        BadgeStyle.DOTS -> R.string.badge_style_dots
        BadgeStyle.NUMERIC -> R.string.badge_style_numeric
        BadgeStyle.DYNAMIC -> R.string.badge_style_dynamic
    }

    @StringRes
    fun of(mode: ThemeMode): Int = when (mode) {
        ThemeMode.SYSTEM -> R.string.theme_system
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
        ThemeMode.AUTO -> R.string.theme_auto
        ThemeMode.SCHEDULE -> R.string.theme_schedule
    }

    @StringRes
    fun of(accent: AccentColor): Int = when (accent) {
        AccentColor.BLUE -> R.string.accent_blue
        AccentColor.INDIGO -> R.string.accent_indigo
        AccentColor.PURPLE -> R.string.accent_purple
        AccentColor.PINK -> R.string.accent_pink
        AccentColor.RED -> R.string.accent_red
        AccentColor.ORANGE -> R.string.accent_orange
        AccentColor.AMBER -> R.string.accent_amber
        AccentColor.GREEN -> R.string.accent_green
        AccentColor.TEAL -> R.string.accent_teal
        AccentColor.CYAN -> R.string.accent_cyan
    }

    @StringRes
    fun of(position: BadgePosition): Int = when (position) {
        BadgePosition.TOP_RIGHT -> R.string.pos_top_right
        BadgePosition.TOP_LEFT -> R.string.pos_top_left
        BadgePosition.BOTTOM_RIGHT -> R.string.pos_bottom_right
        BadgePosition.BOTTOM_LEFT -> R.string.pos_bottom_left
    }

    @StringRes
    fun of(style: FolderPreviewStyle): Int = when (style) {
        FolderPreviewStyle.GRID -> R.string.preview_grid
        FolderPreviewStyle.STACK -> R.string.preview_stack
        FolderPreviewStyle.FAN -> R.string.preview_fan
        FolderPreviewStyle.LINE -> R.string.preview_line
        FolderPreviewStyle.RADIAL -> R.string.preview_radial
        FolderPreviewStyle.RADIAL_DENSE -> R.string.preview_radial_dense
        FolderPreviewStyle.GRID_3X3 -> R.string.preview_grid_3x3
    }

    @StringRes
    fun of(mode: FolderMode): Int = when (mode) {
        FolderMode.WINDOW -> R.string.folder_mode_window
        FolderMode.IMMERSIVE -> R.string.folder_mode_immersive
    }

    @StringRes
    fun of(scroll: FolderScroll): Int = when (scroll) {
        FolderScroll.VERTICAL -> R.string.folder_scroll_vertical
        FolderScroll.HORIZONTAL -> R.string.folder_scroll_horizontal
    }

    @StringRes
    fun of(bar: DrawerTabBar): Int = when (bar) {
        DrawerTabBar.TOP -> R.string.tab_bar_top
        DrawerTabBar.BOTTOM -> R.string.tab_bar_bottom
        DrawerTabBar.NONE -> R.string.tab_bar_none
    }

    @StringRes
    fun of(style: com.supernova.launcher.ui.DrawerTabStyle): Int = when (style) {
        com.supernova.launcher.ui.DrawerTabStyle.CLASSIC -> R.string.tab_style_classic
        com.supernova.launcher.ui.DrawerTabStyle.BUBBLE -> R.string.tab_style_bubble
    }

    @StringRes
    fun of(category: SmartCategory): Int = when (category) {
        SmartCategory.GAMES -> R.string.category_games
        SmartCategory.ENTERTAINMENT -> R.string.category_entertainment
        SmartCategory.IMAGE -> R.string.category_image
        SmartCategory.SOCIAL -> R.string.category_social
        SmartCategory.NEWS -> R.string.category_news
        SmartCategory.MAPS -> R.string.category_maps
        SmartCategory.PRODUCTIVITY -> R.string.category_productivity
        SmartCategory.FINANCE -> R.string.category_finance
    }

    @StringRes
    fun of(style: DrawerStyle): Int = when (style) {
        DrawerStyle.VERTICAL_GRID -> R.string.style_vertical_grid
        DrawerStyle.PAGED_GRID -> R.string.style_paged_grid
        DrawerStyle.VERTICAL_LIST -> R.string.style_vertical_list
    }

    @StringRes
    fun of(transition: com.supernova.launcher.ui.DrawerTransition): Int = when (transition) {
        com.supernova.launcher.ui.DrawerTransition.SLIDE_UP -> R.string.transition_slide_up
        com.supernova.launcher.ui.DrawerTransition.ZOOM -> R.string.transition_zoom
        com.supernova.launcher.ui.DrawerTransition.FADE -> R.string.transition_fade
    }

    fun of(sort: DrawerSort): Int = when (sort) {
        DrawerSort.ALPHA -> R.string.sort_alpha
        DrawerSort.ALPHA_REVERSE -> R.string.sort_alpha_reverse
        DrawerSort.INSTALL_DATE -> R.string.sort_install
        DrawerSort.MOST_USED -> R.string.sort_most_used
    }

    @StringRes
    fun of(style: DockBgStyle): Int = when (style) {
        DockBgStyle.NONE -> R.string.dock_bg_none
        DockBgStyle.PILL -> R.string.dock_bg_pill
        DockBgStyle.ROUNDED -> R.string.dock_bg_rounded
        DockBgStyle.SQUARE -> R.string.dock_bg_square
    }

    @StringRes
    fun of(style: IndicatorStyle): Int = when (style) {
        IndicatorStyle.NONE -> R.string.indicator_none
        IndicatorStyle.DOTS -> R.string.indicator_dots
        IndicatorStyle.CIRCLE -> R.string.indicator_circle
        IndicatorStyle.LINE -> R.string.indicator_line
    }

    @StringRes
    fun of(orientation: HomeOrientation): Int = when (orientation) {
        HomeOrientation.DEFAULT -> R.string.orientation_default
        HomeOrientation.AUTO_ROTATE -> R.string.orientation_auto_rotate
        HomeOrientation.PORTRAIT -> R.string.orientation_portrait
        HomeOrientation.LANDSCAPE -> R.string.orientation_landscape
    }

    @StringRes
    fun of(mode: WallpaperScrollMode): Int = when (mode) {
        WallpaperScrollMode.ON -> R.string.mode_on
        WallpaperScrollMode.OFF -> R.string.mode_off
        WallpaperScrollMode.REVERSE -> R.string.wallpaper_scroll_reverse
    }

    @StringRes
    fun of(mode: HideClock): Int = when (mode) {
        HideClock.NEVER -> R.string.hide_clock_never
        HideClock.HOME_PAGE -> R.string.hide_clock_home_page
        HideClock.DESKTOP -> R.string.hide_clock_desktop
        HideClock.EVERYWHERE -> R.string.hide_clock_everywhere
    }

    @StringRes
    fun of(glass: IconGlass): Int = when (glass) {
        IconGlass.OFF -> R.string.mode_off
        IconGlass.FROSTED -> R.string.icon_glass_frosted
        IconGlass.CLEAR -> R.string.icon_glass_clear
        IconGlass.SMOKED -> R.string.icon_glass_smoked
    }

    @StringRes
    fun of(mode: ReshapeLegacy): Int = when (mode) {
        ReshapeLegacy.OFF -> R.string.mode_off
        ReshapeLegacy.AUTO -> R.string.reshape_legacy_auto
        ReshapeLegacy.ON -> R.string.reshape_legacy_on
    }

    @StringRes
    fun of(style: com.supernova.launcher.ui.PopupMenuStyle): Int = when (style) {
        com.supernova.launcher.ui.PopupMenuStyle.BLOCK -> R.string.popup_style_block
        com.supernova.launcher.ui.PopupMenuStyle.PILLS -> R.string.popup_style_pills
        com.supernova.launcher.ui.PopupMenuStyle.ANDROID12 -> R.string.popup_style_android12
    }

    @StringRes
    fun of(mode: PaletteSourceMode): Int = when (mode) {
        PaletteSourceMode.SYSTEM -> R.string.palette_system
        PaletteSourceMode.WALLPAPER -> R.string.palette_wallpaper
        PaletteSourceMode.CUSTOM -> R.string.palette_custom
    }

    @StringRes
    fun of(surface: SurfaceId): Int = when (surface) {
        SurfaceId.DRAWER -> R.string.settings_section_drawer
        SurfaceId.DOCK -> R.string.settings_section_dock
        SurfaceId.FOLDER -> R.string.settings_section_folders
        SurfaceId.POPUP -> R.string.style_popup
        SurfaceId.SEARCH_BAR -> R.string.style_search_bar
        SurfaceId.SETTINGS -> R.string.settings
    }

    @StringRes
    fun of(effect: ScrollEffect): Int = when (effect) {
        ScrollEffect.NONE -> R.string.effect_none
        ScrollEffect.SIMPLE -> R.string.effect_simple
        ScrollEffect.ZOOM -> R.string.effect_zoom
        ScrollEffect.ACCORDION -> R.string.effect_accordion
        ScrollEffect.FLIP -> R.string.effect_flip
        ScrollEffect.DEPTH -> R.string.effect_depth
        ScrollEffect.CUBE -> R.string.effect_cube
        ScrollEffect.CARD_STACK -> R.string.effect_card_stack
        ScrollEffect.TABLET -> R.string.effect_tablet
        ScrollEffect.REVOLVING_DOOR -> R.string.effect_revolving_door
        ScrollEffect.FLIP_VERTICAL -> R.string.effect_flip_vertical
        ScrollEffect.IN_AND_OUT -> R.string.effect_in_and_out
        ScrollEffect.GLASS -> R.string.effect_glass
        ScrollEffect.WIPE -> R.string.effect_wipe
        ScrollEffect.ZOOM_FADE -> R.string.effect_zoom_fade
        ScrollEffect.THROW -> R.string.effect_throw
    }

    @StringRes
    fun of(placement: SearchBarPlacement): Int = when (placement) {
        SearchBarPlacement.NONE -> R.string.search_bar_none
        SearchBarPlacement.DOCK_ABOVE -> R.string.search_bar_above_dock
        SearchBarPlacement.DOCK_BELOW -> R.string.search_bar_below_dock
        SearchBarPlacement.PERSISTENT -> R.string.search_bar_persistent
        SearchBarPlacement.WIDGET -> R.string.search_bar_widget
    }

    @StringRes
    fun of(font: LabelFont): Int = when (font) {
        LabelFont.DEFAULT -> R.string.font_default
        LabelFont.CONDENSED -> R.string.font_condensed
        LabelFont.SERIF -> R.string.font_serif
        LabelFont.MONOSPACE -> R.string.font_monospace
    }
}
