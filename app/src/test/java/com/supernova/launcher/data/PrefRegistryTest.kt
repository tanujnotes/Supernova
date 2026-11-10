package com.supernova.launcher.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Pref is the one list of settings. These hold it to LauncherPrefs, and to
// what installs in the field already have on disk.
class PrefRegistryTest {

    private val fields: List<String> =
        LauncherPrefs.serializer().descriptor.let { d -> (0 until d.elementsCount).map(d::getElementName) }

    @Test
    fun `every LauncherPrefs field has one line in Pref`() {
        val registered = Pref.all.map { it.field }
        assertEquals("a setting listed twice", registered.size, registered.toSet().size)
        assertEquals(fields.toSet(), registered.toSet())
    }

    @Test
    fun `an empty store reads as the defaults`() {
        assertEquals(LauncherPrefs(), LauncherPreferences.decode(emptyPreferences()))
    }

    // Each key holding something other than its default, the same store has
    // to come back from a read and a write: a spec of the wrong type, or one
    // filling the wrong field, breaks it.
    @Test
    fun `every setting survives a read and a write`() {
        val defaults = mutablePreferencesOf().also { LauncherPreferences.writeAll(it, LauncherPrefs()) }
        val changed = mutablePreferencesOf()
        Pref.all.forEach { changed.putChanged(it, defaults) }

        val read = LauncherPreferences.decode(changed)
        val written = mutablePreferencesOf().also { LauncherPreferences.writeAll(it, read) }

        assertEquals(changed.asMap(), written.asMap())
        Pref.all.forEach { spec ->
            assertNotEquals("${spec.field} was left at its default", defaults[spec.key], written[spec.key])
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> MutablePreferences.putChanged(spec: PrefSpec<T>, defaults: Preferences) {
        val changed: Any = when (val default = checkNotNull(defaults[spec.key]) { "${spec.field} wasn't written" }) {
            is Boolean -> !default
            is String -> default + "x"
            is Set<*> -> setOf("x")
            // A step up, or down from a default that sits at the top of its range.
            is Int -> (spec as PrefSpec<Int>).clamped(default + 1).takeIf { it != default } ?: (default - 1)
            is Float -> (spec as PrefSpec<Float>).clamped(default + 0.1f).takeIf { it != default } ?: (default - 0.1f)
            else -> error("unexpected type for ${spec.field}")
        }
        this[spec.key] = changed as T
    }

    @Test
    fun `a number outside its range is held to it, read or written`() {
        val stored = mutablePreferencesOf(Pref.gridRows.key to 99, Pref.iconScale.key to 9f)
        val read = LauncherPreferences.decode(stored)
        assertEquals(GridLimits.DESKTOP_BIG, read.gridRows)
        assertEquals(2f, read.iconScale)

        val written = mutablePreferencesOf()
        LauncherPreferences.writeAll(written, LauncherPrefs(gridRows = 1, feedShortcutRows = 9))
        assertEquals(2, written[Pref.gridRows.key])
        assertEquals(3, written[Pref.feedShortcutRows.key])
    }

    // The keys and types version 0.1.8 stored its settings under. A key
    // renamed or retyped here is a setting every upgrading user loses, so
    // this list only ever grows by leaving it alone: new settings need no
    // entry.
    @Test
    fun `every key an earlier version stored is still read`() {
        val current = Pref.all.associate { it.key.name to it.key }
        STORED_BY_0_1_8.trim().split(Regex("\\s+")).forEach { entry ->
            val (name, type) = entry.split(':')
            val expected: Preferences.Key<*> = when (type) {
                "B" -> booleanPreferencesKey(name)
                "S" -> stringPreferencesKey(name)
                "I" -> intPreferencesKey(name)
                "F" -> floatPreferencesKey(name)
                "L" -> stringSetPreferencesKey(name)
                else -> error(type)
            }
            assertTrue("$name is no longer a setting", name in current)
            // Keys compare by name alone, so the type is checked by reading
            // a value of the old type back through the registry.
            val probe = mutablePreferencesOf()
            probe.putProbe(expected, type)
            LauncherPreferences.decode(probe)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun MutablePreferences.putProbe(key: Preferences.Key<*>, type: String) {
        when (type) {
            "B" -> this[key as Preferences.Key<Boolean>] = true
            "S" -> this[key as Preferences.Key<String>] = "x"
            "I" -> this[key as Preferences.Key<Int>] = 3
            "F" -> this[key as Preferences.Key<Float>] = 1f
            "L" -> this[key as Preferences.Key<Set<String>>] = setOf("x")
        }
    }

    private companion object {
        // name:type, B Boolean, S String, I Int, F Float, L set of strings.
        const val STORED_BY_0_1_8 = """
        grid_rows:I grid_cols:I dock_count:I icon_scale:F show_labels:B wallpaper_scroll:B
        wallpaper_scroll_mode:S icon_pack:S scroll_effect:S icon_shape:S icon_shape_custom:S
        subgrid:B badges:B badge_counts:B badge_style:S badge_apps:L hidden_apps:L
        gesture_bindings:L drawer_groups:L page_indicator_style:S page_indicator_auto_hide:B
        page_indicator_color:S infinite_scroll:B remove_empty_pages:B desktop_padding_h:I
        desktop_padding_v:I desktop_locked:B dock_enabled:B dock_pages:I dock_bg_style:S
        dock_bg_opacity:F dock_bg_behind_nav:B dock_bg_padding:B dock_labels:B dock_icon_scale:F
        widget_overlap:B overlap_when_placing:B widget_padding:I drawer_style:S drawer_columns:I
        drawer_rows:I drawer_auto_keyboard:B drawer_infinite_scroll:B drawer_page_effect:S
        drawer_transition:S drawer_tab_bar:S drawer_tab_style:S drawer_tabs_no_swipe:B
        drawer_frequent_row:B drawer_remember_position:B drawer_auto_close:B drawer_folders_first:B
        smart_folders:B smart_folders_dismissed:L drawer_icon_scale:F drawer_match_desktop:B
        drawer_bg_opacity:F drawer_fast_scroller:B drawer_pull_to_search:B drawer_swipe_indicator:B
        drawer_swipe_haptic:B swipe_down_haptic:B drawer_scrubber_color:S drawer_action_buttons:S
        drawer_isolate_tabs:B drawer_sort:S drawer_work_tab:B drawer_default_tab:S
        drawer_hide_all_tab:B label_size:I label_config:S label_color:S label_shadow:B
        drawer_labels:B folder_labels:B icon_normalize:B reshape_legacy:S icon_shadow:B
        icon_glass:S icon_swipe_indicator:B themed_icons:B themed_icons_autogen:B lock_method:S
        badge_color:S badge_position:S badge_scale:F badge_text_color:S badge_corner_radius:I
        badge_outline:B folder_preview_style:S folder_prioritize_first:B folder_columns:I
        folder_sort_az:B folder_close_on_launch:B folder_icon_scale:F folder_match_desktop:B
        folder_show_name:B folder_show_count:B folder_mode:S folder_shadow:B folder_scroll:S
        folder_icon_shape:S folder_icon_outline:B folder_clip_to_bg:B folder_match_window_color:B
        folder_plate_color:S folder_badges:B theme_mode:S theme_schedule_start:I
        theme_schedule_end:I accent_color:S drawer_bg_color:S folder_bg_color:S palette_source:S
        palette_seed:S style_config:S widget_corner_radius:I show_scrims:B wallpaper_zoom:B
        auto_add_apps:B label_font:S label_single_line:B dock_infinite_scroll:B
        dock_match_desktop:B dock_overlay:B dock_auto_close:B dock_remember_position:B
        dock_padding_h:I dock_padding_v:I search_web_provider:S search_contacts:B
        search_shortcuts:B search_folders:B search_window_mode:S search_apps_one_row:B
        search_contact_cards:B search_cards:B search_calculator:B search_hidden_apps:B
        search_history:B search_private_keyboard:B search_query_shortcuts:B search_bar_widget_id:I
        search_bar_widget_provider:S search_chips:S home_search:B home_button_action:S
        home_button_on_main_page:B search_bar_config:S popup_notifications:B popup_card_bg:B
        popup_group_shortcuts:B popup_action_icons:B popup_style:S popup_items_home:S
        popup_items_drawer:S drop_targets:B undo_remove:B disable_animations:B animation_speed:S
        launch_animation:S hide_status_bar:B dark_status_icons:S transparent_nav:B icon_wiggle:B
        transparent_status:B home_rotation:B home_orientation:S feed_enabled:B feed_edge_swipe:B
        feed_show_search:B feed_show_frequent:B feed_show_shortcuts:B feed_show_media:B
        feed_show_calendar:B feed_show_contacts:B feed_shortcut_rows:I labs_enabled:B
        labs_show_component:B labs_export_png:B labs_big_grid:B
"""
    }
}
