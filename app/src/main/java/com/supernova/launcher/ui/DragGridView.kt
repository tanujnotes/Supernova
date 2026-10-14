package com.supernova.launcher.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.withStyledAttributes
import com.supernova.launcher.R
import com.supernova.launcher.data.WorkspaceGeometry
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

// The drag grid. While an item is dragged over a page, a four-point
// sparkle sits on every cell corner, brightest by the finger and gone about a
// cell and a half away, and a thin white outline marks the cells the drop
// would take. The sparkles under the outline's corners light up fully, so a
// cell-aligned target reads at a glance; a subgrid target, whose corners fall
// between sparkles, lights none. The sparkles are 20dp across, with a linear
// fade that reaches zero at 1.4 cell diagonals from the finger; the outline
// is square-cornered and centred on the cell edges.
//
// A page carries two of these, fed the same updates: one under the page's
// items draws the sparkles (app:gridLayer="sparkles"), one over them draws the
// outline ("outline"). Both span the whole page rather than just the canvas,
// so the sparkles on the grid's outer edge are drawn whole. The dragged
// picture floats above both in DragLayer.
class DragGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var drawsOutline = false
    private val density = resources.displayMetrics.density
    private val sparkle = sparklePath(SPARKLE_RADIUS_DP * density)
    private val sparklePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val shadowRadius = SHADOW_RADIUS_DP * density
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = OUTLINE_STROKE_DP * density
        color = Color.WHITE
        // The sparkles' shadow, for the same pale wallpapers.
        setShadowLayer(shadowRadius, 0f, 0f, Color.argb(SHADOW_ALPHA.toInt(), 0, 0, 0))
    }

    private var cols = 0
    private var rows = 0
    // The page's cell area, in this view's coordinates.
    private val grid = RectF()
    private var fingerX = 0f
    private var fingerY = 0f

    // The outline as drawn, mid-slide while slideAnimator runs.
    private val outline = RectF()
    private var hasOutline = false
    private val slideFrom = RectF()
    private val slideTo = RectF()
    private var slideAnimator: ValueAnimator? = null
    private val slideInterpolator = DecelerateInterpolator()

    // Between the first update() and dismiss().
    private var showing = false

    init {
        context.withStyledAttributes(attrs, R.styleable.DragGridView) {
            drawsOutline = getInt(R.styleable.DragGridView_gridLayer, LAYER_SPARKLES) == LAYER_OUTLINE
        }
        // Draw-only: touches go to the page's items and canvas.
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = INVISIBLE
    }

    // One hover frame. `gridRect` is the canvas and the finger is a point,
    // both in this view's coordinates; `target` is in cells, null for no
    // outline. The outline slides to a new target over `slideMs`, and a grid
    // that was not showing fades in over `fadeMs`.
    fun update(
        cols: Int,
        rows: Int,
        gridRect: RectF,
        fingerX: Float,
        fingerY: Float,
        target: WorkspaceGeometry.CellRect?,
        slideMs: Long,
        fadeMs: Long
    ) {
        this.cols = cols
        this.rows = rows
        grid.set(gridRect)
        this.fingerX = fingerX
        this.fingerY = fingerY
        if (target == null || cols <= 0 || rows <= 0) {
            slideAnimator?.cancel()
            hasOutline = false
        } else {
            val cellW = grid.width() / cols
            val cellH = grid.height() / rows
            val left = grid.left + target.left * cellW
            val top = grid.top + target.top * cellH
            val right = grid.left + target.right * cellW
            val bottom = grid.top + target.bottom * cellH
            if (!hasOutline || !showing || slideMs <= 0L) {
                slideAnimator?.cancel()
                outline.set(left, top, right, bottom)
                slideTo.set(outline)
            } else if (slideTo.left != left || slideTo.top != top || slideTo.right != right || slideTo.bottom != bottom) {
                slideTo.set(left, top, right, bottom)
                slide(slideMs)
            }
            hasOutline = true
        }
        if (!showing) {
            showing = true
            animate().cancel()
            // Caught mid fade-out, it fades back in from where it is.
            if (visibility != VISIBLE) {
                alpha = 0f
                visibility = VISIBLE
            }
            if (fadeMs > 0L) animate().alpha(1f).setDuration(fadeMs).start() else alpha = 1f
        }
        invalidate()
    }

    // Fades the grid out over `fadeMs` (0 hides it at once).
    fun dismiss(fadeMs: Long) {
        if (!showing) return
        showing = false
        animate().cancel()
        if (fadeMs > 0L && isShown) {
            animate().alpha(0f).setDuration(fadeMs).withEndAction { if (!showing) clear() }.start()
        } else {
            clear()
        }
    }

    private fun clear() {
        slideAnimator?.cancel()
        hasOutline = false
        visibility = INVISIBLE
    }

    private fun slide(durationMs: Long) {
        slideAnimator?.cancel()
        slideFrom.set(outline)
        slideAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = slideInterpolator
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                outline.set(
                    lerp(slideFrom.left, slideTo.left, t),
                    lerp(slideFrom.top, slideTo.top, t),
                    lerp(slideFrom.right, slideTo.right, t),
                    lerp(slideFrom.bottom, slideTo.bottom, t)
                )
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (cols <= 0 || rows <= 0 || grid.isEmpty) return
        if (drawsOutline) {
            if (hasOutline) canvas.drawRect(outline, outlinePaint)
            return
        }
        val cellW = grid.width() / cols
        val cellH = grid.height() / rows
        val fadeDistance = FADE_DIAGONALS * hypot(cellW, cellH)
        // A sparkle this close to an outline corner is lit, fully when on it.
        val cornerReach = min(cellW, cellH) / 2f
        for (row in 0..rows) {
            val y = grid.top + row * cellH
            for (col in 0..cols) {
                val x = grid.left + col * cellW
                var strength = 1f - hypot(x - fingerX, y - fingerY) / fadeDistance
                if (hasOutline) strength = max(strength, 1f - cornerDistance(x, y) / cornerReach)
                if (strength <= MIN_STRENGTH) continue
                val a = strength.coerceAtMost(1f)
                sparklePaint.alpha = (a * 255).toInt()
                // The shadow keeps the white legible over a pale wallpaper. It
                // must fade with the sparkle: a translucent shadow colour is
                // not scaled by the paint's alpha.
                sparklePaint.setShadowLayer(
                    shadowRadius, 0f, 0f, Color.argb((a * SHADOW_ALPHA).toInt(), 0, 0, 0)
                )
                canvas.save()
                canvas.translate(x, y)
                canvas.drawPath(sparkle, sparklePaint)
                canvas.restore()
            }
        }
    }

    // Distance from a grid point to the outline's nearest corner.
    private fun cornerDistance(x: Float, y: Float): Float = hypot(
        min(abs(x - outline.left), abs(x - outline.right)),
        min(abs(y - outline.top), abs(y - outline.bottom))
    )

    private companion object {
        // gridLayer's enum values (res/values/attrs.xml).
        const val LAYER_SPARKLES = 0
        const val LAYER_OUTLINE = 1

        const val SPARKLE_RADIUS_DP = 10f
        const val OUTLINE_STROKE_DP = 1.5f
        const val SHADOW_RADIUS_DP = 2f
        const val SHADOW_ALPHA = 70f
        const val FADE_DIAGONALS = 1.4f
        const val MIN_STRENGTH = 0.02f

        // The side curves' control points, as fractions of the radius: near
        // the axis for sharp tips, further out for a full body - a ✦.
        const val CURVE_NEAR = 0.10f
        const val CURVE_FAR = 0.45f

        fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t

        // Four points joined by concave curves, centred on the origin.
        fun sparklePath(radius: Float): Path {
            val near = CURVE_NEAR * radius
            val far = CURVE_FAR * radius
            return Path().apply {
                moveTo(0f, -radius)
                cubicTo(near, -far, far, -near, radius, 0f)
                cubicTo(far, near, near, far, 0f, radius)
                cubicTo(-near, far, -far, near, -radius, 0f)
                cubicTo(-far, -near, -near, -far, 0f, -radius)
                close()
            }
        }
    }
}
