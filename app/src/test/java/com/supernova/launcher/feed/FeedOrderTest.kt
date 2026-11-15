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
    fun layoutGroupsPinnedThenRestWithBuiltIns() {
        val plain = FeedCard(id = "plain")
        val other = FeedCard(id = "other")
        val pinned = FeedCard(id = "pinned", pinned = true)
        val off = FeedCard(id = "off", enabled = false)
        val order = listOf("plain", CALENDAR, "other", MEDIA, "pinned", "off", CONTACTS)
        val entries = FeedOrder.layout(
            order, listOf(plain, other, pinned, off), shownBuiltIns = setOf(MEDIA, CONTACTS)
        )
        assertEquals(listOf("pinned", "plain", "other", MEDIA, CONTACTS), entries.map { it.key })
        assertEquals(
            listOf(Group.PINNED, Group.REST, Group.REST, Group.REST, Group.REST),
            entries.map { it.group }
        )
        assertNull(entries.first { it.key == MEDIA }.card)
    }

    @Test
    fun frameDefaultsOnForOldCards() {
        assertEquals(true, FeedCard(id = "old").showFrame)
    }
}
