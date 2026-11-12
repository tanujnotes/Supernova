package com.supernova.launcher.data

import kotlin.math.abs
import kotlin.math.max

// The ink of an icon drawn on a solid tile: a shortcut's icon is often a
// finished tile (a white square with a blue "+"), whose plain alpha
// silhouette is the whole square. For such a picture the ink is what differs
// from the tile's colour, read off the tile's own edge. Pixels are ARGB ints,
// row by row; the result is one alpha (0-255) per pixel, or null when the
// picture isn't a solid tile with something drawn on it, and the plain alpha
// silhouette should be used instead.
object SilhouetteMask {

    fun of(pixels: IntArray, width: Int, height: Int): IntArray? {
        val content = pixels.count { alpha(it) > 0 }
        val solid = content >= pixels.size * MIN_CONTENT && pixels.count { alpha(it) >= OPAQUE } >= content * MIN_OPAQUE
        val tile = (if (solid) edgeColour(pixels, width, height) else null) ?: return null
        val mask = IntArray(pixels.size) { i ->
            val p = pixels[i]
            if (alpha(p) == 0) 0 else inkAlpha(p, tile) * alpha(p) / FULL
        }
        val ink = mask.count { it >= FULL / 2 }
        return mask.takeIf { ink > content * MIN_INK && ink < content * MAX_INK }
    }

    // The commonest colour among each row's outermost drawn pixels.
    private fun edgeColour(pixels: IntArray, width: Int, height: Int): Int? {
        val counts = HashMap<Int, Int>()
        val colours = HashMap<Int, Int>()
        for (y in 0 until height) {
            val row = y * width
            val first = (0 until width).firstOrNull { alpha(pixels[row + it]) >= OPAQUE } ?: continue
            val last = (width - 1 downTo 0).first { alpha(pixels[row + it]) >= OPAQUE }
            for (x in intArrayOf(first, last)) {
                val p = pixels[row + x]
                val key = quantized(p)
                counts[key] = (counts[key] ?: 0) + 1
                colours.putIfAbsent(key, p)
            }
        }
        return counts.maxByOrNull { it.value }?.key?.let(colours::get)
    }

    // 0 for the tile's colour, rising to full a little way from it, so the
    // ink's anti-aliased edge stays soft.
    private fun inkAlpha(p: Int, tile: Int): Int {
        val d = max(abs(red(p) - red(tile)), max(abs(green(p) - green(tile)), abs(blue(p) - blue(tile))))
        return ((d - NEAR) * FULL / (FAR - NEAR)).coerceIn(0, FULL)
    }

    // Each channel cut to its top bits, so near shades count as one colour.
    private fun quantized(p: Int): Int =
        (red(p) shr DROP_BITS shl (2 * KEEP_BITS)) or (green(p) shr DROP_BITS shl KEEP_BITS) or (blue(p) shr DROP_BITS)

    private fun alpha(p: Int) = p ushr ALPHA_SHIFT
    private fun red(p: Int) = p shr RED_SHIFT and FULL
    private fun green(p: Int) = p shr GREEN_SHIFT and FULL
    private fun blue(p: Int) = p and FULL

    private const val FULL = 255
    private const val ALPHA_SHIFT = 24
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val DROP_BITS = 4
    private const val KEEP_BITS = 4
    private const val OPAQUE = 250

    // Colour distances: closer than NEAR is tile, farther than FAR is ink.
    private const val NEAR = 24
    private const val FAR = 72

    // A picture covering less than this share of its canvas isn't a tile.
    private const val MIN_CONTENT = 0.25

    // Most of what is drawn must be opaque for it to be a solid tile.
    private const val MIN_OPAQUE = 0.9

    // Ink covering almost nothing or almost everything means no tile/ink split.
    private const val MIN_INK = 0.005
    private const val MAX_INK = 0.85
}
