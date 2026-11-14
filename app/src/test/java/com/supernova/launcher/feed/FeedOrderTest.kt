package com.supernova.launcher.feed

import com.supernova.launcher.feed.FeedOrder.CALENDAR
import com.supernova.launcher.feed.FeedOrder.CONTACTS
import com.supernova.launcher.feed.FeedOrder.Group
import com.supernova.launcher.feed.FeedOrder.MEDIA
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeedOrderTest {

    @Test
    fun emptyOrderKeepsBuiltInsFirstThenCardsInStoredOrder() {
        assertEquals(
            listOf(MEDIA, CALENDAR, CONTACTS, "a", "b"),
            FeedOrder.resolve(emptyList(), listOf("a", "b"))
        )
    }

    @Test
    fun resolveDropsGoneKeysAndAddsNewCardsLast() {
        val stored = listOf("b", CONTACTS, "gone", MEDIA, "a", CALENDAR, "b")
        assertEquals(
            listOf("b", CONTACTS, MEDIA, "a", CALENDAR, "c"),
            FeedOrder.resolve(stored, listOf("a", "b", "c"))
        )
    }

    @Test
    fun resolvePutsAMissingBuiltInAtTheFront() {
        assertEquals(
            listOf(CONTACTS, "a", MEDIA, CALENDAR),
            FeedOrder.resolve(listOf("a", MEDIA, CALENDAR), listOf("a"))
        )
    }

    @Test
    fun moveBeforeAndAfter() {
        val order = listOf("a", "b", "c", "d")
        assertEquals(listOf("a", "d", "b", "c"), FeedOrder.move(order, "d", "b", after = false))
        assertEquals(listOf("b", "c", "a", "d"), FeedOrder.move(order, "a", "c", after = true))
        assertEquals(listOf("a", "b", "c", "d"), FeedOrder.move(order, "a", "a", after = true))
        assertEquals(order, FeedOrder.move(order, "x", "a", after = true))
    }

    @Test
    fun layoutGroupsPinnedThenActivatedThenRestWithBuiltIns() {
        val plain = FeedCard(id = "plain")
        val active = FeedCard(id = "active")
        val pinned = FeedCard(id = "pinned", pinned = true)
        val off = FeedCard(id = "off", enabled = false)
        val order = listOf("plain", CALENDAR, "active", MEDIA, "pinned", "off", CONTACTS)
        val entries = FeedOrder.layout(
            order, listOf(plain, active, pinned, off), shownBuiltIns = setOf(MEDIA, CONTACTS)
        ) { it.id == "active" }
        assertEquals(listOf("pinned", "active", "plain", MEDIA, CONTACTS), entries.map { it.key })
        assertEquals(
            listOf(Group.PINNED, Group.ACTIVATED, Group.REST, Group.REST, Group.REST),
            entries.map { it.group }
        )
        assertNull(entries.first { it.key == MEDIA }.card)
    }

    @Test
    fun neighbourStaysInsideTheGroup() {
        val entries = listOf(
            FeedOrder.Entry("p", Group.PINNED, null),
            FeedOrder.Entry("x", Group.REST, null),
            FeedOrder.Entry("y", Group.REST, null)
        )
        assertNull(FeedOrder.neighbour(entries, "p", up = false))
        assertNull(FeedOrder.neighbour(entries, "x", up = true))
        assertEquals("y", FeedOrder.neighbour(entries, "x", up = false)?.key)
        assertEquals("x", FeedOrder.neighbour(entries, "y", up = true)?.key)
        assertNull(FeedOrder.neighbour(entries, "y", up = false))
        assertNull(FeedOrder.neighbour(entries, "missing", up = true))
    }

    @Test
    fun frameDefaultsOnForOldCards() {
        assertEquals(true, FeedCard(id = "old").showFrame)
    }
}
