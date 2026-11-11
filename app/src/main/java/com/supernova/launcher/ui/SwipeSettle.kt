package com.supernova.launcher.ui

import android.view.animation.Interpolator
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToLong

// How a surface that a finger was dragging - the drawer's sheet, the feed's
// pane - comes to rest once it is let go.
//
// A fixed-length ease-out starts at twice its average speed whatever the
// finger was doing: let go of a slow drag and the surface leapt away at
// several times the finger's speed, which read as a snap. This is a spring
// with no bounce instead (critically damped). It carries on from the speed
// the finger left it at, picks up smoothly, and eases in to the end; a faster
// fling arrives sooner, and never past the end.
//
// An Interpolator, so the animators already in place take it along with
// durationMs.
class SwipeSettle private constructor(
    val durationMs: Long,
    private val omega: Float,
    // omega less the release's own rate (speed over the distance left).
    private val rate: Float
) : Interpolator {

    private val seconds = durationMs / MS_PER_SECOND
    // What is covered by the time it stops; scaled out so it ends exactly on 1.
    private val covered = 1f - left(seconds)

    override fun getInterpolation(input: Float): Float =
        if (durationMs == 0L || covered <= 0f) 1f else (1f - left(input * seconds)) / covered

    // The share of the distance still to go, t seconds in.
    private fun left(t: Float): Float = (1f + rate * t) * exp(-omega * t)

    companion object {
        // How long a release from a standstill takes: the same as the drawer's
        // own slide (DrawerController.SLIDE_MS), at the user's request - 320
        // and 400 were tried and read as slow. Half the travel is done in
        // under a quarter of it and nine tenths by half way; the rest is the
        // ease into place.
        const val NOMINAL_MS = 240L

        private const val MS_PER_SECOND = 1000f
        // At rest once this share of the distance is left...
        private const val REST = 0.004f
        // ...which a release from a standstill reaches when omega * t is this:
        // (1 + x) * e^-x = REST.
        private const val REST_AT = 7.7f
        private const val LONGEST = 2f
        private const val STEP = 0.001f

        // remaining: how far there is to go, in any unit. velocity: the same
        // unit per second, positive towards the end. nominalMs: NOMINAL_MS
        // through the animation speed setting; 0 lands at once.
        fun of(remaining: Float, velocity: Float, nominalMs: Long): SwipeSettle {
            if (nominalMs <= 0L || remaining < 1f) return SwipeSettle(0L, 1f, 0f)
            val base = REST_AT / (nominalMs / MS_PER_SECOND)
            // A finger still moving the other way is turned round smoothly,
            // within reason.
            val release = (velocity / remaining).coerceAtLeast(-base)
            // A fling faster than the spring would overshoot it: stiffen the
            // spring to the fling, and the surface simply decelerates in.
            val omega = max(base, release)
            val rate = omega - release
            val longest = LONGEST * nominalMs / MS_PER_SECOND
            var t = STEP
            while (t < longest && (1f + rate * t) * exp(-omega * t) > REST) t += STEP
            return SwipeSettle((t * MS_PER_SECOND).roundToLong().coerceAtLeast(1L), omega, rate)
        }
    }
}
