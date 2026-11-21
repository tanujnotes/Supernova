package com.supernova.launcher.ui

import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView
import com.supernova.launcher.data.LauncherPrefs

// A list's row animations (an Advanced section opening or closing, rows
// added or moved) follow the launcher's own switch, like its other motion.
//
// They can't be left to the item animator alone: with the animator scale at
// 0 it ends removes and moves at once, but still holds the rows that slide in
// at alpha 0 for its unscaled remove + move delay, so they sat blank for a
// third of a second and then popped in (#312). With animations off, the list
// just lays its rows out where they go.
fun RecyclerView.followLauncherAnimations(prefs: LauncherPrefs) {
    val on = Animations.enabled(prefs)
    if (on == (itemAnimator != null)) return
    itemAnimator = if (on) DefaultItemAnimator() else null
}
