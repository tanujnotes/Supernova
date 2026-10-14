package com.supernova.launcher.ui.settings

import com.supernova.launcher.data.LauncherPrefs
import com.supernova.launcher.ui.LauncherViewModel
import com.supernova.launcher.ui.settings.preview.SettingsPreviewView

// Routes uncommitted slider values to whichever Preview header is on screen.
// Ticks mutate the attached view directly - no DataStore write, no adapter
// notify - so a drag never rebuilds the list it is happening inside of.
class PreviewController(private val viewModel: LauncherViewModel) {

    // True while a slider thumb is held. PreviewHolder skips rebinds during a
    // drag so a prefs emission can't clobber the in-flight tick frame.
    var isTracking = false

    // Kept in sync by the adapter; ticks are computed against the last
    // committed snapshot.
    var latestPrefs = LauncherPrefs()

    private var attached: SettingsPreviewView? = null

    fun attach(view: SettingsPreviewView, kind: PreviewKind) {
        attached = view
        view.bind(kind, latestPrefs, viewModel)
    }

    fun onTick(item: SettingItem.Slider, value: Int) = onTick(item.preview, value)

    // For a bar that isn't a Slider row of its own (the Label card's size).
    fun onTick(transform: ((LauncherPrefs, Int) -> LauncherPrefs)?, value: Int) {
        if (transform == null) return
        attached?.tick(transform(latestPrefs, value))
    }
}
