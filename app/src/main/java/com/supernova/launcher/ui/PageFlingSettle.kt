package com.supernova.launcher.ui

import android.util.DisplayMetrics
import android.view.View
import android.view.animation.Interpolator
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SnapHelper
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

// Home page flings settle in a time that shrinks with the fling's speed, as
// the classic launcher pager does (#188). ViewPager2's PagerSnapHelper settles
// every fling in about 300ms however fast it was, so a quick flick sat ~100ms
// behind; a slow release keeps that 300ms ceiling. The snap helper still
// picks the target page and the distance; only the settle's timing changes.
internal object PageFlingSettle {
    fun attach(recycler: RecyclerView) {
        val snap = recycler.onFlingListener as? SnapHelper ?: return
        recycler.onFlingListener = object : RecyclerView.OnFlingListener() {
            override fun onFling(velocityX: Int, velocityY: Int): Boolean =
                settle(recycler, snap, velocityX, velocityY)
        }
    }

    // RecyclerView only calls this past its minimum fling speed.
    private fun settle(recycler: RecyclerView, snap: SnapHelper, velocityX: Int, velocityY: Int): Boolean {
        val layout = recycler.layoutManager
        val target = layout?.let { snap.findTargetSnapPosition(it, velocityX, velocityY) } ?: RecyclerView.NO_POSITION
        if (layout == null || target == RecyclerView.NO_POSITION) return false
        val horizontal = layout.canScrollHorizontally()
        val velocity = abs(if (horizontal) velocityX else velocityY).toFloat()
        val pageSize = if (horizontal) recycler.width else recycler.height
        val density = recycler.resources.displayMetrics.density
        val scroller = object : LinearSmoothScroller(recycler.context) {
            override fun onTargetFound(targetView: View, state: RecyclerView.State, action: Action) {
                val distance = snap.calculateDistanceToFinalSnap(layout, targetView) ?: return
                val (dx, dy) = distance[0] to distance[1]
                val duration = durationMs(abs(if (horizontal) dx else dy), pageSize, velocity, density)
                if (duration > 0) action.update(dx, dy, duration, QuinticOut)
            }

            override fun calculateSpeedPerPixel(displayMetrics: DisplayMetrics): Float =
                MS_PER_INCH / displayMetrics.densityDpi
        }
        scroller.targetPosition = target
        layout.startSmoothScroll(scroller)
        return true
    }

    // The classic pager's snap time: the remaining distance, weighted toward
    // half a page, over the fling's speed (never under a floor), times four;
    // capped at PagerSnapHelper's own ~300ms so no settle gets slower.
    fun durationMs(distancePx: Int, pageSizePx: Int, velocityPxPerS: Float, density: Float): Int {
        if (distancePx <= 0 || pageSizePx <= 0) return 0
        val half = pageSizePx / 2f
        val ratio = (distancePx / (2f * half)).coerceAtMost(1f)
        val weighted = half + half * distanceInfluence(ratio)
        val speed = velocityPxPerS.coerceAtLeast(MIN_SNAP_VELOCITY_DP * density)
        return (SNAP_FACTOR * (MS_PER_S * weighted / speed).roundToInt()).coerceAtMost(MAX_SETTLE_MS)
    }

    private fun distanceInfluence(ratio: Float): Float =
        sin((ratio - HALF) * INFLUENCE * Math.PI.toFloat() / 2f)

    val QuinticOut = Interpolator { t ->
        val u = t - 1f
        u * u * u * u * u + 1f
    }

    private const val MS_PER_INCH = 100f
    private const val MS_PER_S = 1000f
    private const val SNAP_FACTOR = 4
    private const val MIN_SNAP_VELOCITY_DP = 1500f
    private const val MAX_SETTLE_MS = 300
    private const val HALF = 0.5f
    private const val INFLUENCE = 0.3f
}
