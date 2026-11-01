package com.supernova.launcher.data

// A backup's widget ids mean something only on the install that wrote it, and
// then only while they are still bound: any other is cleared, so it can't show
// someone else's widget. The provider stays, for WidgetController to bind a
// new id to.
internal object RestoredWidgets {

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
