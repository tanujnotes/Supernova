package com.supernova.launcher.data

import android.content.pm.LauncherActivityInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.drawable.Drawable

// Date icons an app ships itself, as Google Calendar does (#266): the
// activity's "<package>.dynamic_icons" meta-data names an array of 31
// drawables, one per day of the month. Its plain launcher icon is a fixed
// date, so the day's drawable stands in for it.
class DynamicAppIcons(private val pm: PackageManager, private val iconDensity: Int) {

    // component -> the array's resource id, 0 for none. Cleared per package
    // on an update, so a lookup costs one package query per app, once.
    private val arrays = java.util.concurrent.ConcurrentHashMap<String, Int>()

    fun has(info: LauncherActivityInfo): Boolean = arrayId(info) != 0

    // The drawable for `dayOfMonth` (1..31), or null when the app ships none.
    fun forDay(info: LauncherActivityInfo, dayOfMonth: Int): Drawable? {
        val arrayId = arrayId(info).takeIf { it != 0 } ?: return null
        return runCatching {
            val res = pm.getResourcesForApplication(info.applicationInfo)
            val array = res.obtainTypedArray(arrayId)
            val id = try {
                array.getResourceId(dayOfMonth - 1, Resources.ID_NULL)
            } finally {
                array.recycle()
            }
            if (id == Resources.ID_NULL) null else res.getDrawableForDensity(id, iconDensity, null)
        }.getOrNull()
    }

    fun forget(packageName: String) {
        arrays.keys.filter { it.startsWith("$packageName/") }.forEach(arrays::remove)
    }

    private fun arrayId(info: LauncherActivityInfo): Int {
        val component = info.componentName
        return arrays.getOrPut(component.flattenToString()) {
            val key = component.packageName + META_SUFFIX
            runCatching {
                pm.getActivityInfo(component, PackageManager.GET_META_DATA).metaData?.getInt(key, 0)
            }.getOrNull()?.takeIf { it != 0 }
                ?: info.applicationInfo.metaData?.getInt(key, 0)
                ?: 0
        }
    }

    private companion object {
        const val META_SUFFIX = ".dynamic_icons"
    }
}
