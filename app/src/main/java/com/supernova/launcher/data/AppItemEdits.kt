package com.supernova.launcher.data

// A placed app's own edit (LauncherItem.appEdit) against its drawer entry's
// (LauncherModel.appOverrides). An edit on the home screen, the dock or in a
// folder changes that one copy; one in the drawer changes the drawer entry,
// and every copy with no icon of its own draws the drawer's icon. A copy's
// name and hidden label are always its own: it takes the drawer's when it is
// placed, and a later rename in the drawer leaves it as it is.
object AppItemEdits {

    // The icon fields: the icon picked, and the shape and style picked for it.
    fun AppOverride.hasIcon(): Boolean =
        iconPackPackage != null || iconDrawableName != null || iconBase64 != null ||
            iconShape != null || iconVariant != null

    fun AppOverride.withoutIcon(): AppOverride = copy(
        iconPackPackage = null, iconDrawableName = null, iconBase64 = null,
        iconShape = null, iconVariant = null
    )

    private fun AppOverride.iconOf(source: AppOverride?): AppOverride = copy(
        iconPackPackage = source?.iconPackPackage, iconDrawableName = source?.iconDrawableName,
        iconBase64 = source?.iconBase64, iconShape = source?.iconShape, iconVariant = source?.iconVariant
    )

    fun appKey(item: LauncherItem): String? {
        val pkg = item.packageName
        val cls = item.className
        return if (pkg == null || cls == null) null else "${item.userSerial}/$pkg/$cls"
    }

    // What an app item shows: its own name and hidden label, and its own
    // icon, or the drawer's when it has none. Null for anything but an app,
    // or an app with nothing changed either way.
    fun effective(item: LauncherItem, appOverrides: Map<String, AppOverride>): AppOverride? {
        if (item.itemType != ItemType.APP) return null
        val own = item.appEdit ?: AppOverride()
        val shown = if (own.hasIcon()) own else own.iconOf(appKey(item)?.let(appOverrides::get))
        return shown.takeUnless { it.isEmpty() }
    }

    // The edit an app item's Edit saves: its own name and hidden label, and
    // the icon only when it differs from the drawer's, so a copy left as it
    // was keeps following the drawer. Null when nothing is its own.
    fun stored(edit: AppOverride, drawer: AppOverride?): AppOverride? {
        val kept = if (AppOverride().iconOf(edit) == AppOverride().iconOf(drawer)) edit.withoutIcon() else edit
        return kept.takeUnless { it.isEmpty() }
    }

    // A newly placed app takes the drawer's hidden label, as it takes the
    // drawer's name in its label: from then on both are its own. Items
    // already in `before` (moved, resized, re-ranked) keep what they have.
    fun stampNew(before: LauncherModel, after: LauncherModel): LauncherModel {
        val known = before.items.mapTo(HashSet()) { it.id }
        if (after.items.none { it.id !in known && it.itemType == ItemType.APP }) return after
        return after.copy(
            items = after.items.map { item ->
                if (item.id in known || item.itemType != ItemType.APP || item.appEdit != null) return@map item
                val drawer = appKey(item)?.let(after.appOverrides::get)
                if (drawer?.hideLabel == true) item.copy(appEdit = AppOverride(hideLabel = true)) else item
            }
        )
    }

    // v6: names and hidden labels became each copy's own. Every placed copy
    // of an app renamed or label-hidden in its one shared edit gets that name
    // and hidden label as its own, so nothing looks different; icons need
    // nothing, a copy with none of its own still draws the drawer's. An app
    // in a folder showed its own label all along, never the shared name or
    // hidden label, so it is left as it is.
    fun migrateLabels(model: LauncherModel): LauncherModel = model.copy(
        items = model.items.map { item ->
            if (item.itemType != ItemType.APP || item.container == ContainerType.FOLDER) return@map item
            val drawer = appKey(item)?.let(model.appOverrides::get) ?: return@map item
            if (drawer.customLabel == null && !drawer.hideLabel) return@map item
            val own = item.appEdit ?: AppOverride()
            item.copy(
                appEdit = own.copy(
                    customLabel = own.customLabel ?: drawer.customLabel,
                    hideLabel = own.hideLabel || drawer.hideLabel
                )
            )
        }
    )
}
