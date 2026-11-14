package com.supernova.launcher.data

import java.util.concurrent.atomic.AtomicInteger

// Who is waiting on an icon right now (AppCatalog.iconFor), so the
// background warm can step aside between its icons: a change of icon
// settings warms every app, and the icons on screen used to queue behind
// it for the render lock, a whole warm late.
internal class IconWaiters {

    private val waiting = AtomicInteger()

    fun <T> ahead(render: () -> T): T {
        waiting.incrementAndGet()
        try {
            return render()
        } finally {
            waiting.decrementAndGet()
        }
    }

    // Blocks the warm's thread while anyone waits; a caller is in for one
    // icon, a few milliseconds.
    fun stepAside() {
        while (waiting.get() > 0) Thread.sleep(YIELD_MS)
    }

    private companion object {
        const val YIELD_MS = 2L
    }
}
