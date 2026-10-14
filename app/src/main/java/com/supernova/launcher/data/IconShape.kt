package com.supernova.launcher.data

import android.graphics.Path
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

enum class IconShape {
    SYSTEM,
    CIRCLE,
    SQUIRCLE,
    ROUNDED_SQUARE,
    SQUARE,
    TEARDROP,
    HEXAGON,
    PEBBLE,
    VESSEL,
    PENTAGON,
    HEXAGON_ROUNDED,
    HEPTAGON,
    OCTAGON,
    CROP,
    FLOWER,
    PLATFORM,
    CUSTOM;

    companion object {
        fun fromName(name: String): IconShape =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: SYSTEM

        // Cubic handle length approximating a quarter circle.
        private const val CIRCLE_HANDLE = 0.5523f

        // A squircle's handle, as a share of the way from a curve's end
        // to the corner it rounds. 0.55 - what both squircles used - is
        // the circle's own constant, so Squircle drew a plain circle and a
        // custom squircle corner matched a round one. At 0.92 the outline
        // reaches about 1.19 of the radius on the diagonal, where a circle
        // reaches 1.0 and a square 1.41.
        private const val SQUIRCLE_HANDLE = 0.92f
    }

    // Path describing the mask for a size x size icon, or null for SYSTEM
    // (adaptive icons keep the platform mask, legacy icons stay unmasked).
    // CUSTOM reads its per-corner config from `custom` (default 25% ROUND).
    fun pathFor(size: Float, custom: CustomShapeSpec? = null): Path? {
        if (this == SYSTEM) return null
        val path = Path()
        val rect = RectF(0f, 0f, size, size)
        when (this) {
            CIRCLE -> path.addOval(rect, Path.Direction.CW)
            SQUIRCLE -> addSquircle(path, size)
            ROUNDED_SQUARE -> path.addRoundRect(rect, size * 0.22f, size * 0.22f, Path.Direction.CW)
            SQUARE -> path.addRect(rect, Path.Direction.CW)
            TEARDROP -> addTeardrop(path, size)
            HEXAGON -> addHexagon(path, size)
            PEBBLE -> addPebble(path, size)
            VESSEL -> addVessel(path, size)
            PENTAGON -> addRegularPolygon(path, size, sides = 5, cornerFraction = 0.14f)
            HEXAGON_ROUNDED -> addRegularPolygon(path, size, sides = 6, cornerFraction = 0.14f)
            HEPTAGON -> addRegularPolygon(path, size, sides = 7, cornerFraction = 0.12f)
            OCTAGON -> addRegularPolygon(
                path, size, sides = 8, cornerFraction = 0.06f, rotationDeg = -67.5f
            )
            CROP -> path.addRoundRect(rect, size * 0.08f, size * 0.08f, Path.Direction.CW)
            FLOWER -> addFlower(path, size)
            PLATFORM -> addPlatform(path, size)
            CUSTOM -> addCustom(path, size, custom ?: CustomShapeSpec())
            SYSTEM -> return null
        }
        return path
    }

    private fun addSquircle(path: Path, size: Float) {
        // Superellipse-ish outline built from four cubic curves.
        val c = size * 0.5f
        val r = size * 0.5f
        val k = r * SQUIRCLE_HANDLE
        path.moveTo(c, c - r)
        path.cubicTo(c + k, c - r, c + r, c - k, c + r, c)
        path.cubicTo(c + r, c + k, c + k, c + r, c, c + r)
        path.cubicTo(c - k, c + r, c - r, c + k, c - r, c)
        path.cubicTo(c - r, c - k, c - k, c - r, c, c - r)
        path.close()
    }

    private fun addTeardrop(path: Path, size: Float) {
        val r = size * 0.5f
        path.addRoundRect(
            RectF(0f, 0f, size, size),
            floatArrayOf(r, r, r, r, r, r, size * 0.12f, size * 0.12f),
            Path.Direction.CW
        )
    }

