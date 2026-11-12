package com.supernova.launcher.ui

import android.view.MotionEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardSwipeCloseTest {

    private fun shown() = CardSwipeClose(windowMs = 3_000).apply { onCardShown() }

    @Test
    fun cardClosedOnTheHomeSwipesPressCountsForItsHomePress() {
        val close = shown()
        close.onTouch(MotionEvent.ACTION_DOWN)
        close.onCardClosed(now = 1_000, carried = false)
        assertTrue(close.takeFor(now = 1_800))
    }

    @Test
    fun pressInAnotherWindowCountsToo() {
        val close = shown()
        close.onTouch(MotionEvent.ACTION_OUTSIDE)
        close.onCardClosed(now = 1_000, carried = false)
        assertTrue(close.takeFor(now = 1_200))
    }

    @Test
    fun cardClosedByATapDoesNotCount() {
        val close = shown()
        close.onTouch(MotionEvent.ACTION_DOWN)
        close.onTouch(MotionEvent.ACTION_UP)
        close.onCardClosed(now = 1_000, carried = false)
        assertFalse(close.takeFor(now = 1_100))
    }

    @Test
    fun cardClosedAfterTheSystemTookTheSwipeDoesNotCount() {
        // The press reached the card, the system cancelled it, and the home
        // press itself closed the card: that press had a job already.
        val close = shown()
        close.onTouch(MotionEvent.ACTION_DOWN)
        close.onTouch(MotionEvent.ACTION_MOVE)
        close.onTouch(MotionEvent.ACTION_CANCEL)
        close.onCardClosed(now = 1_000, carried = false)
        assertFalse(close.takeFor(now = 1_000))
    }

    @Test
    fun cardClosedUnderADragDoesNotCount() {
        val close = shown()
        close.onTouch(MotionEvent.ACTION_DOWN)
        close.onCardClosed(now = 1_000, carried = true)
        assertFalse(close.takeFor(now = 1_100))
    }

    @Test
    fun homePressLongAfterTheCloseIsAPressOfItsOwn() {
        val close = shown()
        close.onTouch(MotionEvent.ACTION_DOWN)
        close.onCardClosed(now = 1_000, carried = false)
        assertFalse(close.takeFor(now = 4_001))
    }

    @Test
    fun closeIsReadOnce() {
        val close = shown()
        close.onTouch(MotionEvent.ACTION_DOWN)
        close.onCardClosed(now = 1_000, carried = false)
        assertTrue(close.takeFor(now = 1_500))
        assertFalse(close.takeFor(now = 1_600))
    }

    @Test
    fun newCardForgetsTheLastOnesPress() {
        val close = shown()
        close.onTouch(MotionEvent.ACTION_DOWN)
        close.onCardShown()
        close.onCardClosed(now = 1_000, carried = false)
        assertFalse(close.takeFor(now = 1_100))
    }

    @Test
    fun nothingClosedNothingCounted() {
        assertFalse(shown().takeFor(now = 1_000))
    }
}
