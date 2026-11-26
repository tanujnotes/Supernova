package com.supernova.launcher.widget

import android.content.Context
import androidx.core.content.edit

// The old -> new widget id map Android hands a restored launcher.
//
// When the platform restores the launcher's own data (a new-phone transfer,
// or a cloud backup coming back) it restores the widget host with it: it
// allocates a fresh id for every widget the old install had, binds it to the
// same provider, and tells the widget's own app to move its settings across
// (ACTION_APPWIDGET_RESTORED, which the app handles in
// AppWidgetProvider.onRestored). So the widgets are already live and already
// configured; all the launcher has to do is point its layout at the new ids
// instead of the dead ones it was saved with.
//
// The map for that arrives in ACTION_APPWIDGET_HOST_RESTORED, in one
// broadcast or several, and can land before or after the restored layout
// itself is in place - so it is kept here until a start-up can use it
// (WidgetController.adoptRestoredIds), and only dropped once it has been
// applied.
//
// Deliberately SharedPreferences and not the settings store: a receiver has
// to finish inside onReceive, with no coroutine to wait on, and the backup
// rules cover files/ only, so nothing kept here is itself backed up or
// overwritten by the very restore it describes.
internal object WidgetHostRestore {

    fun record(context: Context, oldIds: IntArray, newIds: IntArray) {
        val pairs = oldIds.zip(newIds).filter { (old, new) -> old != 0 && new != 0 }
        if (pairs.isEmpty()) return
        // Another part of the same restore may already be waiting.
        val merged = pending(context) + pairs
        val text = merged.entries.joinToString(BETWEEN) { "${it.key}$WITHIN${it.value}" }
        store(context).edit(commit = true) { putString(KEY, text) }
    }

    fun pending(context: Context): Map<Int, Int> =
        store(context).getString(KEY, null).orEmpty()
            .split(BETWEEN)
            .mapNotNull { pair ->
                val parts = pair.split(WITHIN).takeIf { it.size == 2 } ?: return@mapNotNull null
                val old = parts[0].toIntOrNull() ?: return@mapNotNull null
                val new = parts[1].toIntOrNull() ?: return@mapNotNull null
                old to new
            }
            .toMap()

    fun clear(context: Context) {
        store(context).edit(commit = true) { remove(KEY) }
    }

    private fun store(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private const val FILE = "widget_host_restore"
    private const val KEY = "old_to_new"
    private const val WITHIN = ":"
    private const val BETWEEN = ","
}
