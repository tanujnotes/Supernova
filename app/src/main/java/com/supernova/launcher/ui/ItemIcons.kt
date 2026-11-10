package com.supernova.launcher.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Process
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.toDrawable
import com.supernova.launcher.data.CustomShapeSpec
import com.supernova.launcher.data.DrawerGroup
import com.supernova.launcher.data.GalleryIconCodec
import com.supernova.launcher.data.ItemType
import com.supernova.launcher.data.LaunchableApp
import com.supernova.launcher.data.LauncherItem
import com.supernova.launcher.gesture.ActionShortcut
import com.supernova.launcher.notification.SupernovaNotificationListener
import com.supernova.launcher.ui.theme.Styles
import com.supernova.launcher.ui.theme.SurfaceId

// The icons the launcher draws beyond an app's own: a folder's preview (or
// the picture or pack icon picked for it), a shortcut's stored picture, and
// the notification badge over any of them. One place for the home screen,
// the dock, the drawer and the folders opened from them, so the appearance
// settings (shape, outline, badge style) apply the same everywhere. Reads
// the settings as they stand at each call.
class ItemIcons(private val context: Context, private val viewModel: LauncherViewModel) {

    // Dynamic badges' pictures, rendered once per notification.
    private val badgeFaces by lazy { BadgeFaces(context.applicationContext) }

    fun withBadge(
        icon: Drawable?,
        packageName: String?,
        className: String?,
        userSerial: Long
    ): Drawable? {
        val prefs = viewModel.state.value.prefs
        if (!prefs.badges || icon == null || packageName == null) return icon
        // Per-app badge filter: an empty set badges everything.
        if (prefs.badgeApps.isNotEmpty() &&
            "$userSerial/$packageName/${className.orEmpty()}" !in prefs.badgeApps
        ) {
            return icon
        }
        val user = viewModel.appCatalog().userForSerial(userSerial) ?: Process.myUserHandle()
        val count = SupernovaNotificationListener.countFor(packageName, user.hashCode())
        if (count <= 0) return icon
        // The icon's dominant color, computed and cached at render time: what
        // Dots are colored with and what a picture-less Dynamic badge falls
        // back to. Before the first render the badge color stands in.
        val iconColor = viewModel.appCatalog().dominantColorFor(userSerial, packageName, className)
        val face = if (BadgeStyle.effective(prefs) == BadgeStyle.DYNAMIC) {
            SupernovaNotificationListener.faceFor(packageName, user.hashCode())?.let {
                badgeFaces.bitmapFor(it, iconColor ?: BadgeDrawable.DEFAULT_BADGE_COLOR)
            }
        } else {
            null
        }
        return BadgeLook(prefs, context.resources.displayMetrics.density).wrap(icon, count, iconColor, face)
    }

    // Summed unread count over a folder's members, honoring the badge
    // allowlist; 0 unless folder badges are on.
    fun folderBadgeCount(folder: LauncherItem): Int {
        val prefs = viewModel.state.value.prefs
        if (!prefs.badges || !prefs.folderBadges) return 0
        return viewModel.folderMembers(folder.id).sumOf { member ->
            val pkg = member.packageName ?: return@sumOf 0
            if (prefs.badgeApps.isNotEmpty() &&
                "${member.userSerial}/$pkg/${member.className.orEmpty()}" !in prefs.badgeApps
            ) {
                return@sumOf 0
            }
            val user = viewModel.appCatalog().userForSerial(member.userSerial) ?: Process.myUserHandle()
            SupernovaNotificationListener.countFor(pkg, user.hashCode())
        }
    }

    // Wraps an icon in a badge with a known count. Folders have no single
    // app, so a Dynamic badge is a dot in the badge color there.
    fun badgeDrawable(icon: Drawable, count: Int): Drawable =
        BadgeLook(viewModel.state.value.prefs, context.resources.displayMetrics.density).wrap(icon, count)

    // A drawer folder's icon with its members' summed count, when folder
    // badges are on.
    fun withFolderBadge(
        icon: Drawable,
        members: List<LaunchableApp>
    ): Drawable {
        val prefs = viewModel.state.value.prefs
        if (!prefs.badges || !prefs.folderBadges) return icon
        val count = members.sumOf { app ->
            val key = "${app.userSerial}/${app.packageName}/${app.className}"
            if (prefs.badgeApps.isNotEmpty() && key !in prefs.badgeApps) {
                return@sumOf 0
            }
            val user = viewModel.appCatalog().userForSerial(app.userSerial) ?: Process.myUserHandle()
            SupernovaNotificationListener.countFor(app.packageName, user.hashCode())
        }
        return if (count > 0) badgeDrawable(icon, count) else icon
    }

