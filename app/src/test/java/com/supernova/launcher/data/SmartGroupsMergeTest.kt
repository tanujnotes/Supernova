package com.supernova.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// A smart-folder regeneration rewrites only the smart groups. A dismissed
// folder - one the user deleted, or one a fresh install left out as too big -
// stays out whichever pass writes last, and the user's own groups are never
// touched, whatever they're called.
class SmartGroupsMergeTest {

    private fun folder(name: String, vararg members: String, order: Int = Int.MAX_VALUE, smart: Boolean = false) =
        DrawerGroup(name, members.toSet(), order, DrawerGroupType.FOLDER, smart)

    private fun merge(stored: List<DrawerGroup>, generated: List<DrawerGroup>, dismissed: Set<String> = emptySet()) =
        LauncherPreferences.withSmartGroups(stored.map { it.encode() }.toSet(), generated, dismissed)
            .mapNotNull(DrawerGroup::decode)
            .associateBy { it.name }

    private val games = folder("Games", "a", "b", "c")
    private val social = folder("Social", "d", "e", "f", "g", "h")

    @Test
    fun theLauncherPassLandingAfterTheWizardsLeavesItsCutOut() {
        // The wizard's fresh-install pass kept Games and dismissed Social; the
        // launcher's own first pass generated both.
        val merged = merge(
            stored = listOf(games.copy(smart = true)),
            generated = listOf(games, social),
            dismissed = setOf("Social")
        )
        assertEquals(setOf("Games"), merged.keys)
        assertTrue(merged.getValue("Games").smart)
    }

    @Test
    fun theWizardPassLandingAfterTheLaunchersReplacesItsFolders() {
        val merged = merge(
            stored = listOf(games.copy(smart = true), social.copy(smart = true)),
            generated = listOf(games),
            dismissed = setOf("Social")
        )
        assertEquals(setOf("Games"), merged.keys)
    }

    @Test
    fun theUsersOwnGroupsStayAsTheyWere() {
        // Named like a generated folder and like a dismissed one: both stay
        // the user's, as they were.
        val mine = folder("Social", "x", order = 0)
        val myGames = folder("Games", "y", "z")
        val work = DrawerGroup("Work", setOf("w"), type = DrawerGroupType.TAB)
        val merged = merge(
            stored = listOf(mine, myGames, work),
            generated = listOf(social, games.copy(name = "Maps")),
            dismissed = setOf("Games")
        )
        assertEquals(setOf("Social", "Games", "Work", "Maps"), merged.keys)
        assertEquals(mine, merged.getValue("Social"))
        assertEquals(myGames, merged.getValue("Games"))
        assertEquals(work, merged.getValue("Work"))
        assertFalse(merged.getValue("Social").smart)
        assertTrue(merged.getValue("Maps").smart)
    }

    @Test
    fun aMovedSmartFolderKeepsItsPlace() {
        val merged = merge(
            stored = listOf(games.copy(order = 2, smart = true)),
            generated = listOf(games.copy(members = games.members + "n"))
        )
        assertEquals(2, merged.getValue("Games").order)
        assertEquals(setOf("a", "b", "c", "n"), merged.getValue("Games").members)
    }
}
