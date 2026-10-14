package com.supernova.launcher.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.supernova.launcher.R
import com.supernova.launcher.SettingsActivity
import com.supernova.launcher.ui.BadgeLook

// "Test notification badge": one notification from Supernova itself,
// so a badge turns up on the Supernova Settings icon in the drawer. It
// carries a picture, which is what a Dynamic badge shows.
object BadgeTestNotification {

    private const val CHANNEL_ID = "badge_test"
    private const val NOTIFICATION_ID = 0x5B
    private const val PICTURE_PX = 192

    // False when Supernova may not post notifications (the permission, or
    // the user switched them off for the app).
    fun post(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
            ?.takeIf { it.areNotificationsEnabled() } ?: return false
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.badge_test_channel),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { setShowBadge(true) }
        manager.createNotificationChannel(channel)
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_menu_notification)
            .setLargeIcon(BadgeLook.samplePicture(context.getString(R.string.app_name).take(1), PICTURE_PX))
            .setContentTitle(context.getString(R.string.badge_test_title))
            .setContentText(context.getString(R.string.badge_test_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
        return true
    }
}
