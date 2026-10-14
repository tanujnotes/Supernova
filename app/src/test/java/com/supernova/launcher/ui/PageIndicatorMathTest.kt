package com.supernova.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PageIndicatorMathTest {

    private val delta = 0.0001f

    @Test
    fun theDotSettlesNearAPageAndSpansTwoInBetween() {
        assertEquals(1f, PageIndicatorMath.dotTarget(1.05f, 4), delta)
        assertEquals(1.5f, PageIndicatorMath.dotTarget(1.2f, 4), delta)
        assertEquals(1.5f, PageIndicatorMath.dotTarget(1.8f, 4), delta)
        assertEquals(2f, PageIndicatorMath.dotTarget(1.95f, 4), delta)
        assertEquals(3f, PageIndicatorMath.dotTarget(3f, 4), delta)
    }

    @Test
    fun aWrapFromTheLastPageSwitchesEndsHalfWay() {
        assertEquals(2f, PageIndicatorMath.dotTarget(2.3f, 3), delta)
        assertEquals(0f, PageIndicatorMath.dotTarget(2.7f, 3), delta)
    }

    @Test
    fun theDotWalksHalfAPageAtATime() {
        assertEquals(0.5f, PageIndicatorMath.nextDotStop(0f, 1f), delta)
        assertEquals(1f, PageIndicatorMath.nextDotStop(0.5f, 1f), delta)
        assertEquals(1.5f, PageIndicatorMath.nextDotStop(2f, 1f), delta)
        // Off the half-page grid it still lands on the target, never past it.
        assertEquals(1f, PageIndicatorMath.nextDotStop(0.63f, 1f), delta)
        // The wrap between the ends goes straight across.
        assertEquals(0f, PageIndicatorMath.nextDotStop(4f, 0f), delta)
    }

    @Test
    fun theDotStretchesOntoTheNextPageThenLetsGo() {
        val out = FloatArray(2)
        // On page 1: a plain dot around its centre, 12 along.
        PageIndicatorMath.dotSpan(1f, 4f, 12f, out)
        assertEquals(8f, out[0], delta)
        assertEquals(16f, out[1], delta)
        // Half way: from page 1's left edge to page 2's right edge.
        PageIndicatorMath.dotSpan(1.5f, 4f, 12f, out)
        assertEquals(8f, out[0], delta)
        assertEquals(28f, out[1], delta)
        // Three quarters: the left edge half way across.
        PageIndicatorMath.dotSpan(1.75f, 4f, 12f, out)
        assertEquals(14f, out[0], delta)
        assertEquals(28f, out[1], delta)
    }

    @Test
    fun theGlidingMarksComeInFromTheOtherEndOnAWrap() {
        assertEquals(2.4f, PageIndicatorMath.wrapped(2.4f, 3), delta)
        assertEquals(-0.4f, PageIndicatorMath.wrapped(2.6f, 3), delta)
    }

    @Test
    fun theLineRunsFromEndToEnd() {
        assertEquals(0f, PageIndicatorMath.lineLeft(0f, 3, 900f), delta)
        assertEquals(300f, PageIndicatorMath.lineLeft(1f, 3, 900f), delta)
        assertEquals(600f, PageIndicatorMath.lineLeft(2f, 3, 900f), delta)
        assertEquals(0f, PageIndicatorMath.lineLeft(0f, 1, 900f), delta)
    }
}
