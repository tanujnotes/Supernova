package com.supernova.launcher.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardItemsTest {

    private val a = CardAction("APP", "com.a/com.a.Main@0", "A")
    private val b = CardAction("SHORTCUT", "com.b|one@0", "B: One")
    private val c = CardAction("APP", "com.c/com.c.Main@0", "C")

    @Test
    fun aLargeTileIsTheListsFirstItem() {
        val card = FeedCard(
            id = "x", featuredType = "APP", featuredTarget = "com.t/com.t.Main@0", actions = listOf(a, b)
        )
        assertTrue(CardItems.largeTile(card))
        assertEquals(listOf("com.t/com.t.Main@0", a.target, b.target), CardItems.of(card).map { it.target })
    }

    @Test
    fun switchingTheLargeTileOnMakesTheFirstItemFeatured() {
        val card = CardItems.apply(FeedCard(id = "x"), listOf(a, b, c), largeTile = true)
        assertEquals("APP", card.featuredType)
        assertEquals(a.target, card.featuredTarget)
        assertEquals(listOf(b, c), card.actions)
    }

    @Test
    fun switchingItOffKeepsEveryItemAsAnAction() {
        val featured = FeedCard(id = "x", featuredType = "SHORTCUT", featuredTarget = b.target, actions = listOf(a))
        val card = CardItems.apply(featured, CardItems.of(featured), largeTile = false)
        assertNull(card.featuredType)
        assertNull(card.featuredTarget)
        assertEquals(listOf(b.target, a.target), card.actions.map { it.target })
        assertFalse(CardItems.largeTile(card))
    }

    @Test
    fun anEmptyListWithTheTileOnHasNoFeatured() {
        val tiled = FeedCard(id = "x", featuredType = "APP", featuredTarget = a.target)
        val card = CardItems.apply(tiled, emptyList(), largeTile = true)
        assertNull(card.featuredType)
        assertTrue(card.actions.isEmpty())
    }

    @Test
    fun aWidgetCardKeepsItsWidgetAndTakesTheListAsActions() {
        val widget = FeedCard(
            id = "w", featuredType = "WIDGET", featuredTarget = "42", appWidgetId = 42, actions = listOf(a)
        )
        assertEquals(listOf(a), CardItems.of(widget))
        val card = CardItems.apply(widget, listOf(a, b), largeTile = true)
        assertEquals("WIDGET", card.featuredType)
        assertEquals(42, card.appWidgetId)
        assertEquals(listOf(a, b), card.actions)
    }

    @Test
    fun neverMoreThanTheMaximumActions() {
        val many = (1..9).map { CardAction("APP", "com.p$it/com.p$it.Main@0", "P$it") }
        assertEquals(CardItems.MAX, CardItems.apply(FeedCard(id = "x"), many, largeTile = false).actions.size)
        val tiled = CardItems.apply(FeedCard(id = "x"), many, largeTile = true)
        assertEquals(many.first().target, tiled.featuredTarget)
        assertEquals(CardItems.MAX, tiled.actions.size)
    }
}
