package com.supernova.launcher.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Per-corner curve style for the CUSTOM icon shape.
enum class CornerCut {
    ROUND,
    SQUIRCLE,
    CUT;

    companion object {
        fun fromName(name: String?): CornerCut =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: ROUND
    }
}

// The CUSTOM icon shape's configuration, persisted as JSON in the
// iconShapeCustom pref. Corner order is [TL, TR, BR, BL]; radii are percents
// of the icon's half-size. Cuts are stored as strings so a single bad value
// degrades to ROUND instead of failing the whole document.
@Serializable
data class CustomShapeSpec(
    val radii: List<Int> = List(CORNERS) { DEFAULT_RADIUS },
    val cuts: List<String> = List(CORNERS) { CornerCut.ROUND.name }
) {
    fun cutAt(index: Int): CornerCut = CornerCut.fromName(cuts.getOrNull(index))

    fun radiusAt(index: Int): Int =
        (radii.getOrNull(index) ?: DEFAULT_RADIUS).coerceIn(0, 100)

    fun encode(): String = json.encodeToString(serializer(), sanitized())

    // Missing corners are filled with defaults and every value is clamped, so
    // downstream consumers can index 0..3 blindly.
    fun sanitized(): CustomShapeSpec = CustomShapeSpec(
        radii = List(CORNERS, ::radiusAt),
        cuts = List(CORNERS) { cutAt(it).name }
    )

    companion object {
        const val CORNERS = 4
        const val DEFAULT_RADIUS = 25

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        // Tolerant decode: blank or junk input yields the sensible default
        // (25% rounded corners) rather than an error.
        fun decode(raw: String): CustomShapeSpec {
            if (raw.isBlank()) return CustomShapeSpec()
            return runCatching { json.decodeFromString(serializer(), raw) }
                .getOrElse { return CustomShapeSpec() }
                .sanitized()
        }
    }
}
