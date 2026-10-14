package com.supernova.launcher.ui.theme

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import com.supernova.launcher.data.CornerCut
import kotlin.math.min

// Surface backgrounds with per-corner cut styles (C1). GradientDrawable only
// draws circular corners, so a surface with any SQUIRCLE or CUT corner renders
// through CornerCutDrawable instead; all-round surfaces keep the cheap
// GradientDrawable path they always had.
object SurfaceShapes {

    // The cubic handle fractions the CUSTOM icon shape uses; a circle's
    // quarter-arc approximation vs the tighter squircle curve.
    private const val CIRCLE_HANDLE = 0.55f
    private const val SQUIRCLE_HANDLE = 0.2f

    fun allRound(cuts: List<CornerCut>?): Boolean =
        cuts == null || cuts.all { it == CornerCut.ROUND }

    // Background for a resolved surface. radiiPx/cuts are [TL, TR, BR, BL];
    // null radii means the surface's built-in shape (uniform defaultRadiusPx).
    fun background(
        color: Int,
        radiiPx: FloatArray?,
        cuts: List<CornerCut>?,
        defaultRadiusPx: Float
    ): Drawable {
        if (radiiPx == null || allRound(cuts)) {
            return GradientDrawable().apply {
                setColor(color)
                if (radiiPx == null) {
                    cornerRadius = defaultRadiusPx
                } else {
                    // GradientDrawable wants [TL, TL, TR, TR, BR, BR, BL, BL].
                    cornerRadii = floatArrayOf(
                        radiiPx[0], radiiPx[0], radiiPx[1], radiiPx[1],
                        radiiPx[2], radiiPx[2], radiiPx[3], radiiPx[3]
                    )
                }
            }
        }
        return CornerCutDrawable(color, radiiPx, cuts ?: List(4) { CornerCut.ROUND })
    }

    // Re-colors a background made by background() in place; both shapes
    // support it so callers don't need to rebuild on alpha/color changes.
    fun setColor(drawable: Drawable?, color: Int) {
        when (drawable) {
            is GradientDrawable -> drawable.setColor(color)
            is CornerCutDrawable -> drawable.setColor(color)
        }
    }

    // Rounded-rect outline with per-corner cut styles, the rectangular twin of
    // the CUSTOM icon shape's corner math. Radii clamp to half the short edge.
    fun addRectPath(path: Path, width: Float, height: Float, radiiPx: FloatArray, cuts: List<CornerCut>) {
        val cap = min(width, height) / 2f
        val rTl = radiiPx[0].coerceIn(0f, cap)
        val rTr = radiiPx[1].coerceIn(0f, cap)
        val rBr = radiiPx[2].coerceIn(0f, cap)
        val rBl = radiiPx[3].coerceIn(0f, cap)

        fun cutAt(index: Int): CornerCut = cuts.getOrElse(index) { CornerCut.ROUND }

        var ax = rTl
        var ay = 0f

        fun lineTo(x: Float, y: Float) {
            path.lineTo(x, y)
            ax = x
            ay = y
        }

        // A is the current point entering the corner, V the square vertex,
        // B the exit point; handles pull A and B toward V.
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
        lineTo(width - rTr, 0f)
        corner(width, 0f, width, rTr, cutAt(1))
        lineTo(width, height - rBr)
        corner(width, height, width - rBr, height, cutAt(2))
        lineTo(rBl, height)
        corner(0f, height, 0f, height - rBl, cutAt(3))
        lineTo(0f, rTl)
        corner(0f, 0f, rTl, 0f, cutAt(0))
        path.close()
    }
}

class CornerCutDrawable(
    color: Int,
    private val radiiPx: FloatArray,
    private val cuts: List<CornerCut>
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private val path = Path()

    fun setColor(color: Int) {
        if (paint.color != color) {
            paint.color = color
            invalidateSelf()
        }
    }

    override fun onBoundsChange(bounds: Rect) {
        path.reset()
        if (bounds.width() > 0 && bounds.height() > 0) {
            SurfaceShapes.addRectPath(path, bounds.width().toFloat(), bounds.height().toFloat(), radiiPx, cuts)
            path.offset(bounds.left.toFloat(), bounds.top.toFloat())
        }
    }

    override fun draw(canvas: Canvas) {
        canvas.drawPath(path, paint)
    }

    // clipToOutline consumers (the popup menu) need a real outline; these
    // corner paths are always convex.
    override fun getOutline(outline: Outline) {
        if (path.isEmpty) {
            super.getOutline(outline)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            outline.setPath(path)
        } else {
            @Suppress("DEPRECATION")
            outline.setConvexPath(path)
        }
        outline.alpha = 1f
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
