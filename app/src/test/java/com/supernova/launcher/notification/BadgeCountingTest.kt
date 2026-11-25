package com.supernova.launcher.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BadgeCountingTest {

    // One plain, badge-worthy chat notification; tests vary it with copy().
    private val chat = BadgeCounting.Candidate(
        appKey = "0/com.chat",
        number = 0,
        isGroupSummary = false,
        canShowBadge = true,
        ongoing = false,
        defaultChannel = false,
        hasContent = true,
        groupKey = "g1"
    )

    @Test
    fun eachNotificationCountsOnce() {
        assertEquals(mapOf("0/com.chat" to 2), BadgeCounting.counts(listOf(chat, chat)))
    }

    @Test
    fun groupSummaryIsNotCountedOnTopOfItsChildren() {
        val group = listOf(chat.copy(isGroupSummary = true), chat, chat)
        assertEquals(mapOf("0/com.chat" to 2), BadgeCounting.counts(group))
    }

    @Test
    fun aSummaryPostedAloneStillCounts() {
        assertEquals(mapOf("0/com.chat" to 1), BadgeCounting.counts(listOf(chat.copy(isGroupSummary = true))))
        // Its children belong to another group: this summary is alone in its.
        val mixed = listOf(chat.copy(isGroupSummary = true, groupKey = "a"), chat.copy(groupKey = "b"))
        assertEquals(mapOf("0/com.chat" to 2), BadgeCounting.counts(mixed))
    }

    // Children that don't badge themselves (a progress bar, dots off for
    // their channel) still make the summary theirs, as in Launcher3.
    @Test
    fun aSummaryOfChildrenThatDontBadgeStaysUncounted() {
        val group = listOf(chat.copy(isGroupSummary = true), chat.copy(canShowBadge = false))
        assertEquals(emptyMap<String, Int>(), BadgeCounting.counts(group))
    }

    @Test
    fun theAppsOwnNumberIsTheCount() {
        val mail = chat.copy(appKey = "0/com.mail", number = 5)
        assertEquals(mapOf("0/com.mail" to 5), BadgeCounting.counts(listOf(mail)))
    }

    @Test
    fun appsAndChannelsThatDontBadgeAreLeftOut() {
        assertEquals(emptyMap<String, Int>(), BadgeCounting.counts(listOf(chat.copy(canShowBadge = false))))
    }

    @Test
    fun ongoingOnTheDefaultChannelAndEmptyNotificationsAreLeftOut() {
        val left = listOf(chat.copy(ongoing = true, defaultChannel = true), chat.copy(hasContent = false))
        assertTrue(BadgeCounting.badged(left).isEmpty())
        assertFalse(BadgeCounting.badged(listOf(chat)).isEmpty())
    }

    // A foreground service's notification on a channel of its own (a push
    // service, a stopwatch, music) badges, as in the reference (#344).
    @Test
    fun ongoingOnItsOwnChannelCounts() {
        assertEquals(mapOf("0/com.chat" to 1), BadgeCounting.counts(listOf(chat.copy(ongoing = true))))
        assertEquals(mapOf("0/com.chat" to 1), BadgeCounting.counts(listOf(chat.copy(defaultChannel = true))))
    }

    @Test
    fun appsAreCountedSeparately() {
        val apps = listOf(
            chat.copy(appKey = "0/com.a"),
            chat.copy(appKey = "10/com.a"),
            chat.copy(appKey = "0/com.b", number = 3)
        )
        assertEquals(mapOf("0/com.a" to 1, "10/com.a" to 1, "0/com.b" to 3), BadgeCounting.counts(apps))
    }
}
