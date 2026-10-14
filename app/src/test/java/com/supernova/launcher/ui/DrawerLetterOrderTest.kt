package com.supernova.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

// Folders merged in by name follow the list's direction.
class DrawerLetterOrderTest {

    private fun merge(apps: List<String>, folders: List<String>, descending: Boolean) =
        AppDrawerAdapter.mergeByLabel(apps, folders, descending) { it }

    @Test
    fun foldersMergeByNameWhateverTheGroupOrder() {
        // Move up/down put Travel before Entertainment in the groups list.
        val merged = merge(listOf("bhim", "camera", "maps", "zoom"), listOf("travel", "entertainment"), false)
        assertEquals(listOf("bhim", "camera", "entertainment", "maps", "travel", "zoom"), merged)
    }

    @Test
    fun aReverseListTakesItsFoldersInReverse() {
        val merged = merge(listOf("zoom", "maps", "camera", "bhim"), listOf("entertainment", "travel"), true)
        assertEquals(listOf("zoom", "travel", "maps", "entertainment", "camera", "bhim"), merged)
    }

    @Test
    fun foldersPastTheLastAppGoAtTheEnd() {
        assertEquals(listOf("bhim", "work"), merge(listOf("bhim"), listOf("work"), false))
        assertEquals(listOf("bhim", "apps"), merge(listOf("bhim"), listOf("apps"), true))
    }
}
