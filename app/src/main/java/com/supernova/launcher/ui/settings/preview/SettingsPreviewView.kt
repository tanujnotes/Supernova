package com.supernova.launcher.ui.settings.preview

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.supernova.launcher.R
import com.supernova.launcher.data.LaunchableApp
import com.supernova.launcher.data.LauncherPrefs
import com.supernova.launcher.ui.BadgeLook
import com.supernova.launcher.ui.DockBgStyle
import com.supernova.launcher.ui.FolderIconDrawable
import com.supernova.launcher.ui.FolderPreviewStyle
import com.supernova.launcher.ui.LabelContext
import com.supernova.launcher.ui.LabelStyler
import com.supernova.launcher.ui.LauncherViewModel
import com.supernova.launcher.ui.SearchBarConfig
import com.supernova.launcher.ui.SearchBarView
import com.supernova.launcher.ui.dp
import com.supernova.launcher.ui.settings.PreviewKind
import com.supernova.launcher.ui.theme.StyleConfig
import com.supernova.launcher.ui.theme.Styles
import com.supernova.launcher.ui.theme.SurfaceId

// Renders one PreviewKind from a LauncherPrefs snapshot. bind() rebuilds the
// tree and resolves surface colors (committed prefs only); tick() runs on
// every slider drag frame and may only mutate cheap view properties - no
// inflation, no Styles.resolve, no drawable construction.
class SettingsPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private var kind: PreviewKind? = null

    private val iconViews = mutableListOf<PreviewIcon>()
    // The drawer's and the dock's icon size bars tick this view as they move
    // (see onMeasure).
    private var keepsLargestIconRoom = false
    private val labelViews = mutableListOf<TextView>()
    private val slotViews = mutableListOf<View>()
    private var surfaceBg: android.graphics.drawable.Drawable? = null
    // Folder preview: the open window's card, its background and its title, so
    // a tick can move radius, shadow and the name without rebuilding.
    private var folderCard: View? = null
    private var folderCardBg: android.graphics.drawable.Drawable? = null
    private var folderTitleView: TextView? = null
    // Search preview: the result rows keyed by the toggle that owns them, so a
    // tick can show and hide them without rebuilding the window.
    private val searchRows = mutableMapOf<String, View>()
    private var searchAppsSecondRow: View? = null
    // Desktop search bar preview: the bar itself, and the surface it wears,
    // resolved once at bind so a slider frame only re-applies the config.
    private var searchBar: SearchBarView? = null
    private var searchBarSurface: com.supernova.launcher.ui.theme.ResolvedSurface? = null
    // Drawer field preview: the field's pill and start icon, on the drawer's
    // own surface (searchBarSurface holds the DRAWER colours for it).
    private var drawerField: View? = null
    private var drawerFieldPill: GradientDrawable? = null
    private var drawerFieldIcon: ImageView? = null

    // Base RGB plus any explicit style-config alpha, captured at bind so a
    // tick can recombine the surface color without re-resolving.
    private var surfaceRgb = 0
    private var styleAlpha: Float? = null

    fun bind(kind: PreviewKind, prefs: LauncherPrefs, viewModel: LauncherViewModel) {
        this.kind = kind
        removeAllViews()
        iconViews.clear()
        labelViews.clear()
        slotViews.clear()
        surfaceBg = null
        folderCard = null
        folderCardBg = null
        folderTitleView = null
        searchRows.clear()
        searchAppsSecondRow = null
        searchBar = null
        searchBarSurface = null
        drawerField = null
        drawerFieldPill = null
        drawerFieldIcon = null
        keepsLargestIconRoom = kind == PreviewKind.DRAWER || kind == PreviewKind.DOCK

        when (kind) {
            PreviewKind.ICON_ROW -> buildIconRow(prefs, viewModel)
            PreviewKind.HOME -> buildHome(prefs, viewModel)
            PreviewKind.DOCK -> buildDock(prefs, viewModel)
            PreviewKind.FOLDER -> buildFolder(prefs, viewModel)
            PreviewKind.DRAWER -> buildDrawer(prefs, viewModel)
            PreviewKind.SEARCH -> buildSearch(prefs, viewModel)
            PreviewKind.SEARCH_BAR -> buildSearchBar(prefs)
            PreviewKind.DRAWER_SEARCH_BAR -> buildDrawerField(prefs)
        }
        tick(prefs)
    }

    fun tick(prefs: LauncherPrefs) {
        when (kind) {
            PreviewKind.ICON_ROW -> {
                sizeIcons(prefs.iconScale)
                styleLabels(prefs, visible = prefs.showLabels)
            }
            PreviewKind.HOME -> {
                sizeIcons(prefs.iconScale)
                styleLabels(prefs, visible = prefs.showLabels, onWallpaper = true)
            }
            PreviewKind.DOCK -> {
                com.supernova.launcher.ui.theme.SurfaceShapes.setColor(surfaceBg, withAlpha(styleAlpha ?: prefs.dockBgOpacity))
                slotViews.forEachIndexed { index, slot ->
                    slot.visibility = if (index < prefs.dockCount) VISIBLE else GONE
                }
                sizeIcons(if (prefs.dockMatchDesktop) prefs.iconScale else prefs.dockIconScale)
                // The dock's own switch alone, as on the real dock.
                styleLabels(prefs, visible = prefs.dockLabels, context = LabelContext.DOCK)
            }
            PreviewKind.FOLDER -> {
                sizeIcons(if (prefs.folderMatchDesktop) prefs.iconScale else prefs.folderIconScale)
                styleLabels(prefs, visible = prefs.folderLabels, context = LabelContext.FOLDER)
                folderTitleView?.visibility = if (prefs.folderShowName) VISIBLE else GONE
                folderCard?.elevation = if (prefs.folderShadow) dp(6).toFloat() else 0f
                val columns = prefs.folderColumns.takeIf { it > 0 } ?: FOLDER_PREVIEW_COLS
                slotViews.forEachIndexed { index, slot ->
                    slot.visibility = if (index % FOLDER_PREVIEW_COLS < columns) VISIBLE else GONE
                }
                // Radius rides the style config, so the slider lands here too.
                val radius = StyleConfig.decode(prefs.styleConfig)
                    .styleFor(SurfaceId.FOLDER).cornerRadii?.firstOrNull()
                    ?: DEFAULT_FOLDER_RADIUS_DP
                (folderCardBg as? GradientDrawable)?.cornerRadius =
                    radius * resources.displayMetrics.density
            }
            PreviewKind.DRAWER -> {
                com.supernova.launcher.ui.theme.SurfaceShapes.setColor(surfaceBg, withAlpha(styleAlpha ?: prefs.drawerBgOpacity))
                sizeIcons(if (prefs.drawerMatchDesktop) prefs.iconScale else prefs.drawerIconScale)
                styleLabels(prefs, visible = prefs.drawerLabels, context = LabelContext.DRAWER)
            }
            PreviewKind.SEARCH -> {
                searchAppsSecondRow?.visibility = if (prefs.searchAppsOneRow) GONE else VISIBLE
                searchRows["calc"]?.visibility = if (prefs.searchCalculator) VISIBLE else GONE
                searchRows["contact"]?.visibility =
                    if (prefs.searchContacts && !prefs.searchContactCards) VISIBLE else GONE
                searchRows["contactCard"]?.visibility =
                    if (prefs.searchContacts && prefs.searchContactCards) VISIBLE else GONE
                searchRows["shortcut"]?.visibility = if (prefs.searchShortcuts) VISIBLE else GONE
                searchRows["folder"]?.visibility = if (prefs.searchFolders) VISIBLE else GONE
                styleLabels(prefs, visible = true)
            }
            PreviewKind.SEARCH_BAR -> searchBarSurface?.let { surface ->
                searchBar?.apply(SearchBarConfig.decode(prefs.searchBarConfig).desktop, surface)
            }
            PreviewKind.DRAWER_SEARCH_BAR -> styleDrawerField(prefs)
            null -> Unit
        }
    }

    // Every preview sizes its icon boxes, as the surfaces it shows do, so a
    // label sits below its icon at any size instead of over a scaled one.
    // That relays the preview out, which is still cheap enough for a tick: a
    // few views.
    private fun sizeIcons(scale: Float) {
        val box = (dp(ICON_BASE_DP) * scale).toInt()
        for (icon in iconViews) icon.box = box
    }

    // The drawer's and the dock's icon size bars tick this view as they move,
    // and a sized box relays the strip out. So those previews keep the
    // height the largest size would take and centre their strip in it, as
    // the Home strip keeps a fixed height: dragging a bar never moves the
    // list under the finger.
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (!keepsLargestIconRoom) return
        // The tallest icon sets the strip's height. Slots split the width
        // unevenly by a pixel, so boxes held inside them can differ by one.
        val side = iconViews.filter { it.isShown }.maxOfOrNull { it.measuredHeight } ?: return
        val growth = ((dp(ICON_BASE_DP) * MAX_ICON_SCALE).toInt() - side).coerceAtLeast(0)
        if (growth > 0) {
            setMeasuredDimension(measuredWidth, resolveSize(measuredHeight + growth, heightMeasureSpec))
        }
    }

    // Previews sit on the settings surface, so by default a label's color
    // follows the theme to stay legible; the Home screen's sits on the
    // wallpaper and takes the home screen's own light default. The drawer,
    // folder and dock previews draw their surface, so their labels take its
    // text color, as the real ones do (LabelStyler).
    private fun styleLabels(
        prefs: LauncherPrefs,
        visible: Boolean,
        onWallpaper: Boolean = false,
        context: LabelContext = LabelContext.DESKTOP
    ) {
        for (label in labelViews) {
            label.visibility = if (visible) VISIBLE else GONE
            LabelStyler.apply(label, prefs, onWallpaper, context)
        }
    }

    private fun withAlpha(alpha: Float): Int =
        ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24) or surfaceRgb

    private fun buildIconRow(prefs: LauncherPrefs, viewModel: LauncherViewModel) {
        val row = newRow()
        sampleApps(viewModel, 5).forEachIndexed { index, (app, icon) ->
            // One badged icon shows the configured badge look.
            val shown = if (index == 1 && prefs.badges) {
                val style = com.supernova.launcher.ui.BadgeStyle.effective(prefs)
                val face = if (style == com.supernova.launcher.ui.BadgeStyle.DYNAMIC) {
                    BadgeLook.samplePicture(app.label.take(1), SAMPLE_FACE_PX)
                } else {
                    null
                }
                val iconColor = viewModel.appCatalog().dominantColorFor(app.userSerial, app.packageName, app.className)
                BadgeLook(prefs, resources.displayMetrics.density).wrap(icon, SAMPLE_COUNT, iconColor, face)
            } else {
                icon
            }
            row.addView(iconColumn(shown, app.label), slotParams())
        }
        addView(row, stripParams())
    }

    // The Home screen preview: a row of the desktop's own icons standing
    // on the real wallpaper, which the pane shows through (PreviewPane). One
    // icon per desktop column, each built like item_icon.xml - 4dp padding,
    // the 48dp box, a label of up to two lines - so size, wrapping and
    // truncation read the way they will on the home screen. The strip keeps
    // one height whatever the settings, so dragging a bar never moves the
    // list under the finger.
    // The bar as the home screen shows it, over the wallpaper, at its real
    // height and side inset; its buttons stay inert here.
    private fun buildSearchBar(prefs: LauncherPrefs) {
        searchBarSurface = Styles.resolve(context, prefs).of(SurfaceId.SEARCH_BAR)
        val bar = SearchBarView(context).apply {
            interactive = false
            isClickable = false
            isLongClickable = false
        }
        searchBar = bar
        addView(
            bar,
            LayoutParams(LayoutParams.MATCH_PARENT, dp(SEARCH_BAR_HEIGHT_DP)).apply {
                val inset = dp(SEARCH_BAR_INSET_DP)
                val pad = dp(SEARCH_BAR_PAD_DP)
                setMargins(inset, pad, inset, pad)
            }
        )
    }

    // The drawer's search field as the drawer draws it (DrawerController's
    // field styling), on a strip of the drawer's surface.
    private fun buildDrawerField(prefs: LauncherPrefs) {
        val surface = Styles.resolve(context, prefs).of(SurfaceId.DRAWER)
        searchBarSurface = surface
        setBackgroundColor(surface.surface)
        val pill = GradientDrawable()
        val field = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(SEARCH_BAR_INSET_DP), 0, dp(SEARCH_BAR_INSET_DP), 0)
            background = pill
        }
        val icon = ImageView(context).apply { setImageResource(R.drawable.ic_search) }
        field.addView(icon, LinearLayout.LayoutParams(dp(DRAWER_ICON_DP), dp(DRAWER_ICON_DP)).apply {
            marginEnd = dp(DRAWER_ICON_GAP_DP)
        })
        field.addView(TextView(context).apply {
            setText(R.string.search_apps)
            textSize = DRAWER_HINT_SP
            setTextColor((DRAWER_HINT_ALPHA shl 24) or (surface.text and 0xFFFFFF))
        }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        // The drawer's action buttons, inside the field as the drawer has them.
        val buttons = com.supernova.launcher.ui.DrawerActionButton.decode(prefs.drawerActionButtons)
        buttons.forEach { button ->
            field.addView(ImageView(context).apply {
                setImageResource(
                    when (button) {
                        com.supernova.launcher.ui.DrawerActionButton.PLAY_STORE -> R.drawable.ic_play_store
                        com.supernova.launcher.ui.DrawerActionButton.SETTINGS -> R.drawable.ic_tab_settings
                    }
                )
                imageTintList = android.content.res.ColorStateList.valueOf(surface.text)
                val pad = dp(DRAWER_BUTTON_PAD_DP)
                setPadding(pad, pad, pad, pad)
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(DRAWER_BUTTON_DP), dp(DRAWER_BUTTON_DP)))
        }
        if (buttons.isNotEmpty()) field.setPaddingRelative(dp(SEARCH_BAR_INSET_DP), 0, dp(DRAWER_BUTTON_END_DP), 0)
        drawerField = field
        drawerFieldPill = pill
        drawerFieldIcon = icon
        addView(
            field,
            LayoutParams(LayoutParams.MATCH_PARENT, dp(SEARCH_BAR_HEIGHT_DP)).apply {
                val inset = dp(SEARCH_BAR_INSET_DP)
                val pad = dp(SEARCH_BAR_PAD_DP)
                setMargins(inset, pad, inset, pad)
            }
        )
    }

    private fun styleDrawerField(prefs: LauncherPrefs) {
        val surface = searchBarSurface ?: return
        val config = SearchBarConfig.decode(prefs.searchBarConfig).drawer
        // Hidden leaves the strip empty, as the drawer has no field then.
        drawerField?.visibility =
            if (com.supernova.launcher.ui.DrawerBarPlacement.fromConfig(config) ==
                com.supernova.launcher.ui.DrawerBarPlacement.HIDDEN
            ) INVISIBLE else VISIBLE
        val density = resources.displayMetrics.density
        val max = com.supernova.launcher.ui.DrawerSearchBarConfig.MAX_RADIUS
        val glass = com.supernova.launcher.data.IconGlass.fromName(config.glass)
        drawerField?.background = if (glass == com.supernova.launcher.data.IconGlass.OFF) {
            drawerFieldPill
        } else {
            com.supernova.launcher.ui.GlassPillDrawable(glass, (config.radius ?: max).coerceIn(0, max) * density)
        }
        drawerFieldPill?.apply {
            cornerRadius = (config.radius ?: max).coerceIn(0, max) * density
            if (config.filled) {
                setColor((DRAWER_FILL_ALPHA shl 24) or (surface.text and 0xFFFFFF))
                setStroke(0, android.graphics.Color.TRANSPARENT)
            } else {
                setColor(android.graphics.Color.TRANSPARENT)
                setStroke(dp(2), (DRAWER_STROKE_ALPHA shl 24) or (surface.text and 0xFFFFFF))
            }
        }
        drawerFieldIcon?.imageTintList = android.content.res.ColorStateList.valueOf(surface.text)
    }

    private fun buildHome(prefs: LauncherPrefs, viewModel: LauncherViewModel) {
        val row = newRow()
        homeSampleApps(viewModel, prefs.gridCols.coerceIn(HOME_MIN_COLS, HOME_MAX_COLS)).forEach { (app, icon) ->
            val column = iconColumn(icon, app.label, labelLines = 2)
            val pad = dp(4)
            column.setPadding(pad, pad, pad, pad)
            // A big icon's box can be wider than its slot inside that
            // padding, as it can be wider than its cell on the desktop;
            // show all of it.
            column.clipToPadding = false
            row.addView(column, slotParams())
        }
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, dp(HOME_HEIGHT_DP)))
    }

    // Resolved once per view: how to recognise the app the phone uses for
    // each everyday role below.
    private var homeRoles: List<(LaunchableApp) -> Boolean>? = null

    // The strip shows the same everyday apps on every phone - its own
    // entry, then Calculator, Clock, Messages and Settings - so it reads as a
    // home screen rather than the top of the alphabet. Any the phone lacks
    // or hides are filled in from the drawer's order.
    private fun homeSampleApps(viewModel: LauncherViewModel, count: Int): List<Pair<LaunchableApp, Drawable>> {
        val roles = homeRoles ?: resolveHomeRoles().also { homeRoles = it }
        val apps = viewModel.visibleApps()
        val picked = LinkedHashSet<LaunchableApp>()
        for (role in roles) apps.firstOrNull(role)?.let(picked::add)
        for (app in apps) {
            if (picked.size >= count) break
            picked.add(app)
        }
        val catalog = viewModel.appCatalog()
        return picked.asSequence()
            .mapNotNull { app -> catalog.iconFor(app)?.copied()?.let { app to it } }
            .take(count)
            .toList()
    }

    private fun resolveHomeRoles(): List<(LaunchableApp) -> Boolean> {
        val pm = context.packageManager
        // The default handler where there is one; with several and no
        // default the system answers with its chooser, so take the first.
        fun handler(intent: android.content.Intent): String? = runCatching {
            pm.resolveActivity(intent, 0)?.activityInfo?.packageName?.takeIf { it != "android" }
                ?: pm.queryIntentActivities(intent, 0).firstOrNull()?.activityInfo?.packageName
        }.getOrNull()
        // A phone's own app can leave its role undeclared (Samsung's
        // calculator doesn't claim the calculator category), so with no
        // handler the role falls back to an app whose package names it.
        fun role(intent: android.content.Intent, packageHint: String? = null): (LaunchableApp) -> Boolean {
            val handler = handler(intent)
            return { app ->
                if (handler != null) app.packageName == handler
                else packageHint != null && app.packageName.contains(packageHint)
            }
        }
        val main = android.content.Intent.ACTION_MAIN
        val own = context.packageName
        return listOf(
            { app -> app.packageName == own },
            role(android.content.Intent.makeMainSelectorActivity(main, android.content.Intent.CATEGORY_APP_CALCULATOR), "calculator"),
            role(android.content.Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS), "clock"),
            role(android.content.Intent.makeMainSelectorActivity(main, android.content.Intent.CATEGORY_APP_MESSAGING)),
            role(android.content.Intent(android.provider.Settings.ACTION_SETTINGS))
        )
    }

    private fun buildDock(prefs: LauncherPrefs, viewModel: LauncherViewModel) {
        val resolved = Styles.resolve(context, prefs).of(SurfaceId.DOCK)
        surfaceRgb = resolved.surface and 0xFFFFFF
        styleAlpha = StyleConfig.decode(prefs.styleConfig).styleFor(SurfaceId.DOCK).alpha

        val strip = newRow()
        strip.setPadding(dp(10), dp(8), dp(10), dp(8))
        when (DockBgStyle.fromName(prefs.dockBgStyle)) {
            DockBgStyle.NONE -> Unit
            DockBgStyle.PILL -> strip.background = surfaceDrawable(resolved, dp(30).toFloat())
            DockBgStyle.ROUNDED -> strip.background = surfaceDrawable(resolved, dp(14).toFloat())
            DockBgStyle.SQUARE -> strip.background = surfaceDrawable(resolved, 0f)
        }
        // Every slot the dock can have, Big grid's included; binding shows
        // dockCount of them.
        val slots = com.supernova.launcher.data.GridLimits.DOCK_BIG
        val apps = sampleApps(viewModel, slots)
        repeat(slots) { index ->
            val (app, icon) = apps.getOrNull(index % apps.size.coerceAtLeast(1)) ?: return@repeat
            // A dock item's own side padding, which its box stays inside, so
            // a big icon stops growing where the dock's does.
            val column = iconColumn(icon.copied(), app.label, keepInsideSlot = true)
            column.setPadding(dp(DOCK_ITEM_PADDING_H_DP), 0, dp(DOCK_ITEM_PADDING_H_DP), 0)
            slotViews += column
            strip.addView(column, slotParams())
        }
        addView(strip, stripParams())
    }

    // Demo: the closed icon next to the window it opens, so every
    // setting in the section - icon appearance, window chrome, member grid -
    // shows up here.
    private fun buildFolder(prefs: LauncherPrefs, viewModel: LauncherViewModel) {
        val resolved = Styles.resolve(context, prefs).of(SurfaceId.FOLDER)
        surfaceRgb = resolved.surface and 0xFFFFFF
        styleAlpha = StyleConfig.decode(prefs.styleConfig).styleFor(SurfaceId.FOLDER).alpha

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        row.addView(
            folderIconView(prefs, viewModel),
            LinearLayout.LayoutParams(dp(64), dp(64)).apply {
                marginStart = dp(10)
                marginEnd = dp(14)
            }
        )
        row.addView(
            folderWindowView(prefs, viewModel, resolved),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(10)
            }
        )
        addView(
            row,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
                topMargin = dp(10)
                bottomMargin = dp(10)
            }
        )
    }

    private fun folderIconView(prefs: LauncherPrefs, viewModel: LauncherViewModel): View {
        val resolved = Styles.resolve(context, prefs).of(SurfaceId.FOLDER)
        val icons = sampleApps(viewModel, 9).map { it.second }
        val image = ImageView(context)
        image.setImageDrawable(
            FolderIconDrawable(
                icons, dp(64),
                style = FolderPreviewStyle.fromName(prefs.folderPreviewStyle),
                plateColor = if (prefs.folderMatchWindowColor) {
                    resolved.surface
                } else {
                    FolderIconDrawable.DEFAULT_PLATE_COLOR
                },
                shape = com.supernova.launcher.data.IconShape.fromName(prefs.folderIconShape)
                    .takeIf { prefs.folderIconShape.isNotEmpty() },
                customSpec = com.supernova.launcher.data.CustomShapeSpec.decode(prefs.iconShapeCustom),
                outlineColor = if (prefs.folderIconOutline) resolved.accent else 0,
                outlineWidthPx = 2f * resources.displayMetrics.density,
                clipMembers = prefs.folderClipToBg,
                prioritizeFirst = prefs.folderPrioritizeFirst
            )
        )
        return image
    }

    // A miniature of the open folder: same surface, corner radius, shadow,
    // title and member grid the real window uses.
    private fun folderWindowView(
        prefs: LauncherPrefs,
        viewModel: LauncherViewModel,
        resolved: com.supernova.launcher.ui.theme.ResolvedSurface
    ): View {
        val density = resources.displayMetrics.density
        val style = StyleConfig.decode(prefs.styleConfig).styleFor(SurfaceId.FOLDER)
        val radiiPx = resolved.cornerRadiiDp?.let { dpArr -> FloatArray(4) { dpArr[it] * density } }
        val background = com.supernova.launcher.ui.theme.SurfaceShapes.background(
            resolved.surface,
            radiiPx,
            resolved.cornerCuts,
            (style.cornerRadii?.firstOrNull() ?: DEFAULT_FOLDER_RADIUS_DP) * density
        )
        folderCardBg = background

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackground(background)
            val pad = dp(10)
            setPadding(pad, pad, pad, pad)
        }
        folderCard = card

        val title = TextView(context).apply {
            text = context.getString(R.string.folder)
            maxLines = 1
            setTextColor(resolved.text)
        }
        folderTitleView = title
        card.addView(
            title,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
        )

        // Two rows of the widest grid the columns slider allows; tick hides the
        // cells past the current column count.
        val apps = sampleApps(viewModel, FOLDER_PREVIEW_COLS * 2)
        repeat(2) { rowIndex ->
            val cells = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
            }
            repeat(FOLDER_PREVIEW_COLS) { col ->
                val (app, icon) = apps.getOrNull(rowIndex * FOLDER_PREVIEW_COLS + col) ?: return@repeat
                val column = iconColumn(icon.copied(), app.label, keepInsideSlot = true)
                slotViews += column
                cells.addView(column, slotParams())
            }
            card.addView(
                cells,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        // IMMERSIVE fills the screen over a scrim; the stage shows that rather
        // than pretending the card just got wider.
        val immersive = com.supernova.launcher.ui.FolderMode.fromName(prefs.folderMode) ==
            com.supernova.launcher.ui.FolderMode.IMMERSIVE
        if (!immersive) return card
        val stage = FrameLayout(context).apply { setBackgroundColor(IMMERSIVE_STAGE_SCRIM) }
        stage.addView(
            card,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
                val inset = dp(8)
                setMargins(inset, inset, inset, inset)
            }
        )
        return stage
    }

    // Search demo: the window as it opens, with a stock query and
    // the result kinds each provider toggle contributes.
    private fun buildSearch(prefs: LauncherPrefs, viewModel: LauncherViewModel) {
        val resolved = Styles.resolve(context, prefs).of(SurfaceId.SEARCH_BAR)
        surfaceRgb = resolved.surface and 0xFFFFFF
        styleAlpha = StyleConfig.decode(prefs.styleConfig).styleFor(SurfaceId.SEARCH_BAR).alpha
        val density = resources.displayMetrics.density
        // WINDOW floats an inset rounded card; IMMERSIVE fills the screen. Same
        // split the real overlay makes.
        val windowMode = prefs.searchWindowMode.equals("WINDOW", ignoreCase = true)

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = com.supernova.launcher.ui.theme.SurfaceShapes.background(
                resolved.surface, null, null, if (windowMode) 24f * density else 0f
            )
            val pad = dp(12)
            setPadding(pad, pad, pad, pad)
        }
        surfaceBg = panel.background

        panel.addView(searchField(prefs, resolved))
        panel.addView(searchAppResults(viewModel))
        searchRows["calc"] = searchResultRow(
            resolved, R.drawable.ic_menu_info, context.getString(R.string.search_demo_calc)
        ).also { panel.addView(it) }
        searchRows["shortcut"] = searchResultRow(
            resolved, R.drawable.ic_menu_open, context.getString(R.string.search_demo_shortcut)
        ).also { panel.addView(it) }
        searchRows["contact"] = searchResultRow(
            resolved, R.drawable.ic_action_phone, context.getString(R.string.search_demo_contact)
        ).also { panel.addView(it) }
        searchRows["contactCard"] = searchContactCard(resolved).also { panel.addView(it) }
        searchRows["folder"] = searchResultRow(
            resolved, R.drawable.ic_folder, context.getString(R.string.search_demo_folder)
        ).also { panel.addView(it) }
        panel.addView(
            searchResultRow(
                resolved, R.drawable.ic_search,
                context.getString(
                    R.string.search_web_for,
                    com.supernova.launcher.search.WebProvider.fromName(prefs.searchWebProvider).displayName,
                    SEARCH_DEMO_QUERY
                )
            )
        )

        val stage = FrameLayout(context)
        stage.addView(
            panel,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                val side = if (windowMode) dp(14) else 0
                setMargins(side, if (windowMode) dp(8) else 0, side, if (windowMode) dp(8) else 0)
            }
        )
        addView(stage, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun searchField(prefs: LauncherPrefs, resolved: com.supernova.launcher.ui.theme.ResolvedSurface): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = dp(8)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor((0x24 shl 24) or (resolved.text and 0xFFFFFF))
            }
        }
        val icon = ImageView(context).apply {
            setImageResource(R.drawable.ic_search)
            imageTintList = android.content.res.ColorStateList.valueOf(resolved.accent)
        }
        row.addView(icon, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) })
        val text = TextView(context).apply {
            text = SEARCH_DEMO_QUERY
            setTextColor(resolved.text)
            maxLines = 1
        }
        row.addView(text)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            )
        }
    }

    private fun searchAppResults(viewModel: LauncherViewModel): View {
        val holder = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val apps = sampleApps(viewModel, SEARCH_DEMO_COLS * 2)
        repeat(2) { rowIndex ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            repeat(SEARCH_DEMO_COLS) { col ->
                val (app, icon) = apps.getOrNull(rowIndex * SEARCH_DEMO_COLS + col) ?: return@repeat
                row.addView(iconColumn(icon.copied(), app.label), slotParams())
            }
            holder.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            if (rowIndex == 1) searchAppsSecondRow = row
        }
        return holder
    }

    // Contact card view: the same result drawn as a card with call/message
    // affordances instead of a plain row.
    private fun searchContactCard(resolved: com.supernova.launcher.ui.theme.ResolvedSurface): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = dp(8)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor((0x1F shl 24) or (resolved.text and 0xFFFFFF))
            }
        }
        val avatar = ImageView(context).apply {
            setImageResource(R.drawable.ic_action_phone)
            imageTintList = android.content.res.ColorStateList.valueOf(resolved.accent)
        }
        card.addView(avatar, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(10) })
        val text = TextView(context).apply {
            text = context.getString(R.string.search_demo_contact)
            setTextColor(resolved.text)
            maxLines = 1
        }
        card.addView(
            text,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        repeat(2) {
            val action = View(context).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(9).toFloat()
                    setColor((0x40 shl 24) or (resolved.accent and 0xFFFFFF))
                }
            }
            card.addView(
                action,
                LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(6) }
            )
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            addView(
                card,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun searchResultRow(
        resolved: com.supernova.launcher.ui.theme.ResolvedSurface,
        iconRes: Int,
        label: String
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = dp(6)
            setPadding(0, pad, 0, pad)
        }
        val icon = ImageView(context).apply {
            setImageResource(iconRes)
            imageTintList = android.content.res.ColorStateList.valueOf(resolved.text)
        }
        row.addView(icon, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(10) })
        val text = TextView(context).apply {
            this.text = label
            setTextColor(resolved.text)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        row.addView(text)
        return row
    }

    private fun buildDrawer(prefs: LauncherPrefs, viewModel: LauncherViewModel) {
        val resolved = Styles.resolve(context, prefs).of(SurfaceId.DRAWER)
        surfaceRgb = resolved.surface and 0xFFFFFF
        styleAlpha = StyleConfig.decode(prefs.styleConfig).styleFor(SurfaceId.DRAWER).alpha

        val sheet = LinearLayout(context)
        sheet.orientation = LinearLayout.VERTICAL
        sheet.clipChildren = false
        sheet.setPadding(dp(14), dp(12), dp(14), dp(12))
        sheet.background = surfaceDrawable(resolved, dp(18).toFloat())

        val pill = TextView(context)
        pill.text = context.getString(R.string.search_apps)
        pill.setTextColor((0x99 shl 24) or (resolved.text and 0xFFFFFF))
        pill.textSize = 13f
        pill.setPadding(dp(14), dp(7), dp(14), dp(7))
        pill.background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor((0x1F shl 24) or (resolved.text and 0xFFFFFF))
        }
        sheet.addView(
            pill,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        val row = newRow()
        row.setPadding(0, dp(12), 0, 0)
        sampleApps(viewModel, 4).forEach { (app, icon) ->
            row.addView(iconColumn(icon, app.label, keepInsideSlot = true), slotParams())
        }
        sheet.addView(
            row,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        addView(sheet, stripParams())
    }

    // Cache-shared drawables have exactly one bounds; every preview draw at
    // non-standard bounds must work on its own copy.
    private fun sampleApps(viewModel: LauncherViewModel, count: Int): List<Pair<LaunchableApp, Drawable>> {
        val catalog = viewModel.appCatalog()
        return viewModel.visibleApps().asSequence()
            .mapNotNull { app -> catalog.iconFor(app)?.copied()?.let { app to it } }
            .take(count)
            .toList()
    }

    private fun Drawable.copied(): Drawable =
        constantState?.newDrawable(resources)?.mutate() ?: this

    private fun newRow(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        clipChildren = false
    }

    // labelLines is the surface's own line limit, which the single-line
    // setting then narrows (LabelStyler remembers it per view).
    // keepInsideSlot stops the icon's box at the column's width, for the
    // surfaces whose own box stays inside its slot (the drawer, the dock and
    // the folder window, but not the desktop).
    private fun iconColumn(
        icon: Drawable,
        label: CharSequence,
        labelLines: Int = 1,
        keepInsideSlot: Boolean = false
    ): LinearLayout {
        val column = LinearLayout(context)
        column.orientation = LinearLayout.VERTICAL
        column.gravity = Gravity.CENTER_HORIZONTAL
        column.clipChildren = false
        val image = PreviewIcon(context, dp(ICON_BASE_DP), keepInsideSlot)
        image.setImageDrawable(icon)
        iconViews += image
        // The box is the surfaces' own (item_icon.xml and item_drawer_app.xml
        // declare 48dp), sized by tick, so the preview shows the size the
        // setting is about to produce. The width runs to the column so the
        // icon knows how much room its slot has.
        column.addView(
            image,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        val text = TextView(context)
        text.text = label
        text.maxLines = labelLines
        text.ellipsize = android.text.TextUtils.TruncateAt.END
        text.gravity = Gravity.CENTER_HORIZONTAL
        labelViews += text
        column.addView(
            text,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        return column
    }

    private fun slotParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

    // Centred, so a strip kept in the room its largest icons need (onMeasure)
    // sits in the middle of it.
    private fun stripParams(): LayoutParams =
        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL).apply {
            topMargin = dp(6)
            bottomMargin = dp(6)
        }

    private fun surfaceDrawable(
        resolved: com.supernova.launcher.ui.theme.ResolvedSurface,
        defaultRadius: Float
    ): android.graphics.drawable.Drawable {
        val density = resources.displayMetrics.density
        val drawable = com.supernova.launcher.ui.theme.SurfaceShapes.background(
            resolved.surface,
            resolved.cornerRadiiDp?.let { dpArr -> FloatArray(4) { dpArr[it] * density } },
            resolved.cornerCuts,
            defaultRadius
        )
        surfaceBg = drawable
        return drawable
    }


    // A preview's icon: a square box at the size its surface draws, set by
    // tick. keepInsideSlot stops it at the width its column gives it, as the
    // real box stops at its slot or tile; otherwise it may overhang, as a big
    // desktop icon overhangs its cell.
    private class PreviewIcon(context: Context, initialBox: Int, private val keepInsideSlot: Boolean) :
        androidx.appcompat.widget.AppCompatImageView(context) {
        var box = initialBox
            set(value) {
                if (field == value) return
                field = value
                requestLayout()
            }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val room = MeasureSpec.getSize(widthMeasureSpec)
            val limited = keepInsideSlot && MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED
            val side = if (limited) minOf(box, room) else box
            setMeasuredDimension(side, side)
        }
    }

    private companion object {
        // The home bar's height and side inset, with room above and below.
        const val SEARCH_BAR_HEIGHT_DP = 44
        const val SEARCH_BAR_INSET_DP = 16
        const val SEARCH_BAR_PAD_DP = 28
        // The drawer field's start icon, hint and box, as the drawer has them.
        const val DRAWER_ICON_DP = 20
        const val DRAWER_ICON_GAP_DP = 12
        const val DRAWER_HINT_SP = 16f
        const val DRAWER_HINT_ALPHA = 0x8A
        const val DRAWER_FILL_ALPHA = 0x24
        const val DRAWER_STROKE_ALPHA = 0x5A
        const val DRAWER_BUTTON_DP = 36
        const val DRAWER_BUTTON_PAD_DP = 8
        const val DRAWER_BUTTON_END_DP = 4
        // The stand-in notification picture a Dynamic badge shows here, and
        // the count a Numeric one does.
        const val SAMPLE_FACE_PX = 96
        const val SAMPLE_COUNT = 3
        // The icon box item_icon.xml and item_drawer_app.xml declare, which
        // every surface's icon size scales, and the top of the icon size bars.
        const val ICON_BASE_DP = 48
        const val MAX_ICON_SCALE = 1.6f
        // A dock item's side padding (LauncherActivity.DOCK_ITEM_PADDING_H_DP).
        const val DOCK_ITEM_PADDING_H_DP = 2
        // Widest grid the preview draws; the columns slider hides the rest.
        const val FOLDER_PREVIEW_COLS = 5
        // bg_folder's built-in radius, the fallback the real window uses.
        const val DEFAULT_FOLDER_RADIUS_DP = 24f
        const val IMMERSIVE_STAGE_SCRIM = -0x4D000000
        const val SEARCH_DEMO_COLS = 5
        // The Home strip's height and the range of
        // desktop columns it mirrors, one icon per column.
        const val HOME_HEIGHT_DP = 144
        const val HOME_MIN_COLS = 3
        const val HOME_MAX_COLS = 7
        const val SEARCH_DEMO_QUERY = "cal"
    }
}
