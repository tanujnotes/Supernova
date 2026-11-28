package com.supernova.launcher.ui

import com.supernova.launcher.data.ItemType
import com.supernova.launcher.data.NestedFolders

// What a home or dock folder's icon is drawn from beyond the folder itself:
// its members, and for a folder inside it (#369) that folder's own icon and
// members, as the preview draws them. A page or the dock rebinds a folder
// whose key changed.
object FolderPreviewKey {

    fun of(state: LauncherUiState, folderId: String, depth: Int = 0): String =
        NestedFolders.members(state.model.items, folderId)
            .sortedBy { it.rank }
            .joinToString(",", prefix = "[", postfix = "]") {
                // A shortcut member's Edit changes its tile in the preview.
                "${it.id}:${it.packageName}/${it.className}/${it.shortcutEdit?.hashCode()}" +
                    "/${it.appEdit?.hashCode()}" +
                    PausedIcon.mark(state, it.userSerial) +
                    if (it.itemType == ItemType.FOLDER && depth == 0) {
                        FolderIconDrawable.customIconKey(it) + of(state, it.id, depth + 1)
                    } else {
                        ""
                    }
            }
}
