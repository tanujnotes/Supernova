package com.supernova.launcher.feed

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.Calendar

// One activation evaluator shared by every surface that shows cards (feed
// pane, drawer). It owns the ConditionMonitor and keeps the dormancy rule:
// the monitor runs only while at least one surface holds it AND some enabled
// card actually carries a Wi-Fi/Bluetooth condition. Surfaces acquire on
// show, release on hide, and listen for signal changes to re-evaluate.
class CardActivation(context: Context, private val cards: () -> List<FeedCard>) {

    private val monitor = ConditionMonitor(context.applicationContext)
    private val holders = HashSet<Any>()
    private val listeners = ArrayList<() -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())
    // When each card last became activated, for the card header's history
    // mark (it shows the last activation). Process lifetime, like
    // the monitor; a card already active when first evaluated counts from
    // then.
    private val lastActivatedAt = HashMap<String, Long>()
    private var wasActivated: Set<String> = emptySet()

    init {
        // Network/Bluetooth callbacks arrive off the main thread.
        monitor.onChanged = {
            mainHandler.post {
                if (holders.isNotEmpty()) listeners.toList().forEach { it() }
            }
        }
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun acquire(owner: Any) {
        holders.add(owner)
        ensureMonitor()
    }

    fun release(owner: Any) {
        holders.remove(owner)
        if (holders.isEmpty()) monitor.stop()
    }

    // The owning activity is going: whatever still holds or listens is let
    // go, and the monitor stops, so no system callback keeps it alive.
    fun dispose() {
        holders.clear()
        listeners.clear()
        monitor.stop()
    }

    // The card set changed while a surface is showing: re-arm with the new
    // signal needs (a stopped monitor is cheap; a stale one misses signals).
    fun refresh() {
        if (holders.isEmpty()) return
        monitor.stop()
        ensureMonitor()
    }

    val locationUsable: Boolean get() = monitor.locationUsable()

    private fun ensureMonitor() {
        val kinds = cards().filter { it.enabled }
            .flatMap { it.conditions }
            .mapNotNull { ConditionKind.fromName(it.kind) }
        val needsWifi = ConditionKind.WIFI in kinds
        val needsBt = ConditionKind.BLUETOOTH in kinds
        if (needsWifi || needsBt) monitor.start(needsWifi, needsBt)
    }

    fun environment(): CardVisibility.Environment {
        val calendar = Calendar.getInstance()
        val isoDay = when (calendar.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 1
            Calendar.TUESDAY -> 2
            Calendar.WEDNESDAY -> 3
            Calendar.THURSDAY -> 4
            Calendar.FRIDAY -> 5
            Calendar.SATURDAY -> 6
            else -> 7
        }
        return CardVisibility.Environment(
            dayOfWeek = isoDay,
            minuteOfDay = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE),
            wifiSsid = monitor.currentSsid,
            btDeviceNames = monitor.btDeviceNames()
        )
    }

    // Feed membership and order: pinned, then activated, then the rest.
    fun feedOrder(): List<FeedCard> {
        val environment = environment()
        noteActivations(CardVisibility.activated(cards(), environment))
        return CardVisibility.feedOrder(cards(), environment)
    }

    // Drawer membership: activated cards only.
    fun activated(): List<FeedCard> =
        CardVisibility.activated(cards(), environment()).also(::noteActivations)

    fun lastActivatedAt(cardId: String): Long? = lastActivatedAt[cardId]

    private fun noteActivations(activated: List<FeedCard>) {
        val ids = activated.mapTo(HashSet()) { it.id }
        val now = System.currentTimeMillis()
        for (id in ids) if (id !in wasActivated) lastActivatedAt[id] = now
        wasActivated = ids
    }

    fun minutesToNextBoundary(): Int? = CardVisibility.minutesToNextBoundary(cards(), environment())
}
