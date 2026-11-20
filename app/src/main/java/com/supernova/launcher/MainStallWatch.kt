package com.supernova.launcher

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

// Notes a frozen main thread in the crash log: how long it froze, how long
// after the process started, and where the main thread was a second in.
// "Home took ten seconds" came with nothing to go on; this is the evidence a
// shared log brings. It runs only while the launcher is in front (started),
// so it never wakes the phone in the background.
object MainStallWatch {
    private const val STALL_MS = 1_000L
    private const val GIVE_UP_MS = 30_000L
    private const val POLL_MS = 100L
    private const val FRAMES = 25

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var running = false

    @Volatile
    private var answeredAt = 0L
    private var watcher: Thread? = null

    // Main thread only, from onStart and onStop.
    fun start() {
        if (running) return
        running = true
        watcher = Thread(::watch, "stall-watch").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        watcher?.interrupt()
        watcher = null
    }

    // The main thread answers a ping a second. One unanswered after a
    // second is a freeze, which began after the last answer and ended at
    // this one's: its length is given as that range.
    private fun watch() {
        var lastAnswer = SystemClock.uptimeMillis()
        try {
            while (running) {
                val askedAt = SystemClock.uptimeMillis()
                answeredAt = 0L
                main.post { answeredAt = SystemClock.uptimeMillis() }
                Thread.sleep(STALL_MS)
                if (answeredAt == 0L && running) awaitAndReport(askedAt, lastAnswer)
                if (answeredAt != 0L) lastAnswer = answeredAt
            }
        } catch (_: InterruptedException) {
            // Stopped.
        }
    }

    // Where the main thread is a second in, then how long the freeze lasts.
    private fun awaitAndReport(askedAt: Long, lastAnswer: Long) {
        val stack = Looper.getMainLooper().thread.stackTrace
        while (answeredAt == 0L && running && SystemClock.uptimeMillis() - askedAt < GIVE_UP_MS) {
            Thread.sleep(POLL_MS)
        }
        val end = if (answeredAt != 0L) answeredAt else SystemClock.uptimeMillis()
        report(end - askedAt, end - lastAnswer, askedAt, stack, answeredAt == 0L)
    }

    private fun report(
        leastMs: Long,
        mostMs: Long,
        askedAt: Long,
        stack: Array<StackTraceElement>,
        stillFrozen: Boolean
    ) {
        val sinceStart = askedAt - android.os.Process.getStartUptimeMillis()
        val detail = buildString {
            if (stillFrozen) append("Still frozen after ").append(leastMs).append(" ms")
            else append("Frozen for ").append(leastMs).append("-").append(mostMs).append(" ms")
            append(", ").append(sinceStart).append(" ms after the process started. Main thread at 1 s:\n")
            stack.take(FRAMES).forEach { append("    at ").append(it).append("\n") }
        }
        Diagnostics.note("main thread froze", detail)
    }
}
