package com.supernova.launcher.data

// The glyph a generated themed icon draws for an icon that ships no
// monochrome layer, read off its picture: ARGB ints, row by row, covering
// the icon's whole 108dp canvas. The result is one alpha (0-255) per pixel.
object ThemedGlyph {

    // An app's: its plain silhouette when that reads as a glyph, else the
    // ink of a solid tile (a white logo on an orange square, whose silhouette
    // is the whole square), else null. A photo or a gradient tile has no
    // glyph to take, and the app keeps its own colours.
    fun forApp(pixels: IntArray, width: Int, height: Int): IntArray? {
        val plain = plainAlpha(pixels)
        if (reads(plain, width, height)) return plain
        return SilhouetteMask.of(pixels, width, height)?.takeIf { reads(it, width, height) }
    }

    // A shortcut's: the ink of a solid tile, else its silhouette.
    fun forShortcut(pixels: IntArray, width: Int, height: Int): IntArray =
        SilhouetteMask.of(pixels, width, height) ?: plainAlpha(pixels)

    // The share (0-1) of what an icon shape shows - the middle 72dp of the
    // 108dp canvas - that the glyph covers.
    fun coverage(alpha: IntArray, width: Int, height: Int): Float {
        val (left, top) = width / VISIBLE_INSET to height / VISIBLE_INSET
        val (right, bottom) = width - left to height - top
        var sum = 0L
        for (y in top until bottom) {
            for (x in left until right) sum += alpha[y * width + x]
        }
        val area = (right - left).toLong() * (bottom - top)
        return if (area == 0L) 0f else sum.toFloat() / (FULL * area)
    }

    // A picture with no glyph to take, redrawn in two colours: each pixel's
    // lightness, stretched over the picture's own range, runs from the
    // darker colour to the lighter, so its detail stays readable. Alpha is
    // kept; clear pixels stay clear.
    fun tinted(pixels: IntArray, first: Int, second: Int): IntArray {
        val (dark, light) = if (lightness(first) <= lightness(second)) first to second else second to first
        var low = FULL
        var high = 0
        pixels.forEach {
            if (it ushr ALPHA_SHIFT > 0) {
                low = minOf(low, lightness(it))
                high = maxOf(high, lightness(it))
            }
        }
        val span = maxOf(high - low, 1)
        return IntArray(pixels.size) { i ->
            val p = pixels[i]
            val alpha = p ushr ALPHA_SHIFT
            if (alpha == 0) {
                0
            } else {
                val t = ((lightness(p) - low) * FULL / span).coerceIn(0, FULL)
                (alpha shl ALPHA_SHIFT) or mix(dark, light, t)
            }
        }
    }

    // 0-255, weighted the way the eye sees the three channels.
    private fun lightness(p: Int): Int =
        (channel(p, RED_SHIFT) * RED_WEIGHT + channel(p, GREEN_SHIFT) * GREEN_WEIGHT + channel(p, 0) * BLUE_WEIGHT) /
            WEIGHTS

    // `a` blended toward `b` by t/255, as an RGB int with no alpha.
    private fun mix(a: Int, b: Int, t: Int): Int =
        intArrayOf(RED_SHIFT, GREEN_SHIFT, 0).fold(0) { rgb, shift ->
            val c = channel(a, shift) + (channel(b, shift) - channel(a, shift)) * t / FULL
            rgb or (c shl shift)
        }

    private fun channel(p: Int, shift: Int) = p shr shift and FULL

    private fun reads(alpha: IntArray, width: Int, height: Int): Boolean =
        coverage(alpha, width, height) in MIN_COVERAGE..MAX_COVERAGE

    private fun plainAlpha(pixels: IntArray) = IntArray(pixels.size) { pixels[it] ushr ALPHA_SHIFT }

    private const val FULL = 255
    private const val ALPHA_SHIFT = 24
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val RED_WEIGHT = 299
    private const val GREEN_WEIGHT = 587
    private const val BLUE_WEIGHT = 114
    private const val WEIGHTS = 1000

    // 18dp of the 108dp canvas on each side lies outside every icon shape.
    private const val VISIBLE_INSET = 6

    // A glyph covering almost nothing is no glyph; one covering more than
    // half the shape is a tile or a picture, which tints to a blank plate.
    private const val MIN_COVERAGE = 0.01f
    private const val MAX_COVERAGE = 0.5f
}
