package com.supernova.launcher

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context

// Minimal device-admin receiver so screen lock can use DevicePolicyManager
// (an alternative to the accessibility service). The policy declares only
// force-lock; no wipe, no password control.
class SupernovaDeviceAdmin : DeviceAdminReceiver() {

    companion object {
        fun component(context: Context): ComponentName =
            ComponentName(context, SupernovaDeviceAdmin::class.java)

        fun isActive(context: Context): Boolean =
            context.getSystemService(DevicePolicyManager::class.java)
                ?.isAdminActive(component(context)) == true

        // Locks the screen when this launcher is an active device admin.
        fun lock(context: Context): Boolean {
            val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return false
            if (!dpm.isAdminActive(component(context))) return false
            return runCatching { dpm.lockNow() }.isSuccess
        }
    }
}
