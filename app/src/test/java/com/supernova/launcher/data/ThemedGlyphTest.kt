package com.supernova.launcher.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun aLogoMadeOfPiecesKeepsItsBodyDimmedBehindItsInk() {
        val purple = 0xFF5B3FE0.toInt()
        // A page with a cloud across its lower left, reaching past its edge.
        val logo = picture { x, y ->
            when {
                x in 4..16 && y in 14..20 -> white
                x in 12..22 && y in 4..24 -> purple
                else -> clear
            }
        }

        val alpha = requireNotNull(ThemedGlyph.forApp(logo, size, size))
        assertEquals(255, alpha[17 * size + 8])
        assertTrue("the page stays, dimmed", alpha[8 * size + 18] in 60..160)
        assertEquals(0, alpha[8 * size + 6])
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
    fun aTintedCopyRunsFromTheDarkerColourToTheLighter() {
        val plate = 0xFF1B1B1F.toInt()
        val glyph = 0xFFAAC7FF.toInt()
        val black = 0xFF000000.toInt()
        val pixels = intArrayOf(black, orange, white, clear, 0x80FFFFFF.toInt())

        val tinted = ThemedGlyph.tinted(pixels, plate, glyph)

        assertEquals(plate, tinted[0])
        assertEquals(glyph, tinted[2])
        assertEquals(0, tinted[3])
        assertEquals(0x80, tinted[4] ushr 24)
        assertEquals(glyph and 0xFFFFFF, tinted[4] and 0xFFFFFF)
        val mid = tinted[1] shr 8 and 0xFF
        assertTrue("orange lands between the two", mid in (plate shr 8 and 0xFF) + 1 until (glyph shr 8 and 0xFF))
    }

    @Test
    fun aTintedCopyStretchesADullPictureOverTheWholeRange() {
        val dull = intArrayOf(0xFF404040.toInt(), 0xFF909090.toInt())

        val tinted = ThemedGlyph.tinted(dull, 0xFF000000.toInt(), white)

        assertEquals(0xFF000000.toInt(), tinted[0])
        assertEquals(white, tinted[1])
    }

    @Test
    fun aFlatPictureIsTintedInTheGlyphColourNotThePlates() {
        val plate = 0xFF1B1B1F.toInt()
        val glyph = 0xFFAAC7FF.toInt()

        val tinted = ThemedGlyph.tinted(intArrayOf(orange, orange, clear), plate, glyph)

        assertEquals(glyph, tinted[0])
        assertEquals(glyph, tinted[1])
        assertEquals(0, tinted[2])
    }

    @Test
    fun aBlockOfColoursOnAClearBackgroundIsNoGlyph() {
        val stripes = intArrayOf(0xFFE53935.toInt(), 0xFF1E88E5.toInt(), 0xFF43A047.toInt())
        val books = picture { x, y -> if (x in 9..20 && y in 9..20) stripes[(y - 9) / 4] else clear }

        assertNull(ThemedGlyph.forApp(books, size, size))
    }

    @Test
    fun aRingIsAGlyphNotABlock() {
        val ring = picture { x, y ->
            val d = (x - 14.5f) * (x - 14.5f) + (y - 14.5f) * (y - 14.5f)
            if (d in 16f..36f) white else clear
        }

        assertNotNull(ThemedGlyph.forApp(ring, size, size))
    }

    @Test
    fun aOneColourLayerIsPlainAndAPictureIsNot() {
        assertTrue(ThemedGlyph.isPlain(picture { _, _ -> white }))
        assertTrue(ThemedGlyph.isPlain(picture { x, y -> if (x == 0 && y == 0) orange else white }))
        assertTrue(!ThemedGlyph.isPlain(picture { x, y -> if (plus(x, y)) orange else white }))
    }

    @Test
    fun coverageCountsOnlyTheVisibleMiddle() {
        val full = IntArray(size * size) { 255 }
        val rim = picture { x, y -> if (x in 5..24 && y in 5..24) 0 else 255 }

        assertEquals(1f, ThemedGlyph.coverage(full, size, size), 0.001f)
        assertEquals(0f, ThemedGlyph.coverage(rim, size, size), 0.001f)
    }
}