    // A custom icon is fitted to the icon shape like any other, unless its
    // Edit has Reshape off: then it is drawn as it is.
    fun folderPreviewDrawable(folder: LauncherItem): Drawable {
        customFolderIcon(
            folder.iconBase64, folder.customIconPack, folder.customIconName, folder.iconRaw, isFolder = true
        )
            ?.let { return it }
        val memberIcons = viewModel.folderMembers(folder.id)
            // The dense styles (3x3, dense ring) show up to nine members.
            .take(PREVIEW_MEMBERS)
            .mapNotNull { member ->
                member.takeIf { it.itemType == ItemType.SHORTCUT }?.let(::intentItemIcon)
                    ?: member.packageName?.let { pkg ->
                        member.className?.let { cls ->
                            viewModel.appCatalog().iconFor(LaunchableApp(pkg, cls, member.label, member.userSerial))
                        }
                    }
            }
        return composedFolderIcon(memberIcons)
    }

    fun drawerFolderIcon(group: DrawerGroup, members: List<LaunchableApp>): Drawable =
        customFolderIcon(group.iconBase64, group.iconPack, group.iconName, group.iconRaw, isFolder = true)
            ?: composedFolderIcon(members.mapNotNull { app -> viewModel.appCatalog().iconFor(app) })

    fun customFolderIcon(
        photo: String?,
        pack: String?,
        name: String?,
        raw: Boolean,
        isFolder: Boolean = false
    ): Drawable? {
        val catalog = viewModel.appCatalog()
        val picture = photo?.takeIf { it.isNotEmpty() }
            ?.let { GalleryIconCodec.decode(it) }
        return when {
            picture != null -> (
                when {
                    raw -> null
                    isFolder -> catalog.renderFolderPicture(picture)
                    else -> catalog.renderPickedPicture(picture)
                }
                )
                ?: picture.toDrawable(context.resources)
            pack == null || name == null -> null
            raw -> catalog.packDrawable(pack, name)
            isFolder -> catalog.renderFolderPack(pack, name)
            else -> catalog.renderPackDrawable(pack, name)
        }
    }

    // The one FolderIconDrawable factory: workspace/dock previews and drawer
    // folder entries all run through it so the folder-icon appearance prefs
    // (shape, outline, clip, window color) apply everywhere.
    fun composedFolderIcon(icons: List<Drawable>): Drawable {
        val prefs = viewModel.state.value.prefs
        val resolvedFolder = Styles.resolve(context, prefs)
            .of(SurfaceId.FOLDER)
        return FolderIconDrawable(
            // The preview draws members at mini-cell bounds. The cache hands
            // out shared instances, and a Drawable has exactly one bounds -
            // mutating the shared one shrinks the same icon in the drawer.
            icons.take(PREVIEW_MEMBERS).map { icon ->
                icon.constantState?.newDrawable(context.resources)?.mutate() ?: icon
            },
            context.dp(ICON_DP),
            style = FolderPreviewStyle.fromName(prefs.folderPreviewStyle),
            plateColor = FolderIconDrawable.plateColor(
                prefs.folderMatchWindowColor, prefs.folderPlateColor, resolvedFolder.surface
            ),
            shape = FolderIconDrawable.plateShape(prefs.folderIconShape, prefs),
            customSpec = CustomShapeSpec.decode(prefs.iconShapeCustom),
            outlineColor = if (prefs.folderIconOutline) resolvedFolder.accent else 0,
            outlineWidthPx = 2f * context.resources.displayMetrics.density,
            clipMembers = prefs.folderClipToBg,
            prioritizeFirst = prefs.folderPrioritizeFirst,
            glass = FolderIconDrawable.glassFor(prefs),
            shadow = prefs.iconShadow
        )
    }

    // Icon for a SHORTCUT item carrying an intentUri: a launcher-action tile by
    // action, or the snapshot icon activities/legacy shortcuts stored. Null for
    // a plain app shortcut, which falls back to the app's own icon.
    fun intentItemIcon(item: LauncherItem): Drawable? {
        val catalog = viewModel.appCatalog()
        item.intentUri?.let { uri ->
            ActionShortcut.decode(uri)?.let { binding ->
                customFolderIcon(item.iconBase64, item.customIconPack, item.customIconName, item.iconRaw)
                    ?.let { return it }
                AppCompatResources.getDrawable(context, ActionIcons.of(binding.action))?.let {
                    return catalog.renderActionTile(it)
                }
            }
        }
        val stored = item.iconBase64?.takeIf { it.isNotEmpty() }
            ?.let { GalleryIconCodec.decode(it) } ?: return null
        // An app shortcut's picture (a Fossify contact's photo) is drawn
        // across the shape the same way every time; through glass it came
        // out whole, small or half cleared, depending on the photo.
        val picture = if (item.shortcutId != null) catalog.renderShortcutPicture(stored) else null
        return picture ?: catalog.renderThemedDrawable(stored.toDrawable(context.resources))
    }

    private companion object {
        // A folder preview's box, the base icon size.
        const val ICON_DP = 48

        // The dense preview styles (3x3, dense ring) show up to nine members.
        const val PREVIEW_MEMBERS = 9
    }
}
