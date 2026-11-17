package com.supernova.launcher.ui

import com.supernova.launcher.ui.FastScrollMath.Section
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// The fast scrollbar's letters and where its thumb sits.
class FastScrollMathTest {

    private fun initials(vararg labels: String, from: Int = 0) =
        labels.mapIndexed { index, label -> (from + index) to FastScrollMath.plainLabel(label) }

    @Test
    fun eachLetterStartsWhereItFirstAppears() {
        assertEquals(
            listOf(Section("#", 2), Section("B", 3), Section("C", 5), Section("M", 6)),
            FastScrollMath.sections(initials("1password", "bhim", "brave", "camera", "maps", from = 2))
        )
    }

    @Test
    fun digitsAndSymbolsShareHash() {
        assertEquals(
            listOf(Section("#", 0), Section("A", 2)),
            FastScrollMath.sections(initials("1password", "@home", "apps"))
        )
    }

    @Test
    fun aReverseListKeepsItsOwnOrder() {
        assertEquals(
            listOf(Section("Z", 0), Section("M", 1), Section("#", 2)),
            FastScrollMath.sections(initials("zoom", "maps", "1password"))
        )
    }

    // A list of 11 items (0..10): the frequent row and a divider, then
    // sections starting at 2, 5 and 8.
    private val sections = listOf(Section("A", 2), Section("F", 5), Section("M", 8))

    @Test
    fun theBubbleShowsTheLetterTheItemIsIn() {
        assertEquals("F", FastScrollMath.label(5, sections))
        // Short of M's start, still F.
        assertEquals("F", FastScrollMath.label(7, sections))
        assertEquals("M", FastScrollMath.label(10, sections))
    }

    @Test
    fun aboveTheFirstLetterTheBubbleShowsTheFirstLetter() {
        // The frequent row and its divider.
        assertEquals("A", FastScrollMath.label(0, sections))
        assertEquals("A", FastScrollMath.label(1, sections))
    }

    @Test
    fun aListNotByNameHasNoLetter() {
        assertNull(FastScrollMath.label(5, emptyList()))
    }

    @Test
    fun theThumbSitsWhereTheListIs() {
        assertEquals(0f, FastScrollMath.thumbOffset(0, 1000, 400f)!!, 0f)
        assertEquals(200f, FastScrollMath.thumbOffset(500, 1000, 400f)!!, 0f)
        assertEquals(400f, FastScrollMath.thumbOffset(1200, 1000, 400f)!!, 0f)
        // Nothing to scroll: no thumb.
        assertNull(FastScrollMath.thumbOffset(0, 0, 400f))
    }

    @Test
    fun everyLetterGetsTheSameShareOfTheTrack() {
        // A at the top, B one row in, C (one app) sharing B's row, D far down.
        val starts = intArrayOf(0, 100, 100, 1000)
        assertEquals(0, FastScrollMath.sectionTarget(0.1f, starts, 2000)[0])
        assertEquals(1, FastScrollMath.sectionTarget(0.3f, starts, 2000)[0])
        assertEquals(2, FastScrollMath.sectionTarget(0.6f, starts, 2000)[0])
        assertEquals(3, FastScrollMath.sectionTarget(0.9f, starts, 2000)[0])
        assertEquals(3, FastScrollMath.sectionTarget(1f, starts, 2000)[0])
    }

    @Test
    fun theListMovesThroughALetterAsTheThumbMovesThroughItsShare() {
        val starts = intArrayOf(0, 400, 800)
        // Halfway through B's share: halfway from B to C.
        assertEquals(600, FastScrollMath.sectionTarget(0.5f, starts, 1200)[1])
        // The last letter runs to the end; a start past the end stops there.
        assertEquals(1200, FastScrollMath.sectionTarget(1f, starts, 1200)[1])
        assertEquals(500, FastScrollMath.sectionTarget(0.9f, intArrayOf(0, 900), 500)[1])
        // The first letter counts from the top, above folders shown first.
        assertEquals(0, FastScrollMath.sectionTarget(0f, intArrayOf(300, 900), 1200)[1])
    }

    @Test
    fun aListWithoutLettersFollowsTheThumb() {
        val result = FastScrollMath.sectionTarget(0.25f, IntArray(0), 1000)
        assertEquals(-1, result[0])
        assertEquals(250, result[1])
    }
}
