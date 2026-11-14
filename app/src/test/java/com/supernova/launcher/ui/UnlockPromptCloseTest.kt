package com.supernova.launcher.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnlockPromptCloseTest {

    private fun asked() = UnlockPromptClose(windowMs = 3_000).apply { onPromptAsked() }

    @Test
    fun homePressOverTheShowingPromptClosesIt() {
        assertTrue(asked().takeFor(now = 1_000))
    }

    @Test
    fun promptGoneAsTheSwipeBeganStillCountsForItsHomePress() {
        // #135: the launcher is back in front before the swipe's home press.
        val prompt = asked()
        prompt.onResumed(now = 1_000)
        assertTrue(prompt.takeFor(now = 1_600))
    }

    @Test
    fun homePressLongAfterThePromptWentIsAPressOfItsOwn() {
        val prompt = asked()
        prompt.onResumed(now = 1_000)
        assertFalse(prompt.takeFor(now = 4_001))
    }

    @Test
    fun touchOnTheHomeScreenAfterThePromptWentEndsIt() {
        val prompt = asked()
        prompt.onResumed(now = 1_000)
        prompt.onHomeTouched()
        assertFalse(prompt.takeFor(now = 1_200))
    }

    @Test
    fun touchWhileThePromptShowsDoesNotEndIt() {
        // The prompt has the screen; a touch reaching the launcher then is
        // not the user using the home screen, and a later resume restarts
        // the window anyway.
        val prompt = asked()
        prompt.onHomeTouched()
        assertTrue(prompt.takeFor(now = 1_000))
    }

    @Test
    fun spaceOpenedItsCardNothingLeftToCount() {
        val prompt = asked()
        prompt.onResumed(now = 1_000)
        prompt.forget()
        assertFalse(prompt.takeFor(now = 1_100))
    }

    @Test
    fun closeIsReadOnce() {
        val prompt = asked()
        assertTrue(prompt.takeFor(now = 1_000))
        assertFalse(prompt.takeFor(now = 1_100))
    }

    @Test
    fun resumeWithoutAPromptCountsNothing() {
        val prompt = UnlockPromptClose(windowMs = 3_000)
        prompt.onResumed(now = 1_000)
        assertFalse(prompt.takeFor(now = 1_100))
    }
}
