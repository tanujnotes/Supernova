package com.supernova.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// A shortcut's picture is told from an icon on a clear canvas, round from
// square, and a round one is enlarged just enough to cover the shape.
class PictureFrameTest {

    private val size = 96
    private val solid = 0xFF336699.toInt()

    private fun picture(paint: (x: Int, y: Int) -> Boolean): IntArray =
        IntArray(size * size) { if (paint(it % size, it / size)) solid else 0 }

    private fun inDisc(x: Int, y: Int, radius: Float): Boolean {
        val dx = x + 0.5f - size / 2f
        val dy = y + 0.5f - size / 2f
        return dx * dx + dy * dy <= radius * radius
    }

    @Test
    fun aRoundPhotoFillsItsFrame() {
        val photo = picture { x, y -> inDisc(x, y, size / 2f) }
        val box = PictureFrame.frame(photo, size)
        assertNotNull(box)
        assertFalse(PictureFrame.square(photo, size, box!!))
    }

    @Test
    fun aSquarePhotoFillsItsFrameAndIsSquare() {
        val photo = picture { _, _ -> true }
        val box = PictureFrame.frame(photo, size)
        assertNotNull(box)
        assertTrue(PictureFrame.square(photo, size, box!!))
    }

    @Test
    fun aGlyphOnAClearCanvasDoesNot() {
        assertNull(PictureFrame.frame(picture { x, y -> inDisc(x, y, size / 3f) }, size))
    }

    @Test
    fun aRingWithAHoleDoesNot() {
        assertNull(PictureFrame.frame(picture { x, y -> inDisc(x, y, size / 2f) && !inDisc(x, y, size / 4f) }, size))
    }

    @Test
    fun aLegacySquareWithAMarginIsATile() {
        val margin = size / 12
        val icon = picture { x, y -> x in margin until size - margin && y in margin until size - margin }
        assertNull(PictureFrame.frame(icon, size))
        val box = PictureFrame.tile(icon, size)
        assertNotNull(box)
        assertTrue(PictureFrame.square(icon, size, box!!))
    }

    @Test
    fun aSmallGlyphIsNoTile() {
        assertNull(PictureFrame.tile(picture { x, y -> inDisc(x, y, size / 3f) }, size))
    }

    @Test
    fun aWideBarIsNoTile() {
        assertNull(PictureFrame.tile(picture { _, y -> y in size / 6 until size - size / 3 }, size))
    }

    @Test
    fun aHollowFrameIsNoTile() {
        val frame = picture { x, y -> !inDisc(x, y, size / 3f) && x in 4 until size - 4 && y in 4 until size - 4 }
        assertNull(PictureFrame.tile(frame, size))
    }

    @Test
    fun aCircleMaskNeedsNoEnlarging() {
        assertEquals(1f, PictureFrame.reach(picture { x, y -> inDisc(x, y, size / 2f) }, size), 0.02f)
    }

    @Test
    fun aSquareMaskNeedsTheDiagonal() {
        assertEquals(1.414f, PictureFrame.reach(picture { _, _ -> true }, size), 0.02f)
    }
}
