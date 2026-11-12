package com.supernova.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Test

// The icon picker's search: pack drawables by name read as words, the App
// icons grid by app name.
class IconSearchTest {

    private val pack = listOf(
        "a_433_football", "all_football", "bbcsport", "besoccer", "bt_sport",
        "calculator", "folder_sports", "generic_sports", "google_play_store"
    )

    private fun found(query: String, names: List<String> = pack, labels: List<String>? = null): List<String> =
        IconSearch.filter(names, labels, query).map { names[it] }

    @Test
    fun blankQueryKeepsEveryIconInOrder() {
        assertEquals(pack, found(""))
        assertEquals(pack, found("   "))
    }

    @Test
    fun matchesAnywhereInTheName() {
        assertEquals(listOf("bbcsport", "bt_sport", "folder_sports", "generic_sports"), found("sport"))
        assertEquals(listOf("a_433_football", "all_football"), found("football"))
        assertEquals(listOf("besoccer"), found("soccer"))
    }

    @Test
    fun ignoresCase() {
        assertEquals(listOf("a_433_football", "all_football"), found("FootBall"))
    }

    @Test
    fun underscoresReadAsSpaces() {
        assertEquals(listOf("folder_sports"), found("folder sp"))
        assertEquals(listOf("folder_sports"), found("folder_sp"))
        assertEquals(listOf("google_play_store"), found("play  store"))
    }

    @Test
    fun noMatchLeavesTheGridEmpty() {
        assertEquals(emptyList<String>(), found("zebra"))
    }

    @Test
    fun appIconsAreSearchedByLabel() {
        val names = listOf("0/com.a/A", "0/com.b/B", "0/com.c/C")
        val labels = listOf("Calculator", "Calendar", "YouTube Music")
        assertEquals(listOf("0/com.a/A", "0/com.b/B"), found("cal", names, labels))
        assertEquals(listOf("0/com.c/C"), found("music", names, labels))
        // The package in the name is not searched.
        assertEquals(emptyList<String>(), found("com.a", names, labels))
    }

    @Test
    fun wordsNormalisesSeparators() {
        assertEquals("folder sports", IconSearch.words("  Folder__Sports "))
        assertEquals("a b c", IconSearch.words("a-b_c"))
    }
}
