package com.supernova.launcher.feed

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Upcoming-events card: CalendarContract.Instances now -> +7d, top 5. Behind
// the READ_CALENDAR runtime permission; without it the card renders an
// in-card grant state (NeedsPermission) instead of silently vanishing. A
// ContentObserver refreshes the list while shown; everything unregisters on
// hidden.
class CalendarCardSource(
    private val context: Context,
    private val scope: CoroutineScope
) : CardSource<CalendarCardSource.CalendarState> {

    sealed class CalendarState {
        object NeedsPermission : CalendarState()
        data class Events(val events: List<Event>) : CalendarState()
    }

    data class Event(
        val eventId: Long,
        val title: String,
        val beginMs: Long,
        val allDay: Boolean
    )

    override val state = MutableStateFlow<CalendarState?>(null)

    private var shown = false
    private var observerRegistered = false
    private var queryJob: Job? = null

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = requery()
    }

    private fun granted(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    override fun onShown() {
        if (shown) return
        shown = true
        if (!granted()) {
            state.value = CalendarState.NeedsPermission
            return
        }
        runCatching {
            context.contentResolver.registerContentObserver(
                CalendarContract.Instances.CONTENT_URI, true, observer
            )
            observerRegistered = true
        }
        requery()
    }

    override fun onHidden() {
        if (!shown) return
        shown = false
        queryJob?.cancel()
        queryJob = null
        if (observerRegistered) {
            observerRegistered = false
            runCatching { context.contentResolver.unregisterContentObserver(observer) }
        }
        state.value = null
    }

    // Re-check after an in-card grant: the permission result arrives while
    // the feed is still open, so shown-state must be able to upgrade itself.
    fun refresh() {
        if (!shown) return
        if (!granted()) {
            state.value = CalendarState.NeedsPermission
            return
        }
        if (!observerRegistered) {
            runCatching {
                context.contentResolver.registerContentObserver(
                    CalendarContract.Instances.CONTENT_URI, true, observer
                )
                observerRegistered = true
            }
        }
        requery()
    }

    private fun requery() {
        if (!shown) return
        queryJob?.cancel()
        queryJob = scope.launch {
            val events = withContext(Dispatchers.IO) { queryUpcoming() }
            if (shown) state.value = CalendarState.Events(events)
        }
    }

    private fun queryUpcoming(): List<Event> {
        val now = System.currentTimeMillis()
        val end = now + WINDOW_MS
        return runCatching {
            val projection = arrayOf(
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.END
            )
            // A day wider than the window at the front: an all-day event is
            // stored as its UTC day, which west of UTC ends while the local
            // day still has hours to go. Each instance is then kept by when
            // it ends here.
            CalendarContract.Instances.query(context.contentResolver, projection, now - DAY_MS, end)
                ?.use { cursor ->
                    val events = ArrayList<Event>()
                    while (cursor.moveToNext()) {
                        val allDay = cursor.getInt(3) != 0
                        val begin = cursor.getLong(2)
                        val finish = cursor.getLong(4)
                        val localBegin = if (allDay) utcDayToLocal(begin) else begin
                        val localEnd = if (allDay) utcDayToLocal(finish) else finish
                        if (localEnd <= now) continue
                        events.add(
                            Event(
                                eventId = cursor.getLong(0),
                                title = cursor.getString(1).orEmpty(),
                                beginMs = localBegin,
                                allDay = allDay
                            )
                        )
                    }
                    events.sortedBy { it.beginMs }.take(MAX_EVENTS)
                }
        }.getOrNull().orEmpty()
    }

    // An all-day event's times are midnight UTC of its dates; the same date's
    // midnight here is what "Today" and the weekday are read from. Labelled
    // from the UTC time, west of UTC today's all-day event read as
    // yesterday's weekday and was gone by the evening, tomorrow's then
    // showing as today.
    private fun utcDayToLocal(utcMs: Long): Long =
        java.time.Instant.ofEpochMilli(utcMs).atZone(java.time.ZoneOffset.UTC).toLocalDate()
            .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    private companion object {
        const val WINDOW_MS = 7L * 24 * 60 * 60 * 1000
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val MAX_EVENTS = 5
    }
}
