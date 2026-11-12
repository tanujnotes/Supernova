package com.supernova.launcher.ui

import android.view.MotionEvent

// A folder card, the private space's included, is a window of its own, and a
// press that lands outside its card - the system's home swipe starts at the
// screen's very bottom edge - closes it on the spot. The home press that
// same swipe ends in then found nothing open and ran the home button's
// action, the drawer by default, on top of the close (#110). This remembers
// a card closed with a finger still down, so that press counts as the close.
class CardSwipeClose(private val windowMs: Long = WINDOW_MS) {
    private var pressed = false
    private var closedAt = NONE

    // Every event the card's window is handed.
    fun onTouch(action: Int) {
        when (action) {
            // OUTSIDE: a press in another window, whose lift the card never sees.
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_OUTSIDE -> pressed = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pressed = false
            else -> Unit
        }
    }

    // A new card: nothing of an earlier one's touches carries over.
    fun onCardShown() {
        pressed = false
        closedAt = NONE
    }

    // carried: an app held in the card or dragged out of it; that close is
    // the drag's, not a swipe's.
    fun onCardClosed(now: Long, carried: Boolean) {
        if (pressed && !carried) closedAt = now
        pressed = false
    }

    // At a home press: whether a card closed under the gesture that press
    // ends. Read once.
    fun takeFor(now: Long): Boolean {
        val at = closedAt
        closedAt = NONE
        return at != NONE && now - at in 0..windowMs
    }

    private companion object {
        const val NONE = Long.MIN_VALUE

        // A slow swipe and the system's own animation to home, with room
        // to spare; a card closed by a tap is never counted at all.
        const val WINDOW_MS = 3_000L
    }
}
