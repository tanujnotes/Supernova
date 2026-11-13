package com.supernova.launcher.ui.settings

import com.supernova.launcher.data.LauncherPrefs
import com.supernova.launcher.data.Pref
import com.supernova.launcher.ui.LauncherViewModel
import com.supernova.launcher.ui.theme.StyleConfig
import com.supernova.launcher.ui.theme.SurfaceId
import kotlin.math.roundToInt

// The Dock's and the App drawer's background opacity is one value, the
// page's "Background opacity" pref, set from that page or from the Style
// sheet. The sheet used to store its own (style.alpha), which then overrode
// the page's slider for good; a stored one is cleared as either is moved.
// Values here are percents, as both sliders show them.
object SurfaceOpacity {

    fun handles(id: SurfaceId): Boolean = id == SurfaceId.DOCK || id == SurfaceId.DRAWER

    // What is drawn: an alpha the sheet stored before, else the pref.
    fun effective(prefs: LauncherPrefs, id: SurfaceId): Int =
        ((StyleConfig.decode(prefs.styleConfig).styleFor(id).alpha ?: pref(prefs, id)) * PERCENT).roundToInt()

    fun min(id: SurfaceId): Int = if (id == SurfaceId.DRAWER) DRAWER_MIN_PERCENT else 0

    // From the page's slider.
    fun set(viewModel: LauncherViewModel, id: SurfaceId, percent: Int) {
        val prefs = viewModel.state.value.prefs
        setPref(viewModel, id, percent)
        if (StyleConfig.decode(prefs.styleConfig).styleFor(id).alpha != null) {
            viewModel.set(Pref.styleConfig, withoutAlpha(prefs, id))
        }
    }

    // The sheet clears style.alpha itself, in the config it is editing.
    fun setPref(viewModel: LauncherViewModel, id: SurfaceId, percent: Int) {
        val value = percent.coerceAtLeast(min(id)) / PERCENT
        when (id) {
            SurfaceId.DOCK -> viewModel.set(Pref.dockBgOpacity, value)
            SurfaceId.DRAWER -> viewModel.set(Pref.drawerBgOpacity, value)
            else -> Unit
        }
    }

    // A slider position drawn before it is written: the page's live preview,
    // the sheet's strip.
    fun preview(prefs: LauncherPrefs, id: SurfaceId, percent: Int): LauncherPrefs {
        val cleared = prefs.copy(styleConfig = withoutAlpha(prefs, id))
        val value = percent / PERCENT
        return when (id) {
            SurfaceId.DOCK -> cleared.copy(dockBgOpacity = value)
            SurfaceId.DRAWER -> cleared.copy(drawerBgOpacity = value)
            else -> prefs
        }
    }

    private fun pref(prefs: LauncherPrefs, id: SurfaceId): Float =
        if (id == SurfaceId.DRAWER) prefs.drawerBgOpacity else prefs.dockBgOpacity

    private fun withoutAlpha(prefs: LauncherPrefs, id: SurfaceId): String {
        val config = StyleConfig.decode(prefs.styleConfig)
        return config.with(id, config.styleFor(id).copy(alpha = null)).encode()
    }

    private const val PERCENT = 100f

    // Pref.drawerBgOpacity's floor.
    private const val DRAWER_MIN_PERCENT = 20
}
