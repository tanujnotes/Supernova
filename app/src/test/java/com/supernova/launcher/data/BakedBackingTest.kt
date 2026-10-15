package com.supernova.launcher.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BakedBackingTest {

    private val white = 0xFFFFFFFF.toInt()
    private val red = 0xFFE8453C.toInt()
    private val clear = 0

    // Rows of characters: W white, R red, . transparent.
    private fun image(vararg rows: String): IntArray =
        rows.joinToString("").map {
            when (it) {
                'W' -> white
                'R' -> red
                else -> clear
            }
        }.toIntArray()

    @Test
    fun aLayerFilledWithTheBackingKeepsOnlyTheLogo() {
        // Play Store: white to every edge, the logo in the middle.
        val pixels = image(
            "WWWWW",
            "WRRRW",
            "WRRRW",
            "WRRRW",
            "WWWWW"
        )
        assertTrue(BakedBacking.clear(pixels, 5, white))
        assertArrayEquals(
            image(
                ".....",
                ".RRR.",
                ".RRR.",
                ".RRR.",
                "....."
            ),
            pixels
        )
    }

    @Test
    fun aTileOnATransparentLayerIsClearedButAHoleInTheLogoStays() {
        // Maps: a white tile inside the layer, the pin on it with a white hole.
        val pixels = image(
            ".......",
            ".WWWWW.",
            ".WRRRW.",
            ".WRWRW.",
            ".WRRRW.",
            ".WWWWW.",
            "......."
        )
        assertTrue(BakedBacking.clear(pixels, 7, white))
        assertArrayEquals(
            image(
                ".......",
                ".......",
                "..RRR..",
                "..RWR..",
                "..RRR..",
                ".......",
                "......."
            ),
            pixels
        )
    }

    @Test
    fun aLogoInAnotherColourIsLeftAlone() {
        // A white glyph on a coloured background layer: nothing to clear.
        val pixels = image(
            ".....",
            ".WWW.",
            ".W.W.",
            ".WWW.",
            "....."
        )
        val before = pixels.copyOf()
        assertFalse(BakedBacking.clear(pixels, 5, red))
        assertArrayEquals(before, pixels)
    }

    @Test
    fun nearWhiteRimPixelsCountAsBacking() {
        val offWhite = 0xFFE4E4E4.toInt()
        val pixels = intArrayOf(offWhite, white, red, white)
        assertTrue(BakedBacking.clear(pixels, 2, white))
        assertArrayEquals(intArrayOf(0, 0, red, 0), pixels)
    }
}
