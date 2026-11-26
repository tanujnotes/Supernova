package com.supernova.launcher.data

// A backup's widget ids mean something only on the install that wrote it, and
// then only while they are still bound: any other is cleared, so it can't show
// someone else's widget. The provider stays, for WidgetController to bind a
// new id to.
internal object RestoredWidgets {

    // What the import's message counts: widgets and widget cards with no id.
    fun needingRebind(model: LauncherModel): Int =
        model.items.count { it.itemType == ItemType.WIDGET && it.appWidgetId == null } +
            model.feedCards.count { it.widgetProvider != null && it.appWidgetId == null }

    // Android's own restore (WidgetHostRestore) re-bound every widget under a
    // new id and had its app move the settings across, so a layout saved with
    // the dead ids only has to follow the map: those widgets then come up as
    // they were, with no setup to repeat. A new id is taken only while it is
    // really bound to the provider the item was saved with - a map from some
    // other restore must never park a stranger's widget in a cell.
    fun withRemappedIds(
        model: LauncherModel,
        oldToNew: Map<Int, Int>,
        widgetBound: (appWidgetId: Int, provider: String?) -> Boolean
    ): LauncherModel {
        if (oldToNew.isEmpty()) return model
        fun remap(id: Int?, provider: String?): Int? {
            val moved = id?.let(oldToNew::get) ?: return id
            return if (widgetBound(moved, provider)) moved else id
        }
        return model.copy(
            items = model.items.map { item ->
                if (item.itemType != ItemType.WIDGET) {
                    item
                } else {
                    item.copy(appWidgetId = remap(item.appWidgetId, item.widgetProvider))
                }
            },
            feedCards = model.feedCards.map { card ->
                card.copy(appWidgetId = remap(card.appWidgetId, card.widgetProvider))
            }
        )
    }

    fun withStaleCleared(
        model: LauncherModel,
        widgetBound: (appWidgetId: Int, provider: String?) -> Boolean
    ): LauncherModel = model.copy(
        items = model.items.map { item ->
            val id = item.appWidgetId
            if (item.itemType == ItemType.WIDGET && id != null && !widgetBound(id, item.widgetProvider)) {
                item.copy(appWidgetId = null)
            } else {
                item
            }
        },
        feedCards = model.feedCards.map { card ->
            val id = card.appWidgetId
            if (id != null && !widgetBound(id, card.widgetProvider)) card.copy(appWidgetId = null) else card
        }
    )
}
