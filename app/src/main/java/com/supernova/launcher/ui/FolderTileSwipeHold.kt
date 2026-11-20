package com.supernova.launcher.ui

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

// An app with swipes in an open folder (#243). The folder's grid takes any
// move past the touch slop as a scroll - its layouts always report one, even
// when every app fits - and cancelled the tile before the lift, so no swipe
// ever ran. The tile holds the gesture from the press until the finger has
// gone one way: a flick the tile binds stays the tile's, anything else goes
// back to the grid, which then scrolls or pages from where the finger went
// down.
class FolderTileSwipeHold(view: View, private val claimsUp: Boolean, private val claimsDown: Boolean) {
    private val slop = ViewConfiguration.get(view.context).scaledTouchSlop
    private var startX = 0f
    private var startY = 0f
    private var settled = false

    fun onTouch(v: View, event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> onDown(v, event.rawX, event.rawY)
            MotionEvent.ACTION_MOVE -> onMove(v, event.rawX, event.rawY)
        }
    }

    private fun onDown(v: View, rawX: Float, rawY: Float) {
        startX = rawX
        startY = rawY
        settled = false
        v.parent?.requestDisallowInterceptTouchEvent(true)
    }

    private fun onMove(v: View, rawX: Float, rawY: Float) {
        if (settled) return
        val dx = rawX - startX
        val dy = rawY - startY
        if (abs(dx) <= slop && abs(dy) <= slop) return
        settled = true
        if (!keeps(dx, dy, claimsUp, claimsDown)) v.parent?.requestDisallowInterceptTouchEvent(false)
    }

    companion object {
        // dx, dy: the finger's travel in screen pixels, down positive.
        fun keeps(dx: Float, dy: Float, claimsUp: Boolean, claimsDown: Boolean): Boolean =
            (claimsUp && -dy > abs(dx)) || (claimsDown && dy > abs(dx))
    }
}
