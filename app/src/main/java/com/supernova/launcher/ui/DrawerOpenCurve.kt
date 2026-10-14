package com.supernova.launcher.ui

import kotlin.math.pow

// The drawer's open choreography as functions of open progress: 0 is the
// sheet parked off screen, 1 is open. Fractions are of the sheet's full
// travel (its height), so a finger pull and the animated slide read the
// same curve.
//
// Tuned frame by frame (2026-09-19, a swipe-up flick and a 1.2 s drag
// on the Galaxy): the desktop drifts up at about a
// quarter of the finger's pace and is gone after roughly 700 px of the
// 1870 px travel, dropping fastest at the start; a scrim over the
// wallpaper darkens linearly to about half; and the sheet's content is
// invisible until the sheet is nearly half way up, then fades in over the
// next 40% - until then the sheet is a bare panel with the search row on
// its top edge. The fling settles in about 240 ms, decelerating.
object DrawerOpenCurve {
    // Progress at which the desktop has fully faded.
    const val HOME_FADE_END = 0.3f
    // Desktop drift per unit of sheet travel while it can still be seen.
    const val HOME_DRIFT = 0.25f
    // Progress by which the drift has returned to rest, unseen.
    private const val HOME_DRIFT_REST = 0.5f
    const val CONTENT_FADE_START = 0.35f
    const val CONTENT_FADE_END = 0.7f
    const val SCRIM_MAX = 0.5f

    fun homeAlpha(progress: Float): Float {
        val t = (progress / HOME_FADE_END).coerceIn(0f, 1f)
        // Convex: a quarter of the desktop goes in the first 5% of travel.
        return 1f - t.pow(0.7f)
    }

    // How far above its rest position the desktop sits, as a fraction of the
    // sheet's height. It rises with the finger while it can be seen, then
    // eases back to rest while invisible, so an open drawer leaves the pager
    // exactly where drops and taps expect it.
    fun homeShift(progress: Float): Float {
        val p = progress.coerceIn(0f, 1f)
        val peak = HOME_DRIFT * HOME_FADE_END
        return when {
            p <= HOME_FADE_END -> HOME_DRIFT * p
            p < HOME_DRIFT_REST -> peak * (HOME_DRIFT_REST - p) / (HOME_DRIFT_REST - HOME_FADE_END)
            else -> 0f
        }
    }

    fun contentAlpha(progress: Float): Float =
        ((progress - CONTENT_FADE_START) / (CONTENT_FADE_END - CONTENT_FADE_START)).coerceIn(0f, 1f)

    fun scrimAlpha(progress: Float): Float = SCRIM_MAX * progress.coerceIn(0f, 1f)
}
