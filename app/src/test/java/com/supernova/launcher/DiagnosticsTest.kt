package com.supernova.launcher

import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTest {

    @Test
    fun `a failure is handed back, not thrown`() {
        val result = runLogged("parse") { error("bad input") }
        assertTrue(result.isFailure)
        assertEquals("bad input", result.exceptionOrNull()?.message)
    }

    @Test
    fun `a success passes through`() {
        assertEquals(4, runLogged("add") { 2 + 2 }.getOrNull())
    }

    @Test
    fun `a cancellation is never swallowed`() {
        assertThrows(CancellationException::class.java) {
            runLogged("wait") { throw CancellationException("cancelled") }
        }
    }

    @Test
    fun `an entry names the action and the exception, apart from crashes`() {
        val entry = Diagnostics.entry(
            "open app info",
            IllegalStateException("no such app"),
            Instant.parse("2026-11-10T10:00:00Z")
        )
        val lines = entry.trim().lines()
        assertEquals("--- 2026-11-10T10:00:00Z caught: open app info", lines[0])
        assertEquals("java.lang.IllegalStateException: no such app", lines[1])
        assertTrue(lines[2].startsWith("    at "))
        // Crashes are counted by lines that open with CRASH_ENTRY.
        assertFalse(entry.lines().any { it.startsWith(SupernovaApp.CRASH_ENTRY) })
    }
}
