package com.supernova.launcher.ui.settings.groups

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.Drawable
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.drawable.toDrawable
import com.supernova.launcher.R
import com.supernova.launcher.data.DrawerGroup
import com.supernova.launcher.data.LaunchableApp
import com.supernova.launcher.ui.ItemIcons
import com.supernova.launcher.ui.LauncherViewModel
import com.supernova.launcher.ui.dp

// The pictures on the Drawer groups pages: an app's icon, a folder's icon as
// the drawer shows it, and a tab's first few apps overlapping.
class GroupIcons(
    private val context: Context,
    private val viewModel: LauncherViewModel,
    private val itemIcons: ItemIcons
) {
    fun app(app: LaunchableApp): Drawable? = viewModel.appCatalog().iconFor(app)

    fun folder(group: DrawerGroup): Drawable = itemIcons.drawerFolderIcon(group, viewModel.drawerFolderApps(group))

    // A tab's row picture: its first apps' icons, each over the one before
    // with a gap cut round it, so they read apart on any card. An empty
    // tab's is a blank tile. Drawn into a bitmap: the catalog's drawables
    // are shared with every view showing the app, and are left as they are.
    fun tab(members: List<LaunchableApp>): Drawable? {
        val icons = members.take(STACK_ICONS).mapNotNull(::app)
        if (icons.isEmpty()) return AppCompatResources.getDrawable(context, R.drawable.bg_settings_tile_outline)
        val box = context.dp(STACK_BOX_DP)
        val size = context.dp(STACK_ICON_DP)
        val step = if (icons.size > 1) (box - size) / (icons.size - 1) else 0
        val gap = context.dp(STACK_GAP_DP)
        val top = (box - size) / 2f
        val bitmap = createBitmap(box, box)
        val canvas = Canvas(bitmap)
        val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        icons.forEachIndexed { index, icon ->
            val left = (index * step).toFloat()
            if (index > 0) {
                val corner = size * CUT_CORNER + gap
                canvas.drawRoundRect(left - gap, top - gap, left + size + gap, top + size + gap, corner, corner, clear)
            }
            canvas.drawBitmap(icon.toBitmap(size, size), left, top, null)
        }
        return bitmap.toDrawable(context.resources)
    }

    private companion object {
        const val STACK_ICONS = 3
        const val STACK_BOX_DP = 40
        const val STACK_ICON_DP = 22
        const val STACK_GAP_DP = 2
        // The cut's corners, as a share of the icon: round enough for a
        // squircle, and close on a circle.
        const val CUT_CORNER = 0.3f
    }
}
