package com.supernova.launcher.ui

import android.widget.Toast
import com.supernova.launcher.R

// A tapped add (Add to Home, Move to Home, a tapped widget or shortcut row):
// the page given, else the next page with room, else a new page, which is
// said when it isn't the page given. A drop keeps its own cell and isn't
// placed through here. False, with a message, when there is no room anywhere.
object TappedPlacement {

    fun place(
        viewModel: LauncherViewModel,
        page: Int,
        spanX: Int = 1,
        spanY: Int = 1,
        add: (LauncherViewModel.FreeSpot) -> Unit
    ): Boolean {
        val context = viewModel.getApplication<android.app.Application>()
        val spot = viewModel.freeSpotFrom(page, spanX, spanY)
        if (spot == null) {
            Toast.makeText(context, R.string.no_room_anywhere, Toast.LENGTH_SHORT).show()
            return false
        }
        add(spot)
        if (spot.page != page) {
            Toast.makeText(context, context.getString(R.string.added_to_page, spot.page + 1), Toast.LENGTH_SHORT)
                .show()
        }
        return true
    }
}
