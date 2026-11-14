package com.supernova.launcher.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ThemedGlyphTest {

    private val size = 30
    private val white = 0xFFFFFFFF.toInt()
    private val orange = 0xFFFB7701.toInt()
    private val clear = 0

    private fun picture(paint: (x: Int, y: Int) -> Int) = IntArray(size * size) { paint(it % size, it / size) }

    private fun plus(x: Int, y: Int) = (x in 14..15 && y in 9..20) || (y in 14..15 && x in 9..20)

    @Test
    fun aGlyphOnAClearBackgroundKeepsItsSilhouette() {
        val glyph = picture { x, y -> if (plus(x, y)) white else clear }

        val alpha = requireNotNull(ThemedGlyph.forApp(glyph, size, size))
        assertEquals(255, alpha[15 * size + 15])
        assertEquals(0, alpha[5 * size + 5])
    }

    @Test
    fun aFullBleedTileGivesItsInkNotABlankPlate() {
        val tile = picture { x, y -> if (plus(x, y)) white else orange }

        val alpha = requireNotNull(ThemedGlyph.forApp(tile, size, size))
        assertEquals(255, alpha[15 * size + 15])
        assertEquals(0, alpha[10 * size + 10])
    }

    @Test
    fun aSquareLegacyIconInsetOnTheCanvasGivesItsInk() {
        val legacy = picture { x, y ->
            when {
                x !in 6..23 || y !in 6..23 -> clear
                plus(x, y) -> white
                else -> orange
            }
        }

        val alpha = requireNotNull(ThemedGlyph.forApp(legacy, size, size))
        assertEquals(255, alpha[15 * size + 15])
        assertEquals(0, alpha[7 * size + 7])
    }

    @Test
    fun aGradientTileHasNoGlyphSoTheAppKeepsItsColours() {
        val gradient = picture { x, _ -> (0xFF shl 24) or ((x * 255 / (size - 1)) shl 16) or 0x2040 }

        assertNull(ThemedGlyph.forApp(gradient, size, size))
    }

    @Test
    fun aPlainTileHasNoGlyph() {
        assertNull(ThemedGlyph.forApp(picture { _, _ -> orange }, size, size))
    }

    @Test
    fun aShortcutTileStillFallsBackToItsSilhouette() {
        val tile = picture { _, _ -> orange }

        val alpha = ThemedGlyph.forShortcut(tile, size, size)
        assertNotNull(alpha)
        assertArrayEquals(IntArray(size * size) { 255 }, alpha)
    }

    @Test
    fun coverageCountsOnlyTheVisibleMiddle() {
        val full = IntArray(size * size) { 255 }
        val rim = picture { x, y -> if (x in 5..24 && y in 5..24) 0 else 255 }

        assertEquals(1f, ThemedGlyph.coverage(full, size, size), 0.001f)
        assertEquals(0f, ThemedGlyph.coverage(rim, size, size), 0.001f)
    }
}
