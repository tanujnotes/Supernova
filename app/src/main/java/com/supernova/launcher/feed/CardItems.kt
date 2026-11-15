package com.supernova.launcher.feed

// The card editor shows an apps-and-shortcuts card as one list, with a
// switch for "Show the first as a large tile". The model keeps that first
// item as the featured app or shortcut and the rest as actions; this maps
// one onto the other. A widget card's list is its actions alone (the widget
// is its featured content).
object CardItems {

    // How many apps and shortcuts a card holds, the large tile included.
    const val MAX = FeedCard.MAX_ACTIONS

    fun isWidget(card: FeedCard): Boolean = FeaturedType.fromName(card.featuredType) == FeaturedType.WIDGET

    // The featured app or shortcut, as an item, when there is one.
    private fun tile(card: FeedCard): CardAction? {
        val type = FeaturedType.fromName(card.featuredType)
        val target = card.featuredTarget
        return if ((type == FeaturedType.APP || type == FeaturedType.SHORTCUT) && target != null) {
            CardAction(type.name, target, "")
        } else {
            null
        }
    }

    fun largeTile(card: FeedCard): Boolean = tile(card) != null

    // The editor's list: the large tile first, then the actions.
    fun of(card: FeedCard): List<CardAction> =
        listOfNotNull(tile(card).takeUnless { isWidget(card) }) + card.actions

    // The list written back. With the large tile on, the first item becomes
    // the featured one; a widget card keeps its widget and takes the list as
    // its actions. At most MAX actions either way.
    fun apply(card: FeedCard, items: List<CardAction>, largeTile: Boolean): FeedCard {
        if (isWidget(card)) return card.copy(actions = items.take(MAX))
        val first = items.firstOrNull()?.takeIf { largeTile }
        return card.copy(
            featuredType = first?.type,
            featuredTarget = first?.target,
            actions = (if (first != null) items.drop(1) else items).take(MAX)
        )
    }
}
