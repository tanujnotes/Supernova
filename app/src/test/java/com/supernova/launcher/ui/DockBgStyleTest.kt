package com.supernova.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DockBgStyleTest {

    // A dock saved with the removed Pill style keeps a background.
    @Test
    fun removedPillReadsAsRounded() {
        assertEquals(DockBgStyle.ROUNDED, DockBgStyle.fromName("PILL"))
        assertEquals(DockBgStyle.ROUNDED, DockBgStyle.fromName("pill"))
    }

    @Test
    fun unknownStillFallsBackToNone() {
        assertEquals(DockBgStyle.NONE, DockBgStyle.fromName("SPARKLY"))
        assertEquals(DockBgStyle.SQUARE, DockBgStyle.fromName("SQUARE"))
    }
}
