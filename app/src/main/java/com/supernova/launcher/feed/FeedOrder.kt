package com.supernova.launcher.feed

// The feed's own order of cards, the launcher's built-in ones included,
// as the user arranges it (drag, or Move up / Move down in a card's menu).
//
// Stored as one list of keys (LauncherModel.feedOrder): a custom card's id,
// or one of the built-in keys below. The feed shows it in three groups -
// pinned cards, then activated ones, then the rest - and each group keeps
// the stored order, so a card only ever moves among its own group.
object FeedOrder {

    const val MEDIA = "builtin:media"
    const val CALENDAR = "builtin:calendar"
    const val CONTACTS = "builtin:contacts"

    // Where they stood before the order could be changed: first.
    val BUILT_INS = listOf(MEDIA, CALENDAR, CONTACTS)

    enum class Group { PINNED, ACTIVATED, REST }

    // One card as the feed shows it; card is null for a built-in.
    data class Entry(val key: String, val group: Group, val card: FeedCard?)

    // Every key in feed order: the stored order wherever it names something
    // that still exists, then any card it doesn't name (a new one) at the
    // end, and a built-in it doesn't name at the front, where the feed
    // always had them. An empty list is the order the feed had before.
    fun resolve(stored: List<String>, cardIds: List<String>): List<String> {
        val known = HashSet<String>(BUILT_INS).apply { addAll(cardIds) }
        val kept = stored.filter { it in known }.distinct()
        val missingBuiltIns = BUILT_INS.filter { it !in kept }
        val missingCards = cardIds.filter { it !in kept }
        return missingBuiltIns + kept + missingCards
    }

    // The feed: pinned cards, then activated ones, then the rest with the
    // built-ins among them, each group in `order`. Disabled cards drop out,
    // and so does a built-in not in `shownBuiltIns` (switched off, or with
    // nothing to show).
    fun layout(
        order: List<String>,
        cards: List<FeedCard>,
        shownBuiltIns: Set<String>,
        isActivated: (FeedCard) -> Boolean
    ): List<Entry> {
        val byId = cards.associateBy { it.id }
        val keys = resolve(order, cards.map { it.id })
        val pinned = ArrayList<Entry>()
        val activated = ArrayList<Entry>()
        val rest = ArrayList<Entry>()
        for (key in keys) {
            val card = byId[key]
            when {
                card == null -> if (key in shownBuiltIns) rest.add(Entry(key, Group.REST, null))
                !card.enabled -> Unit
                card.pinned -> pinned.add(Entry(key, Group.PINNED, card))
                isActivated(card) -> activated.add(Entry(key, Group.ACTIVATED, card))
                else -> rest.add(Entry(key, Group.REST, card))
            }
        }
        return pinned + activated + rest
    }

    // `order` with `key` taken out and put back right before `target`, or
    // right after it. Unchanged when either is missing.
    fun move(order: List<String>, key: String, target: String, after: Boolean): List<String> {
        if (key == target || key !in order || target !in order) return order
        val without = order.filter { it != key }
        val at = without.indexOf(target) + if (after) 1 else 0
        return without.toMutableList().apply { add(at, key) }
    }

    // The neighbour a card would swap with to move up (or down) in the feed
    // as shown: the next card in its own group, or null at the group's edge.
    fun neighbour(entries: List<Entry>, key: String, up: Boolean): Entry? {
        val index = entries.indexOfFirst { it.key == key }
        val other = if (index < 0) null else entries.getOrNull(if (up) index - 1 else index + 1)
        return other?.takeIf { it.group == entries[index].group }
    }
}
