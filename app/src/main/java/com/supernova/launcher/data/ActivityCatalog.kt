package com.supernova.launcher.data

import android.content.pm.ActivityInfo
import android.content.pm.PackageManager

// One activity the Activities picker offers: a class another app lets
// anyone start. Its own icon, when it has one, is the picker's to load.
data class ActivityChoice(val className: String, val label: String, val hasOwnIcon: Boolean)

// One installed package and the activities it lets other apps start.
data class ActivityApp(val packageName: String, val label: String, val activities: List<ActivityChoice>)

// What the Activities picker lists: every installed package, launcher icon
// or not (a system package's settings screen is the usual reason to pick an
// activity), with each one's enabled, exported activities; a package with
// none is left out. Packages and their activities go A-Z by label, and
// activities that share a label keep the manifest's order.
object ActivityCatalog {

    // Off the main thread: a binder call per package. Asking for every
    // package's activities in one call can overflow the binder buffer, so
    // they come one package at a time.
    fun load(pm: PackageManager): List<ActivityApp> {
        val packages = runCatching { pm.getInstalledPackages(0) }.getOrDefault(emptyList())
        val apps = packages.mapNotNull { info ->
            val activities = runCatching {
                pm.getPackageInfo(info.packageName, PackageManager.GET_ACTIVITIES).activities
            }.getOrNull().orEmpty().filter(::offered)
            if (activities.isEmpty()) return@mapNotNull null
            val choices = activities.map { activity ->
                ActivityChoice(
                    className = activity.name,
                    label = runCatching { activity.loadLabel(pm).toString() }.getOrDefault(activity.name),
                    hasOwnIcon = activity.icon != 0
                )
            }
            ActivityApp(
                packageName = info.packageName,
                label = info.applicationInfo?.let { app -> runCatching { app.loadLabel(pm).toString() }.getOrNull() }
                    ?: info.packageName,
                activities = LabelOrder.sorted(choices) { it.label }
            )
        }
        return LabelOrder.sorted(apps) { it.label }
    }

    // Only what another app may start: an activity that isn't exported
    // throws a SecurityException when the icon is tapped.
    private fun offered(activity: ActivityInfo): Boolean = activity.isEnabled && activity.exported

    // The class under an activity's name: from the package's own dot when it
    // lives in its package (".neko.NekoLand"), the whole name when not.
    fun shortClassName(packageName: String, className: String): String =
        if (className.startsWith("$packageName.")) className.removePrefix(packageName) else className
}
