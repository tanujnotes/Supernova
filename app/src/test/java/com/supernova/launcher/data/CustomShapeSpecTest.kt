package com.supernova.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CustomShapeSpecTest {

    private val default = CustomShapeSpec()

    @Test
    fun blankDecodesToDefault() {
        assertEquals(default, CustomShapeSpec.decode(""))
        assertEquals(default, CustomShapeSpec.decode("   "))
    }

    @Test
    fun junkDecodesToDefault() {
        assertEquals(default, CustomShapeSpec.decode("not json at all"))
        assertEquals(default, CustomShapeSpec.decode("{"))
        assertEquals(default, CustomShapeSpec.decode("""{"radii":"nope"}"""))
        assertEquals(default, CustomShapeSpec.decode("[1,2,3]"))
    }

    @Test
    fun defaultIsQuarterRound() {
        assertEquals(listOf(25, 25, 25, 25), default.radii)
        assertEquals(List(4) { "ROUND" }, default.cuts)
    }

    @Test
    fun roundTripsThroughEncode() {
        val spec = CustomShapeSpec(
            radii = listOf(10, 20, 30, 40),
            cuts = listOf("ROUND", "CUT", "SQUIRCLE", "ROUND")
        )
        assertEquals(spec, CustomShapeSpec.decode(spec.encode()))
    }

    @Test
    fun shortArraysArePadded() {
        val decoded = CustomShapeSpec.decode("""{"radii":[10],"cuts":["CUT"]}""")
        assertEquals(listOf(10, 25, 25, 25), decoded.radii)
        assertEquals(listOf("CUT", "ROUND", "ROUND", "ROUND"), decoded.cuts)
    }

    @Test
    fun outOfRangeRadiiAreClamped() {
        val decoded = CustomShapeSpec.decode("""{"radii":[-5,240,50,100],"cuts":[]}""")
        assertEquals(listOf(0, 100, 50, 100), decoded.radii)
    }

    @Test
    fun unknownCutNamesDegradeToRound() {
        val decoded = CustomShapeSpec.decode(
            """{"radii":[25,25,25,25],"cuts":["BANANA","cut","squircle","?"]}"""
        )
        assertEquals(listOf("ROUND", "CUT", "SQUIRCLE", "ROUND"), decoded.cuts)
    }

    @Test
    fun extraJsonKeysAreIgnored() {
        val decoded = CustomShapeSpec.decode(
            """{"radii":[5,5,5,5],"cuts":["CUT","CUT","CUT","CUT"],"future":true}"""
        )
        assertEquals(listOf(5, 5, 5, 5), decoded.radii)
    }

    @Test
    fun accessorsClampAndDefault() {
        val spec = CustomShapeSpec(radii = listOf(400), cuts = listOf("CUT"))
        assertEquals(100, spec.radiusAt(0))
        assertEquals(CustomShapeSpec.DEFAULT_RADIUS, spec.radiusAt(3))
        assertEquals(CornerCut.CUT, spec.cutAt(0))
        assertEquals(CornerCut.ROUND, spec.cutAt(2))
    }
}
