package com.supernova.launcher.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShortcutNameRankTest {
    @Test
    fun labelStartRanksFirst() {
        assertEquals(0, ShortcutSearchProvider.nameRank("Alice Smith", "ali"))
    }

    @Test
    fun laterWordRanksSecond() {
        assertEquals(1, ShortcutSearchProvider.nameRank("New incognito tab", "incog"))
        assertEquals(1, ShortcutSearchProvider.nameRank("Note to self", "SELF"))
    }

    @Test
    fun midWordIsNoMatch() {
        assertNull(ShortcutSearchProvider.nameRank("Alice Smith", "mith"))
        assertNull(ShortcutSearchProvider.nameRank("", "a"))
    }
}
