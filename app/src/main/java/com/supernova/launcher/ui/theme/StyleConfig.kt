package com.supernova.launcher.ui.theme

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Surfaces the style engine can recolor. SEARCH_BAR and POPUP are declared
// ahead of their consumers (phases 4 and 6) so backups written then decode
// now and vice versa.
@Serializable
enum class SurfaceId { DRAWER, DOCK, FOLDER, POPUP, SEARCH_BAR, SETTINGS }

// Tonal-palette roles, matching Material's accent1/neutral1/neutral2 ramps.
@Serializable
enum class PaletteRole { PRIMARY, NEUTRAL, NEUTRAL_VARIANT }

// One color slot: a tone from the active palette, a fixed ARGB, or default
// (fall through to the legacy pref, then the theme).
@Serializable
data class ColorSpec(
    val role: PaletteRole? = null,
    val tone: Int? = null,
    // "#AARRGGBB" or "#RRGGBB"
    val fixed: String? = null
) {
    val isDefault: Boolean get() = fixed == null && (role == null || tone == null)
}

@Serializable
data class SurfaceStyle(
    val surface: ColorSpec = ColorSpec(),
    val text: ColorSpec = ColorSpec(),
    val accent: ColorSpec = ColorSpec(),
    // null = keep the surface's legacy/default opacity.
    val alpha: Float? = null,
    // Corner radii in dp, [topLeft, topRight, bottomRight, bottomLeft];
    // null = the surface's built-in shape.
    val cornerRadii: List<Float>? = null,
    // Per-corner cut styles (CornerCut names, same order); null = all ROUND.
    // Only meaningful alongside cornerRadii.
    val cornerCuts: List<String>? = null,
    // Minimum contrast: the least tone gap (Contrast) the text keeps
    // from the surface, shading itself to hold it; null = off.
    val minContrast: Int? = null
) {
    fun isEmpty(): Boolean =
        surface.isDefault && text.isDefault && accent.isDefault &&
            alpha == null && cornerRadii == null && cornerCuts == null && minContrast == null
}

// The whole per-surface style document, persisted as one JSON pref
// (StoreKeys.styleConfig). An empty config must reproduce the pre-style-engine
// rendering exactly.
@Serializable
data class StyleConfig(
    val surfaces: Map<SurfaceId, SurfaceStyle> = emptyMap()
) {

    fun styleFor(id: SurfaceId): SurfaceStyle = surfaces[id] ?: EMPTY_SURFACE

    fun with(id: SurfaceId, style: SurfaceStyle): StyleConfig =
        if (style.isEmpty()) StyleConfig(surfaces - id) else StyleConfig(surfaces + (id to style))

    fun encode(): String = if (surfaces.isEmpty()) "" else json.encodeToString(serializer(), this)

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

        val EMPTY = StyleConfig()
        private val EMPTY_SURFACE = SurfaceStyle()

        fun decode(raw: String): StyleConfig =
            if (raw.isBlank()) EMPTY
            else runCatching { json.decodeFromString(serializer(), raw) }.getOrDefault(EMPTY)
    }
}
