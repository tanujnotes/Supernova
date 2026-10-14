package com.supernova.launcher.ui.theme

import android.app.WallpaperManager
import android.content.Context
import android.os.Build

enum class PaletteSourceMode {
    SYSTEM,
    WALLPAPER,
    CUSTOM;

    companion object {
        fun fromName(name: String): PaletteSourceMode =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: SYSTEM
    }
}

// Thirteen tones per role, indexed parallel to ToneRamp.TONES.
class TonalPalette(
    val primary: IntArray,
    val neutral: IntArray,
    val neutralVariant: IntArray
) {
    fun tone(role: PaletteRole, tone: Int): Int {
        val ramp = when (role) {
            PaletteRole.PRIMARY -> primary
            PaletteRole.NEUTRAL -> neutral
            PaletteRole.NEUTRAL_VARIANT -> neutralVariant
        }
        return ramp[ToneRamp.indexOfTone(tone)]
    }
}

object PaletteFactory {

    // system_accent1_<shade> shades parallel to ToneRamp.TONES: tone 0 is
    // shade 1000, tone 100 is shade 0.
    private val accentShadeIds = intArrayOf(
        android.R.color.system_accent1_1000, android.R.color.system_accent1_900,
        android.R.color.system_accent1_800, android.R.color.system_accent1_700,
        android.R.color.system_accent1_600, android.R.color.system_accent1_500,
        android.R.color.system_accent1_400, android.R.color.system_accent1_300,
        android.R.color.system_accent1_200, android.R.color.system_accent1_100,
        android.R.color.system_accent1_50, android.R.color.system_accent1_10,
        android.R.color.system_accent1_0
    )
    private val neutralShadeIds = intArrayOf(
        android.R.color.system_neutral1_1000, android.R.color.system_neutral1_900,
        android.R.color.system_neutral1_800, android.R.color.system_neutral1_700,
        android.R.color.system_neutral1_600, android.R.color.system_neutral1_500,
        android.R.color.system_neutral1_400, android.R.color.system_neutral1_300,
        android.R.color.system_neutral1_200, android.R.color.system_neutral1_100,
        android.R.color.system_neutral1_50, android.R.color.system_neutral1_10,
        android.R.color.system_neutral1_0
    )
    private val neutralVariantShadeIds = intArrayOf(
        android.R.color.system_neutral2_1000, android.R.color.system_neutral2_900,
        android.R.color.system_neutral2_800, android.R.color.system_neutral2_700,
        android.R.color.system_neutral2_600, android.R.color.system_neutral2_500,
        android.R.color.system_neutral2_400, android.R.color.system_neutral2_300,
        android.R.color.system_neutral2_200, android.R.color.system_neutral2_100,
        android.R.color.system_neutral2_50, android.R.color.system_neutral2_10,
        android.R.color.system_neutral2_0
    )

    // fallbackSeed anchors WALLPAPER (no colors reported — some live
    // wallpapers) and CUSTOM (unparseable seed) so resolution never fails.
    fun resolve(context: Context, mode: PaletteSourceMode, seedHex: String, fallbackSeed: Int): TonalPalette =
        when (mode) {
            PaletteSourceMode.SYSTEM ->
                if (Build.VERSION.SDK_INT >= 31) fromSystem(context)
                else fromSeed(wallpaperSeed(context) ?: fallbackSeed)
            PaletteSourceMode.WALLPAPER -> fromSeed(wallpaperSeed(context) ?: fallbackSeed)
            PaletteSourceMode.CUSTOM -> fromSeed(parseSeed(seedHex) ?: fallbackSeed)
        }

    private fun fromSystem(context: Context): TonalPalette = TonalPalette(
        primary = IntArray(accentShadeIds.size) { context.getColor(accentShadeIds[it]) },
        neutral = IntArray(neutralShadeIds.size) { context.getColor(neutralShadeIds[it]) },
        neutralVariant = IntArray(neutralVariantShadeIds.size) { context.getColor(neutralVariantShadeIds[it]) }
    )

    fun fromSeed(seed: Int): TonalPalette = TonalPalette(
        primary = ToneRamp.ramp(seed, 1f),
        neutral = ToneRamp.ramp(seed, 0.12f),
        neutralVariant = ToneRamp.ramp(seed, 0.25f)
    )

    private fun wallpaperSeed(context: Context): Int? = runCatching {
        WallpaperManager.getInstance(context)
            .getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
            ?.primaryColor?.toArgb()
    }.getOrNull()

    private fun parseSeed(hex: String): Int? {
        val digits = hex.removePrefix("#")
        if (digits.length != 6) return null
        val rgb = digits.toLongOrNull(16) ?: return null
        return (0xFF shl 24) or rgb.toInt()
    }
}
