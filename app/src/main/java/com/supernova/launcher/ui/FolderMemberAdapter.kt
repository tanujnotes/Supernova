package com.supernova.launcher.ui

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import com.supernova.launcher.data.AppCatalog
import com.supernova.launcher.data.LaunchableApp
import com.supernova.launcher.data.LauncherItem
import com.supernova.launcher.data.LauncherPrefs
import com.supernova.launcher.databinding.ItemIconBinding

class FolderMemberAdapter(
    private val appCatalog: AppCatalog,
    private val showLabels: Boolean,
    private val labelPrefs: LauncherPrefs? = null,
    // folderIconScale (or iconScale when folderMatchDesktop), resolved by the
    // caller like the drawer's scale. FolderPopup widens its cells with it.
    val iconScale: Float = 1f,
    private val onClick: (LauncherItem) -> Unit,
    private val onLongPress: (View, LauncherItem) -> Unit
) : RecyclerView.Adapter<FolderMemberAdapter.MemberViewHolder>() {

    private val items = mutableListOf<LauncherItem>()

    // The host's notification badge, as on the home screen and in the
    // drawer; set before the first bind.
    var decorateIcon: (Drawable?, LauncherItem) -> Drawable? = { icon, _ -> icon }

    // Tile width in px, set by FolderPopup from the grid width and column
    // count. A fixed width (not match_parent) keeps horizontal paging at
    // `columns` tiles per page; 0 falls back to match_parent.
    var cellWidthPx: Int = 0

    // Rebinds the members in place (a badge moved), keeping the list.
    fun refreshIcons() = notifyItemRangeChanged(0, itemCount)

    fun submit(list: List<LauncherItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MemberViewHolder {
        val binding = ItemIconBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        // Fill the grid cell so the tile centres in it; the shared item_icon
        // layout wraps its content for the workspace and dock.
        binding.root.layoutParams = binding.root.layoutParams.apply {
            width = if (cellWidthPx > 0) cellWidthPx else ViewGroup.LayoutParams.MATCH_PARENT
        }
        return MemberViewHolder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: MemberViewHolder, position: Int) {
        holder.bind(items[position])
    }

    inner class MemberViewHolder(private val binding: ItemIconBinding) : RecyclerView.ViewHolder(binding.root) {
        // The box item_icon declares, before a bind has sized it.
        private val baseIconPx = binding.icon.layoutParams.width

        fun bind(item: LauncherItem) {
            binding.label.text = item.label
            labelPrefs?.let { LabelStyler.apply(binding.label, it, onWallpaper = false, context = LabelContext.FOLDER) }
            binding.label.visibility = if (showLabels) View.VISIBLE else View.GONE
            binding.root.contentDescription = item.label
            binding.icon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            // Sized, not scaled, as on the desktop: scaleX/scaleY drew a big
            // icon out past its box, where the tile's padding cut it square
            // and the label, drawn after it, covered its bottom. The tile now
            // measures what it draws, so the label sits below the icon. The
            // box stays inside the tile, so more columns than the icons fit
            // shrink them instead of clipping them.
            var box = (baseIconPx * iconScale).toInt()
            val room = cellWidthPx - binding.root.paddingLeft - binding.root.paddingRight
            if (cellWidthPx > 0 && room > 0) box = box.coerceAtMost(room)
            binding.icon.updateLayoutParams { width = box; height = box }
            val app = item.packageName?.let { pkg ->
                item.className?.let { cls -> LaunchableApp(pkg, cls, item.label, item.userSerial) }
            }
            binding.icon.setImageDrawable(
                app?.let { decorateIcon(appCatalog.iconFor(it), item) }
                    ?: binding.root.context.getDrawable(com.supernova.launcher.R.drawable.ic_folder)
            )
            binding.root.setOnClickListener { onClick(item) }
            binding.root.setOnLongClickListener {
                onLongPress(it, item)
                true
            }
        }
    }
}
