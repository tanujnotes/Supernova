package com.supernova.launcher.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// Android telling the launcher, after restoring its data, which of its
// widgets now live under which id. Declared in the manifest because it
// arrives around the restore, with the launcher not running; the ids are
// only written down here (see WidgetHostRestore) and applied to the layout
// at the next start-up.
//
// Only the system can send this broadcast, and only our own host id is
// listened for - a widget belonging to some other host would otherwise be
// adopted into our layout.
class WidgetHostRestoredReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val ours = intent.action == AppWidgetManager.ACTION_APPWIDGET_HOST_RESTORED &&
            intent.getIntExtra(AppWidgetManager.EXTRA_HOST_ID, NO_HOST) == SupernovaWidgetHost.HOST_ID
        if (!ours) return
        val oldIds = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_OLD_IDS)
        val newIds = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
        if (oldIds != null && newIds != null) WidgetHostRestore.record(context, oldIds, newIds)
    }

    private companion object {
        const val NO_HOST = -1
    }
}
