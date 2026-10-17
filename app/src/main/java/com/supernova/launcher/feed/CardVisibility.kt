package com.supernova.launcher.feed

// Pure activation evaluation for custom cards, run when the feed or drawer
// shows (and on monitor updates / the next day-time boundary while shown -
// never while both are hidden). The Environment snapshot is the injection
// seam: production builds it from the clock and ConditionMonitor, tests
// construct it directly.
//
// The model: every enabled card lives in the feed and
// in search; a card whose conditions are ALL met is "activated", which floats
// it to the top of the feed and shows it at the top of the app drawer.
object CardVisibility {

    data class Environment(
        // ISO day of week: 1 = Monday .. 7 = Sunday.
        val dayOfWeek: Int,
        // Minutes since local midnight, 0..1439.
        val minuteOfDay: Int,
        // Names of connected headset/A2DP devices; empty when unknown.
        val btDeviceNames: List<String> = emptyList()
    )

    // Enabled and EVERY condition holds (vacuously true without conditions).
    // A condition whose signal is unavailable (empty BT list) is unmet - the
    // documented degrade for a missing Bluetooth permission. A condition with
    // an unknown kind (a newer backup's, or the retired WIFI) is ignored.
    fun isVisible(card: FeedCard, env: Environment): Boolean {
        if (!card.enabled) return false
        return card.conditions.all { holds(it, env) }
    }

    // Activated = enabled, activation switched on, carries at least one
    // condition, and all of them hold. Only activated cards enter the app
    // drawer.
    fun isActivated(card: FeedCard, env: Environment): Boolean =
        card.activationEnabled && card.hasConditions && isVisible(card, env)

    // The feed's order: pinned cards first, then activated ones, then the
    // rest, each group keeping the stored order. Disabled cards drop out.
    fun feedOrder(cards: List<FeedCard>, env: Environment): List<FeedCard> {
        val enabled = cards.filter { it.enabled }
        val pinned = enabled.filter { it.pinned }
        val activated = enabled.filter { !it.pinned && isActivated(it, env) }
        val rest = enabled.filter { !it.pinned && !isActivated(it, env) }
        return pinned + activated + rest
    }

    // Drawer membership: activated cards in stored order.
    fun activated(cards: List<FeedCard>, env: Environment): List<FeedCard> =
        cards.filter { isActivated(it, env) }

    private fun holds(condition: CardCondition, env: Environment): Boolean =
        when (ConditionKind.fromName(condition.kind)) {
            ConditionKind.DAY_TIME -> dayTimeHolds(condition, env)
            ConditionKind.BLUETOOTH -> {
                val wanted = condition.btName?.trim().orEmpty()
                wanted.isEmpty() || env.btDeviceNames.any { it.equals(wanted, ignoreCase = true) }
            }
            null -> true
        }

    private fun dayTimeHolds(condition: CardCondition, env: Environment): Boolean {
        val days = condition.days
        if (!days.isNullOrEmpty() && env.dayOfWeek !in days) return false
        val start = condition.startMinute
        val end = condition.endMinute
        if (start == null || end == null) return true
        return minuteInRange(env.minuteOfDay, start, end)
    }

    // Inclusive start, exclusive end; start == end means "all day". A range
    // with end < start wraps past midnight (22:00-06:00).
    internal fun minuteInRange(minute: Int, start: Int, end: Int): Boolean = when {
        start == end -> true
        start < end -> minute in start until end
        else -> minute >= start || minute < end
    }

    // Minutes until the next start/end boundary of any DAY_TIME condition on
    // the visible cards, so the controller can post exactly one re-evaluation
    // while the feed stays open. Null = nothing time-gated.
    fun minutesToNextBoundary(cards: List<FeedCard>, env: Environment): Int? {
        var best: Int? = null
        cards.asSequence()
            .filter { it.enabled }
            .flatMap { it.conditions.asSequence() }
            .filter { ConditionKind.fromName(it.kind) == ConditionKind.DAY_TIME }
            .forEach { condition ->
                listOfNotNull(condition.startMinute, condition.endMinute).forEach { boundary ->
                    val delta = ((boundary - env.minuteOfDay).mod(MINUTES_PER_DAY))
                        .let { if (it == 0) MINUTES_PER_DAY else it }
                    best = minOf(best ?: delta, delta)
                }
            }
        return best
    }

    private const val MINUTES_PER_DAY = 24 * 60
}
