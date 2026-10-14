package com.supernova.launcher.ui

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Where the desktop search bar sits. WIDGET is declared ahead of its consumer
// (the workspace-widget placement arrives in a later phase) so configs written
// then decode now; settings never offers it yet.
enum class SearchBarPlacement {
    NONE, DOCK_ABOVE, DOCK_BELOW, PERSISTENT, WIDGET;

    companion object {
        fun fromName(name: String): SearchBarPlacement =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: NONE
    }
}

// Action chips drawn on the right edge of the desktop bar, rendered in the
// order they appear in the config's chips list.
enum class SearchBarChip {
    VOICE, ASSISTANT, DATE, PLAY_STORE, APP_DRAWER;

    companion object {
        fun fromName(name: String): SearchBarChip? =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

// The mark on the left edge of the bar: the magnifier (the pre-logo default),
// the Supernova spark, or nothing.
enum class SearchBarLogo {
    SEARCH, SUPERNOVA, NONE;

    companion object {
        fun fromName(name: String): SearchBarLogo =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: SEARCH
    }
}

@Serializable
data class DesktopSearchBarConfig(
    // SearchBarPlacement name; tolerated free-form, unknown decodes as NONE.
    val placement: String = "NONE",
    // Pill corner radius in dp (0..22; 22 on the 44dp bar = full pill).
    val radius: Int = 22,
    // Background opacity 0..1.
    val alpha: Float = 1f,
    // Outline-only rendering: transparent fill, text-colored stroke.
    val wireframe: Boolean = false,
    val shadow: Boolean = false,
    // SearchBarChip names; unknown entries are ignored.
    val chips: List<String> = emptyList(),
    // SearchBarLogo name; unknown decodes as SEARCH (the old fixed mark).
    val logo: String = "SEARCH",
    // Tint the logo with the bar's text color; false uses the accent.
    val logoMonochrome: Boolean = true,
    // The "Search" text beside the logo. The fresh bar has none.
    val hint: Boolean = true
) {
    // These field defaults are what a config that leaves a field out has
    // always meant, so a bar set up before a field existed keeps its look. A
    // fresh install starts on FRESH instead.
    companion object {
        // Half the bar's height: rounder than this is the same pill. A
        // radius saved when the bar was 52dp tall (up to 26) draws as it.
        const val MAX_RADIUS = 22

        // The bar as a fresh install shows it: under the dock, with a
        // shadow, its own logo in colour, the Assistant on the right and no
        // text.
        val FRESH = DesktopSearchBarConfig(
            placement = SearchBarPlacement.DOCK_BELOW.name,
            shadow = true,
            chips = listOf(SearchBarChip.ASSISTANT.name),
            logo = SearchBarLogo.SUPERNOVA.name,
            logoMonochrome = false,
            hint = false
        )
    }
}

@Serializable
data class DrawerSearchBarConfig(
    // Whether the drawer's search field is shown at all. Kept alongside
    // placement for compat; HIDDEN placement and enabled=false mean the same.
    val enabled: Boolean = true,
    // Tapping the drawer field opens the global overlay instead of focusing
    // the local filter.
    val globalOnTap: Boolean = false,
    // DrawerBarPlacement name; "" derives from enabled (TOP or HIDDEN).
    val placement: String = "",
    // Box corner radius in dp; null keeps the stock outlined-box radius.
    // 22 on the 44dp field is a full pill, which is the default look.
    val radius: Int? = 22,
    // Solid filled box instead of the stock outline.
    val filled: Boolean = true,
    // SearchBarLogo name for the field's start icon; NONE = no icon (stock).
    val logo: String = "SEARCH",
    // Tint the start icon with the drawer text color; false uses the accent.
    val logoMonochrome: Boolean = true
) {
    companion object {
        // Half the field's height; a radius saved against the 52dp field
        // draws as the same pill.
        const val MAX_RADIUS = 22
    }
}

// Where the drawer's search field sits (C7).
enum class DrawerBarPlacement {
    TOP, BOTTOM, HIDDEN;

    companion object {
        fun fromConfig(config: DrawerSearchBarConfig): DrawerBarPlacement {
            entries.firstOrNull { it.name.equals(config.placement, ignoreCase = true) }?.let { return it }
            return if (config.enabled) TOP else HIDDEN
        }
    }
}

// The whole search-bar document behind the searchBarConfig pref. "" decodes to
// the defaults: no desktop bar, and a drawer field that is visible, locally
// filtering, and drawn as a filled pill.
@Serializable
data class SearchBarConfig(
    val desktop: DesktopSearchBarConfig = DesktopSearchBarConfig(),
    val drawer: DrawerSearchBarConfig = DrawerSearchBarConfig()
) {

    fun encode(): String = if (this == DEFAULT) "" else json.encodeToString(serializer(), this)

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

        val DEFAULT = SearchBarConfig()

        fun decode(raw: String): SearchBarConfig =
            if (raw.isBlank()) DEFAULT
            else runCatching { json.decodeFromString(serializer(), raw) }.getOrDefault(DEFAULT)
    }
}
