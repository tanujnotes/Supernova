package com.supernova.launcher.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Pure day/time (and signal-availability) logic. The Environment snapshot is
// the injectable clock: tests build it directly, no Android in sight.
class CardVisibilityTest {

    private fun env(
        day: Int = 1,
        minute: Int = 12 * 60,
        ssid: String? = null,
        bt: List<String> = emptyList()
    ) = CardVisibility.Environment(day, minute, ssid, bt)

    private fun card(vararg conditions: CardCondition, enabled: Boolean = true) =
        FeedCard(id = "t", conditions = conditions.toList(), enabled = enabled)

    private fun dayTime(
        days: List<Int>? = null,
        start: Int? = null,
        end: Int? = null
    ) = CardCondition(kind = "DAY_TIME", days = days, startMinute = start, endMinute = end)

    @Test
    fun activationSwitchOffKeepsConditionsButNeverActivates() {
        val gated = card(dayTime(days = listOf(1)))
        assertTrue(CardVisibility.isActivated(gated, env(day = 1)))
        val off = gated.copy(activationEnabled = false)
        assertFalse(CardVisibility.isActivated(off, env(day = 1)))
        // Still an ordinary feed card, whatever day it is.
        assertEquals(listOf(off), CardVisibility.feedOrder(listOf(off), env(day = 1)))
        assertEquals(listOf(off), CardVisibility.feedOrder(listOf(off), env(day = 6)))
        assertTrue(CardVisibility.activated(listOf(off), env(day = 1)).isEmpty())
    }

    @Test
    fun noConditionsAlwaysVisible() {
        assertTrue(CardVisibility.isVisible(card(), env()))
    }

    @Test
    fun disabledCardNeverVisible() {
        assertFalse(CardVisibility.isVisible(card(enabled = false), env()))
    }

    @Test
    fun dayFilterMatchesOnlyListedDays() {
        val weekdayCard = card(dayTime(days = listOf(1, 2, 3, 4, 5)))
        assertTrue(CardVisibility.isVisible(weekdayCard, env(day = 3)))
        assertFalse(CardVisibility.isVisible(weekdayCard, env(day = 6)))
        assertFalse(CardVisibility.isVisible(weekdayCard, env(day = 7)))
    }

    @Test
    fun timeRangeIsStartInclusiveEndExclusive() {
        val morning = card(dayTime(start = 6 * 60, end = 11 * 60))
        assertFalse(CardVisibility.isVisible(morning, env(minute = 6 * 60 - 1)))
        assertTrue(CardVisibility.isVisible(morning, env(minute = 6 * 60)))
        assertTrue(CardVisibility.isVisible(morning, env(minute = 10 * 60 + 59)))
        assertFalse(CardVisibility.isVisible(morning, env(minute = 11 * 60)))
    }

    @Test
    fun wrappingRangeCoversMidnight() {
        // 22:00 -> 06:00.
        val night = card(dayTime(start = 22 * 60, end = 6 * 60))
        assertTrue(CardVisibility.isVisible(night, env(minute = 23 * 60)))
        assertTrue(CardVisibility.isVisible(night, env(minute = 0)))
        assertTrue(CardVisibility.isVisible(night, env(minute = 5 * 60 + 59)))
        assertFalse(CardVisibility.isVisible(night, env(minute = 6 * 60)))
        assertFalse(CardVisibility.isVisible(night, env(minute = 12 * 60)))
        assertTrue(CardVisibility.isVisible(night, env(minute = 22 * 60)))
    }

    @Test
    fun equalStartEndMeansAllDay() {
        val allDay = card(dayTime(days = listOf(2), start = 9 * 60, end = 9 * 60))
        assertTrue(CardVisibility.isVisible(allDay, env(day = 2, minute = 0)))
        assertTrue(CardVisibility.isVisible(allDay, env(day = 2, minute = 23 * 60)))
        assertFalse(CardVisibility.isVisible(allDay, env(day = 3, minute = 0)))
    }

    @Test
    fun dayAndTimeMustBothHold() {
        val condition = card(dayTime(days = listOf(6, 7), start = 8 * 60, end = 12 * 60))
        assertTrue(CardVisibility.isVisible(condition, env(day = 6, minute = 9 * 60)))
        assertFalse(CardVisibility.isVisible(condition, env(day = 6, minute = 13 * 60)))
        assertFalse(CardVisibility.isVisible(condition, env(day = 1, minute = 9 * 60)))
    }

    @Test
    fun wifiConditionUnmetWhenSignalUnavailable() {
        val homeOnly = card(CardCondition(kind = "WIFI", ssid = "HomeNet"))
        // Null SSID = no permission / location off: documented unmet.
        assertFalse(CardVisibility.isVisible(homeOnly, env(ssid = null)))
        assertFalse(CardVisibility.isVisible(homeOnly, env(ssid = "OfficeNet")))
        assertTrue(CardVisibility.isVisible(homeOnly, env(ssid = "HomeNet")))
        assertTrue(CardVisibility.isVisible(homeOnly, env(ssid = "homenet")))
    }

