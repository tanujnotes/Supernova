package com.supernova.launcher.ui.settings

import android.content.res.ColorStateList
import android.widget.SeekBar
import com.supernova.launcher.R
import com.supernova.launcher.ui.theme.CustomAccent

// The settings pages' bars, coloured outright: left to the theme, the filled
// part and the thumb came out white on the white cards and the track was
// all but invisible.
object SettingsSeekBars {

    fun tint(seek: SeekBar) {
        val accent = ColorStateList.valueOf(CustomAccent.of(seek))
        seek.progressTintList = accent
        seek.thumbTintList = accent
        seek.progressBackgroundTintList =
            ColorStateList.valueOf(seek.context.getColor(R.color.settingsTextSecondary))
    }
}
