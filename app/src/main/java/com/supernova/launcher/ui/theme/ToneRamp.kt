package com.supernova.launcher.ui.theme

// Pure-Kotlin tonal-ramp generation for API 29/30 and CUSTOM seeds, where the
// real Material You system ramps aren't available. HSL lightness ramps are
// not HCT-exact but are dependency-free and close enough for launcher chrome;
// swapping in a better color science later changes nothing outside this file.
object ToneRamp {

    // Material's standard tone stops; parallel to the system_accent1_* shades
    // (shade = (100 - tone) * 10).
    val TONES = intArrayOf(0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 95, 99, 100)

    fun indexOfTone(tone: Int): Int {
        var best = 0
        var bestDistance = Int.MAX_VALUE
        for (i in TONES.indices) {
            val d = kotlin.math.abs(TONES[i] - tone)
            if (d < bestDistance) {
                bestDistance = d
                best = i
            }
        }
        return best
    }

    // One ramp: the seed's hue at each tone stop, saturation damped by
    // saturationFactor (1 = full chroma, ~0.12 = neutral).
    fun ramp(seed: Int, saturationFactor: Float): IntArray {
        val hsl = rgbToHsl(seed)
        val hue = hsl[0]
        val saturation = (hsl[1] * saturationFactor).coerceIn(0f, 1f)
        return IntArray(TONES.size) { i ->
            val tone = TONES[i]
            when (tone) {
                0 -> 0xFF000000.toInt()
                100 -> 0xFFFFFFFF.toInt()
                else -> hslToRgb(hue, saturation, tone / 100f)
            }
        }
    }

    fun rgbToHsl(color: Int): FloatArray {
        val r = (color shr 16 and 0xFF) / 255f
        val g = (color shr 8 and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        val lightness = (max + min) / 2f
        if (delta == 0f) return floatArrayOf(0f, 0f, lightness)
        val saturation = delta / (1f - kotlin.math.abs(2f * lightness - 1f))
        val hue = when (max) {
            r -> 60f * (((g - b) / delta) % 6f)
            g -> 60f * ((b - r) / delta + 2f)
            else -> 60f * ((r - g) / delta + 4f)
        }.let { if (it < 0) it + 360f else it }
        return floatArrayOf(hue, saturation.coerceIn(0f, 1f), lightness)
    }

    fun hslToRgb(hue: Float, saturation: Float, lightness: Float): Int {
        val c = (1f - kotlin.math.abs(2f * lightness - 1f)) * saturation
        val x = c * (1f - kotlin.math.abs((hue / 60f) % 2f - 1f))
        val m = lightness - c / 2f
        val (r1, g1, b1) = when {
            hue < 60f -> Triple(c, x, 0f)
            hue < 120f -> Triple(x, c, 0f)
            hue < 180f -> Triple(0f, c, x)
            hue < 240f -> Triple(0f, x, c)
            hue < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val r = ((r1 + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
        val g = ((g1 + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
        val b = ((b1 + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
        return 0xFF shl 24 or (r shl 16) or (g shl 8) or b
    }
}
