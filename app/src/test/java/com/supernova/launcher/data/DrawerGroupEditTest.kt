package com.supernova.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

// A tab's Edit dialog saves its name and Keep apps in main app tab together.
class DrawerGroupEditTest {

    private val games = DrawerGroup("Games", setOf("0/a/A"), order = 0, smart = true)
    private val work = DrawerGroup("Work", setOf("0/b/B"), order = 1, keepInMain = true)

    @Test
    fun renamesAndStoresTheKeepChoiceInOneEdit() {
        val edited = DrawerGroupEdit.apply(listOf(games, work), "Games", "Play", keepInMain = true)
        val play = edited.single { it.name == "Play" }
        assertEquals(setOf("0/a/A"), play.members)
        assertEquals(0, play.order)
        assertEquals(true, play.keepInMain)
        assertFalse(play.smart)
        assertEquals(work, edited.single { it.name == "Work" })
    }

    @Test
    fun aNullKeepLeavesTheStoredChoice() {
        val edited = DrawerGroupEdit.apply(listOf(work), "Work", "Office", keepInMain = null)
        assertEquals(true, edited.single().keepInMain)
        assertEquals("Office", edited.single().name)
    }

    @Test
    fun theKeepChoiceAloneChangesUnderTheSameName() {
        val edited = DrawerGroupEdit.apply(listOf(games, work), "Work", "Work", keepInMain = false)
        assertEquals(false, edited.single { it.name == "Work" }.keepInMain)
        assertEquals(2, edited.size)
    }

    @Test
    fun aNameAnotherGroupHasIsTakenToo() {
        val edited = DrawerGroupEdit.apply(listOf(games, work), "Games", "Work", keepInMain = null)
        assertEquals(listOf("Work", "Work"), edited.map { it.name })
        assertEquals(work, edited.single { it.id == "Work" })
        assertEquals(setOf("0/a/A"), edited.single { it.id == "Games" }.members)
    }

    @Test
    fun theGroupIsFoundByIdNotName() {
        val twin = DrawerGroup("Games", setOf("0/c/C"), id = "#twin")
        val edited = DrawerGroupEdit.apply(listOf(games, twin), "#twin", "Arcade", keepInMain = null)
        assertEquals(games, edited.single { it.id == "Games" })
        assertEquals("Arcade", edited.single { it.id == "#twin" }.name)
    }
}
