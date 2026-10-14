package com.supernova.launcher.gesture

import kotlin.math.abs

// Which home-screen swipe a finger's travel amounts to, decided purely on the
// distance between the press and the lift - no velocity anywhere, so a slow
// drag counts exactly like a flick. The
// detector used to ride GestureDetector.onFling, which meant a drag that
// paused before the lift was no swipe at all.
//
// Kept free of MotionEvent so the rules can be unit-tested directly.
object HomeSwipeClassifier {

    // dx/dy are lift-minus-press, in pixels, screen axes (dy < 0 is upward).
    // canScroll answers whether the workspace pager can still scroll that way
    // (+1 = towards the next page): horizontal swipes belong to the pager
    // until it runs out of pages.
    fun classify(
        dx: Float,
        dy: Float,
        minDistance: Float,
        bias: Float,
        canScroll: (direction: Int) -> Boolean
    ): Gesture? {
        // Vertical swipes take priority, mirroring the original detector.
        if (abs(dy) >= minDistance && abs(dy) >= abs(dx) * bias) {
            return if (dy < 0) Gesture.SWIPE_UP else Gesture.SWIPE_DOWN
        }
        // Horizontal edge swipes: only when the pager is already at the edge
        // in that direction (a left swipe would scroll forward).
        if (abs(dx) >= minDistance && abs(dx) > abs(dy) * bias) {
            val direction = if (dx < 0) 1 else -1
            if (!canScroll(direction)) {
                return if (dx < 0) Gesture.SWIPE_LEFT else Gesture.SWIPE_RIGHT
            }
        }
        return null
    }
}
