package com.supernova.launcher.ui.theme

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.supernova.launcher.R

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    // Auto: dark from sunset to sunrise (NightWindow).
    AUTO,
    // Dark during a user-set daily window (D1); no location, pure clock.
    SCHEDULE;

    companion object {
        fun fromName(name: String): ThemeMode =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: SYSTEM
    }
}

// Curated accents: arbitrary RGB can't reach Material widget internals
// without generating overlays at runtime, so each entry maps to a static
// ThemeOverlay defined in themes.xml.
enum class AccentColor(val colorRes: Int, val overlayRes: Int) {
    BLUE(R.color.accentBlue, R.style.ThemeOverlay_Supernova_Blue),
    INDIGO(R.color.accentIndigo, R.style.ThemeOverlay_Supernova_Indigo),
    PURPLE(R.color.accentPurple, R.style.ThemeOverlay_Supernova_Purple),
    PINK(R.color.accentPink, R.style.ThemeOverlay_Supernova_Pink),
    RED(R.color.accentRed, R.style.ThemeOverlay_Supernova_Red),
    ORANGE(R.color.accentOrange, R.style.ThemeOverlay_Supernova_Orange),
    AMBER(R.color.accentAmber, R.style.ThemeOverlay_Supernova_Amber),
    GREEN(R.color.accentGreen, R.style.ThemeOverlay_Supernova_Green),
    TEAL(R.color.accentTeal, R.style.ThemeOverlay_Supernova_Teal),
    CYAN(R.color.accentCyan, R.style.ThemeOverlay_Supernova_Cyan);

    companion object {
        fun fromName(name: String): AccentColor =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: BLUE
    }
}

// Applies theme mode and accent. Night mode is a process-wide delegate call;
// the accent is a theme overlay stamped onto each activity before its content
// is inflated, so dialogs built from that activity inherit it.
object ThemeEngine {

    @Volatile
    private var appliedNight: Int? = null
    private val scheduleHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // The theme mode the prefs ask for. SCHEDULE and AUTO are dark during a
    // daily window, minutes since midnight where an end before the start
    // wraps past midnight, and arm a main-thread re-check at the next
    // boundary so the theme flips while the launcher sits open.
    fun applyMode(context: Context, prefs: com.supernova.launcher.data.LauncherPrefs) {
        scheduleHandler.removeCallbacksAndMessages(null)
        val app = context.applicationContext
        val night = modeFor(prefs) { delay ->
            scheduleHandler.postDelayed({ applyMode(app, prefs) }, delay)
        }
        if (night == appliedNight) return
        appliedNight = night
        AppCompatDelegate.setDefaultNightMode(night)
    }

    // Whether the launcher is dark under these prefs right now, from the
    // same reckoning applyMode makes; Follow system reads the system. For
    // what isn't themed by the night resources, like themed icons' tint.
    fun isNight(context: Context, prefs: com.supernova.launcher.data.LauncherPrefs): Boolean {
        val app = context.applicationContext
        return when (modeFor(prefs, recheckIn = null)) {
            AppCompatDelegate.MODE_NIGHT_YES -> true
            AppCompatDelegate.MODE_NIGHT_NO -> false
            // The application's configuration is the system's; AppCompat
            // overrides only its activities'.
            else -> (app.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
    }

    // recheckIn, when given, is told how long until the answer can change.
    private fun modeFor(
        prefs: com.supernova.launcher.data.LauncherPrefs,
        recheckIn: ((Long) -> Unit)?
    ): Int = when (ThemeMode.fromName(prefs.themeMode)) {
        ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
        ThemeMode.SCHEDULE -> darkWithin(prefs.themeScheduleStart, prefs.themeScheduleEnd, recheckIn)
        ThemeMode.AUTO -> when (val tonight = NightWindow.today()) {
            is NightWindow.Tonight.Window -> darkWithin(tonight.sunset, tonight.sunrise, recheckIn)
            is NightWindow.Tonight.AllDay -> {
                // Polar day or night: look again tomorrow.
                recheckIn?.invoke(untilMidnightMs())
                if (tonight.dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
            }
        }
    }

    private fun darkWithin(startMinute: Int, endMinute: Int, recheckIn: ((Long) -> Unit)?): Int {
        val now = java.util.Calendar.getInstance()
        val minute = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
        val dark = com.supernova.launcher.feed.CardVisibility.minuteInRange(minute, startMinute, endMinute)
        recheckIn?.invoke(nextBoundaryDelayMs(minute, startMinute, endMinute, now.get(java.util.Calendar.SECOND)))
        return if (dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
    }

    private fun untilMidnightMs(): Long {
        val now = java.util.Calendar.getInstance()
        val minute = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
        return (1440 - minute) * 60_000L - now.get(java.util.Calendar.SECOND) * 1000L + 2_000L
    }

    private fun nextBoundaryDelayMs(minute: Int, start: Int, end: Int, second: Int): Long {
        fun minutesUntil(target: Int): Int = ((target - minute) % 1440 + 1440) % 1440
        val until = listOf(minutesUntil(start), minutesUntil(end))
            .map { if (it == 0) 1440 else it }
            .min()
        return until * 60_000L - second * 1000L + 2_000L
    }

    // Call before setContentView.
    fun applyAccent(activity: Activity, accent: AccentColor) {
        activity.theme.applyStyle(accent.overlayRes, true)
    }

    fun accentColor(context: Context, accent: AccentColor): Int =
        context.getColor(accent.colorRes)
}
