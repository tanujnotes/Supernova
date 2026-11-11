package com.supernova.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeSettleTest {

    private val nominal = SwipeSettle.NOMINAL_MS

    private fun settle(remaining: Float, velocity: Float) = SwipeSettle.of(remaining, velocity, nominal)

    // Speed at the very start, in the caller's units per second.
    private fun startSpeed(settle: SwipeSettle, remaining: Float): Float {
        val step = 0.0005f
        return settle.getInterpolation(step) * remaining / (step * settle.durationMs / 1000f)
    }

    @Test
    fun itStartsAndEndsWhereItShould() {
        for (velocity in listOf(0f, 800f, 4000f, 20000f, -300f)) {
            val settle = settle(1500f, velocity)
            assertEquals(0f, settle.getInterpolation(0f), 0f)
            assertEquals(1f, settle.getInterpolation(1f), 0.0001f)
        }
    }

    @Test
    fun itCarriesOnAtTheSpeedTheFingerLeftItAt() {
        // The point of it: no jump in speed at the release.
        for (velocity in listOf(800f, 4000f, 9000f)) {
            val settle = settle(1500f, velocity)
            // Measured over a short first step, in which it has already
            // begun to pick up.
            assertEquals(velocity, startSpeed(settle, 1500f), 150f)
        }
    }

    @Test
    fun aReleaseFromAStandstillStartsSlowAndTakesTheNominalTime() {
        val settle = settle(1500f, 0f)
        assertEquals(nominal.toFloat(), settle.durationMs.toFloat(), 3f)
        // The old ease-out left at over 12000 px/s here.
        assertTrue(startSpeed(settle, 1500f) < 100f)
    }

    @Test
    fun itNeverGoesPastTheEndOrBackwards() {
        for (velocity in listOf(0f, 3000f, 30000f, 200000f)) {
            val settle = settle(900f, velocity)
            var last = 0f
            for (i in 1..200) {
                val now = settle.getInterpolation(i / 200f)
                assertTrue("backwards at $i for $velocity", now >= last - 0.0001f)
                assertTrue("past the end at $i for $velocity", now <= 1.0001f)
                last = now
            }
        }
    }

    @Test
    fun aFasterFlingArrivesSooner() {
        val slow = settle(1500f, 0f).durationMs
        val quick = settle(1500f, 6000f).durationMs
        val flung = settle(1500f, 60000f).durationMs
        assertTrue(quick < slow)
        assertTrue(flung < quick)
        assertTrue(flung >= 1L)
    }

    @Test
    fun aFingerGoingTheOtherWayIsTurnedRoundNotLostTrackOf() {
        // Hard the other way: it gives ground first, then comes back.
        val settle = settle(1000f, -20000f)
        assertTrue(settle.getInterpolation(0.01f) < 0f)
        assertEquals(1f, settle.getInterpolation(1f), 0.0001f)
        assertTrue(settle.durationMs <= 2 * nominal)
    }

    @Test
    fun nothingToAnimateWithAnimationsOffOrNowhereToGo() {
        assertEquals(0L, SwipeSettle.of(1500f, 3000f, 0L).durationMs)
        assertEquals(0L, settle(0.2f, 3000f).durationMs)
        assertEquals(1f, SwipeSettle.of(1500f, 3000f, 0L).getInterpolation(0.3f), 0f)
    }
}
