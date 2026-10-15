package com.supernova.launcher.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BakedBackingTest {

    private val white = 0xFFFFFFFF.toInt()
    private val red = 0xFFE8453C.toInt()
    private val blue = 0xFF2F6FE0.toInt()
    private val clear = 0

    // Rows of characters: W white, R red, B blue, . transparent.
    private fun image(vararg rows: String): IntArray =
        rows.joinToString("").map {
            when (it) {
                'W' -> white
                'R' -> red
                'B' -> blue
                else -> clear
            }
        }.toIntArray()

    @Test
    fun aPictureFilledWithItsBackingKeepsOnlyTheLogo() {
        // Play Store: white to every edge, the logo in the middle.
        val pixels = image(
            "WWWWW",
            "WRRRW",
            "WRRRW",
            "WRRRW",
            "WWWWW"
        )
        val cleared = BakedBacking.clear(pixels, 5)
        assertNotNull(cleared)
        assertEquals(white, cleared!!.color)
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
        // Maps: a white tile, the pin on it with a white hole.
        val pixels = image(
            ".......",
            ".WWWWW.",
            ".WRRRW.",
            ".WRWRW.",
            ".WRRRW.",
            ".WWWWW.",
            "......."
        )
        assertNotNull(BakedBacking.clear(pixels, 7))
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
    fun aGradientTileIsFollowedToTheLogo() {
        // Fender: an orange-to-red tile, shading a little each pixel, with a
        // white mark the flood must stop at.
        val size = 12
        val pixels = IntArray(size * size) { index ->
            val x = index % size
            val y = index / size
            if (x in 4..7 && y in 4..7) {
                white
            } else {
                val shade = (x + y) * 3
                0xFF000000.toInt() or ((0xF0 - shade / 2) shl 16) or ((0x80 - shade) shl 8) or 0x20
            }
        }
        assertNotNull(BakedBacking.clear(pixels, size))
        for (index in pixels.indices) {
            val x = index % size
            val y = index / size
            val expected = if (x in 4..7 && y in 4..7) white else 0
            assertEquals("pixel $x,$y", expected, pixels[index])
        }
    }

    @Test
    fun aThinOutlineIsNoTileAndTheArtIsLeftWhole() {
        // Blue art to the edge with a 1px white rim: the rim is the edge's
        // colour but under 15% of the icon, so nothing is cleared.
        val size = 30
        val pixels = IntArray(size * size) { index ->
            val x = index % size
            val y = index / size
            if (minOf(x, y) == 0 || maxOf(x, y) == size - 1) white else blue
        }
        val before = pixels.copyOf()
        assertNull(BakedBacking.clear(pixels, size))
        assertArrayEquals(before, pixels)
    }

    @Test
    fun nothingSolidIsNoBacking() {
        val pixels = IntArray(16)
        assertNull(BakedBacking.clear(pixels, 4))
    }

    @Test
    fun aTileSpansItsContentButAnObjectWithSomethingBesideItDoesNot() {
        // FAST: a white disc with a red mark - the white reaches every side.
        val disc = image(
            "..WWW..",
            ".WWWWW.",
            "WWRRRWW",
            "WWRRRWW",
            "WWRRRWW",
            ".WWWWW.",
            "..WWW.."
        )
        val discBounds = BakedBacking.contentBounds(disc, 7)!!
        val discCleared = BakedBacking.clear(disc, 7)!!
        assertTrue(BakedBacking.spans(discCleared, discBounds, slack = 0))
        // The SIM card: a white card with a red gear poking out at the right.
        val sim = image(
            "WWWW...",
            "WWWW...",
            "WWWWRR.",
            "WWWWRRR",
            "WWWWRR.",
            "WWWW...",
            "WWWW..."
        )
        val simBounds = BakedBacking.contentBounds(sim, 7)!!
        val simCleared = BakedBacking.clear(sim, 7)!!
        assertFalse(BakedBacking.spans(simCleared, simBounds, slack = 1))
    }

    @Test
    fun anOutlineMixedWithTheBackingBecomesTheLogoAtPartOpacity() {
        // A red mark on white whose left column is anti-aliased: half red,
        // half white. That pixel must come out red at half opacity, not as
        // the pale pink that would rim the logo on glass.
        val pink = 0xFFF4A29E.toInt()
        val pixels = image(
            "WWWWWW",
            "WWWWWW",
            "WWRRWW",
            "WWRRWW",
            "WWWWWW",
            "WWWWWW"
        )
        pixels[2 * 6 + 1] = pink
        pixels[3 * 6 + 1] = pink
        assertNotNull(BakedBacking.clear(pixels, 6))
        for (index in intArrayOf(2 * 6 + 1, 3 * 6 + 1)) {
            val pixel = pixels[index]
            assertEquals("colour", red and 0xFFFFFF, pixel and 0xFFFFFF)
            val alpha = pixel ushr 24
            assertTrue("alpha $alpha", alpha in 110..145)
        }
        // The solid logo is untouched.
        assertEquals(red, pixels[2 * 6 + 2])
        assertEquals(red, pixels[3 * 6 + 3])
    }

    @Test
    fun aForegroundCopyOfTheBackingGoesOnlyWhereTheBackgroundWasCleared() {
        // Over cleared background (allowed) the white goes; over the
        // background's own detail (not allowed) the same white stays.
        val front = image(
            "WWW...",
            "WWW...",
            "WWW...",
            "...WWW",
            "...WWW",
            "...WWW"
        )
        val allowed = BooleanArray(36) { index -> index % 6 < 3 && index / 6 < 3 }
        assertTrue(BakedBacking.clearMatching(front, 6, white, allowed))
        for (index in 0 until 36) {
            val x = index % 6
            val y = index / 6
            val expected = if (x >= 3 && y >= 3) white else 0
            assertEquals("pixel $x,$y", expected, front[index])
        }
    }
}
