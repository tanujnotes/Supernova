package com.supernova.launcher.ui

import android.view.View
import android.widget.SeekBar
import java.util.WeakHashMap

// The minus and plus beside a bar. A tap takes one step; held, it keeps
// stepping at a steady rate once the press has outlasted the long-press
// delay, as a key repeat does, and stops at the end of the range instead of
// spinning. The value is written in settle(), which runs when the press ends,
// so a long hold saves once rather than on every step.
object Steppers {

    // Between repeated steps; the system's long-press timeout stands in for
    // the usual wait before the first of them.
    private const val REPEAT_MS = 50L

    // The beat once the range has run out: nothing left to step, so the hold
    // only waits for the finger.
    private const val IDLE_MS = 150L

    private val repeats = WeakHashMap<View, Repeat>()

    // step() moves the value and says whether it could: false (the end of its
    // range) stops the stepping.
    fun hold(button: View, step: () -> Boolean, settle: () -> Unit = {}) {
        val repeat = Repeat(button, step, settle)
        repeats.put(button, repeat)?.cancel()
        button.setOnClickListener { if (step()) settle() }
        button.setOnLongClickListener {
            // Taking the long press is what keeps the click from firing once
            // more when the finger lifts.
            repeat.start()
            true
        }
    }

    // The common case: the stepper moves a bar by delta, inside the bar's own
    // range, and onStep follows the value it reached.
    fun bar(button: View, bar: SeekBar, delta: Int, settle: () -> Unit = {}, onStep: () -> Unit) {
        hold(button, {
            val target = (bar.progress + delta).coerceIn(bar.min, bar.max)
            if (target == bar.progress) {
                false
            } else {
                bar.progress = target
                onStep()
                true
            }
        }, settle)
    }

    // A stepper that only taps (a settings row waiting on another one): any
    // repeat wired before is dropped.
    fun tap(button: View, onTap: () -> Unit) {
        repeats.remove(button)?.cancel()
        button.setOnClickListener { onTap() }
        button.setOnLongClickListener(null)
        button.isLongClickable = false
    }

    private class Repeat(
        private val button: View,
        private val step: () -> Boolean,
        private val settle: () -> Unit
    ) : Runnable {
        private var running = false

        // A held step moved the value, so the release has something to save.
        private var moved = false

        fun start() {
            running = true
            moved = false
            run()
        }

        override fun run() {
            if (!running) return
            // The press ended, the finger slid off the button, or a sheet
            // closed under it: the pressed state is what says so, since the
            // release itself belongs to the button's own listeners.
            if (!button.isPressed || !button.isAttachedToWindow) {
                val save = moved
                cancel()
                if (save) settle()
                return
            }
            val stepped = step()
            if (stepped) moved = true
            button.postDelayed(this, if (stepped) REPEAT_MS else IDLE_MS)
        }

        fun cancel() {
            running = false
            moved = false
            button.removeCallbacks(this)
        }
    }
}
