package com.supernova.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class BadgeDiffTest {

    @Test
    fun anIdenticalMapChangesNothing() {
        // The case that matters most: a StateFlow replay on every return to
        // the home screen used to rebuild the whole workspace.
        val counts = mapOf("0/com.a" to 2, "0/com.b" to 1)
        assertEquals(emptySet<String>(), BadgeDiff.changedKeys(counts, counts))
    }

    @Test
    fun onlyTheAppWhoseCountMovedIsReported() {
        val old = mapOf("0/com.a" to 2, "0/com.b" to 1)
        val new = mapOf("0/com.a" to 3, "0/com.b" to 1)
        assertEquals(setOf("0/com.a"), BadgeDiff.changedKeys(old, new))
    }

    @Test
    fun anAppAppearingOrVanishingCounts() {
        assertEquals(
            setOf("0/com.new"),
            BadgeDiff.changedKeys(emptyMap(), mapOf("0/com.new" to 1))
        )
        assertEquals(
            setOf("0/com.gone"),
            BadgeDiff.changedKeys(mapOf("0/com.gone" to 4), emptyMap())
        )
    }

    @Test
    fun absentAndZeroAreTheSameThing() {
        // The listener drops an app once its last notification clears, but a
        // zero left in the map means the same: no badge, so no redraw.
        assertEquals(
            emptySet<String>(),
            BadgeDiff.changedKeys(mapOf("0/com.a" to 0), emptyMap())
        )
        assertEquals(
            emptySet<String>(),
            BadgeDiff.changedKeys(emptyMap(), mapOf("0/com.a" to 0))
        )
    }

    @Test
    fun theSameAppUnderTwoProfilesIsTwoKeys() {
        val old = mapOf("0/com.a" to 1, "11/com.a" to 1)
        val new = mapOf("0/com.a" to 1, "11/com.a" to 5)
        assertEquals(setOf("11/com.a"), BadgeDiff.changedKeys(old, new))
    }

    @Test
    fun bothEmptyIsEmpty() {
        assertEquals(emptySet<String>(), BadgeDiff.changedKeys(emptyMap(), emptyMap()))
    }

    // A Dynamic badge's picture: a newer notification is a change even when
    // the count stays put, and an unchanged one is not.
    @Test
    fun changedValuesFindsReplacedAddedAndRemovedEntries() {
        val old = mapOf("0/com.a" to "msg1", "0/com.b" to "msg1", "0/com.gone" to "x")
        val new = mapOf("0/com.a" to "msg2", "0/com.b" to "msg1", "0/com.new" to "y")
        assertEquals(setOf("0/com.a", "0/com.gone", "0/com.new"), BadgeDiff.changedValues(old, new))
    }
}
