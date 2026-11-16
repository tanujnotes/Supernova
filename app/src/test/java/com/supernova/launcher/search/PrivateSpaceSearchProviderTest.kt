package com.supernova.launcher.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateSpaceSearchProviderTest {

    @Test
    fun matchesTheNameAndEachWordFromThreeLetters() {
        assertTrue(PrivateSpaceSearchProvider.matches("Private space", "pri"))
        assertTrue(PrivateSpaceSearchProvider.matches("Private space", "Private space"))
        assertTrue(PrivateSpaceSearchProvider.matches("Private space", "SPACE"))
        assertFalse(PrivateSpaceSearchProvider.matches("Private space", "pr"))
        assertFalse(PrivateSpaceSearchProvider.matches("Private space", "vate"))
        assertFalse(PrivateSpaceSearchProvider.matches("Private space", "private spaces"))
    }

    @Test
    fun noRowWithoutAPrivateSpace() {
        val none = PrivateSpaceSearchProvider(label = { null }, icon = { null })
        assertTrue(none.search("private").isEmpty())
    }

    @Test
    fun theRowOpensThePrivateSpace() {
        val provider = PrivateSpaceSearchProvider(label = { "Private space" }, icon = { null })
        val row = provider.search("private").single() as SearchResult.Folder
        assertTrue(row.privateSpace)
        assertEquals("Private space", row.label)
    }
}
