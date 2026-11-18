package com.supernova.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LargeIconBoxTest {

    @Test
    fun `one cell is the icon box`() {
        assertEquals(147, largeIconBoxPx(1, 216, 243, 147, 11))
    }

    @Test
    fun `a 2x2 icon grows by a cell height, top and label on the rows' lines`() {
        // 243 + 147: the box a centred item needs to put its icon's top on
        // the top row's icon line and its label on the bottom row's.
        assertEquals(390, largeIconBoxPx(2, 216, 243, 147, 11))
    }

    @Test
    fun `it is never wider than its block less the padding`() {
        // Tall cells: 400 + 147 would spill past 2 x 200 - 2 x 11.
        assertEquals(378, largeIconBoxPx(2, 200, 400, 147, 11))
    }

    @Test
    fun `a dense grid shrinks it with the one-cell icons`() {
        // At half scale the one-cell box is 74: 243 + 74.
        assertEquals(317, largeIconBoxPx(2, 216, 243, 74, 6))
    }
}
