package com.supernova.launcher.ui

// The private space's unlock prompt is the system's own activity over the
// home screen, and a home press made over it is that prompt's close: it
// must not go on to the home button's action or move the page (#110).
// The prompt can finish as soon as the home swipe begins, though, putting
// the launcher back in front before the swipe's home press arrives (#135).
// So the prompt still counts for a moment after the launcher is back, until
// the home screen is touched.
class UnlockPromptClose(private val windowMs: Long = WINDOW_MS) {
    private var showing = false
    private var leftAt = NONE

    // The unlock was asked for: the prompt is what the user sees.
    fun onPromptAsked() {
        showing = true
        leftAt = NONE
    }

    // The launcher is in front again, the prompt gone however it went.
    fun onResumed(now: Long) {
        if (!showing) return
        showing = false
        leftAt = now
    }

    // A press on the home screen: the user is past the prompt.
    fun onHomeTouched() {
        leftAt = NONE
    }

    // The space opened its card, or the wait was called off: nothing left
    // of the prompt to count.
    fun forget() {
        showing = false
        leftAt = NONE
    }

    // At a home press: whether it closed the prompt, which is still up or
    // left just before. Read once.
    fun takeFor(now: Long): Boolean {
        val closed = showing || (leftAt != NONE && now - leftAt in 0..windowMs)
        forget()
        return closed
    }

    private companion object {
        const val NONE = Long.MIN_VALUE

        // A slow swipe and the system's animation to home, with room to
        // spare, as for a folder card closed by the same swipe.
        const val WINDOW_MS = 3_000L
    }
}
