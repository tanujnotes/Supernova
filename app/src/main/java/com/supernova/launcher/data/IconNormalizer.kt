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
        val (alpha, width, height) = alphaOf(source, side)
        return scale(alpha, side, width, height)
    }

    // How wide the drawable's visible part is, drawn into a box `side`
    // pixels across keeping its proportions: the longer side of the box
    // around it as a share of `side`, and whether it is round (its hull
    // fills less of that box than a circle does). `alphaMin` sets what
    // counts as visible; a higher one leaves out a soft shadow around art.
    fun footprint(source: Drawable, side: Int, alphaMin: Int = ALPHA_MIN): Footprint? {
        val (alpha, width, height) = alphaOf(source, side)
        return footprintOf(hull(alpha, side, width, height, alphaMin), side)
    }

    // The same for alpha values (0-255) of a square `side` pixels across.
    fun footprint(alpha: IntArray, side: Int): Footprint? = footprintOf(hull(alpha, side, side, side), side)

    private fun footprintOf(hull: Hull?, side: Int): Footprint? =
        hull?.let { Footprint(it.longer / side, it.area / it.rectArea < ROUND_AREA_BY_RECT) }

    class Footprint(val box: Float, val round: Boolean)

    // How much wider than a square one round art may be, by the guidelines:
    // a circle's allowed diameter against a square's allowed side.
    val roundAllowance: Float = sqrt(MAX_CIRCLE_AREA / CIRCLE_AREA_BY_RECT) / sqrt(MAX_SQUARE_AREA)

    private fun alphaOf(source: Drawable, side: Int): Triple<IntArray, Int, Int> {
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
        return Triple(IntArray(pixels.size) { pixels[it] ushr ALPHA_SHIFT }, width, height)
    }

    // The same from alpha values (0-255), row by row with `stride` per row,
    // of a picture `width` by `height`.
    fun scale(alpha: IntArray, stride: Int, width: Int, height: Int): Float {
        val hull = hull(alpha, stride, width, height) ?: return 1f
        val hullByRect = hull.area / hull.rectArea
        val allowed = if (hullByRect < CIRCLE_AREA_BY_RECT) {
            MAX_CIRCLE_AREA
        } else {
            MAX_SQUARE_AREA + SQUARE_SLOPE * (1 - hullByRect)
        }
        val share = hull.area / (width.toFloat() * height)
        return if (share > allowed) sqrt(allowed / share) else 1f
    }

    // The visible area's convex hull, and the rectangle around it, in pixels.
    private class Hull(val area: Float, val rectArea: Float, val longer: Float)

    private fun hull(alpha: IntArray, stride: Int, width: Int, height: Int, alphaMin: Int = ALPHA_MIN): Hull? {
        val left = FloatArray(height) { -1f }
        val right = FloatArray(height) { -1f }
        var minX = width + 1
        var maxX = -1
        var top = -1
        var bottom = -1
        for (y in 0 until height) {
            val first = (0 until width).firstOrNull { alpha[y * stride + it] > alphaMin } ?: -1
            val last = (width - 1 downTo 0).firstOrNull { alpha[y * stride + it] > alphaMin } ?: -1
            left[y] = first.toFloat()
            right[y] = last.toFloat()
            if (first != -1) {
                bottom = y
                if (top == -1) top = y
                minX = minOf(minX, first)
                maxX = maxOf(maxX, last)
            }
        }
        if (top == -1 || maxX == -1) return null
        convex(left, 1, top, bottom)
        convex(right, -1, top, bottom)
        var area = 0f
        for (y in top..bottom) {
            if (left[y] <= -1f) continue
            area += right[y] - left[y] + 1
        }
        val rows = (bottom + 1 - top).toFloat()
        val columns = (maxX + 1 - minX).toFloat()
        return Hull(area, rows * columns, maxOf(rows, columns))
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

    // A drawn circle's hull, made of pixel rows, fills a little more of its
    // box than a true circle does; art up to this counts as round.
    private const val ROUND_AREA_BY_RECT = 0.8f

    // The largest share of its box a round icon may cover (380/576), and a
    // square one (375/576), plus how much more a shape between them may.
    private const val MAX_CIRCLE_AREA = 380f / 576f
    private const val MAX_SQUARE_AREA = 375f / 576f
    private const val SQUARE_SLOPE = 0.040449437f
}
