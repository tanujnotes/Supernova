package com.supernova.launcher.ui.theme

import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.pow

// Minimum contrast: a surface's text stays at least so many tones from
// the surface, the "Δ" the setting shows. Tone is CIELAB lightness (L*, the
// tone of Material's HCT), 0 black to 100 white. Text too close keeps its
// hue and saturation and only changes shade, lighter or darker.
object Contrast {

    fun tone(argb: Int): Double {
        val y = 0.2126 * linear(argb shr 16 and 0xFF) +
            0.7152 * linear(argb shr 8 and 0xFF) +
            0.0722 * linear(argb and 0xFF)
        return if (y <= 216.0 / 24389.0) y * 24389.0 / 27.0 else 116.0 * cbrt(y) - 16.0
    }

    // text as it is when it already stands `minimum` tones clear of the
    // surface (its alpha ignored); else the nearest shade that does, on the
    // side of the surface it was on if there is room there, else on the
    // other, else as far as the roomier side goes.
    fun ensure(text: Int, surface: Int, minimum: Int?): Int {
        if (minimum == null || minimum <= 0) return text
        val surfaceTone = tone(surface)
        val textTone = tone(text)
        if (abs(textTone - surfaceTone) >= minimum) return text
        val lighter = surfaceTone + minimum
        val darker = surfaceTone - minimum
        val target = when {
            textTone >= surfaceTone && lighter <= 100.0 -> lighter
            textTone < surfaceTone && darker >= 0.0 -> darker
            lighter <= 100.0 -> lighter
            darker >= 0.0 -> darker
            surfaceTone < 50.0 -> 100.0
            else -> 0.0
        }
        return withTone(text, target)
    }

    // The same hue and saturation at the HSL lightness whose tone is target:
    // tone only rises with lightness, so halving the range finds it.
    fun withTone(argb: Int, target: Double): Int {
        val hsl = ToneRamp.rgbToHsl(argb)
        var low = 0f
        var high = 1f
        repeat(24) {
            val mid = (low + high) / 2f
            if (tone(ToneRamp.hslToRgb(hsl[0], hsl[1], mid)) < target) low = mid else high = mid
        }
        // The side of the search that meets the target, so the result never
        // falls a rounding step short of the contrast asked for.
        val lightness = if (target >= tone(argb)) high else low
        val rgb = ToneRamp.hslToRgb(hsl[0], hsl[1], lightness)
        return (argb and 0xFF000000.toInt()) or (rgb and 0x00FFFFFF)
    }

    private fun linear(channel: Int): Double {
        val v = channel / 255.0
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }
}
