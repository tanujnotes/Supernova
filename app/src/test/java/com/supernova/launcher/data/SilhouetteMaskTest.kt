package com.supernova.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SilhouetteMaskTest {

    private val size = 20
    private val white = 0xFFFFFFFF.toInt()
    private val blue = 0xFF1A73E8.toInt()
    private val clear = 0

    // A white tile with a blue "+" across its middle.
    private fun tileWithPlus(): IntArray = IntArray(size * size) { i ->
        val (x, y) = i % size to i / size
        val upright = x in 9..10 && y in 4..15
        val across = y in 9..10 && x in 4..15
        if (upright || across) blue else white
    }

    @Test
    fun theInkOfASolidTileIsWhatDiffersFromItsEdge() {
        val mask = requireNotNull(SilhouetteMask.of(tileWithPlus(), size, size))

        assertEquals(255, mask[10 * size + 10])
        assertEquals(255, mask[5 * size + 9])
        assertEquals(0, mask[0])
        assertEquals(0, mask[2 * size + 2])
    }

    @Test
    fun aShapedTileWithClearCornersStillCounts() {
        val pixels = tileWithPlus()
        listOf(0, size - 1, size * (size - 1), size * size - 1).forEach { pixels[it] = clear }

        val mask = requireNotNull(SilhouetteMask.of(pixels, size, size))
        assertEquals(0, mask[0])
        assertEquals(255, mask[10 * size + 10])
    }

    @Test
    fun aGlyphOnAClearBackgroundUsesItsPlainAlpha() {
        val glyph = IntArray(size * size) { i -> if (i % size in 8..11) blue else clear }

        assertNull(SilhouetteMask.of(glyph, size, size))
    }

    @Test
    fun aPlainTileHasNoInk() {
        assertNull(SilhouetteMask.of(IntArray(size * size) { white }, size, size))
    }
}
