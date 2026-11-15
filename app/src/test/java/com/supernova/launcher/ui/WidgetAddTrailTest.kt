package com.supernova.launcher.ui

import android.app.Activity
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetAddTrailTest {

    @Test
    fun `a report lists the trail under the process and screen state`() {
        val report = WidgetAddTrail.report(
            launcher = "screen 40s old, add kept in view model",
            processAgeMs = 41_500,
            trail = listOf("add id 12, com.example/.Clock", "+20ms bound", "+90ms setup screen opened")
        )
        assertEquals(
            "process 41s old, screen 40s old, add kept in view model\n" +
                "  add id 12, com.example/.Clock\n" +
                "  +20ms bound\n" +
                "  +90ms setup screen opened\n",
            report
        )
    }

    @Test
    fun `a process started for the answer says it saw no add`() {
        val report = WidgetAddTrail.report("screen 2s old, no saved state", 2_000, emptyList())
        assertEquals("process 2s old, screen 2s old, no saved state\nno add started in this process\n", report)
    }

    @Test
    fun `result codes read as words`() {
        assertEquals("OK", WidgetAddTrail.answer(Activity.RESULT_OK))
        assertEquals("cancel", WidgetAddTrail.answer(Activity.RESULT_CANCELED))
        assertEquals("7", WidgetAddTrail.answer(7))
    }
}
