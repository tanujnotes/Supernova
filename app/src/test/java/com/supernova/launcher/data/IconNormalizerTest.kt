package com.supernova.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sqrt

// Normalize icon size (#332): a full square or disc shrinks to the Material
// guideline share of its box, an icon already inside it keeps its size.
class IconNormalizerTest {

    private val size = 192

    private fun alpha(paint: (x: Int, y: Int) -> Boolean): IntArray =
        IntArray(size * size) { if (paint(it % size, it / size)) 255 else 0 }

    private fun scale(paint: (x: Int, y: Int) -> Boolean) =
        IconNormalizer.scale(alpha(paint), size, size, size)

    private fun inDisc(x: Int, y: Int, radius: Float): Boolean {
        val (dx, dy) = x + 0.5f - size / 2f to y + 0.5f - size / 2f
        return dx * dx + dy * dy <= radius * radius
    }

    @Test
    fun fullSquareShrinksToTheSquareShare() {
        assertEquals(sqrt(375f / 576f), scale { _, _ -> true }, 0.005f)
    }

    @Test
    fun fullDiscShrinksToTheRoundShare() {
        val disc = scale { x, y -> inDisc(x, y, size / 2f) }
        // A disc's hull is about a quarter-circle short of its bounds.
        assertEquals(sqrt((380f / 576f) / (Math.PI.toFloat() / 4f)), disc, 0.02f)
    }

    @Test
    fun smallIconKeepsItsSize() {
        assertEquals(1f, scale { x, y -> x in 48 until 144 && y in 48 until 144 }, 0f)
    }

    @Test
    fun thinRingCountsItsHullNotItsInk() {
        // Line art: a ring is measured by the disc it outlines.
        val ring = scale { x, y -> inDisc(x, y, size / 2f) && !inDisc(x, y, size / 2f - 6) }
        val disc = scale { x, y -> inDisc(x, y, size / 2f) }
        assertEquals(disc, ring, 0.01f)
    }

    @Test
    fun emptyPictureKeepsItsSize() {
        assertEquals(1f, scale { _, _ -> false }, 0f)
    }
}
