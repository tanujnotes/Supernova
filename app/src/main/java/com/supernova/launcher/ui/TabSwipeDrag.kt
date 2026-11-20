package com.supernova.launcher.ui

import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.sign

// A sideways swipe on the drawer list moves to the neighbouring tab the way
// the classic launcher's tab pages do (#278): the list follows the finger,
// and the lift changes tab past 40% of the width or on a fling over
// 500dp/s, unless the fling heads back the other way from a third of the
// width on. Anything less springs back. Before, only a quick fling switched,
// and a deliberate drag that slowed before the lift did nothing at all.
//
// Watching only, so vertical scrolling is untouched, until the gesture
// reads as a sideways swipe. Then it is taken: the row under the finger
// gets a cancel and drops its press, so a swipe never opens the app it
// crosses.
internal class TabSwipeDrag(
    // What moves with the finger: the list's container.
    private val list: View,
    private val swipeable: () -> Boolean,
    // Whether there is a tab that way (+1 the next, -1 the previous).
    private val hasTab: (Int) -> Boolean,
    // The tab that way is picked; the list is remainingPx short of where a
    // page would sit and the finger left at velocityPxPerS.
    private val onCommit: (delta: Int, remainingPx: Float, velocityPxPerS: Float) -> Unit,
    private val animate: () -> Boolean,
    // True as the list starts to move, false once a drag that changed
    // nothing is back in place (a commit's settle is the caller's).
    private val onMoving: (Boolean) -> Unit
) : RecyclerView.SimpleOnItemTouchListener() {

    private val config = ViewConfiguration.get(list.context)
    private val slop = config.scaledTouchSlop
    private val pagingSlop = config.scaledPagingTouchSlop
    private val maxVelocity = config.scaledMaximumFlingVelocity.toFloat()
    private val density = list.resources.displayMetrics.density

    private var downX = 0f
    private var downY = 0f
    private var startX = 0f
    private var totalMotion = 0f
    private var lastX = 0f
    private var dragging = false
    private var tracker: VelocityTracker? = null

    override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX
                downY = e.rawY
                dragging = false
                tracker?.recycle()
                tracker = VelocityTracker.obtain()
                track(e)
            }
            MotionEvent.ACTION_MOVE -> {
                track(e)
                val dx = abs(e.rawX - downX)
                val dy = abs(e.rawY - downY)
                if (dx > slop && dx > dy * SIDEWAYS_RATIO && swipeable()) start(rv, e.rawX)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> release()
        }
        return dragging
    }

    override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
        if (!dragging) return
        track(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> follow(e.rawX)
            MotionEvent.ACTION_UP -> finish(e.rawX, cancelled = false)
            MotionEvent.ACTION_CANCEL -> finish(e.rawX, cancelled = true)
        }
    }

    // Screen coordinates throughout: the list's own move with the list,
    // which follows the finger, so they saw only part of each drag and
    // its speed.
    private fun track(e: MotionEvent) {
        val onScreen = MotionEvent.obtain(e)
        onScreen.setLocation(e.rawX, e.rawY)
        tracker?.addMovement(onScreen)
        onScreen.recycle()
    }

    private fun start(rv: RecyclerView, x: Float) {
        dragging = true
        // From where the swipe was claimed, so the list doesn't jump by the
        // slop.
        startX = x
        lastX = x
        totalMotion = 0f
        list.animate().cancel()
        onMoving(true)
        // The fade below would otherwise redraw the whole list offscreen on
        // every frame.
        list.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        rv.parent?.requestDisallowInterceptTouchEvent(true)
    }

    private fun follow(x: Float) {
        totalMotion += abs(x - lastX)
        lastX = x
        list.translationX = offsetFor(x - startX)
        val width = list.width.takeIf { it > 0 } ?: return
        list.alpha = 1f - FADE * (abs(list.translationX) / width).coerceAtMost(1f)
    }

    // Past the first or last tab (Loop tabs off) the list gives a little and
    // comes back, like the edge of a page.
    private fun offsetFor(dx: Float): Float {
        val delta = if (dx < 0) 1 else -1
        return if (dx != 0f && !hasTab(delta)) dx * EDGE_RESISTANCE else dx
    }

    private fun finish(x: Float, cancelled: Boolean) {
        val velocityTracker = tracker
        velocityTracker?.computeCurrentVelocity(MS_PER_S, maxVelocity)
        val velocity = velocityTracker?.xVelocity ?: 0f
        release()
        val width = list.width.toFloat()
        // Measured from the press, as the pager does; the list itself only
        // moved from where the swipe was claimed.
        val dx = x - downX
        val delta = if (cancelled || width <= 0f) 0 else target(dx, velocity, width)
        if (delta != 0 && hasTab(delta)) {
            onCommit(delta, (width - abs(list.translationX)).coerceAtLeast(0f), abs(velocity))
        } else {
            springBack(abs(velocity))
        }
    }

    // The classic launcher pager's choice on a lift: a significant move goes
    // the way it went, a fling the way it flew, and a fling back against a
    // long move stays.
    private fun target(dx: Float, velocity: Float, width: Float): Int {
        val significant = abs(dx) > width * SIGNIFICANT_MOVE
        val fling = totalMotion > pagingSlop && abs(velocity) > FLING_THRESHOLD_DP * density
        val flungBack = fling && abs(dx) > width * RETURN_THRESHOLD && sign(velocity) != sign(dx)
        return when {
            flungBack -> 0
            fling -> if (velocity < 0) 1 else -1
            significant -> if (dx < 0) 1 else -1
            else -> 0
        }
    }

    // Back in the pager's settle time for the finger's speed.
    private fun springBack(velocityPxPerS: Float) {
        val settled = {
            list.setLayerType(View.LAYER_TYPE_NONE, null)
            onMoving(false)
        }
        if (!animate() || (list.translationX == 0f && list.alpha == 1f)) {
            list.translationX = 0f
            list.alpha = 1f
            settled()
            return
        }
        val ms = PageFlingSettle.durationMs(abs(list.translationX).toInt(), list.width, velocityPxPerS, density)
        list.animate().translationX(0f).alpha(1f)
            .setDuration(ms.toLong())
            .setInterpolator(PageFlingSettle.QuinticOut)
            .withEndAction(settled)
            .start()
    }

    private fun release() {
        dragging = false
        tracker?.recycle()
        tracker = null
    }

    private companion object {
        const val SIDEWAYS_RATIO = 1.5f
        // The classic launcher pager's thresholds.
        const val SIGNIFICANT_MOVE = 0.4f
        const val RETURN_THRESHOLD = 0.33f
        const val FLING_THRESHOLD_DP = 500f
        const val EDGE_RESISTANCE = 0.25f
        // How far the leaving list fades by a full width's drag.
        const val FADE = 0.5f
        const val MS_PER_S = 1000
    }
}