    @Test
    fun bluetoothConditionMatchesConnectedNames() {
        val carOnly = card(CardCondition(kind = "BLUETOOTH", btName = "Car Audio"))
        assertFalse(CardVisibility.isVisible(carOnly, env(bt = emptyList())))
        assertTrue(CardVisibility.isVisible(carOnly, env(bt = listOf("Buds", "car audio"))))
    }

    @Test
    fun allConditionsMustHoldTogether() {
        val combined = card(
            dayTime(days = listOf(1), start = 8 * 60, end = 18 * 60),
            CardCondition(kind = "WIFI", ssid = "HomeNet")
        )
        assertTrue(
            CardVisibility.isVisible(combined, env(day = 1, minute = 9 * 60, ssid = "HomeNet"))
        )
        assertFalse(
            CardVisibility.isVisible(combined, env(day = 1, minute = 9 * 60, ssid = null))
        )
        assertFalse(
            CardVisibility.isVisible(combined, env(day = 2, minute = 9 * 60, ssid = "HomeNet"))
        )
    }

    @Test
    fun nextBoundaryPicksTheNearestEdge() {
        val cards = listOf(
            card(dayTime(start = 9 * 60, end = 17 * 60)),
            card(dayTime(start = 22 * 60, end = 6 * 60))
        )
        // At 08:00: 9:00 start is 60 minutes away - nearer than 17:00, 22:00
        // or tomorrow's 06:00.
        assertEquals(60, CardVisibility.minutesToNextBoundary(cards, env(minute = 8 * 60)))
        // At 21:00: 22:00 start (60m) beats tomorrow's 06:00/09:00.
        assertEquals(60, CardVisibility.minutesToNextBoundary(cards, env(minute = 21 * 60)))
        // Exactly on a boundary: the next occurrence is a full day away, so
        // the nearest other edge wins (17:00, 480 minutes after 09:00).
        assertEquals(480, CardVisibility.minutesToNextBoundary(cards, env(minute = 9 * 60)))
    }

    @Test
    fun noTimeConditionsMeansNoBoundary() {
        val cards = listOf(
            card(CardCondition(kind = "WIFI", ssid = "Net")),
            card(dayTime(days = listOf(1)))
        )
        assertNull(CardVisibility.minutesToNextBoundary(cards, env()))
    }

    @Test
    fun disabledCardsDoNotSchedule() {
        val cards = listOf(card(dayTime(start = 600, end = 700), enabled = false))
        assertNull(CardVisibility.minutesToNextBoundary(cards, env(minute = 0)))
    }

    // ---- Activation semantics ---------------------------------------------

    @Test
    fun cardWithoutConditionsIsNeverActivated() {
        assertFalse(CardVisibility.isActivated(card(), env()))
        assertTrue(CardVisibility.isVisible(card(), env()))
    }

    @Test
    fun cardActivatesOnlyWhenEveryConditionHolds() {
        val both = card(dayTime(days = listOf(1)), CardCondition(kind = "WIFI", ssid = "Home"))
        assertTrue(CardVisibility.isActivated(both, env(day = 1, ssid = "Home")))
        assertFalse(CardVisibility.isActivated(both, env(day = 2, ssid = "Home")))
        assertFalse(CardVisibility.isActivated(both, env(day = 1, ssid = null)))
        assertFalse(CardVisibility.isActivated(both.copy(enabled = false), env(day = 1, ssid = "Home")))
    }

    @Test
    fun feedOrderIsPinnedThenActivatedThenRest() {
        val plain = FeedCard(id = "plain")
        val active = FeedCard(id = "active", conditions = listOf(dayTime(days = listOf(1))))
        val dormant = FeedCard(id = "dormant", conditions = listOf(dayTime(days = listOf(5))))
        val pinned = FeedCard(id = "pinned", pinned = true)
        val pinnedActive = FeedCard(
            id = "pinnedActive", pinned = true, conditions = listOf(dayTime(days = listOf(1)))
        )
        val off = FeedCard(id = "off", enabled = false, pinned = true)
        val ordered = CardVisibility.feedOrder(
            listOf(plain, active, dormant, pinned, off, pinnedActive), env(day = 1)
        )
        assertEquals(
            listOf("pinned", "pinnedActive", "active", "plain", "dormant"),
            ordered.map { it.id }
        )
    }

    @Test
    fun activatedListKeepsStoredOrderAndSkipsDormantAndDisabled() {
        val a = FeedCard(id = "a", conditions = listOf(dayTime(days = listOf(1))))
        val b = FeedCard(id = "b", conditions = listOf(dayTime(days = listOf(2))))
        val c = FeedCard(id = "c", conditions = listOf(dayTime(days = listOf(1))))
        val d = FeedCard(id = "d", enabled = false, conditions = listOf(dayTime(days = listOf(1))))
        val plain = FeedCard(id = "plain")
        assertEquals(
            listOf("a", "c"),
            CardVisibility.activated(listOf(a, b, c, d, plain), env(day = 1)).map { it.id }
        )
    }
}
