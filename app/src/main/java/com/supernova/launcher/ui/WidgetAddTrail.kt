package com.supernova.launcher.ui

import android.app.Activity
import android.os.Process
import android.os.SystemClock
import com.supernova.launcher.Diagnostics

// The steps of the latest widget add in this process, written to the crash
// log only when an add fails: a phone that loses the add while a widget's
// setup screen is up (#148) leaves nothing else to go on. Process-wide on
// purpose: a recreated activity keeps it, and a process the system started
// just for the answer has none, which is itself the clue.
internal object WidgetAddTrail {
    private const val MAX_STEPS = 12
    private const val MS_PER_S = 1000

    private val steps = ArrayDeque<String>()
    private var startedAt = 0L

    @Synchronized
    fun start(widgetId: Int, provider: String) {
        steps.clear()
        startedAt = SystemClock.elapsedRealtime()
        steps.addLast("add id $widgetId, $provider")
    }

    @Synchronized
    fun step(what: String) {
        if (startedAt == 0L) return
        if (steps.size >= MAX_STEPS) steps.removeFirst()
        steps.addLast("+${SystemClock.elapsedRealtime() - startedAt}ms $what")
    }

    // `launcher` is the activity's side: how old it is and where its pending
    // add came from. The crash log takes the first few of each kind.
    fun fail(what: String, launcher: String) {
        val processAge = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
        Diagnostics.note("widget add: $what", report(launcher, processAge, snapshot()))
    }

    @Synchronized
    private fun snapshot(): List<String> = steps.toList()

    internal fun report(launcher: String, processAgeMs: Long, trail: List<String>): String = buildString {
        append("process ").append(processAgeMs / MS_PER_S).append("s old, ").append(launcher).append('\n')
        if (trail.isEmpty()) {
            append("no add started in this process\n")
        } else {
            trail.forEach { append("  ").append(it).append('\n') }
        }
    }

    internal fun answer(resultCode: Int): String = when (resultCode) {
        Activity.RESULT_OK -> "OK"
        Activity.RESULT_CANCELED -> "cancel"
        else -> resultCode.toString()
    }
}
