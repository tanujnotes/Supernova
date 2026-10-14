package com.supernova.launcher.ui

import android.view.View
import android.view.inputmethod.InputMethodManager

// Takes the keyboard down for the window this view belongs to.
//
// Every popup that can open over a text field calls this before it measures,
// for two reasons. A popup that takes focus - which all of them do, either at
// once or on the finger lift - drops the keyboard anyway, but a step after it
// appears: the popup opens over the keyboard and the keyboard slides out from
// under it, which reads as the popup jumping for a reason nothing on screen
// explains. And a popup placed while the keyboard is up is placed against a
// layout that is about to change.
//
// A no-op when no keyboard is up, so callers never have to ask first.
internal fun View.hideKeyboardForWindow() {
    context.getSystemService(InputMethodManager::class.java)
        ?.hideSoftInputFromWindow(windowToken, 0)
}
