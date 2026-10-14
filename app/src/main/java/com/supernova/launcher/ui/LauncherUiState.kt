package com.supernova.launcher.ui

import com.supernova.launcher.data.LaunchableApp
import com.supernova.launcher.data.LauncherModel
import com.supernova.launcher.data.LauncherPrefs

data class LauncherUiState(
    val model: LauncherModel = LauncherModel(),
    val prefs: LauncherPrefs = LauncherPrefs(),
    val apps: List<LaunchableApp> = emptyList(),
    val currentPage: Int = 0,
    val iconGeneration: Int = 0,
    // False only for the stateIn placeholder; the collector skips rendering
    // until the real persisted state has decoded, killing the empty-then-
    // populated first-frame flash.
    val loaded: Boolean = false,
    // loaded says the persisted state has decoded; this says it is safe to
    // BIND it. The icon configuration has been applied and the icons the
    // first frame draws are warmed, so those binds are cache hits. Binding
    // earlier renders them on the main thread instead - and with an icon
    // pack, draws stock icons that are evicted moments later.
    //
    // Chrome that needs no icons (theme, accent, system bars) keys off
    // loaded, so it still happens at once.
    val iconsReady: Boolean = false
)
