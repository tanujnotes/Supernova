package com.supernova.launcher.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.StringRes
import com.supernova.launcher.R
import com.supernova.launcher.SupernovaAccessibilityService
import com.supernova.launcher.SupernovaDeviceAdmin
import com.supernova.launcher.notification.SupernovaNotificationListener

// The Labs Permissions page, for Supernova's own: each permission and
// special access it can use, what for, and whether it has it now. Every one
// is optional and asked for where its feature is switched on; this is the
// place to see them together and change them.
enum class LabsPermission(
    @StringRes val title: Int,
    @StringRes val use: Int,
    // Runtime permissions to ask for; empty for special access, which is
    // granted on a system screen instead.
    val runtime: Array<String> = emptyArray()
) {
    CONTACTS(R.string.permission_contacts, R.string.permission_contacts_use, arrayOf(Manifest.permission.READ_CONTACTS)),
    CALENDAR(R.string.permission_calendar, R.string.permission_calendar_use, arrayOf(Manifest.permission.READ_CALENDAR)),
    // Asked for together, so the system dialog offers precise or approximate.
    LOCATION(
        R.string.permission_location, R.string.permission_location_use,
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    ),
    NEARBY_DEVICES(
        R.string.permission_nearby, R.string.permission_nearby_use,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyArray()
    ),
    NOTIFICATIONS(R.string.permission_notifications, R.string.permission_notifications_use),
    // Posting one is a permission only from Android 13.
    POST_NOTIFICATIONS(
        R.string.permission_post_notifications, R.string.permission_post_notifications_use,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyArray()
        }
    ),
    ACCESSIBILITY(R.string.permission_accessibility, R.string.permission_accessibility_use),
    DEVICE_ADMIN(R.string.permission_device_admin, R.string.permission_device_admin_use);

    // Nearby devices is a permission only from Android 12, and posting
    // notifications only from 13; before those, nothing is asked for.
    val applies: Boolean get() = (this != NEARBY_DEVICES && this != POST_NOTIFICATIONS) || runtime.isNotEmpty()

    fun granted(context: Context): Boolean = when (this) {
        NOTIFICATIONS -> SupernovaNotificationListener.isEnabled(context)
        ACCESSIBILITY -> SupernovaAccessibilityService.isEnabled(context)
        DEVICE_ADMIN -> SupernovaDeviceAdmin.isActive(context)
        // Either location grant counts; status() tells them apart.
        LOCATION -> runtime.any { has(context, it) }
        else -> runtime.all { has(context, it) }
    }

    // "Allowed · contacts in search and the feed", under the row's title.
    fun status(context: Context): String {
        val state = when {
            this == LOCATION && has(context, Manifest.permission.ACCESS_FINE_LOCATION) ->
                R.string.permission_allowed_precise
            this == LOCATION && has(context, Manifest.permission.ACCESS_COARSE_LOCATION) ->
                R.string.permission_allowed_approximate
            runtime.isEmpty() -> if (granted(context)) R.string.permission_on else R.string.permission_off
            granted(context) -> R.string.permission_allowed
            else -> R.string.permission_not_allowed
        }
        return context.getString(R.string.permission_status_line, context.getString(state), context.getString(use))
    }

    private fun has(context: Context, permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
