package com.supernova.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class EdgeFadeRecyclerViewTest {

    private fun strength(hidden: Int, length: Int = 90) = EdgeFadeRecyclerView.strength(hidden, length)

    @Test
    fun aListAtRestAtAnEdgeIsNotFadedThere() {
        assertEquals(0f, strength(hidden = 0), 0f)
        // Overscroll, or a short list that ends before the edge.
        assertEquals(0f, strength(hidden = -40), 0f)
    }

    @Test
    fun theFadeGrowsInOverItsOwnLength() {
        assertEquals(0.5f, strength(hidden = 45), 0.001f)
        assertEquals(1f, strength(hidden = 90), 0f)
    }

    @Test
    fun anyMoreHiddenThanThatIsAFullFade() {
        assertEquals(1f, strength(hidden = 5000), 0f)
        assertEquals(1f, strength(hidden = Int.MAX_VALUE), 0f)
    }

    @Test
    fun noLengthNoFade() {
        assertEquals(0f, strength(hidden = 500, length = 0), 0f)
    }
}