    private fun addHexagon(path: Path, size: Float) {
        val w = size
        val h = size
        path.moveTo(w * 0.5f, 0f)
        path.lineTo(w, h * 0.25f)
        path.lineTo(w, h * 0.75f)
        path.lineTo(w * 0.5f, h)
        path.lineTo(0f, h * 0.75f)
        path.lineTo(0f, h * 0.25f)
        path.close()
    }

    // Organic asymmetric superellipse: the squircle skeleton with slightly
    // displaced anchors and a different handle length per quadrant.
    private fun addPebble(path: Path, size: Float) {
        val top = floatArrayOf(size * 0.53f, 0f)
        val right = floatArrayOf(size, size * 0.47f)
        val bottom = floatArrayOf(size * 0.47f, size)
        val left = floatArrayOf(0f, size * 0.53f)
        val r = size * 0.5f
        val kTr = r * 0.62f
        val kBr = r * 0.52f
        val kBl = r * 0.64f
        val kTl = r * 0.50f
        path.moveTo(top[0], top[1])
        path.cubicTo(top[0] + kTr, top[1], right[0], right[1] - kTr, right[0], right[1])
        path.cubicTo(right[0], right[1] + kBr, bottom[0] + kBr, bottom[1], bottom[0], bottom[1])
        path.cubicTo(bottom[0] - kBl, bottom[1], left[0], left[1] + kBl, left[0], left[1])
        path.cubicTo(left[0], left[1] - kTl, top[0] - kTl, top[1], top[0], top[1])
        path.close()
    }

