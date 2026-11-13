package com.supernova.launcher.ui

import android.content.Context
import android.widget.Toast
import com.supernova.launcher.R

// Lock layout: nothing on the home screen is added, moved, removed or
// re-paged, by drag or by tap. Every path that would change it asks here
// first, so a refusal always says why rather than doing nothing.
object DesktopLock {

    // True, with the "locked" message, when the layout is locked.
    fun refused(context: Context, viewModel: LauncherViewModel): Boolean {
        if (!viewModel.state.value.prefs.desktopLocked) return false
        Toast.makeText(context, R.string.desktop_locked_toast, Toast.LENGTH_SHORT).show()
        return true
    }
}
