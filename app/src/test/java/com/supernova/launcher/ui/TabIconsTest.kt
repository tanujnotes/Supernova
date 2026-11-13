package com.supernova.launcher.ui

import com.supernova.launcher.data.SmartCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TabIconsTest {

    private val labels = mapOf(SmartCategory.MAPS to "Travel", SmartCategory.GAMES to "Games")
    private val labelOf: (SmartCategory) -> String = { labels[it] ?: it.name.lowercase() }

    @Test
    fun `a tab named like a category gets its glyph, in any case`() {
        assertEquals(SmartCategory.MAPS, TabIcons.categoryFor(" travel ", labelOf))
        assertEquals(SmartCategory.GAMES, TabIcons.categoryFor("GAMES", labelOf))
        // The category's own name counts too.
        assertEquals(SmartCategory.MAPS, TabIcons.categoryFor("Maps", labelOf))
    }

    @Test
    fun `any other name has no category`() {
        assertNull(TabIcons.categoryFor("Work stuff", labelOf))
    }

    @Test
    fun `the initial is the first character as read, upper case`() {
        assertEquals("M", TabIcons.initialOf("  music"))
        assertEquals("É", TabIcons.initialOf("école"))
        assertEquals("😀", TabIcons.initialOf("😀 fun"))
        assertEquals("?", TabIcons.initialOf("   "))
    }
}
