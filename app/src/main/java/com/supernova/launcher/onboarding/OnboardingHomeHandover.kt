package com.supernova.launcher.onboarding

import android.app.Activity
import com.supernova.launcher.HomeRole
import java.lang.ref.WeakReference

// A yes to the home role starts the home screen in a task of its own, and the
// first run that asked, left behind in the task it was opened in, never
// resumes to hear the answer: the home screen opened a second run from the
// Welcome page over it, and both runs and both launchers stayed alive for as
// long as the process did. The home screen hands the answer over instead.
// Main thread only.
internal object OnboardingHomeHandover {

    // A run's Set as my home screen, waiting on the home-role answer.
    private class Waiting(val run: WeakReference<OnboardingActivity>, val then: () -> Unit)

    // A run the home screen is finishing: its dialog goes on the home
    // screen, which is told once the run is done.
    private class Handed(
        val run: WeakReference<OnboardingActivity>,
        val home: WeakReference<Activity>,
        val onFinished: () -> Unit
    )

    private var waiting: Waiting? = null
    private var handed: Handed? = null

    fun asked(run: OnboardingActivity, then: () -> Unit) {
        waiting = Waiting(WeakReference(run), then)
    }

    // The run's own answer: what it was to do, or null if nothing waits.
    fun answered(run: OnboardingActivity): (() -> Unit)? {
        val mine = waiting?.takeIf { it.run.get() === run }
        if (mine != null) waiting = null
        return mine?.then
    }

    // Called by the home screen as it starts: the waiting run finishes as its
    // answer would have, then `onFinished` runs. True when there was one.
    fun finishForHome(home: Activity, onFinished: () -> Unit): Boolean {
        val pending = waiting?.takeIf { pending ->
            val run = pending.run.get()
            run != null && !run.isFinishing && !run.isDestroyed && HomeRole.isHeld(home)
        } ?: return false
        waiting = null
        handed = Handed(pending.run, WeakReference(home), onFinished)
        pending.then()
        return true
    }

    fun dialogHost(run: OnboardingActivity): Activity? =
        handed?.takeIf { it.run.get() === run }?.home?.get()

    fun finished(run: OnboardingActivity) {
        val mine = handed?.takeIf { it.run.get() === run } ?: return
        handed = null
        mine.onFinished()
    }
}
