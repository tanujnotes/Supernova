package com.supernova.launcher.data

import android.content.pm.LauncherApps
import android.os.Handler
import android.os.Looper
import android.os.UserHandle

// The system's package callbacks, passed on as AppChanges. One listener per
// live view model; the system callback behind them is registered once and
// stays for as long as any of them does.
class AppChanges(
    private val launcherApps: LauncherApps,
    private val serialFor: (UserHandle) -> Long
) {
    private var callback: LauncherApps.Callback? = null
    private val listeners = java.util.concurrent.CopyOnWriteArraySet<(AppChange) -> Unit>()

    fun register(onChange: (AppChange) -> Unit) {
        listeners += onChange
        if (callback != null) return
        val created = object : LauncherApps.Callback() {
            override fun onPackageRemoved(packageName: String, user: UserHandle) {
                dispatch(AppChange.Removed(packageName, serialFor(user)))
            }

            override fun onPackageAdded(packageName: String, user: UserHandle) {
                dispatch(AppChange.Added(packageName, serialFor(user)))
            }

            // The callbacks run on the main thread, and the render lock can
            // be held there for a whole icon pack read: invalidating is left
            // to the listener, off the main thread (invalidatePackages).
            override fun onPackageChanged(packageName: String, user: UserHandle) {
                dispatch(AppChange.Updated(serialFor(user), listOf(packageName)))
            }

            override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) {
                dispatch(AppChange.Updated(serialFor(user), packageNames.toList()))
            }

            override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) {
                dispatch(AppChange.Updated(serialFor(user)))
            }
        }
        callback = created
        launcherApps.registerCallback(created, Handler(Looper.getMainLooper()))
    }

    fun unregister(onChange: (AppChange) -> Unit) {
        listeners -= onChange
        if (listeners.isNotEmpty()) return
        callback?.let(launcherApps::unregisterCallback)
        callback = null
    }

    private fun dispatch(change: AppChange) {
        listeners.forEach { it(change) }
    }
}
