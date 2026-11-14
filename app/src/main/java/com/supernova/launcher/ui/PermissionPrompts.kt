package com.supernova.launcher.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.annotation.StringRes
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.supernova.launcher.R
import com.supernova.launcher.data.LauncherPrefs
import com.supernova.launcher.data.LockMethod
import com.supernova.launcher.gesture.GestureAction
import com.supernova.launcher.gesture.GestureDefaults
import com.supernova.launcher.notification.SupernovaNotificationListener
import com.supernova.launcher.runLogged

// The special-access grants a setting or a gesture depends on: why it is
// wanted is said first, in a dialog, and only then is the user sent to the
// system screen, with "Not now" to stay put.
object PermissionPrompts {

    fun explainNotificationAccess(activity: Activity, @StringRes message: Int) {
        if (SupernovaNotificationListener.isEnabled(activity)) return
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.notification_access_title)
            .setMessage(message)
            .setPositiveButton(R.string.open_settings) { _, _ -> openNotificationAccess(activity) }
            .setNegativeButton(R.string.not_now, null)
            .show()
    }

    // Supernova's own entry where the system has one (API 30+), else the list.
    fun openNotificationAccess(context: Context) {
        val component = ComponentName(context, SupernovaNotificationListener::class.java)
        val detail = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
        } else {
            null
        }
        val opened = detail != null && runCatching { context.startActivity(detail) }.isSuccess
        if (!opened) {
            runLogged("open notification access settings") {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
    }

    fun explainAccessibility(activity: Activity) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.accessibility_needed_title)
            .setMessage(R.string.accessibility_needed_message)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                runLogged("open accessibility settings") {
                    activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
            .setNegativeButton(R.string.not_now, null)
            .show()
    }

    // A gesture is bound to something only the accessibility service can do.
    fun gesturesNeedAccessibility(prefs: LauncherPrefs): Boolean =
        GestureDefaults.decode(prefs.gestureBindings).values.any { binding ->
            when (binding.action) {
                GestureAction.RECENTS, GestureAction.QUICK_SETTINGS -> true
                GestureAction.LOCK_SCREEN -> prefs.lockMethod != LockMethod.DEVICE_ADMIN
                else -> false
            }
        }
}
