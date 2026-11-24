package com.supernova.launcher.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BadgeReportTest {

    private fun seen(
        pkg: String,
        counted: Boolean,
        canShowBadge: Boolean = true,
        ongoing: Boolean = false,
        dnd: Boolean = false
    ) = BadgeReport.Seen(
        packageName = pkg,
        userId = 0,
        candidate = BadgeCounting.Candidate(
            appKey = "0/$pkg",
            number = 0,
            isGroupSummary = false,
            canShowBadge = canShowBadge,
            clearable = !ongoing,
            ongoing = ongoing,
            hasContent = true
        ),
        counted = counted,
        ranked = true,
        dndHidesBadge = dnd
    )

    @Test
    fun namesWhyEachAppsNotificationsDontBadge() {
        val report = BadgeReport.format(
            enabled = true,
            connected = true,
            systemBadging = 1,
            refreshedAgoMs = 3000,
            seen = listOf(
                seen("a.self", counted = true),
                seen("b.chat", counted = false, canShowBadge = false),
                seen("b.chat", counted = false, canShowBadge = false),
                seen("c.music", counted = false, ongoing = true)
            )
        )
        assertTrue(
            report.startsWith("access on, listener connected, system badging on, last read 3s ago, 4 notifications")
        )
        assertTrue(report.contains("0/a.self: 1 seen, 1 badged\n"))
        assertTrue(report.contains("0/b.chat: 2 seen, 0 badged (system says no badge 2)"))
        assertTrue(report.contains("0/c.music: 1 seen, 0 badged (not clearable 1, ongoing 1)"))
    }

    @Test
    fun doNotDisturbIsNamed() {
        assertEquals(
            listOf("dnd hides badge"),
            BadgeReport.reasons(seen("x", counted = false, canShowBadge = false, dnd = true))
        )
    }
}
