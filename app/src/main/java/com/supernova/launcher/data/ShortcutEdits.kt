package com.supernova.launcher.data

// A shortcut's Edit applied to one item: the typed name (blank keeps the
// old one) and what its Edit sheet changed - hidden label, custom icon,
// shape and style.
// The shortcut's own picture (iconBase64) is never touched, so resetting the
// icon to Default brings it back. Anything but that shortcut passes through.
object ShortcutEdits {

    fun apply(item: LauncherItem, itemId: String, label: String, edit: AppOverride): LauncherItem {
        if (item.id != itemId || item.itemType != ItemType.SHORTCUT) return item
        val name = label.trim().ifEmpty { item.label }
        // The name lives in the item's label; the edit never holds one.
        val stored = edit.copy(customLabel = null).takeUnless { it.isEmpty() }
        return item.copy(label = name, shortcutEdit = stored)
    }
}
