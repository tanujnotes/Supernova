package com.supernova.launcher.data

import com.supernova.launcher.data.Pixels.alpha
import kotlin.math.sqrt

// A picture an app made for its shortcut - a contact's photo, cut round or
// square - told from an icon drawn on a clear canvas, and how far a round one
// must be enlarged to cover an icon shape. Plain ARGB ints in
// Bitmap.getPixels order, a picture size pixels square, so it runs in unit
// tests.
internal object PictureFrame {

    // The box round a picture that fills its frame (LEFT, TOP, RIGHT,
    // BOTTOM, as BakedBacking.contentBounds): its solid pixels reach nearly
    // every side and leave no gap in the disc that box encloses. Null for a
    // glyph or an object on a clear canvas.
    fun frame(pixels: IntArray, size: Int): IntArray? =
        BakedBacking.contentBounds(pixels, size)?.takeIf { box ->
            val width = box[BakedBacking.RIGHT] - box[BakedBacking.LEFT] + 1
            val height = box[BakedBacking.BOTTOM] - box[BakedBacking.TOP] + 1
            width >= size * SPAN && height >= size * SPAN && discSolid(pixels, size, box)
        }

    // Whether the disc inside the box is solid, but for a speck.
    private fun discSolid(pixels: IntArray, size: Int, box: IntArray): Boolean {
        val centreX = (box[BakedBacking.LEFT] + box[BakedBacking.RIGHT]) / 2f
        val centreY = (box[BakedBacking.TOP] + box[BakedBacking.BOTTOM]) / 2f
        val radiusX = (box[BakedBacking.RIGHT] - box[BakedBacking.LEFT] + 1) / 2f
        val radiusY = (box[BakedBacking.BOTTOM] - box[BakedBacking.TOP] + 1) / 2f
        var inside = 0
        var solid = 0
        for (y in box[BakedBacking.TOP]..box[BakedBacking.BOTTOM]) {
            for (x in box[BakedBacking.LEFT]..box[BakedBacking.RIGHT]) {
                val dx = (x - centreX) / radiusX
                val dy = (y - centreY) / radiusY
                if (dx * dx + dy * dy > 1f) continue
                inside++
                if (alpha(pixels[y * size + x]) >= SOLID_ALPHA) solid++
            }
        }
        return solid >= inside * SOLID_SHARE
    }

    // Whether the box's corners are solid too: a square picture covers any
    // shape as it is, a round one doesn't.
    fun square(pixels: IntArray, size: Int, box: IntArray): Boolean {
        val inset = (size * CORNER_INSET).toInt()
        val left = box[BakedBacking.LEFT] + inset
        val top = box[BakedBacking.TOP] + inset
        val right = box[BakedBacking.RIGHT] - inset
        val bottom = box[BakedBacking.BOTTOM] - inset
        return listOf(left to top, right to top, left to bottom, right to bottom)
            .all { (x, y) -> alpha(pixels[y * size + x]) >= SOLID_ALPHA }
    }

    // How far a round picture filling the icon must be enlarged to cover a
    // mask drawn into these pixels: its farthest solid pixel's distance from
    // the centre, in radii (1 for a circle, about 1.19 for a squircle).
    fun reach(mask: IntArray, size: Int): Float {
        // Pixel centres sit on whole numbers, the mask's between them.
        val centre = (size - 1) / 2f
        var farthest = 0f
        for (index in mask.indices) {
            if (alpha(mask[index]) < SOLID_ALPHA) continue
            val dx = index % size - centre
            val dy = index / size - centre
            farthest = maxOf(farthest, dx * dx + dy * dy)
        }
        return (sqrt(farthest) * 2f / size).coerceIn(1f, MAX_REACH)
    }

    // A picture reaching this share of each side fills its frame.
    private const val SPAN = 0.9f
    // This share of the disc inside its box must be solid.
    private const val SOLID_SHARE = 0.97f
    private const val SOLID_ALPHA = 128
    // How far in from the box's corners a square picture is still solid,
    // past any rounding of them.
    private const val CORNER_INSET = 0.03f
    // A square's corner: no shape reaches further.
    private const val MAX_REACH = 1.415f
}
