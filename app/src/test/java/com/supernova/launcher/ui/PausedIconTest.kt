package com.supernova.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PausedIconTest {

    @Test
    fun onlyAPausedProfileMarksTheSignature() {
        val state = LauncherUiState(pausedWorkProfiles = setOf(10L))
        assertNotEquals("", PausedIcon.mark(state, 10L))
        assertEquals("", PausedIcon.mark(state, 0L))
        assertEquals("", PausedIcon.mark(LauncherUiState(), 10L))
    }

    @Test
    fun noIconStaysNone() {
        assertNull(PausedIcon.of(null, paused = true))
    }
}
