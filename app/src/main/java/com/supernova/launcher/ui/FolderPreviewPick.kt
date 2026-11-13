package com.supernova.launcher.ui

// Which members a folder's preview draws, in the order it draws them. The
// tiled styles (2 x 2, 3 x 3) are a miniature of the open folder: its
// top-left corner, read row by row as the window is, so an app sits in the
// preview where it sits in the window. Every other style, and a first app
// drawn large, takes the window's first apps in its reading order.
object FolderPreviewPick {

    fun <T> shown(
        members: List<T>,
        windowColumns: Int,
        style: FolderPreviewStyle,
        prioritizeFirst: Boolean,
        limit: Int
    ): List<T> {
        val side = when {
            prioritizeFirst -> 0
            style == FolderPreviewStyle.GRID -> GRID_SIDE
            style == FolderPreviewStyle.GRID_3X3 -> GRID_3X3_SIDE
            else -> 0
        }
        // A window no wider than the tile, or a folder the tile holds whole,
        // reads straight into it.
        if (side == 0 || windowColumns <= side || members.size <= side * side) return members.take(limit)
        return members.filterIndexed { index, _ ->
            index % windowColumns < side && index / windowColumns < side
        }
    }

    private const val GRID_SIDE = 2
    private const val GRID_3X3_SIDE = 3
}
