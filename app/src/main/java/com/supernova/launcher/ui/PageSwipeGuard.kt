package com.supernova.launcher.ui

import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs

// Makes a home swipe one thing: a page turn or a swipe up or down, never both.
//
// The pager starts scrolling once a finger has moved sideways past its slop,
// however far it has gone up or down by then, while the swipe-up and
// swipe-down bindings are judged on the lift from the whole path. A pull for
// the notification shade a little off true did both: the page flipped and the
// shade came down. Holding the pager off only while the path stayed within 30
// degrees of vertical left a band (30 to 40 degrees, where the swipe-down
// still fires) and a swipe that curved at the end doing both.
//
// Launcher3 decides once, at the touch slop: a finger that leaves steeper
// than 45 degrees belongs to its status bar and all-apps pulls, which take
// the stream there; a flatter one is left to the workspace. This does the
// same: a steep start holds the pager's input off until the lift, and a
// stream the pager did take says so, for the lift to drop the swipe up or
// down (HomeSwipeClassifier).
//
// Observes the activity's dispatchTouchEvent stream, ahead of the views.
class PageSwipeGuard(private val pager: ViewPager2) {

    private val slop = ViewConfiguration.get(pager.context).scaledTouchSlop.toFloat()
    private var downX = 0f
    private var downY = 0f
    // One finger down on an idle pager, the pager not scrolling yet.
    private var watching = false
    // The finger has left the slop, and the stream's direction is settled.
    private var decided = false
    private var held = false

    // The pager scrolled under this stream's finger. Kept from the move it
    // started on until the next press, so the lift's swipe can read it.
    var pageTookStream = false
        private set

    fun onTouch(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                release()
                downX = ev.x
                downY = ev.y
                decided = false
                pageTookStream = false
                // A press that catches a page mid-settle already has the
                // pager dragging; taking its input away would strand it.
                watching = pager.scrollState == ViewPager2.SCROLL_STATE_IDLE
            }
            // Pinches and two-finger swipes are not page swipes to judge.
            MotionEvent.ACTION_POINTER_DOWN -> {
                watching = false
                release()
            }
            MotionEvent.ACTION_MOVE -> if (watching && !notePagerScrolling() && !decided) {
                val vertical = isVerticalSwipe(ev.x - downX, ev.y - downY, slop)
                if (vertical != null) {
                    decided = true
                    hold(vertical)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // The pager may have started on the very last move.
                if (watching) notePagerScrolling()
                watching = false
                release()
            }
        }
    }

    // Whether the page is under the finger now; it stays there.
    private fun notePagerScrolling(): Boolean {
        if (pager.scrollState == ViewPager2.SCROLL_STATE_IDLE) return false
        watching = false
        pageTookStream = true
        return true
    }

    private fun hold(on: Boolean) {
        if (on == held) return
        held = on
        pager.isUserInputEnabled = !on
    }

    private fun release() = hold(false)

    companion object {
        // How a finger that has travelled (dx, dy) from its press settles
        // the stream: true for a swipe up or down, which the pager is kept
        // out of until the lift, false for one the pager may take, null
        // while it is still inside the slop.
        fun isVerticalSwipe(dx: Float, dy: Float, slop: Float): Boolean? = when {
            abs(dx) <= slop && abs(dy) <= slop -> null
            else -> abs(dy) > abs(dx)
        }
    }
}
