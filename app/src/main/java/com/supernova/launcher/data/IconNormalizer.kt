package com.supernova.launcher.data

import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.core.graphics.createBitmap
import kotlin.math.sqrt

// Normalize icon size (#332): how far to shrink an icon that is not adaptive
// so its visible area keeps within the Material icon guidelines - a round
// icon to about 66% of its box, a square one to about 65%, and a shape in
// between on a line from one to the other. It never enlarges: an icon that
// already covers less than that keeps its size (scale 1). The visible area is
// the convex hull of the pixels at least ALPHA_MIN opaque, as Android's
// launcher measures it.
object IconNormalizer {

    // The scale (at most 1) for the drawable drawn into a box `side` pixels
    // across, keeping its own proportions.
    fun scale(source: Drawable, side: Int): Float {
        var width = source.intrinsicWidth.takeIf { it > 0 } ?: side
        var height = source.intrinsicHeight.takeIf { it > 0 } ?: side
        val longer = maxOf(width, height)
        width = (width.toLong() * side / longer).toInt().coerceAtLeast(1)
        height = (height.toLong() * side / longer).toInt().coerceAtLeast(1)
        val bitmap = createBitmap(side, side)
        val bounds = source.copyBounds()
        source.setBounds(0, 0, width, height)
        source.draw(Canvas(bitmap))
        source.bounds = bounds
        val pixels = IntArray(side * side)
        bitmap.getPixels(pixels, 0, side, 0, 0, side, side)
        bitmap.recycle()
        return scale(IntArray(pixels.size) { pixels[it] ushr ALPHA_SHIFT }, side, width, height)
    }

    // The same from alpha values (0-255), row by row with `stride` per row,
    // of a picture `width` by `height`.
    fun scale(alpha: IntArray, stride: Int, width: Int, height: Int): Float {
        val left = FloatArray(height) { -1f }
        val right = FloatArray(height) { -1f }
        var minX = width + 1
        var maxX = -1
        var top = -1
        var bottom = -1
        for (y in 0 until height) {
            val first = (0 until width).firstOrNull { alpha[y * stride + it] > ALPHA_MIN } ?: -1
            val last = (width - 1 downTo 0).firstOrNull { alpha[y * stride + it] > ALPHA_MIN } ?: -1
            left[y] = first.toFloat()
            right[y] = last.toFloat()
            if (first != -1) {
                bottom = y
                if (top == -1) top = y
                minX = minOf(minX, first)
                maxX = maxOf(maxX, last)
            }
        }
        if (top == -1 || maxX == -1) return 1f
        convex(left, 1, top, bottom)
        convex(right, -1, top, bottom)
        var area = 0f
        for (y in top..bottom) {
            if (left[y] <= -1f) continue
            area += right[y] - left[y] + 1
        }
        val rectArea = (bottom + 1 - top).toFloat() * (maxX + 1 - minX)
        val hullByRect = area / rectArea
        val allowed = if (hullByRect < CIRCLE_AREA_BY_RECT) {
            MAX_CIRCLE_AREA
        } else {
            MAX_SQUARE_AREA + SQUARE_SLOPE * (1 - hullByRect)
        }
        val share = area / (width.toFloat() * height)
        return if (share > allowed) sqrt(allowed / share) else 1f
    }

    // Turns one side's row edges into that side of the convex hull: an edge
    // that dents in (a concave turn) is replaced by the straight line past it.
    // `direction` is 1 for the left edges and -1 for the right.
    private fun convex(edges: FloatArray, direction: Int, top: Int, bottom: Int) {
        val angles = FloatArray(edges.size)
        var last = -1
        var lastAngle = Float.MAX_VALUE
        for (i in top + 1..bottom) {
            if (edges[i] <= -1f) continue
            val start = when {
                lastAngle == Float.MAX_VALUE -> top
                // A concave turn: back up until the line to row i turns convex.
                ((edges[i] - edges[last]) / (i - last) - lastAngle) * direction < 0 ->
                    (last - 1 downTo top).firstOrNull { from ->
                        ((edges[i] - edges[from]) / (i - from) - angles[from]) * direction >= 0
                    } ?: top
                else -> last
            }
            lastAngle = (edges[i] - edges[start]) / (i - start)
            for (j in start until i) {
                angles[j] = lastAngle
                edges[j] = edges[start] + lastAngle * (j - start)
            }
            last = i
        }
    }

    private const val ALPHA_SHIFT = 24
    private const val ALPHA_MIN = 40

    // A hull filling less of its bounds than a circle does counts as round.
    private const val CIRCLE_AREA_BY_RECT = Math.PI.toFloat() / 4f

    // The largest share of its box a round icon may cover (380/576), and a
    // square one (375/576), plus how much more a shape between them may.
    private const val MAX_CIRCLE_AREA = 380f / 576f
    private const val MAX_SQUARE_AREA = 375f / 576f
    private const val SQUARE_SLOPE = 0.040449437f
}
