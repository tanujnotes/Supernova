package com.supernova.launcher.ui

import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs

// Keeps a mostly vertical swipe from also turning the home page.
//
// The pager starts scrolling as soon as a finger has moved sideways past the
// touch slop, however far it has gone up or down by then: a swipe down for the
// notification shade that drifted a little dragged the page along, and a fast
// one flung it to the next page. Launcher3 refuses to start a page scroll
// while the finger's path from the press is steeper than 60 degrees
// (Workspace.MAX_SWIPE_ANGLE); this does the same, by holding the pager's
// input off for as long as that is true.
//
// Observes the activity's dispatchTouchEvent stream, ahead of the views.
class PageSwipeGuard(private val pager: ViewPager2) {

    private val slop = ViewConfiguration.get(pager.context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    // Still to be decided: one finger down, and the pager not scrolling yet.
    private var watching = false
    private var held = false

    fun onTouch(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                release()
                downX = ev.x
                downY = ev.y
                // A press that catches a page mid-settle already has the
                // pager dragging; taking its input away would strand it.
                watching = pager.scrollState == ViewPager2.SCROLL_STATE_IDLE
            }
            // Pinches and two-finger swipes are not page swipes to judge.
            MotionEvent.ACTION_POINTER_DOWN -> {
                watching = false
                release()
            }
            MotionEvent.ACTION_MOVE -> if (watching) {
                if (pager.scrollState != ViewPager2.SCROLL_STATE_IDLE) {
                    // The page is under the finger now; it stays there.
                    watching = false
                } else {
                    // Judged from the press on every move, as Launcher3 does:
                    // a swipe that sets off steeply and then levels out still
                    // turns the page.
                    hold(blocksPageScroll(ev.x - downX, ev.y - downY, slop.toFloat()))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                watching = false
                release()
            }
        }
    }

    private fun hold(on: Boolean) {
        if (on == held) return
        held = on
        pager.isUserInputEnabled = !on
    }

    private fun release() = hold(false)

    companion object {
        // tan(60 degrees): the path is steeper than that when the vertical
        // travel is more than this many times the horizontal.
        private const val STEEP = 1.7320508f

        // Whether a finger that has travelled (dx, dy) from its press would
        // start a page scroll that this guard refuses. Below the slop
        // sideways the pager would not start one anyway.
        fun blocksPageScroll(dx: Float, dy: Float, slop: Float): Boolean =
            abs(dx) > slop && abs(dy) > abs(dx) * STEEP
    }
}
