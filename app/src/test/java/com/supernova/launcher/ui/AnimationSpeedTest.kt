package com.supernova.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AnimationSpeedTest {

    // Stores and backups from before Relaxed/Fast keep a speed near theirs.
    @Test
    fun oldSpeedsReadAsTheNearerOne() {
        assertEquals(AnimationSpeed.RELAXED, AnimationSpeed.fromName("SLOW"))
        assertEquals(AnimationSpeed.RELAXED, AnimationSpeed.fromName("DEFAULT"))
        assertEquals(AnimationSpeed.FAST, AnimationSpeed.fromName("FAST"))
        assertEquals(AnimationSpeed.FAST, AnimationSpeed.fromName("FASTER"))
        assertEquals(AnimationSpeed.RELAXED, AnimationSpeed.fromName("bogus"))
    }

    @Test
    fun fastIsAboutHalfOfRelaxed() {
        assertEquals(AnimationSpeed.RELAXED.factor / 2f, AnimationSpeed.FAST.factor, 0.05f)
    }
}
