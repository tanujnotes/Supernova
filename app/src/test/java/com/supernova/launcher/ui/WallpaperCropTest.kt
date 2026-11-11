package com.supernova.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperCropTest {

    private val delta = 0.0001f

    @Test
    fun anImageCoversTheFrameAtTheLargerOfItsTwoScales() {
        // A landscape photo on a portrait screen is held by its height.
        assertEquals(0.585f, WallpaperCrop.coverScale(4000, 3000, 1080, 1755), delta)
        // A tall narrow image is held by its width.
        assertEquals(2f, WallpaperCrop.coverScale(540, 3000, 1080, 2340), delta)
    }

    @Test
    fun anImageCannotBeDraggedOffTheFrame() {
        // 2000 wide in a 1000 frame: from flush left (0) to flush right (-1000).
        assertEquals(0f, WallpaperCrop.clampOffset(50f, 2000f, 1000f), delta)
        assertEquals(-1000f, WallpaperCrop.clampOffset(-1500f, 2000f, 1000f), delta)
        assertEquals(-300f, WallpaperCrop.clampOffset(-300f, 2000f, 1000f), delta)
        // Exactly the frame's span: it stays put.
        assertEquals(0f, WallpaperCrop.clampOffset(-40f, 1000f, 1000f), delta)
    }

    @Test
    fun theVisibleBoxIsTheFrameInTheImagesTerms() {
        // 2000x1000 image at scale 2 (4000x2000 drawn), its left edge 1000px
        // off-screen, in a 1000x2000 frame: the second quarter of the width,
        // all of the height.
        val box = WallpaperCrop.visible(2000, 1000, 2f, -1000f, 0f, 1000, 2000)
        assertEquals(0.25f, box.left, delta)
        assertEquals(0f, box.top, delta)
        assertEquals(0.5f, box.right, delta)
        assertEquals(1f, box.bottom, delta)
    }

    @Test
    fun aOnePageHomeGetsTheExtraWidthEquallyOnBothSides() {
        // Room everywhere: up to its own width again, half on each side.
        val wide = WallpaperCrop.widenForScrolling(WallpaperCrop.Box(0.4f, 0f, 0.6f, 1f), 0.5f)
        assertEquals(0.3f, wide.left, delta)
        assertEquals(0.7f, wide.right, delta)
        // Little room on the left: as little on the right, so the framed
        // part stays in the middle, where a one-page home screen shows it.
        val nearEdge = WallpaperCrop.widenForScrolling(WallpaperCrop.Box(0.05f, 0f, 0.35f, 1f), 0.5f)
        assertEquals(0f, nearEdge.left, delta)
        assertEquals(0.4f, nearEdge.right, delta)
        // The whole width already: nothing to add.
        val whole = WallpaperCrop.Box(0f, 0.2f, 1f, 0.8f)
        assertEquals(whole, WallpaperCrop.widenForScrolling(whole, 0.5f))
    }

    @Test
    fun theHomePageShowsWhatWasFramed() {
        // A home page at offset o shows the screen-wide window starting
        // o of the way across the extra width: the framed part, every time.
        val framed = WallpaperCrop.Box(0.3f, 0f, 0.5f, 1f)
        for (offset in listOf(0f, 0.25f, 0.5f, 1f)) {
            val wide = WallpaperCrop.widenForScrolling(framed, offset)
            val extra = wide.width - framed.width
            assertEquals(framed.left, wide.left + offset * extra, delta)
        }
        // First page of several: all of the extra goes to the right.
        val first = WallpaperCrop.widenForScrolling(framed, 0f)
        assertEquals(0.3f, first.left, delta)
        assertEquals(0.7f, first.right, delta)
        // Last page: to the left, as far as the image goes.
        val last = WallpaperCrop.widenForScrolling(framed, 1f)
        assertEquals(0.1f, last.left, delta)
        assertEquals(0.5f, last.right, delta)
    }

    @Test
    fun theHomePageKeepsItsFrameWhenTheImageRunsOutOnOneSide() {
        // Framed against the left edge, home page in the middle: no extra
        // can go left, so none can go right either without moving the frame.
        val framed = WallpaperCrop.Box(0f, 0f, 0.3f, 1f)
        assertEquals(framed, WallpaperCrop.widenForScrolling(framed, 0.5f))
        // On the first page it can all go right.
        assertEquals(0.6f, WallpaperCrop.widenForScrolling(framed, 0f).right, delta)
    }

    @Test
    fun theHomePagesOffsetFollowsTheScrollMode() {
        assertEquals(0.5f, WallpaperScrollMode.ON.offset(0f, 1), delta)
        assertEquals(0f, WallpaperScrollMode.ON.offset(0f, 3), delta)
        assertEquals(0.5f, WallpaperScrollMode.ON.offset(1f, 3), delta)
        assertEquals(1f, WallpaperScrollMode.REVERSE.offset(0f, 3), delta)
    }

    @Test
    fun theSampleSizeKeepsAtLeastTheFramesRows() {
        // 4000 rows framed for a 2340 screen: halving would leave 2000.
        assertEquals(1, WallpaperCrop.sampleSize(3000, 4000, 4000f, 2340))
        // 8000 rows: one halving still leaves 4000, a second 2000.
        assertEquals(2, WallpaperCrop.sampleSize(6000, 8000, 8000f, 2340))
        // Zoomed in on a quarter of a 12 MP photo's rows: full resolution.
        assertEquals(1, WallpaperCrop.sampleSize(3000, 4000, 1000f, 2340))
    }

    @Test
    fun theSampleSizeNeverDecodesMoreThanTheCap() {
        // 100 MP framed tightly would want full resolution; the cap takes
        // it down to every fourth pixel each way.
        val sample = WallpaperCrop.sampleSize(10000, 10000, 2000f, 2340)
        assertEquals(4, sample)
        val pixels = 10000L * 10000 / (sample * sample)
        assertTrue(pixels <= WallpaperCrop.MAX_DECODE_PIXELS)
    }
}