    // Rounded square whose bottom corners curve much more heavily than the top.
    private fun addVessel(path: Path, size: Float) {
        val top = size * 0.22f
        val bottom = size * 0.44f
        path.addRoundRect(
            RectF(0f, 0f, size, size),
            floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom),
            Path.Direction.CW
        )
    }

    // Regular N-gon inscribed in the icon square, corners rounded with quads.
    // The default rotation puts a vertex at the top, which leaves a flat edge
    // (or edge-centered base) facing down for odd side counts.
    private fun addRegularPolygon(
        path: Path,
        size: Float,
        sides: Int,
        cornerFraction: Float,
        rotationDeg: Float = -90f
    ) {
        val raw = FloatArray(sides * 2)
        for (i in 0 until sides) {
            val angle = Math.toRadians(rotationDeg + i * 360.0 / sides)
            raw[i * 2] = cos(angle).toFloat()
            raw[i * 2 + 1] = sin(angle).toFloat()
        }
        // Stretch the polygon to fill the square, like the legacy HEXAGON.
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in 0 until sides) {
            minX = minOf(minX, raw[i * 2]); maxX = maxOf(maxX, raw[i * 2])
            minY = minOf(minY, raw[i * 2 + 1]); maxY = maxOf(maxY, raw[i * 2 + 1])
        }
        val points = FloatArray(sides * 2)
        for (i in 0 until sides) {
            points[i * 2] = (raw[i * 2] - minX) / (maxX - minX) * size
            points[i * 2 + 1] = (raw[i * 2 + 1] - minY) / (maxY - minY) * size
        }
        addRoundedPolygon(path, points, size * cornerFraction)
    }

    // Rounds an arbitrary convex polygon: each vertex is entered and left at
    // `cornerRadius` along its edges and bridged with a quad whose control
    // point is the vertex itself. Pure path math, no arcs needed.
    private fun addRoundedPolygon(path: Path, points: FloatArray, cornerRadius: Float) {
        val n = points.size / 2
        for (i in 0 until n) {
            val prevX = points[((i - 1 + n) % n) * 2]
            val prevY = points[((i - 1 + n) % n) * 2 + 1]
            val curX = points[i * 2]
            val curY = points[i * 2 + 1]
            val nextX = points[((i + 1) % n) * 2]
            val nextY = points[((i + 1) % n) * 2 + 1]

            val inLen = hypot(curX - prevX, curY - prevY)
            val outLen = hypot(nextX - curX, nextY - curY)
            // Rounding never eats more than half of either adjacent edge.
            val rIn = minOf(cornerRadius, inLen * 0.5f)
            val rOut = minOf(cornerRadius, outLen * 0.5f)

            val entryX = curX - (curX - prevX) / inLen * rIn
            val entryY = curY - (curY - prevY) / inLen * rIn
            val exitX = curX + (nextX - curX) / outLen * rOut
            val exitY = curY + (nextY - curY) / outLen * rOut

            if (i == 0) path.moveTo(entryX, entryY) else path.lineTo(entryX, entryY)
            path.quadTo(curX, curY, exitX, exitY)
        }
        path.close()
    }

    // Eight-lobe scalloped circle: notch points on an inner radius joined by
    // quads whose control points push each petal out to the icon edge.
    private fun addFlower(path: Path, size: Float) {
        val c = size * 0.5f
        val lobes = 8
        val notchRadius = size * 0.44f
        val controlRadius = size * 0.59f
        for (i in 0 until lobes) {
            val notchAngle = Math.toRadians(-90.0 + i * 360.0 / lobes - 180.0 / lobes)
            val midAngle = Math.toRadians(-90.0 + i * 360.0 / lobes)
            val nextAngle = Math.toRadians(-90.0 + i * 360.0 / lobes + 180.0 / lobes)
            val startX = c + notchRadius * cos(notchAngle).toFloat()
            val startY = c + notchRadius * sin(notchAngle).toFloat()
            val endX = c + notchRadius * cos(nextAngle).toFloat()
            val endY = c + notchRadius * sin(nextAngle).toFloat()
            if (i == 0) path.moveTo(startX, startY)
            path.quadTo(
                c + controlRadius * cos(midAngle).toFloat(),
                c + controlRadius * sin(midAngle).toFloat(),
                endX, endY
            )
        }
        path.close()
    }

    // Plinth: a rounded square whose top edge is slightly narrower than its
    // base, so the silhouette tapers wider toward the bottom.
    private fun addPlatform(path: Path, size: Float) {
        val inset = size * 0.07f
        addRoundedPolygon(
            path,
            floatArrayOf(
                inset, 0f,
                size - inset, 0f,
                size, size,
                0f, size
            ),
            size * 0.14f
        )
    }

    // The CUSTOM shape: each corner applies its own radius (percent of the
    // half-size) and curve style. ROUND is a circular cubic, SQUIRCLE pulls
    // its handles with the 0.55 factor, CUT is a straight chamfer.
    private fun addCustom(path: Path, size: Float, spec: CustomShapeSpec) {
        val half = size * 0.5f
        val rTl = spec.radiusAt(0) / 100f * half
        val rTr = spec.radiusAt(1) / 100f * half
        val rBr = spec.radiusAt(2) / 100f * half
        val rBl = spec.radiusAt(3) / 100f * half

        // Current path point (the corner entry A), tracked so each corner can
        // aim its handles from A and B toward the vertex V.
        var ax = rTl
        var ay = 0f

        fun lineTo(x: Float, y: Float) {
            path.lineTo(x, y)
            ax = x
            ay = y
        }

        fun corner(vx: Float, vy: Float, bx: Float, by: Float, cut: CornerCut) {
            when (cut) {
                CornerCut.CUT -> path.lineTo(bx, by)
                CornerCut.ROUND, CornerCut.SQUIRCLE -> {
                    val h = if (cut == CornerCut.ROUND) CIRCLE_HANDLE else SQUIRCLE_HANDLE
                    path.cubicTo(
                        ax + (vx - ax) * h, ay + (vy - ay) * h,
                        bx + (vx - bx) * h, by + (vy - by) * h,
                        bx, by
                    )
                }
            }
            ax = bx
            ay = by
        }

        path.moveTo(rTl, 0f)
        lineTo(size - rTr, 0f)
        corner(size, 0f, size, rTr, spec.cutAt(1))
        lineTo(size, size - rBr)
        corner(size, size, size - rBr, size, spec.cutAt(2))
        lineTo(rBl, size)
        corner(0f, size, 0f, size - rBl, spec.cutAt(3))
        lineTo(0f, rTl)
        corner(0f, 0f, rTl, 0f, spec.cutAt(0))
        path.close()
    }
}
