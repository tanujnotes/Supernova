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
import com.supernova.launcher.ui.IconFitRoom
import com.supernova.launcher.ui.LabelContext
import com.supernova.launcher.ui.LabelStyler
import com.supernova.launcher.ui.LauncherViewModel
import com.supernova.launcher.ui.SearchBarButtons
import com.supernova.launcher.ui.SearchBarChip
import com.supernova.launcher.ui.SearchBarConfig
import com.supernova.launcher.ui.SearchBarView
import com.supernova.launcher.ui.cellSize
import com.supernova.launcher.ui.dp
import com.supernova.launcher.ui.settings.PreviewKind
import com.supernova.launcher.ui.textSizeOf
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

    // One stop for a screen reader: "Preview", not every sample label in
    // the fake home screen it draws.
    init {
        contentDescription = context.getString(com.supernova.launcher.R.string.a11y_preview)
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private var kind: PreviewKind? = null

    // The drawer's previews paint the drawer's own surface edge to edge,
    // so the pinned card shows that one colour rather than a strip of it on
    // the card's (SettingsActivity sizes the card around them).
    val fillsPane: Boolean
        get() = kind == PreviewKind.DRAWER || kind == PreviewKind.DRAWER_SEARCH_BAR || kind == PreviewKind.DOCK

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
    // App drawer preview: the grid row or list rows the padding bars move,
    // and the list's icons, which have a box of their own.
    private var drawerItems: View? = null
    private val listIconViews = mutableListOf<PreviewIcon>()
    // Home strip: the row its padding bar insets.
    private var homeRow: View? = null
    // Dock: whether its background runs to the edges with square bottom
    // corners (behind the navigation bar, or not inset).
    private var dockSquareBottom = false
    // Folder window: its cells, re-sized as the icon size bar moves, the
    // column count they were laid out for, and the app count by the name.
    private val folderCells = mutableListOf<View>()
    private var folderColumnCount = 0
    private var folderNameRow: View? = null
    private var folderCountView: View? = null
    // Icons & labels: the badged icon, re-badged as the badge bars move,
    // and the icon that wears the swipe mark.
    private var badgeIcon: ImageView? = null
    private var badgeRaw: Drawable? = null
    private var badgeLetter = ""
    private var badgeIconColor: Int? = null
    private var swipeIcon: ImageView? = null

    // Base RGB plus any explicit style-config alpha, captured at bind so a
    // tick can recombine the surface color without re-resolving.
    private var surfaceRgb = 0
    private var styleAlpha: Float? = null

    fun bind(kind: PreviewKind, prefs: LauncherPrefs, viewModel: LauncherViewModel) {
        this.kind = kind
        removeAllViews()
        // The pinned card reuses this view across pages.
        background = null
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
        drawerItems = null
        listIconViews.clear()
        homeRow = null
        dockSquareBottom = false
        folderCells.clear()
        folderColumnCount = 0
        folderNameRow = null
        folderCountView = null
        badgeIcon = null
        badgeRaw = null
        swipeIcon = null
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
                tickIconMarks(prefs)
            }
            PreviewKind.HOME -> {
                styleLabels(prefs, visible = prefs.showLabels, onWallpaper = true)
                fitHomeIcons(prefs)
            }
            PreviewKind.DOCK -> tickDock(prefs)
            PreviewKind.FOLDER -> tickFolder(prefs)
            PreviewKind.DRAWER -> tickDrawer(prefs)
            PreviewKind.SEARCH -> tickSearch(prefs)
            PreviewKind.SEARCH_BAR -> searchBarSurface?.let { surface ->
                searchBar?.apply(SearchBarConfig.decode(prefs.searchBarConfig).desktop, surface)
            }
            PreviewKind.DRAWER_SEARCH_BAR -> styleDrawerField(prefs)
            null -> Unit
        }
    }

    // The prefs the Home strip was last fitted for, refitted once the strip
    // has its width.
    private var homePrefs: LauncherPrefs? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val prefs = homePrefs ?: return
        if (kind != PreviewKind.HOME || w == oldw) return
        // Not mid-layout: the fit resizes the icons.
        post {
            styleLabels(prefs, visible = prefs.showLabels, onWallpaper = true)
            fitHomeIcons(prefs)
        }
    }

    // The desktop shrinks an icon that needs more room than its cell into
    // it (WorkspaceCanvas.IconFit): icon and label by the same scale, the
    // label on one line. The strip does the same against the desktop's cell
    // (IconFitRoom), so it shows the size the home screen will draw rather
    // than big icons running into each other. Before the home screen has
    // been laid out, the cell is the display's width shared by the columns.
    private fun fitHomeIcons(prefs: LauncherPrefs) {
        homePrefs = prefs
        val box = (dp(ICON_BASE_DP) * prefs.iconScale).toInt()
        val padH = dp(prefs.desktopPaddingH)
        val density = resources.displayMetrics.density
        val padV = com.supernova.launcher.ui.pagePaddingVPx(prefs.desktopPaddingV, density, false)
        val screen = resources.displayMetrics.widthPixels
        val cell = IconFitRoom.desktopCell(prefs.gridCols, prefs.gridRows, padH, padV)
            ?: (cellSize(screen - 2 * padH, prefs.gridCols) to Int.MAX_VALUE)
        // The desktop's side padding, at the strip's scale of the screen.
        val strip = width - paddingLeft - paddingRight
        val inset = if (strip > 0) (padH.toLong() * strip / screen).toInt() else 0
        homeRow?.let { row -> if (row.paddingLeft != inset) row.setPadding(inset, 0, inset, 0) }
        // The strip's slots, a little narrower than the cells (the card is
        // inset from the screen's edges), hold the icons too, or neighbours
        // touch.
        val slot = (strip - 2 * inset).takeIf { it > 0 }?.let { cellSize(it, homeColumns(prefs)) } ?: cell.first
        val line = labelViews.firstOrNull()?.takeIf { prefs.showLabels }?.lineHeight ?: 0
        val fit = IconFitRoom.desktopFitScale(
            minOf(cell.first, slot), cell.second, box, dp(HOME_ITEM_PADDING_DP), line
        )
        sizeIcons(prefs.iconScale * fit)
        if (fit >= 1f) return
        for (label in labelViews) {
            label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, label.textSize * fit)
            label.maxLines = 1
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

    private fun tickDock(prefs: LauncherPrefs) {
        com.supernova.launcher.ui.theme.SurfaceShapes.setColor(
            surfaceBg, withAlpha(storedAlpha(prefs, SurfaceId.DOCK) ?: prefs.dockBgOpacity)
        )
        // The radius bar ticks here too.
        if (prefs.dockBgStyle == DockBgStyle.ROUNDED) roundDock(dp(prefs.dockBgRadius).toFloat())
        slotViews.forEachIndexed { index, slot ->
            slot.visibility = if (index < prefs.dockCount) VISIBLE else GONE
        }
        sizeIcons(if (prefs.dockMatchDesktop) prefs.iconScale else prefs.dockIconScale)
        // The dock's own switch alone, as on the real dock.
        styleLabels(prefs, visible = prefs.dockLabels, context = LabelContext.DOCK)
    }

    private fun roundDock(radius: Float) {
        val bg = surfaceBg as? GradientDrawable ?: return
        if (dockSquareBottom) {
            bg.cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
        } else {
            bg.cornerRadius = radius
        }
    }

    private fun tickFolder(prefs: LauncherPrefs) {
        val scale = if (prefs.folderMatchDesktop) prefs.iconScale else prefs.folderIconScale
        // The cells grow with the icon size as the window's do.
        if (folderColumnCount > 0) {
            val cell = com.supernova.launcher.ui.FolderPopup
                .gridWidthPx(context, folderColumnCount, scale) / folderColumnCount
            folderCells.filter { it.layoutParams.width != cell }.forEach { view ->
                view.layoutParams.width = cell
                view.requestLayout()
            }
        }
        sizeIcons(scale)
        styleLabels(prefs, visible = prefs.folderLabels, context = LabelContext.FOLDER)
        folderNameRow?.visibility = if (prefs.folderShowName) VISIBLE else GONE
        folderCountView?.visibility = if (prefs.folderShowCount) VISIBLE else GONE
        folderCard?.elevation = if (prefs.folderShadow) dp(FOLDER_ELEVATION_DP).toFloat() else 0f
        // Radius rides the style config, so the slider lands here too.
        val radius = StyleConfig.decode(prefs.styleConfig)
            .styleFor(SurfaceId.FOLDER).cornerRadii?.firstOrNull()
            ?: DEFAULT_FOLDER_RADIUS_DP
        (folderCardBg as? GradientDrawable)?.cornerRadius =
            radius * resources.displayMetrics.density
    }

    private fun tickSearch(prefs: LauncherPrefs) {
        searchAppsSecondRow?.visibility = if (prefs.searchAppsOneRow) GONE else VISIBLE
        searchRows["calc"]?.visibility = if (prefs.searchCalculator) VISIBLE else GONE
        searchRows["contact"]?.visibility =
            if (prefs.searchContacts && !prefs.searchContactCards) VISIBLE else GONE
        searchRows["contactCard"]?.visibility =
            if (prefs.searchContacts && prefs.searchContactCards) VISIBLE else GONE
        searchRows["shortcut"]?.visibility = if (prefs.searchShortcuts) VISIBLE else GONE
        searchRows["folder"]?.visibility = if (prefs.searchFolders) VISIBLE else GONE
        searchRows["card"]?.visibility = if (prefs.searchCards) VISIBLE else GONE
        styleLabels(prefs, visible = true)
    }

    private fun tickDrawer(prefs: LauncherPrefs) {
        com.supernova.launcher.ui.theme.SurfaceShapes.setColor(
            surfaceBg, withAlpha(storedAlpha(prefs, SurfaceId.DRAWER) ?: prefs.drawerBgOpacity)
        )
        val scale = if (prefs.drawerMatchDesktop) prefs.iconScale else prefs.drawerIconScale
        sizeIcons(scale)
        val listBox = (dp(DRAWER_LIST_ICON_DP) * scale).toInt()
        for (icon in listIconViews) icon.box = listBox
        // The list always names its rows; the switch is the grid's.
        val list = prefs.drawerStyle == com.supernova.launcher.ui.DrawerStyle.VERTICAL_LIST
        styleLabels(prefs, visible = list || prefs.drawerLabels, context = LabelContext.DRAWER)
        applyDrawerPadding(prefs)
        styleDrawerField(prefs)
    }

    // An alpha the Style sheet stored for the surface, read from the prefs
    // being drawn: the opacity bar's frames clear it (SurfaceOpacity.preview),
    // so one captured at bind would hold the bar still.
    private fun storedAlpha(prefs: LauncherPrefs, id: SurfaceId): Float? =
        StyleConfig.decode(prefs.styleConfig).styleFor(id).alpha

    private fun withAlpha(alpha: Float): Int =
        ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24) or surfaceRgb

    // A few icons as the home screen draws them, one badged and one with a
    // swipe mark, beside the long-press menu with its notification, an app
    // shortcut and its rows, shrunk to fit: every setting on the page shows.
    private fun buildIconRow(prefs: LauncherPrefs, viewModel: LauncherViewModel) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
        }
        val icons = newRow()
        val apps = sampleApps(viewModel, ICON_ROW_APPS + 1)
        apps.take(ICON_ROW_APPS).forEachIndexed { index, (app, icon) ->
            icons.addView(iconColumn(icon, app.label), slotParams())
            val image = iconViews.last()
            when (index) {
                1 -> {
                    badgeIcon = image
                    badgeRaw = icon
                    badgeLetter = app.label.take(1).toString()
                    badgeIconColor = dominantColor(viewModel, app)
                }
                2 -> swipeIcon = image
            }
        }
        row.addView(icons, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(
            FitWidthFrame(context, keepsHeight = false).apply { addView(popupPreview(prefs, apps)) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, ICON_ROW_POPUP_WEIGHT)
        )
        addView(row, stripParams())
    }

    // The long-press menu as the popup settings build it; inert.
    private fun popupPreview(prefs: LauncherPrefs, apps: List<Pair<LaunchableApp, Drawable>>): View {
        val menu = com.supernova.launcher.ui.SupernovaPopupMenu(
            context, Styles.resolve(context, prefs).of(SurfaceId.POPUP), animate = false,
            cardBackground = prefs.popupCardBg, style = prefs.popupStyle, groupShortcuts = prefs.popupGroupShortcuts
        )
        menu.actionIcons = prefs.popupActionIcons
        if (prefs.popupNotifications) {
            menu.addNotification(
                apps.firstOrNull()?.first?.label?.toString().orEmpty(),
                context.getString(R.string.preview_popup_notification)
            ) {}
        }
        menu.addShortcut(context.getString(R.string.search_demo_shortcut), apps.lastOrNull()?.second?.copied()) {}
        menu.addRow(context.getString(R.string.app_info), R.drawable.ic_menu_info) {}
        menu.addRow(context.getString(R.string.uninstall), R.drawable.ic_menu_uninstall) {}
        menu.addRow(context.getString(R.string.edit), R.drawable.ic_menu_edit) {}
        return menu.previewView().apply {
            isClickable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
    }

    // The badge and the swipe mark, re-drawn as the badge bars move.
    private fun tickIconMarks(prefs: LauncherPrefs) {
        val raw = badgeRaw
        if (raw != null) {
            badgeIcon?.setImageDrawable(
                if (prefs.badges) {
                    val style = com.supernova.launcher.ui.BadgeStyle.effective(prefs)
                    val face = if (style == com.supernova.launcher.ui.BadgeStyle.DYNAMIC) {
                        BadgeLook.samplePicture(badgeLetter, SAMPLE_FACE_PX)
                    } else {
                        null
                    }
                    BadgeLook(prefs, resources.displayMetrics.density)
                        .wrap(raw.copied(), SAMPLE_COUNT, badgeIconColor, face)
                } else {
                    raw
                }
            )
        }
        swipeIcon?.foreground = if (prefs.iconSwipeIndicator) {
            com.supernova.launcher.ui.SwipeIndicatorDrawable(
                resources.displayMetrics.density, labelViews.firstOrNull()?.currentTextColor ?: 0
            )
        } else {
            null
        }
    }

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
        val surface = Styles.resolve(context, prefs).of(SurfaceId.DRAWER_SEARCH_BAR)
        setBackgroundColor(surface.surface)
        addView(
            newDrawerField(prefs, surface),
            LayoutParams(LayoutParams.MATCH_PARENT, dp(SEARCH_BAR_HEIGHT_DP), Gravity.CENTER_VERTICAL).apply {
                val inset = dp(SEARCH_BAR_INSET_DP)
                val pad = dp(SEARCH_BAR_PAD_DP)
                setMargins(inset, pad, inset, pad)
            }
        )
    }

    // The field itself: start icon, hint and the drawer's action buttons,
    // styled by styleDrawerField. The Drawer search bar page and the App
    // drawer's sheet both show it.
    private fun newDrawerField(
        prefs: LauncherPrefs,
        surface: com.supernova.launcher.ui.theme.ResolvedSurface
    ): View {
        searchBarSurface = surface
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
            if (SearchBarConfig.decode(prefs.searchBarConfig).drawer.hint) setText(R.string.drawer_search_hint)
            textSize = DRAWER_HINT_SP
            maxLines = 1
            setTextColor((DRAWER_HINT_ALPHA shl 24) or (surface.text and 0xFFFFFF))
        }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        // The drawer's action buttons, inside the field as the drawer has them.
        val buttons = SearchBarButtons.decode(prefs.drawerActionButtons)
        buttons.forEach { button ->
            val width = if (button == SearchBarChip.DATE) LayoutParams.WRAP_CONTENT else dp(DRAWER_BUTTON_DP)
            field.addView(
                SearchBarButtons.view(context, button, surface.text, onClick = null),
                LinearLayout.LayoutParams(width, dp(DRAWER_BUTTON_DP))
            )
        }
        if (buttons.isNotEmpty()) field.setPaddingRelative(dp(SEARCH_BAR_INSET_DP), 0, dp(DRAWER_BUTTON_END_DP), 0)
        drawerField = field
        drawerFieldPill = pill
        drawerFieldIcon = icon
        return field
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
                setColor(surface.surface)
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
        homeSampleApps(viewModel, homeColumns(prefs)).forEach { (app, icon) ->
            val column = iconColumn(icon, app.label, labelLines = 2)
            val pad = dp(HOME_ITEM_PADDING_DP)
            column.setPadding(pad, pad, pad, pad)
            // An icon's shadow falls outside its box (fitHomeIcons keeps
            // the box inside the slot); show all of it.
            column.clipToPadding = false
            row.addView(column, slotParams())
        }
        // The edge scrims under everything else, as the home screen has
        // them, at the strip's ends.
        if (prefs.showScrims) {
            addView(View(context).apply { setBackgroundResource(R.drawable.scrim_top) },
                LayoutParams(LayoutParams.MATCH_PARENT, dp(HOME_SCRIM_DP), Gravity.TOP))
            addView(View(context).apply { setBackgroundResource(R.drawable.scrim_bottom) },
                LayoutParams(LayoutParams.MATCH_PARENT, dp(HOME_SCRIM_DP), Gravity.BOTTOM))
        }
        homeRow = row
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, dp(HOME_HEIGHT_DP)))
        // The page indicator under the icons, always shown here (auto-hide
        // would leave nothing to look at), on the first of a few pages.
        if (prefs.pageIndicatorStyle != com.supernova.launcher.ui.IndicatorStyle.NONE) {
            val indicator = com.supernova.launcher.ui.PageIndicatorView(context)
            indicator.configure(
                prefs.pageIndicatorStyle, HOME_PREVIEW_PAGES, false,
                LabelStyler.parseColor(
                    prefs.pageIndicatorColor.ifEmpty { prefs.labelColor },
                    context.getColor(R.color.onWallpaper)
                ),
                0f
            )
            addView(indicator, LayoutParams(LayoutParams.MATCH_PARENT, dp(HOME_INDICATOR_DP), Gravity.BOTTOM))
        }
    }

    private fun homeColumns(prefs: LauncherPrefs): Int = prefs.gridCols.coerceIn(HOME_MIN_COLS, HOME_MAX_COLS)

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

    // The dock as the home screen lays it out, at the screen's width and
    // shrunk into the card, at its foot over the wallpaper: its background
    // floating clear of the navigation bar, resting on it (not inset), or
    // running under it (behind nav), and the dock's own padding inside.
    private fun buildDock(prefs: LauncherPrefs, viewModel: LauncherViewModel) {
        val resolved = Styles.resolve(context, prefs).of(SurfaceId.DOCK)
        surfaceRgb = resolved.surface and 0xFFFFFF
        styleAlpha = StyleConfig.decode(prefs.styleConfig).styleFor(SurfaceId.DOCK).alpha

        val bg = prefs.dockBgStyle != DockBgStyle.NONE
        val behindNav = bg && prefs.dockBgBehindNav
        val fullBleed = bg && !prefs.dockBgPadding && !behindNav
        dockSquareBottom = behindNav || fullBleed
        val nav = navBarInset()
        val bar = newRow()
        val bgPadV = if (bg) dp(DOCK_BG_PADDING_V_DP) else 0
        bar.setPadding(
            dp(prefs.dockPaddingH), dp(prefs.dockPaddingV) + bgPadV,
            dp(prefs.dockPaddingH), dp(prefs.dockPaddingV) + bgPadV + if (behindNav) nav else 0
        )
        dockBackground(prefs, resolved)?.let { drawable ->
            surfaceBg = drawable
            bar.background = drawable
        }
        // Every slot the dock can have, Big grid's included; binding shows
        // dockCount of them.
        val slots = com.supernova.launcher.data.GridLimits.DOCK_BIG
        val apps = sampleApps(viewModel, slots)
        repeat(slots) { index ->
            val (app, icon) = apps.getOrNull(index % apps.size.coerceAtLeast(1)) ?: return@repeat
            // A dock item's own padding, which its box stays inside, so a
            // big icon stops growing where the dock's does.
            val column = iconColumn(icon.copied(), app.label, keepInsideSlot = true)
            val padV = dp(DOCK_SLOT_PADDING_V_DP + DOCK_ITEM_PADDING_V_DP)
            column.setPadding(dp(DOCK_ITEM_PADDING_H_DP), padV, dp(DOCK_ITEM_PADDING_H_DP), padV)
            slotViews += column
            bar.addView(column, slotParams())
        }
        addDockContent(bar, side = if (!bg || dockSquareBottom) 0 else dp(DOCK_SIDE_MARGIN_DP), bottom = when {
            behindNav -> 0
            fullBleed -> nav
            else -> nav + dp(DOCK_SIDE_MARGIN_DP)
        })
    }

    // The dock at the screen's width, shrunk into the card at its foot.
    private fun addDockContent(bar: View, side: Int, bottom: Int) {
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            minimumWidth = resources.displayMetrics.widthPixels
        }
        content.addView(bar, matchWidth().apply {
            leftMargin = side
            rightMargin = side
            bottomMargin = bottom
        })
        addView(
            FitWidthFrame(context, keepsHeight = false).apply { addView(content) },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        )
    }

    // The dock's background, its bottom corners square when it runs to the
    // edges (LauncherActivity.dockBackgroundDrawable).
    private fun dockBackground(
        prefs: LauncherPrefs,
        resolved: com.supernova.launcher.ui.theme.ResolvedSurface
    ): Drawable? {
        val radius = when (prefs.dockBgStyle) {
            DockBgStyle.NONE -> return null
            DockBgStyle.ROUNDED -> dp(prefs.dockBgRadius).toFloat()
            DockBgStyle.SQUARE -> 0f
        }
        return com.supernova.launcher.ui.theme.SurfaceShapes.background(
            resolved.surface, if (dockSquareBottom) floatArrayOf(radius, radius, 0f, 0f) else null, radius
        )
    }

    // The navigation bar's height, which the dock clears or runs under.
    private fun navBarInset(): Int =
        androidx.core.view.ViewCompat.getRootWindowInsets(this)
            ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())?.bottom
            ?.takeIf { it > 0 }
            ?: dp(DEFAULT_NAV_DP)

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
        val sample = sampleApps(viewModel, FOLDER_SAMPLE_APPS)
        val icons = sample.map { it.second }
        val image = ImageView(context)
        val folderIcon =
            FolderIconDrawable(
                icons, dp(64),
                style = prefs.folderPreviewStyle,
                plateColor = FolderIconDrawable.plateColor(
                    prefs.folderMatchWindowColor, prefs.folderPlateColor, resolved.surface
                ),
                shape = FolderIconDrawable.plateShape(prefs.folderIconShape, prefs),
                customSpec = com.supernova.launcher.data.CustomShapeSpec.decode(prefs.iconShapeCustom),
                outlineColor = if (prefs.folderIconOutline) resolved.accent else 0,
                outlineWidthPx = 2f * resources.displayMetrics.density,
                prioritizeFirst = prefs.folderPrioritizeFirst,
                glass = FolderIconDrawable.glassFor(prefs),
                shadow = prefs.iconShadow
            )
        // Badge folders: the members' count on the folder's icon.
        val first = sample.firstOrNull()?.first
        image.setImageDrawable(
            if (prefs.badges && prefs.folderBadges && first != null) {
                BadgeLook(prefs, resources.displayMetrics.density)
                    .wrap(folderIcon, SAMPLE_COUNT, dominantColor(viewModel, first), null)
            } else {
                folderIcon
            }
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
            (style.cornerRadii?.firstOrNull() ?: DEFAULT_FOLDER_RADIUS_DP) * density
        )
        folderCardBg = background

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackground(background)
            val pad = dp(FOLDER_CARD_PAD_DP)
            setPadding(pad, pad, pad, pad)
        }
        folderCard = card

        // The grid the window opens with for a folder of this many apps, at
        // its real cell width, so icons sit as far apart as they will
        // (FolderPopup). Two rows of it: the window's top.
        val immersive = prefs.folderMode == com.supernova.launcher.ui.FolderMode.IMMERSIVE
        val scale = if (prefs.folderMatchDesktop) prefs.iconScale else prefs.folderIconScale
        val columns = com.supernova.launcher.ui.FolderPopup.columnsFor(
            context, FOLDER_SAMPLE_APPS, prefs.folderColumns, immersive, scale
        )
        val cell = com.supernova.launcher.ui.FolderPopup.gridWidthPx(context, columns, scale) / columns
        folderColumnCount = columns
        val apps = sampleApps(viewModel, columns * 2)
        repeat(2) { rowIndex ->
            val cells = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
            }
            repeat(columns) { col ->
                val (app, icon) = apps.getOrNull(rowIndex * columns + col) ?: return@repeat
                val column = iconColumn(icon.copied(), app.label, keepInsideSlot = true)
                // A member tile's own padding (item_icon.xml), which its
                // icon stays inside.
                val inset = dp(HOME_ITEM_PADDING_DP)
                column.setPadding(inset, inset, inset, inset)
                slotViews += column
                folderCells += column
                cells.addView(column, LinearLayout.LayoutParams(cell, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            card.addView(
                cells,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { gravity = Gravity.CENTER_HORIZONTAL }
            )
        }

        card.addView(folderNameRow(resolved), matchWidth())

        // IMMERSIVE fills the screen over a scrim; the stage shows that rather
        // than pretending the card just got wider.
        if (!immersive) return FitWidthFrame(context).apply { addView(card) }
        val stage = FrameLayout(context).apply { setBackgroundColor(IMMERSIVE_STAGE_SCRIM) }
        stage.addView(
            card,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
                val inset = dp(8)
                setMargins(inset, inset, inset, inset)
            }
        )
        return FitWidthFrame(context).apply { addView(stage) }
    }

    // The name under the grid, as the window has it (popup_folder.xml),
    // with the app count beside it when asked for.
    private fun folderNameRow(resolved: com.supernova.launcher.ui.theme.ResolvedSurface): View {
        val title = TextView(context).apply {
            text = context.getString(R.string.folder)
            maxLines = 1
            setTextColor(resolved.text)
            textSizeOf(R.dimen.text_body)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(FOLDER_TITLE_PAD_DP), dp(FOLDER_TITLE_PAD_DP), dp(FOLDER_TITLE_PAD_DP), 0)
        }
        folderTitleView = title
        // The app count beside it, when asked for (popup_folder.xml).
        val count = TextView(context).apply {
            text = java.text.NumberFormat.getIntegerInstance().format(FOLDER_SAMPLE_APPS)
            maxLines = 1
            textSizeOf(R.dimen.text_secondary)
            setTextColor((FOLDER_COUNT_ALPHA shl 24) or (resolved.text and 0xFFFFFF))
            setPadding(dp(FOLDER_COUNT_PAD_DP), dp(FOLDER_TITLE_PAD_DP), dp(FOLDER_COUNT_PAD_DP), 0)
        }
        folderCountView = count
        val nameRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(count)
        }
        folderNameRow = nameRow
        return nameRow
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
        val windowMode = prefs.searchWindowMode == com.supernova.launcher.data.SearchWindowMode.WINDOW

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = com.supernova.launcher.ui.theme.SurfaceShapes.background(
                resolved.surface, null, if (windowMode) 24f * density else 0f
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
        searchCardRow(viewModel, resolved)?.let { row ->
            searchRows["card"] = row
            panel.addView(row)
        }
        panel.addView(
            searchResultRow(
                resolved, R.drawable.ic_search,
                context.getString(
                    R.string.search_web_for,
                    com.supernova.launcher.search.WebEngine.of(prefs).name,
                    SEARCH_DEMO_QUERY
                )
            )
        )
        searchMarks(prefs, resolved)?.let { panel.addView(it, matchWidth()) }

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

    // One of the feed's own cards, when there is one to find.
    private fun searchCardRow(
        viewModel: LauncherViewModel,
        resolved: com.supernova.launcher.ui.theme.ResolvedSurface
    ): View? {
        val card = viewModel.feedCards().firstOrNull { it.enabled && it.name.isNotBlank() } ?: return null
        return searchResultRow(resolved, R.drawable.ic_action_feed, card.name)
    }

    // The providers' marks under the web row, as the chips set them.
    private fun searchMarks(prefs: LauncherPrefs, resolved: com.supernova.launcher.ui.theme.ResolvedSurface): View? {
        if (com.supernova.launcher.search.SearchChip.decode(prefs.searchChips).isEmpty()) return null
        val marks = com.supernova.launcher.ui.FrequentAppsRowView(context)
        marks.bind(
            com.supernova.launcher.ui.SearchMarks.entries(
                context, prefs, com.supernova.launcher.data.IconRenderer(context), resolved.accent
            ) {},
            com.supernova.launcher.ui.SearchMarks.MARK_DP
        )
        return marks
    }

    private fun searchField(prefs: LauncherPrefs, resolved: com.supernova.launcher.ui.theme.ResolvedSurface): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = dp(8)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
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
        val text = com.supernova.launcher.ui.FadingTextView(context).apply {
            this.text = label
            setTextColor(resolved.text)
            maxLines = 1
        }
        row.addView(text)
        return row
    }

    // The drawer's top as the drawer lays it out, at the screen's width and
    // shrunk as a whole into the card: its search row where the field sits,
    // then one row of the grid (or a few of the list) inside the drawer's
    // padding, at its column count. It shows on the real wallpaper, so the
    // background opacity reads as it will.
    private fun buildDrawer(prefs: LauncherPrefs, viewModel: LauncherViewModel) {
        val resolved = Styles.resolve(context, prefs).of(SurfaceId.DRAWER)
        surfaceRgb = resolved.surface and 0xFFFFFF
        // Square: the pinned card rounds it.
        background = surfaceDrawable(resolved, 0f)

        val screen = resources.displayMetrics.widthPixels
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            minimumWidth = screen
        }
        val list = prefs.drawerStyle == com.supernova.launcher.ui.DrawerStyle.VERTICAL_LIST
        val scale = if (prefs.drawerMatchDesktop) prefs.iconScale else prefs.drawerIconScale
        val columns = drawerColumns(prefs, scale)
        val apps = sampleApps(viewModel, columns * 2)
        val items = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
        }
        drawerItems = items
        if (list) {
            apps.take(DRAWER_LIST_ROWS).forEach { (app, icon) ->
                items.addView(drawerListRow(icon, app.label), matchWidth())
            }
        } else {
            val row = newRow()
            apps.take(columns).forEach { (app, icon) ->
                row.addView(iconColumn(icon, app.label, keepInsideSlot = true), slotParams())
            }
            items.addView(row, matchWidth())
        }
        applyDrawerPadding(prefs)
        val listFrame = drawerListFrame(prefs, resolved, items)

        // Top to bottom as DrawerController stacks the sheet: the search row
        // and the tabs at whichever end they are set to, the top row and its
        // rule with the sort button over the list.
        val placement = com.supernova.launcher.ui.DrawerBarPlacement.fromConfig(
            SearchBarConfig.decode(prefs.searchBarConfig).drawer
        )
        val searchTop = placement == com.supernova.launcher.ui.DrawerBarPlacement.TOP
        val searchRow = drawerSearchRow(prefs, searchTop)
            .takeIf { placement != com.supernova.launcher.ui.DrawerBarPlacement.HIDDEN }
        val tabs = drawerTabs(prefs, viewModel, resolved)
        val tabsBottom = prefs.drawerTabBar == com.supernova.launcher.ui.DrawerTabBar.BOTTOM
        val topRow = if (prefs.drawerFrequentRow) drawerTopRow(apps.drop(columns).ifEmpty { apps }, columns) else null
        val sortBar = drawerSortBar(prefs, resolved, rule = topRow != null)
        val parts = listOfNotNull(
            searchRow.takeIf { searchTop },
            tabs.takeIf { !tabsBottom },
            topRow,
            sortBar,
            listFrame,
            tabs.takeIf { tabsBottom },
            searchRow.takeIf { !searchTop }
        )
        parts.forEach { content.addView(it, matchWidth()) }
        addView(
            FitWidthFrame(context, keepsHeight = false).apply { addView(content) },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL)
        )
    }

    // The list with its fast scrollbar at the end, as the drawer has it in
    // the scrolling styles.
    private fun drawerListFrame(
        prefs: LauncherPrefs,
        resolved: com.supernova.launcher.ui.theme.ResolvedSurface,
        items: View
    ): View {
        val frame = FrameLayout(context).apply { clipChildren = false }
        frame.addView(items, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        if (prefs.drawerFastScroller && prefs.drawerStyle != com.supernova.launcher.ui.DrawerStyle.PAGED_GRID) {
            frame.addView(
                FastScrollPreview(
                    context,
                    thumb = LabelStyler.parseColor(prefs.drawerScrubberColor, resolved.accent),
                    track = (FAST_SCROLL_TRACK_ALPHA shl 24) or (resolved.text and 0xFFFFFF)
                ),
                LayoutParams(dp(FAST_SCROLL_WIDTH_DP), LayoutParams.MATCH_PARENT, Gravity.END)
            )
        }
        return frame
    }

    // The drawer's search row (activity_launcher.xml's drawerSearchRow),
    // styled as the Drawer search bar page sets it.
    private fun drawerSearchRow(prefs: LauncherPrefs, top: Boolean): View {
        val row = FrameLayout(context).apply {
            val side = dp(DRAWER_ROW_SIDE_DP)
            if (top) {
                setPadding(side, dp(DRAWER_ROW_SIDE_DP), side, dp(DRAWER_ROW_GAP_DP))
            } else {
                setPadding(side, dp(DRAWER_ROW_GAP_DP), side, dp(DRAWER_ROW_SIDE_DP))
            }
        }
        row.addView(
            newDrawerField(prefs, Styles.resolve(context, prefs).of(SurfaceId.DRAWER_SEARCH_BAR)),
            LayoutParams(LayoutParams.MATCH_PARENT, dp(SEARCH_BAR_HEIGHT_DP))
        )
        return row
    }

    // The group tabs as DrawerController styles them, or none while the bar
    // is off or there is nothing to put on it.
    private fun drawerTabs(
        prefs: LauncherPrefs,
        viewModel: LauncherViewModel,
        resolved: com.supernova.launcher.ui.theme.ResolvedSurface
    ): View? {
        val groups = viewModel.drawerTabGroups()
        val work = prefs.drawerWorkTab && viewModel.hasWorkApps()
        val off = prefs.drawerTabBar == com.supernova.launcher.ui.DrawerTabBar.NONE
        if (off || (groups.isEmpty() && !work)) return null
        val tabs = android.view.LayoutInflater.from(context)
            .inflate(R.layout.preview_drawer_tabs, this, false) as com.google.android.material.tabs.TabLayout
        styleTabs(tabs, prefs, resolved)
        // All, Work and the group tabs, each with the group id it stands for.
        val all = !prefs.drawerHideAllTab || groups.isEmpty()
        val glyph = com.supernova.launcher.ui.TabIcons
        val entries = buildList {
            if (all) add(Triple(null, context.getString(R.string.drawer_all)) { glyph.glyph(context, glyph.ALL) })
            if (work) add(Triple(null, context.getString(R.string.drawer_work)) { glyph.glyph(context, glyph.WORK) })
            groups.forEach { group -> add(Triple(group.id, group.name) { glyph.groupGlyph(context, group.name) }) }
        }
        val content = prefs.drawerTabContent
        entries.forEach { (_, label, icon) ->
            val tab = tabs.newTab()
            tab.text = if (content.showsLabel) label else null
            if (content.showsIcon) tab.icon = icon()
            tabs.addTab(tab, false)
        }
        val selected = entries.indexOfFirst { it.first != null && it.first == prefs.drawerDefaultTab }.coerceAtLeast(0)
        tabs.getTabAt(selected)?.select()
        return tabs
    }

    // The bar's colours, alignment and selected-tab mark, as
    // DrawerController.applyStyle sets them.
    private fun styleTabs(
        tabs: com.google.android.material.tabs.TabLayout,
        prefs: LauncherPrefs,
        resolved: com.supernova.launcher.ui.theme.ResolvedSurface
    ) {
        val text = resolved.text
        val dim = androidx.core.graphics.ColorUtils.setAlphaComponent(text, TAB_TEXT_ALPHA)
        tabs.setTabTextColors(dim, text)
        tabs.tabIconTint = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()), intArrayOf(text, dim)
        )
        tabs.isInlineLabel = true
        tabs.tabGravity = when (prefs.drawerTabAlign) {
            com.supernova.launcher.ui.DrawerTabAlign.START -> com.google.android.material.tabs.TabLayout.GRAVITY_START
            com.supernova.launcher.ui.DrawerTabAlign.CENTER -> com.google.android.material.tabs.TabLayout.GRAVITY_CENTER
        }
        if (prefs.drawerTabStyle == com.supernova.launcher.ui.DrawerTabStyle.BUBBLE) {
            val pill = GradientDrawable().apply {
                cornerRadius = dp(TAB_BUBBLE_RADIUS_DP).toFloat()
                setColor(android.graphics.Color.WHITE)
            }
            val inset = dp(TAB_BUBBLE_INSET_DP)
            tabs.setSelectedTabIndicator(android.graphics.drawable.InsetDrawable(pill, 0, inset, 0, inset))
            tabs.setSelectedTabIndicatorGravity(com.google.android.material.tabs.TabLayout.INDICATOR_GRAVITY_STRETCH)
            tabs.isTabIndicatorFullWidth = true
            tabs.setSelectedTabIndicatorColor(
                androidx.core.graphics.ColorUtils.setAlphaComponent(resolved.accent, TAB_BUBBLE_ALPHA)
            )
        } else {
            tabs.setSelectedTabIndicatorColor(resolved.accent)
        }
    }

    // The top row of apps over the list (FrequentAppsRowView), at the
    // grid's columns.
    private fun drawerTopRow(apps: List<Pair<LaunchableApp, Drawable>>, columns: Int): View {
        val row = newRow()
        row.setPadding(dp(DRAWER_ROW_SIDE_DP), 0, dp(DRAWER_ROW_SIDE_DP), 0)
        apps.take(columns).forEach { (app, icon) ->
            row.addView(iconColumn(icon.copied(), app.label, keepInsideSlot = true), slotParams())
        }
        return row
    }

    // The strip over the list (DrawerSortBars): the rule under the top row
    // and the sort button at its end, or nothing when neither shows.
    private fun drawerSortBar(
        prefs: LauncherPrefs,
        resolved: com.supernova.launcher.ui.theme.ResolvedSurface,
        rule: Boolean
    ): View? {
        if (!rule && !prefs.drawerSortButtons) return null
        val bar = FrameLayout(context)
        if (rule) {
            bar.addView(
                View(context).apply {
                    background = GradientDrawable().apply {
                        cornerRadius = dp(1).toFloat()
                        setColor((DRAWER_RULE_ALPHA shl 24) or (resolved.text and 0xFFFFFF))
                    }
                },
                LayoutParams(dp(DRAWER_RULE_WIDTH_DP), dp(2), Gravity.CENTER).apply {
                    topMargin = dp(DRAWER_RULE_MARGIN_DP)
                    bottomMargin = dp(DRAWER_RULE_MARGIN_DP)
                }
            )
        }
        if (prefs.drawerSortButtons) {
            bar.addView(
                ImageView(context).apply {
                    setImageResource(R.drawable.glyph_sort)
                    scaleType = ImageView.ScaleType.CENTER
                    imageTintList = android.content.res.ColorStateList.valueOf(
                        androidx.core.graphics.ColorUtils.setAlphaComponent(resolved.text, TAB_TEXT_ALPHA)
                    )
                },
                LayoutParams(
                    dp(DRAWER_SORT_BUTTON_DP), dp(DRAWER_SORT_BUTTON_DP), Gravity.END or Gravity.CENTER_VERTICAL
                ).apply { marginEnd = dp(1) }
            )
        }
        return bar
    }

    private fun matchWidth() =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    // The drawer's padding settings: the sides pull the columns in, the
    // top and bottom hold the rows off the field (DrawerController).
    private fun applyDrawerPadding(prefs: LauncherPrefs) {
        val side = dp(prefs.drawerPaddingH)
        val end = dp(prefs.drawerPaddingV)
        drawerItems?.setPadding(side, end, side, end + dp(DRAWER_ROW_GAP_DP))
    }

    // The drawer's own column count: the one set, or as many automatic
    // cells as the width holds (DrawerController.columnCount).
    private fun drawerColumns(prefs: LauncherPrefs, iconScale: Float): Int {
        if (prefs.drawerColumns > 0) return prefs.drawerColumns
        val cell = (dp(DRAWER_MIN_CELL_DP) * iconScale.coerceAtLeast(1f)).toInt().coerceAtLeast(1)
        return (resources.displayMetrics.widthPixels / cell).coerceAtLeast(DRAWER_MIN_COLUMNS)
    }

    // A list-style row: the icon with its name beside it
    // (item_drawer_app_row.xml).
    private fun drawerListRow(icon: Drawable, label: CharSequence): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val side = dp(DRAWER_ROW_SIDE_DP)
            val pad = dp(DRAWER_LIST_PAD_V_DP)
            setPadding(side, pad, side, pad)
        }
        val image = PreviewIcon(context, dp(DRAWER_LIST_ICON_DP), keepInsideSlot = false)
        image.setImageDrawable(icon)
        listIconViews += image
        row.addView(
            image,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        val text = com.supernova.launcher.ui.FadingTextView(context)
        text.text = label
        text.maxLines = 1
        text.setPaddingRelative(dp(DRAWER_ROW_SIDE_DP), 0, 0, 0)
        labelViews += text
        row.addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun dominantColor(viewModel: LauncherViewModel, app: LaunchableApp): Int? =
        viewModel.appCatalog().dominantColorFor(app.userSerial, app.packageName, app.className)

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
        val text = com.supernova.launcher.ui.FadingTextView(context)
        text.text = label
        text.maxLines = labelLines
        text.gravity = Gravity.CENTER_HORIZONTAL
        // The real labels' sides (item_icon.xml, item_drawer_app.xml).
        text.setPadding(dp(2), 0, dp(2), 0)
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
            defaultRadius
        )
        surfaceBg = drawable
        return drawable
    }


    // The drawer's fast scrollbar at rest, the thumb at the top: its track
    // and thumb as FastScrollerView draws them.
    private class FastScrollPreview(context: Context, thumb: Int, track: Int) : View(context) {
        private val density = resources.displayMetrics.density
        private val trackPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = track }
        private val thumbPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = thumb }

        init {
            val pad = (TRACK_PAD_DP * density).toInt()
            setPadding(0, pad, 0, pad)
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            val top = paddingTop.toFloat()
            val bottom = (height - paddingBottom).toFloat()
            val thumbHeight = THUMB_HEIGHT_DP * density
            if (bottom - top < thumbHeight) return
            val centre = width - EDGE_INSET_DP * density
            val half = TRACK_WIDTH_DP * density / 2f
            canvas.drawRoundRect(centre - half, top, centre + half, bottom, half, half, trackPaint)
            val thumbHalf = half + THUMB_PAD_DP * density
            val bottomOfThumb = top + thumbHeight
            canvas.drawRoundRect(
                centre - thumbHalf, top, centre + thumbHalf, bottomOfThumb, thumbHalf, thumbHalf, thumbPaint
            )
        }

        private companion object {
            const val TRACK_PAD_DP = 12
            const val TRACK_WIDTH_DP = 6f
            const val THUMB_PAD_DP = 1f
            const val THUMB_HEIGHT_DP = 52f
            const val EDGE_INSET_DP = 3f
        }
    }

    // Lays its one child out at the width it asks for and, when that is
    // wider than the room here, draws it scaled down as a whole: a
    // miniature with the real proportions rather than a squeezed grid.
    // The frame keeps the child's unscaled height, so a change that only
    // widens it (more folder columns) never moves the list under the
    // finger tapping the columns bar.
    private class FitWidthFrame(context: Context, private val keepsHeight: Boolean = true) : FrameLayout(context) {
        init {
            clipChildren = false
            clipToPadding = false
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val child = getChildAt(0) ?: return super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            child.measure(unspecified, unspecified)
            val room = MeasureSpec.getSize(widthMeasureSpec)
            val fits = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED || child.measuredWidth <= room
            val scale = if (fits) 1f else room / child.measuredWidth.toFloat()
            child.pivotX = 0f
            child.pivotY = 0f
            child.scaleX = scale
            child.scaleY = scale
            val height = if (keepsHeight) child.measuredHeight else (child.measuredHeight * scale).toInt()
            setMeasuredDimension(
                resolveSize((child.measuredWidth * scale).toInt(), widthMeasureSpec),
                resolveSize(height, heightMeasureSpec)
            )
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val child = getChildAt(0) ?: return
            // Centred in whatever room this frame was given.
            val x = ((width - child.measuredWidth * child.scaleX) / 2f).toInt()
            val y = ((height - child.measuredHeight * child.scaleY) / 2f).toInt()
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
        }
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
        const val SEARCH_BAR_HEIGHT_DP = 48
        const val SEARCH_BAR_INSET_DP = 16
        const val SEARCH_BAR_PAD_DP = 28
        // The drawer field's start icon, hint and box, as the drawer has them.
        const val DRAWER_ICON_DP = 20
        const val DRAWER_ICON_GAP_DP = 12
        const val DRAWER_HINT_SP = 16f
        const val DRAWER_HINT_ALPHA = 0x8A
        const val DRAWER_STROKE_ALPHA = 0x5A
        const val DRAWER_BUTTON_DP = 36
        const val DRAWER_BUTTON_END_DP = 4
        // The drawer's search row padding (activity_launcher.xml), its
        // automatic cell and fewest columns (DrawerController), and the
        // list style's rows (item_drawer_app_row.xml).
        const val DRAWER_ROW_SIDE_DP = 16
        const val DRAWER_ROW_GAP_DP = 12
        const val DRAWER_MIN_CELL_DP = 72
        const val DRAWER_MIN_COLUMNS = 3
        const val DRAWER_LIST_ROWS = 3
        const val DRAWER_LIST_ICON_DP = 40
        const val DRAWER_LIST_PAD_V_DP = 8
        // The drawer's tab bar colours and bubble, its top row's rule, its
        // sort button and its fast scrollbar (DrawerController,
        // DrawerSortBars, activity_launcher.xml).
        const val TAB_TEXT_ALPHA = 0xB3
        const val TAB_BUBBLE_RADIUS_DP = 100
        const val TAB_BUBBLE_INSET_DP = 6
        const val TAB_BUBBLE_ALPHA = 0x3D
        const val DRAWER_RULE_ALPHA = 0x33
        const val DRAWER_RULE_WIDTH_DP = 72
        const val DRAWER_RULE_MARGIN_DP = 10
        const val DRAWER_SORT_BUTTON_DP = 48
        const val FAST_SCROLL_TRACK_ALPHA = 30
        const val FAST_SCROLL_WIDTH_DP = 32
        // The stand-in notification picture a Dynamic badge shows here, and
        // the count a Numeric one does.
        const val SAMPLE_FACE_PX = 96
        const val SAMPLE_COUNT = 3
        // The icon box item_icon.xml and item_drawer_app.xml declare, which
        // every surface's icon size scales, and the top of the icon size bars.
        const val ICON_BASE_DP = 48
        const val MAX_ICON_SCALE = 2f
        // A dock item's side padding (LauncherActivity.DOCK_ITEM_PADDING_H_DP).
        const val DOCK_ITEM_PADDING_H_DP = 2
        // The apps the closed icon shows, which the window's automatic
        // column count is worked out for.
        const val FOLDER_SAMPLE_APPS = 9
        // The window's padding, its name's padding and its shadow
        // (popup_folder.xml).
        const val FOLDER_CARD_PAD_DP = 12
        const val FOLDER_TITLE_PAD_DP = 8
        const val FOLDER_ELEVATION_DP = 8
        // bg_folder's built-in radius, the fallback the real window uses.
        const val DEFAULT_FOLDER_RADIUS_DP = 24f
        const val IMMERSIVE_STAGE_SCRIM = -0x4D000000
        const val SEARCH_DEMO_COLS = 5
        // The Home strip's height and the range of
        // desktop columns it mirrors, one icon per column.
        const val HOME_HEIGHT_DP = 144
        const val HOME_MIN_COLS = 3
        const val HOME_MAX_COLS = 7
        // A desktop icon's padding (item_icon.xml).
        const val HOME_ITEM_PADDING_DP = 4
        const val SEARCH_DEMO_QUERY = "cal"
        // The Home strip's edge scrims and page indicator, and the pages
        // the indicator counts.
        const val HOME_SCRIM_DP = 28
        const val HOME_INDICATOR_DP = 24
        const val HOME_PREVIEW_PAGES = 3
        // The dock's background padding, its item and slot padding, and
        // its margin from the sides and the navigation bar
        // (LauncherActivity, BottomChrome); a navigation bar to stand in
        // before there are insets.
        const val DOCK_BG_PADDING_V_DP = 8
        const val DOCK_ITEM_PADDING_V_DP = 4
        const val DOCK_SLOT_PADDING_V_DP = 2
        const val DOCK_SIDE_MARGIN_DP = 10
        const val DEFAULT_NAV_DP = 24
        // The folder window's app count (popup_folder.xml, FolderPopup).
        const val FOLDER_COUNT_ALPHA = 0xB3
        const val FOLDER_COUNT_PAD_DP = 4
        // Icons & labels: the icons beside the menu, and the menu's share
        // of the row.
        const val ICON_ROW_APPS = 3
        const val ICON_ROW_POPUP_WEIGHT = 1.2f
    }
}
