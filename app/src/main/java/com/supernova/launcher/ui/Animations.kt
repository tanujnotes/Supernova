package com.supernova.launcher.ui

import com.supernova.launcher.data.LauncherPrefs

// Master animation switch. Every animation the launcher owns (launch reveal,
// folder/popup transitions, drag fades, page effects, search fade, wallpaper
// zoom) consults this at its site; system-driven animations are unaffected.
object Animations {
    fun enabled(prefs: LauncherPrefs): Boolean = !prefs.disableAnimations

    // Duration multiplier for the launcher's own animations; 0 when disabled.
    fun factor(prefs: LauncherPrefs): Float =
        if (!enabled(prefs)) 0f else AnimationSpeed.fromName(prefs.animationSpeed).factor

    // Scales a base duration by the animation-speed preference.
    fun duration(prefs: LauncherPrefs, baseMs: Long): Long =
        (baseMs * factor(prefs)).toLong().coerceAtLeast(0L)
}

// Playback speed for launcher-owned animations. DEFAULT keeps the tuned
// durations; the others scale them. "Disable animations" stays the on/off gate.
enum class AnimationSpeed(val factor: Float) {
    SLOW(1.6f),
    DEFAULT(1.0f),
    FAST(0.6f),
    FASTER(0.35f);

    companion object {
        fun fromName(name: String): AnimationSpeed =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: DEFAULT
    }
}

// App-launch transition styles (launchAnimation pref). LauncherActivity's
// launchWithAnimation plays them; ADAPTIVE_ICON is the default.
enum class LaunchAnimation {
    // Launcher3's own launch, the clip reveal from the icon: the same
    // launch as REVEAL.
    SYSTEM,
    // The icon's color fills the screen from it as a circle, then the app
    // fades in (LaunchPrelude.circle).
    CIRCLE,
    // The icon lifts toward the screen's center and fades, and the system
    // scales the app up out of it (LaunchPrelude.liftIcon). One UI plays
    // that as its own splash, whose centered icon takes over from the
    // lifted one.
    ADAPTIVE_ICON,
    // The clip reveal from the icon (Marshmallow style).
    REVEAL,
    // Lollipop style: the app rises from below the screen.
    SLIDE_UP,
    // The system's scale-up from the icon (Jellybean style).
    ZOOM,
    // Ice Cream Sandwich style: the launcher blinks out, then the app opens.
    BLINK,
    // The app slides in from the right over the sinking launcher.
    SLIDE_LEFT;

    companion object {
        fun fromName(name: String): LaunchAnimation =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: ADAPTIVE_ICON
    }
}

// How the app drawer arrives (drawerTransition pref). SLIDE_UP is the
// BottomSheetBehavior slide the drawer has always used; ZOOM and FADE park the
// sheet at its open position and animate it in place, so nothing slides; a
// swipe up opens them outright instead of pulling the sheet under the finger.
enum class DrawerTransition {
    SLIDE_UP,
    ZOOM,
    FADE;

    companion object {
        fun fromName(name: String): DrawerTransition =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: SLIDE_UP
    }
}

// Forced status-bar icon tint (darkStatusIcons pref). AUTO follows the theme
// (light theme = dark icons); LIGHT forces APPEARANCE_LIGHT_STATUS_BARS on
// (dark icons), DARK forces it off (light icons).
enum class StatusBarIcons {
    AUTO,
    LIGHT,
    DARK;

    companion object {
        fun fromName(name: String): StatusBarIcons =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: AUTO
    }
}

// Launcher orientation (homeOrientation pref). DEFAULT pins portrait exactly
// like the old homeRotation=false; AUTO_ROTATE follows the sensor; PORTRAIT
// and LANDSCAPE force an axis but allow the 180-degree sensor flip.
enum class HomeOrientation {
    DEFAULT,
    AUTO_ROTATE,
    PORTRAIT,
    LANDSCAPE;

    companion object {
        // "" derives from the legacy homeRotation flag.
        fun fromPrefs(prefs: LauncherPrefs): HomeOrientation =
            entries.firstOrNull { it.name.equals(prefs.homeOrientation, ignoreCase = true) }
                ?: if (prefs.homeRotation) AUTO_ROTATE else DEFAULT
    }
}

// Wallpaper parallax (wallpaperScrollMode pref). REVERSE pans the wallpaper
// against the page swipe direction.
enum class WallpaperScrollMode {
    ON,
    OFF,
    REVERSE;

    companion object {
        // "" derives from the legacy wallpaperScroll flag.
        fun fromPrefs(prefs: LauncherPrefs): WallpaperScrollMode =
            entries.firstOrNull { it.name.equals(prefs.wallpaperScrollMode, ignoreCase = true) }
                ?: if (prefs.wallpaperScroll) ON else OFF
    }
}

// Where the status-bar clock is hidden (hideClock pref), via the SystemUI
// icon_blacklist secure setting. Inert without WRITE_SECURE_SETTINGS.
enum class HideClock {
    NEVER,
    HOME_PAGE,
    DESKTOP,
    EVERYWHERE;

    companion object {
        fun fromName(name: String): HideClock =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: NEVER
    }
}
