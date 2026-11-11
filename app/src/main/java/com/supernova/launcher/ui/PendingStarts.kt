package com.supernova.launcher.ui

import android.app.Activity
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.IntentSender
import android.os.Build
import android.os.Bundle

// A PendingIntent or IntentSender the launcher fires from a tap. Since
// Android 14 the sender has to pass on its own right to start activities
// (the user is looking at it); otherwise a creator that grants none is
// blocked as a background start. The system's private space senders
// (Install apps, Private space settings) are one such creator.
object PendingStarts {

    fun start(activity: Activity, sender: IntentSender) =
        activity.startIntentSender(sender, null, 0, 0, 0, options())

    fun send(intent: PendingIntent) = intent.send(null, 0, null, null, null, null, options())

    private fun options(): Bundle? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
        } else {
            @Suppress("DEPRECATION")
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
        }
        return ActivityOptions.makeBasic().apply { setPendingIntentBackgroundActivityStartMode(mode) }.toBundle()
    }
}
