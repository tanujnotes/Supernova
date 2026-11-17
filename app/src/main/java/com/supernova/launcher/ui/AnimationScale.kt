package com.supernova.launcher.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.database.ContentObserver
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log

// Supernova's animations follow its own "Disable animations" switch, not
// the system's animation setting. Android hands every app one animator
// duration scale; at 0 ("Remove animations", the developer option, some
// battery savers) every animator jumps to its end. While the launcher's
// animations are on, this process runs its animators at 1 whatever the
// system says; with them off, it goes back to the system's own scale.
//
// The system sets the scale when the first window opens and again on every
// change, so the scale is put back each time it moves (a listener on
// Android 13+, the setting's observer and every activity's resume).
// Should the platform refuse the call, nothing changes: Animations.enabled
// still sees the scale at 0 and the launcher stays still, as it used to.
object AnimationScale {

    private const val TAG = "AnimationScale"

    private val main = Handler(Looper.getMainLooper())

    // The launcher's own switch, from the settings.
    @Volatile private var launcherAnimations = true

    // The scale the system last gave this process.
    @Volatile private var systemScale = 1f

    // True while this object is the one setting the scale.
    @Volatile private var settingOwn = false

    private var installed = false

    private val setter by lazy { findSetter() }

    // Hidden API on purpose (see above); a refusal leaves setter null.
    @SuppressLint("DiscouragedPrivateApi")
    private fun findSetter() = runCatching {
        ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType)
            .apply { isAccessible = true }
    }.onFailure { Log.w(TAG, "No setDurationScale", it) }.getOrNull()

    // Whether the system's animations are off: what the first run asks about.
    fun systemAnimationsOff(context: Context): Boolean =
        systemScale == 0f || settingScale(context) == 0f

    fun install(app: Application) {
        if (installed) return
        installed = true
        systemScale = settingScale(app)
        // The first window of a process takes the system's scale; each
        // resume puts this one back after it.
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                activity.window.decorView.post(::apply)
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ValueAnimator.registerDurationScaleChangeListener { scale ->
                if (!settingOwn) {
                    systemScale = scale
                    main.post(::apply)
                }
            }
        }
        app.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false,
            object : ContentObserver(main) {
                override fun onChange(selfChange: Boolean) {
                    systemScale = settingScale(app)
                    apply()
                }
            }
        )
    }

    // The launcher's switch changed (or was read).
    fun setLauncherAnimations(on: Boolean) {
        launcherAnimations = on
        if (Looper.myLooper() == Looper.getMainLooper()) apply() else main.post(::apply)
    }

    // Puts this process's scale where it belongs now. Cheap when it already
    // is; every activity's resume calls it, after the system's first set.
    fun apply() {
        val wanted = if (launcherAnimations) 1f else systemScale
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ValueAnimator.getDurationScale() == wanted) return
        val method = setter ?: return
        settingOwn = true
        try {
            method.invoke(null, wanted)
        } catch (e: ReflectiveOperationException) {
            Log.w(TAG, "setDurationScale refused", e)
        } finally {
            settingOwn = false
        }
    }

    private fun settingScale(context: Context): Float =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
}
