package com.supernova.launcher.ui

import android.animation.ValueAnimator
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import androidx.core.animation.doOnEnd
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
// The neighbouring tab rides in beside the list as it moves, as the home
// pages do: the caller places it from onOffset, and a commit settles the
// list a whole width over, where that tab sits, before handing over.
//
// Watching only, so vertical scrolling is untouched, until the gesture
// reads as a sideways swipe. Then it is taken: the row under the finger
// gets a cancel and drops its press, so a swipe never opens the app it
// crosses.
internal class TabSwipeDrag(
    // What moves with the finger: the list's container.
    private val list: View,
    private val host: Host
) : RecyclerView.SimpleOnItemTouchListener() {

    interface Host {
        fun swipeable(): Boolean

        // Whether there is a tab that way (+1 the next, -1 the previous).
        fun hasTab(delta: Int): Boolean

        // The list's offset as it moves, on every frame of the drag and the
        // settles; 0 once it is back in place.
        fun onOffset(offset: Float)

        // The tab that way has slid fully in: the caller shows it in the
        // list and puts the list back in place.
        fun onLanded(delta: Int)

        fun animate(): Boolean

        // True as the list starts to move, false once a drag that changed
        // nothing is back in place (a landing is the caller's).
        fun onMoving(moving: Boolean)
    }

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
    private var settle: ValueAnimator? = null

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
                if (dx > slop && dx > dy * SIDEWAYS_RATIO && host.swipeable()) start(rv, e.rawX)
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
        // A settle still running finishes at once: a landing lands, a
        // spring back is back in place.
        settle?.end()
        list.animate().cancel()
        host.onMoving(true)
        rv.parent?.requestDisallowInterceptTouchEvent(true)
    }

    private fun follow(x: Float) {
        totalMotion += abs(x - lastX)
        lastX = x
        val dx = x - startX
        // Past the first or last tab (Loop tabs off) the list gives a little
        // and comes back, like the edge of a page.
        val delta = if (dx < 0) 1 else -1
        moveTo(if (dx != 0f && !host.hasTab(delta)) dx * EDGE_RESISTANCE else dx)
    }

    private fun moveTo(offset: Float) {
        list.translationX = offset
        host.onOffset(offset)
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
        if (delta != 0 && host.hasTab(delta)) {
            // On a whole width over, where the next tab sits.
            settleTo(-delta * width, abs(velocity)) { host.onLanded(delta) }
        } else {
            // Back in place.
            settleTo(0f, abs(velocity)) { host.onMoving(false) }
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

    // In the pager's settle time for the finger's speed.
    private fun settleTo(target: Float, velocityPxPerS: Float, done: () -> Unit) {
        val from = list.translationX
        if (!host.animate() || from == target) {
            moveTo(target)
            done()
            return
        }
        val ms = PageFlingSettle.durationMs(abs(target - from).toInt(), list.width, velocityPxPerS, density)
        settle = ValueAnimator.ofFloat(from, target).apply {
            duration = ms.toLong()
            interpolator = PageFlingSettle.QuinticOut
            addUpdateListener { moveTo(it.animatedValue as Float) }
            // On end or cancel alike: the next drag ends a running settle.
            doOnEnd {
                if (settle === it) settle = null
                moveTo(target)
                done()
            }
            start()
        }
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
        const val MS_PER_S = 1000
    }
}
