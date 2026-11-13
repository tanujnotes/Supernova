package com.supernova.launcher.ui

import android.animation.ValueAnimator
import com.supernova.launcher.data.LauncherPrefs
import com.supernova.launcher.data.SettingEnumSerializer

// Master animation switch. Every animation the launcher owns (launch reveal,
// folder/popup transitions, drag fades, page effects, search fade, wallpaper
// zoom) consults this at its site; system-driven animations are unaffected.
object Animations {
    fun enabled(prefs: LauncherPrefs): Boolean = !prefs.disableAnimations

    // Duration multiplier for the launcher's own animations; 0 when disabled.
    fun factor(prefs: LauncherPrefs): Float =
        if (!enabled(prefs)) 0f else prefs.animationSpeed.factor

    // Scales a base duration by the animation-speed preference.
    fun duration(prefs: LauncherPrefs, baseMs: Long): Long =
        (baseMs * factor(prefs)).toLong().coerceAtLeast(0L)

    // Whether a jump to another page (home press, page action, desktop
    // previews) scrolls there or lands at once. A pager's smooth scroll runs
    // on a scroller, which ignores the system animator scale, so "Remove
    // animations" (scale 0) is checked here as well as the launcher's switch.
    fun scrollPages(prefs: LauncherPrefs): Boolean =
        enabled(prefs) && ValueAnimator.areAnimatorsEnabled()
}

// Playback speed for launcher-owned animations. DEFAULT keeps the tuned
// durations; the others scale them. "Disable animations" stays the on/off gate.
@kotlinx.serialization.Serializable(with = AnimationSpeed.Names::class)
enum class AnimationSpeed(val factor: Float) {
    SLOW(1.6f),
    DEFAULT(1.0f),
    FAST(0.6f),
    FASTER(0.35f);

    // Its name in the store and in a backup, read back leniently.
    object Names : SettingEnumSerializer<AnimationSpeed>("AnimationSpeed", entries, DEFAULT)

    companion object {
        fun fromName(name: String): AnimationSpeed = Names.parse(name)
    }
}

// App-launch transition styles (launchAnimation pref). LauncherActivity's
// launchWithAnimation plays them; ADAPTIVE_ICON is the default.
@kotlinx.serialization.Serializable(with = LaunchAnimation.Names::class)
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

    // Its name in the store and in a backup, read back leniently.
    object Names : SettingEnumSerializer<LaunchAnimation>("LaunchAnimation", entries, ADAPTIVE_ICON)

    companion object {
        fun fromName(name: String): LaunchAnimation = Names.parse(name)
    }
}

// How the app drawer arrives (drawerTransition pref). SLIDE_UP is the
// BottomSheetBehavior slide the drawer has always used; ZOOM and FADE park the
// sheet at its open position and animate it in place, so nothing slides; a
// swipe up opens them outright instead of pulling the sheet under the finger.
@kotlinx.serialization.Serializable(with = DrawerTransition.Names::class)
enum class DrawerTransition {
    SLIDE_UP,
    ZOOM,
    FADE;

    // Its name in the store and in a backup, read back leniently.
    object Names : SettingEnumSerializer<DrawerTransition>("DrawerTransition", entries, SLIDE_UP)

    companion object {
        fun fromName(name: String): DrawerTransition = Names.parse(name)
    }
}

// Forced status-bar icon tint (darkStatusIcons pref). AUTO follows the theme
// (light theme = dark icons); LIGHT forces APPEARANCE_LIGHT_STATUS_BARS on
// (dark icons), DARK forces it off (light icons).
@kotlinx.serialization.Serializable(with = StatusBarIcons.Names::class)
enum class StatusBarIcons {
    AUTO,
    LIGHT,
    DARK;

    // Its name in the store and in a backup, read back leniently.
    object Names : SettingEnumSerializer<StatusBarIcons>("StatusBarIcons", entries, AUTO)

    companion object {
        fun fromName(name: String): StatusBarIcons = Names.parse(name)
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
        private const val ONE_PAGE_OFFSET = 0.5f

        // "" derives from the legacy wallpaperScroll flag.
        fun fromPrefs(prefs: LauncherPrefs): WallpaperScrollMode =
            entries.firstOrNull { it.name.equals(prefs.wallpaperScrollMode, ignoreCase = true) }
                ?: if (prefs.wallpaperScroll) ON else OFF
    }

    // Where across the wallpaper (0..1) the screen sits at this scroll
    // position, in pages: a one-page home screen sits in the middle.
    fun offset(scrollPosition: Float, pages: Int): Float {
        val forward = if (pages <= 1) ONE_PAGE_OFFSET else (scrollPosition / (pages - 1)).coerceIn(0f, 1f)
        return if (this == REVERSE) 1f - forward else forward
    }
}
