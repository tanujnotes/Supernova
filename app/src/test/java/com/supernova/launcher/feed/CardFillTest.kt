package com.supernova.launcher.feed

import org.junit.Assert.assertEquals
import org.junit.Test

class CardFillTest {

    @Test
    fun widgetTakesWhatTheCardLeaves() {
        // A 2000px list, 300px of header, chips, padding and margins.
        assertEquals(1700, CardFill.widgetHeightPx(availablePx = 2000, chromePx = 300, minPx = 180))
    }

    @Test
    fun cardAndWidgetFillTheListExactly() {
        val available = 1834
        val chrome = 257
        assertEquals(available, chrome + CardFill.widgetHeightPx(available, chrome, minPx = 180))
    }

    // A short window (landscape, keyboard up): never below the minimum,
    // the list scrolls instead.
    @Test
    fun neverBelowTheMinimum() {
        assertEquals(180, CardFill.widgetHeightPx(availablePx = 400, chromePx = 300, minPx = 180))
        assertEquals(180, CardFill.widgetHeightPx(availablePx = 100, chromePx = 300, minPx = 180))
    }
}
