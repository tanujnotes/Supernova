package com.supernova.launcher.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.animation.doOnCancel
import androidx.core.animation.doOnEnd
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs
import kotlin.math.floor

enum class IndicatorStyle {
    NONE,
    DOTS,
    CIRCLE,
    LINE;

    companion object {
        fun fromName(name: String): IndicatorStyle =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: LINE
    }
}

// The workspace's page indicator, after Launcher3's WorkspacePageIndicator
// and its dots subclass:
//
// - DOTS: 8dp dots 12dp apart. The current page's dot, in the full color,
//   stretches over the next page as a page is dragged and lets go of the one
//   it left as the page lands, in 150ms half-page steps.
// - CIRCLE: the same dots, the current page's half as big again and gliding
//   with the scroll.
// - LINE: a 1dp line a 1/pages share of the width wide, gliding with the
//   scroll.
//
// With auto-hide, its only behaviour, it is out of sight at rest: it fades
// in as a page starts to move and out again 300ms after the pager comes to
// rest. The fade goes through the paint and never the view's alpha, which the
// drawer, the feed and edit mode use to fade the home screen away - a page
// moving under them must not bring the indicator back.
class PageIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val span = FloatArray(2)
    private val density = resources.displayMetrics.density

    private var style = IndicatorStyle.DOTS
    private var pageCount = 1
    private var autoHide = true
    private var color = Color.WHITE
    // The animation-speed factor; 0 with animations off.
    private var motion = 1f
    private var configured = false

    // The pager's position in pages, as it reports it.
    private var scroll = 0f

    // DOTS: where the stretching dot is drawn, walking toward dotTarget.
    private var dotPosition = 0f
    private var dotTarget = 0f
    private var dotWalk: ValueAnimator? = null

    // How far in the indicator is faded, 0 to 1.
    private var shown = 1f
    private var shownTarget = 1f
    private var fade: ValueAnimator? = null
    private var dragging = false
    private val hide = Runnable { fadeTo(0f, FADE_OUT_MS) }

    fun configure(style: IndicatorStyle, pageCount: Int, autoHide: Boolean, color: Int, motion: Float) {
        val count = pageCount.coerceAtLeast(1)
        val restyled = configured && style != this.style
        if (count != this.pageCount) {
            // A page went away under the position: jump to where it now is.
            this.pageCount = count
            scroll = scroll.coerceAtMost((count - 1).toFloat())
            dotWalk?.cancel()
            dotTarget = PageIndicatorMath.dotTarget(scroll, count)
            dotPosition = dotTarget
        }
        this.style = style
        this.color = color
        this.motion = motion
        visibility = if (style == IndicatorStyle.NONE || count <= 1) GONE else VISIBLE
        if (!configured) {
            configured = true
            this.autoHide = autoHide
            shown = if (autoHide) 0f else 1f
            shownTarget = shown
        } else if (autoHide != this.autoHide) {
            this.autoHide = autoHide
            if (autoHide) scheduleHide() else fadeTo(1f, FADE_IN_MS)
        }
        // A style picked in settings shows itself for a moment on the way back.
        if (restyled && visibility == VISIBLE) show()
        invalidate()
    }

    // Straight from the pager's onPageScrolled: the page at the viewport's
    // left edge and how far past it the viewport is.
    fun onPageScrolled(page: Int, offset: Float) {
        val previous = scroll
        val scroll = page.coerceIn(0, pageCount - 1) + offset.coerceIn(0f, 1f)
        if (scroll == previous) return
        this.scroll = scroll
        // Whole page to whole page is a jump - a restored page, a recentred
        // loop, a switch with animations off - not a page being moved: the
        // dot goes straight there and nothing wakes.
        val jump = scroll == floor(scroll) && previous == floor(previous)
        if (style == IndicatorStyle.DOTS) {
            val target = PageIndicatorMath.dotTarget(scroll, pageCount)
            if (jump) {
                dotWalk?.cancel()
                dotTarget = target
                dotPosition = target
            } else {
                walkDotTo(target)
            }
        }
        invalidate()
        // Faded out with the rest of the home screen (edit mode, the drawer,
        // the feed): it keeps up with the pages but stays out of sight.
        if (!jump && alpha > 0f && visibility == VISIBLE) show()
    }

    // Straight from the pager's onPageScrollStateChanged: held under a finger
    // it stays in sight, however still the page is.
    fun onScrollStateChanged(state: Int) {
        dragging = state == ViewPager2.SCROLL_STATE_DRAGGING
        if (dragging) removeCallbacks(hide) else scheduleHide()
    }

    private fun show() {
        fadeTo(1f, FADE_IN_MS)
        if (dragging) removeCallbacks(hide) else scheduleHide()
    }

    private fun scheduleHide() {
        removeCallbacks(hide)
        if (autoHide) postDelayed(hide, HIDE_DELAY_MS)
    }

    private fun fadeTo(target: Float, durationMs: Long) {
        if (target == shownTarget && (fade != null || shown == target)) return
        shownTarget = target
        fade?.cancel()
        val duration = (durationMs * motion).toLong()
        if (duration <= 0L) {
            shown = target
            invalidate()
            return
        }
        fade = ValueAnimator.ofFloat(shown, target).apply {
            this.duration = duration
            addUpdateListener {
                shown = it.animatedValue as Float
                invalidate()
            }
            doOnEnd { if (fade === it) fade = null }
            start()
        }
    }

    // Launcher3's animateToPosition: a step at a time toward the latest
    // target, picking the next step up when one ends, so a target that moves
    // mid-step is simply where the walk goes next.
    private fun walkDotTo(target: Float) {
        dotTarget = target
        if (abs(dotPosition - target) < PageIndicatorMath.SETTLE_ZONE) dotPosition = target
        if (dotWalk != null || dotPosition == target) return
        val duration = (DOT_STEP_MS * motion).toLong()
        if (duration <= 0L) {
            dotPosition = target
            return
        }
        var cancelled = false
        dotWalk = ValueAnimator.ofFloat(dotPosition, PageIndicatorMath.nextDotStop(dotPosition, target)).apply {
            this.duration = duration
            addUpdateListener {
                dotPosition = it.animatedValue as Float
                invalidate()
            }
            doOnCancel { cancelled = true }
            doOnEnd {
                if (dotWalk === it) dotWalk = null
                if (!cancelled) walkDotTo(dotTarget)
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(hide)
        fade?.cancel()
        dotWalk?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(dp(HEIGHT_DP).toInt(), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (pageCount <= 1 || shown <= 0f || style == IndicatorStyle.NONE) return
        // Mid-fade the marks are drawn whole into a layer that fades as one,
        // or the current page's mark would show the dot under it through.
        val layered = shown < 1f
        if (layered) canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (shown * 255).toInt())
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        if (rtl) {
            canvas.save()
            canvas.scale(-1f, 1f, width / 2f, 0f)
        }
        val centerY = height / 2f
        paint.color = color
        val baseAlpha = Color.alpha(color)
        if (style == IndicatorStyle.LINE) {
            val half = dp(LINE_HEIGHT_DP) / 2f
            val left = PageIndicatorMath.lineLeft(scroll, pageCount, width.toFloat())
            rect.set(left, centerY - half, left + width.toFloat() / pageCount, centerY + half)
            paint.alpha = (baseAlpha * LINE_ALPHA).toInt()
            canvas.drawRoundRect(rect, half, half, paint)
        } else {
            val radius = dp(DOT_RADIUS_DP)
            val spacing = radius * DOT_SPACING_RADII
            val firstX = (width - (pageCount - 1) * spacing) / 2f
            // A settled dot hides the one it sits on rather than drawing over it.
            val settled = if (style == IndicatorStyle.DOTS && dotPosition == floor(dotPosition)) {
                dotPosition.toInt()
            } else {
                -1
            }
            paint.alpha = (baseAlpha * INACTIVE_ALPHA).toInt()
            for (i in 0 until pageCount) {
                if (i != settled) canvas.drawCircle(firstX + i * spacing, centerY, radius, paint)
            }
            paint.alpha = baseAlpha
            if (style == IndicatorStyle.DOTS) {
                PageIndicatorMath.dotSpan(dotPosition, radius, spacing, span)
                rect.set(firstX + span[0], centerY - radius, firstX + span[1], centerY + radius)
                canvas.drawRoundRect(rect, radius, radius, paint)
            } else {
                val x = firstX + PageIndicatorMath.wrapped(scroll, pageCount) * spacing
                canvas.drawCircle(x, centerY, radius * CIRCLE_CURRENT_SCALE, paint)
            }
        }
        if (rtl) canvas.restore()
        if (layered) canvas.restore()
    }


    companion object {
        // The indicator's height: the strip the marks centre in.
        const val HEIGHT_DP = 24f
        // The tallest mark drawn, the CIRCLE style's current page, for the
        // room the home screen keeps it.
        const val MARK_HEIGHT_DP = 12f

        private const val DOT_RADIUS_DP = 4f
        // Launcher3's DOT_GAP_FACTOR: dot centres three radii apart.
        private const val DOT_SPACING_RADII = 3f
        private const val CIRCLE_CURRENT_SCALE = 1.5f
        private const val LINE_HEIGHT_DP = 1f
        // The line is 70% of its color; Launcher3's resting dots half.
        private const val LINE_ALPHA = 0.7f
        private const val INACTIVE_ALPHA = 0.5f
        private const val DOT_STEP_MS = 150L
        // ViewConfiguration's scroll bar fade and delay.
        private const val FADE_IN_MS = 250L
        private const val FADE_OUT_MS = 250L
        private const val HIDE_DELAY_MS = 300L
    }
}
