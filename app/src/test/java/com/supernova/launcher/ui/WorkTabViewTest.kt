package com.supernova.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkTabViewTest {

    @Test
    fun pausedWorkAppsGiveWayToTheCard() {
        assertEquals(WorkTabView.PAUSED_CARD, WorkTabView.of(onWorkTab = true, searching = false, paused = true))
    }

    @Test
    fun runningWorkAppsGetThePauseButton() {
        assertEquals(WorkTabView.PAUSE_BUTTON, WorkTabView.of(onWorkTab = true, searching = false, paused = false))
    }

    @Test
    fun otherTabsShowNeither() {
        assertEquals(WorkTabView.NONE, WorkTabView.of(onWorkTab = false, searching = false, paused = true))
        assertEquals(WorkTabView.NONE, WorkTabView.of(onWorkTab = false, searching = false, paused = false))
    }

    @Test
    fun searchResultsStandInForBoth() {
        assertEquals(WorkTabView.NONE, WorkTabView.of(onWorkTab = true, searching = true, paused = true))
        assertEquals(WorkTabView.NONE, WorkTabView.of(onWorkTab = true, searching = true, paused = false))
    }
}
