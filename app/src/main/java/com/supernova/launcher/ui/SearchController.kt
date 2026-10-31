package com.supernova.launcher.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.provider.ContactsContract
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.ImageViewCompat
import androidx.core.widget.doAfterTextChanged
import com.supernova.launcher.R
import com.supernova.launcher.data.LaunchableApp
import com.supernova.launcher.databinding.ActivityLauncherBinding
import com.supernova.launcher.databinding.ViewSearchOverlayBinding
import com.supernova.launcher.search.AppSearchProvider
import com.supernova.launcher.search.ContactSearchProvider
import com.supernova.launcher.search.MathSearchProvider
import com.supernova.launcher.search.SearchOrchestrator
import com.supernova.launcher.search.SearchResult
import com.supernova.launcher.search.ShortcutSearchProvider
import com.supernova.launcher.search.WebProvider
import com.supernova.launcher.search.SearchChip
import com.supernova.launcher.search.WebSearchProvider
import com.supernova.launcher.ui.theme.Styles
import com.supernova.launcher.ui.theme.SurfaceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// Owns the global search overlay: ViewStub-lazy inflation, the orchestrator
// and its providers, the results adapter, and IME choreography. Launch paths
// route back through LauncherActivity so reveal animations and launch counts
// apply exactly as they do everywhere else.
class SearchController(
    private val binding: ActivityLauncherBinding,
    private val viewModel: LauncherViewModel,
    private val scope: CoroutineScope,
    private val onLaunchApp: (View?, LaunchableApp) -> Unit,
    private val onLaunchShortcut: (View?, SearchResult.Shortcut) -> Unit,
    private val onOpenUri: (String) -> Unit,
    // The top hit's gear: the system App info page for the app.
    private val onAppInfo: (LaunchableApp) -> Unit = {},
    // Folder search (D3): cheap name hits, match-time icons, and the opener.
    private val folderHits: () -> List<com.supernova.launcher.search.FolderSearchProvider.Hit> = { emptyList() },
    private val folderIcon: (com.supernova.launcher.search.FolderSearchProvider.Hit) -> android.graphics.drawable.Drawable? = { null },
    private val onOpenFolder: (SearchResult.Folder) -> Unit = {},
    // Custom cards matched by name render as full cards; the activity builds
    // the binder so launches route (and hide the overlay) its way.
    private val cardBinder: CardViewBinder? = null,
    // The drawer's column count, which the app tiles share so they line up
    // with the drawer's grid in either orientation.
    private val drawerColumns: (() -> Int)? = null,
    // The overlay has closed.
    private val onHidden: () -> Unit = {},
    // Long-presses on an app, an app shortcut or a drawer folder in the
    // results, on either surface: the item's menu, and a drag to the home
    // screen.
    private val onAppLongPress: (View, LaunchableApp) -> Unit = { _, _ -> },
    private val onShortcutLongPress: (View, SearchResult.Shortcut) -> Unit = { _, _ -> },
    private val onFolderLongPress: (View, SearchResult.Folder) -> Unit = { _, _ -> }
) {

    private var overlay: ViewSearchOverlayBinding? = null
    private var overlaySurface: Surface? = null
    private var drawerSurface: Surface? = null
    private var appliedStyle: List<Any?>? = null
    private var suppressQueryEvents = false

    // One place the results render. The drawer search is the search
    // window, so there are two: the overlay, and the app drawer's own field
    // with a results list standing in for the drawer's.
    private inner class Surface(
        val input: android.widget.EditText,
        val ghost: android.widget.TextView,
        val results: androidx.recyclerview.widget.RecyclerView,
        val cards: CardViewBinder?,
        // After an app or shortcut launch.
        val afterLaunch: () -> Unit,
        // After anything else that leaves search behind (a web search, a
        // contact, a folder, App info).
        val dismiss: () -> Unit,
        // What an empty query shows.
        val onEmptyQuery: (Surface) -> Unit
    ) {
        var lastResults: List<SearchResult> = emptyList()
        var appliedStyle: List<Any?>? = null
        val orchestrator: SearchOrchestrator = buildOrchestrator(cards != null)
        val adapter: SearchResultsAdapter =
            SearchResultsAdapter(viewModel.appCatalog(), adapterCallbacks(this)).also { created ->
                cards?.let(created::setCardBinder)
                results.adapter = created
                results.itemAnimator = null
                results.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(results.context)
            }
    }

    // The app drawer's hand-off: it forwards its field's text and the IME's
    // search action, and shows the results list while a query is typed.
    interface DrawerSearch {
        fun query(text: String)

        fun submit()
    }

    fun attachDrawer(
        input: android.widget.EditText,
        ghost: android.widget.TextView,
        results: androidx.recyclerview.widget.RecyclerView,
        cards: CardViewBinder?,
        afterLaunch: () -> Unit,
        dismiss: () -> Unit
    ): DrawerSearch {
        val surface = Surface(
            input, ghost, results, cards, afterLaunch, dismiss,
            onEmptyQuery = { it.adapter.clear() }
        )
        drawerSurface = surface
        return object : DrawerSearch {
            override fun query(text: String) = onQueryChanged(surface, text)

            override fun submit() = onSearchAction(surface)
        }
    }

    // Shared between the immediate tier and the shortcut provider, so both
    // agree on which apps matched.
    private val appProvider = AppSearchProvider(
        apps = { viewModel.searchableApps() },
        launchCounts = { viewModel.launchCounts() }
    )

    var isOpen = false
        private set

    // A pinned recent search lands here: open as usual, then type the query in
    // so the results are already on screen.
    fun open(query: String) {
        open()
        val ui = ensureInflated()
        ui.searchInput.setText(query)
        ui.searchInput.setSelection(query.length)
    }

    fun open() {
        val ui = ensureInflated()
        applyStyle(ui)
        applyImeOptions(ui)
        if (isOpen) {
            ui.searchInput.requestFocus()
            return
        }
        isOpen = true
        showZeroState()
        ui.root.visibility = View.VISIBLE
        ui.root.animate().cancel()
        val backdrop = binding.searchBackdrop
        backdrop.animate().cancel()
        backdrop.visibility = if (windowMode()) View.VISIBLE else View.GONE
        if (Animations.enabled(viewModel.state.value.prefs)) {
            val duration = Animations.duration(viewModel.state.value.prefs, 160L)
            ui.root.alpha = 0f
            ui.root.translationY = binding.root.dp(24f)
            ui.root.animate()
                .alpha(1f).translationY(0f)
                .setDuration(duration)
                .setInterpolator(DecelerateInterpolator())
                .start()
            backdrop.alpha = 0f
            backdrop.animate().alpha(1f).setDuration(duration).start()
        } else {
            ui.root.alpha = 1f
            ui.root.translationY = 0f
            backdrop.alpha = 1f
        }
        ui.searchInput.requestFocus()
        ui.searchInput.post {
            imm()?.showSoftInput(ui.searchInput, 0)
        }
    }

    fun hide() {
        if (!isOpen) return
        isOpen = false
        onHidden()
        val ui = overlay ?: return
        overlaySurface?.orchestrator?.cancel()
        suppressQueryEvents = true
        ui.searchInput.text?.clear()
        suppressQueryEvents = false
        ui.searchClear.visibility = View.GONE
        ui.searchGhost.text = null
        ui.searchInput.clearFocus()
        imm()?.hideSoftInputFromWindow(ui.searchInput.windowToken, 0)
        ui.root.animate().cancel()
        val backdrop = binding.searchBackdrop
        backdrop.animate().cancel()
        if (Animations.enabled(viewModel.state.value.prefs)) {
            val duration = Animations.duration(viewModel.state.value.prefs, 140L)
            ui.root.animate()
                .alpha(0f).translationY(binding.root.dp(16f))
                .setDuration(duration)
                .withEndAction { if (!isOpen) ui.root.visibility = View.GONE }
                .start()
            if (backdrop.visibility == View.VISIBLE) {
                backdrop.animate().alpha(0f).setDuration(duration)
                    .withEndAction { if (!isOpen) backdrop.visibility = View.GONE }
                    .start()
            }
        } else {
            ui.root.alpha = 0f
            ui.root.visibility = View.GONE
            backdrop.visibility = View.GONE
        }
    }

    private fun windowMode(): Boolean =
        viewModel.state.value.prefs.searchWindowMode.equals("WINDOW", ignoreCase = true)

    // The window rotated or was resized. The tiles' columns follow the
    // drawer's, which follow the width, and a query's rows were cut to the
    // old count, so a surface showing results runs its query again.
    fun onWindowResized() {
        overlaySurface?.appliedStyle = null
        drawerSurface?.appliedStyle = null
        refresh()
    }

    // What the results were made from has changed - an app hidden, renamed
    // or installed, a drawer folder renamed or refilled, often from one of
    // their own menus: a surface showing results runs its query again. Left
    // as they were, a renamed folder's row opened nothing (it goes by name).
    fun refresh() {
        overlaySurface?.let { surface ->
            if (isOpen) onQueryChanged(surface, surface.input.text?.toString().orEmpty())
        }
        drawerSurface?.let { surface ->
            val query = surface.input.text?.toString().orEmpty()
            if (query.isNotBlank()) onQueryChanged(surface, query)
        }
    }

    // Wallpaper-palette changes arrive without a prefs emission; drop the
    // guard so the next open re-resolves the surface.
    fun invalidateStyle() {
        appliedStyle = null
        overlaySurface?.appliedStyle = null
        drawerSurface?.appliedStyle = null
        overlay?.let(::applyStyle)
    }

    private fun ensureInflated(): ViewSearchOverlayBinding {
        overlay?.let { return it }
        val root = binding.searchStub.inflate()
        val ui = ViewSearchOverlayBinding.bind(root)
        overlay = ui
        root.visibility = View.GONE
        // The floating window's surroundings: a tap there closes search,
        // where before it reached the icons and dock left showing.
        binding.searchBackdrop.setOnClickListener { hide() }

        // The window is adjustNothing and edge-to-edge, so the panel pads
        // itself out of the system bars; the IME is allowed to overlap, as it
        // is in the drawer.
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            // The panel keeps its surface out to the window edges; the field
            // and the results sit in a centred column on a wide screen.
            val gutter = WideScreen.gutterPx(
                view.context, view.resources.displayMetrics.widthPixels - bars.left - bars.right
            )
            view.setPadding(bars.left + gutter, bars.top, bars.right + gutter, 0)
            val density = view.resources.displayMetrics.density
            ui.searchResults.setPadding(0, 0, 0, (24 * density).toInt() + bars.bottom)
            insets
        }
        root.requestApplyInsets()

        val surface = Surface(
            ui.searchInput, ui.searchGhost, ui.searchResults, cardBinder,
            afterLaunch = ::hide, dismiss = ::hide,
            onEmptyQuery = { showZeroState() }
        )
        overlaySurface = surface

        ui.searchClear.setOnClickListener { ui.searchInput.text?.clear() }
        ui.searchInput.doAfterTextChanged { text ->
            if (suppressQueryEvents) return@doAfterTextChanged
            val query = text?.toString().orEmpty()
            ui.searchClear.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
            onQueryChanged(surface, query)
        }
        ui.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                onSearchAction(surface)
                true
            } else {
                false
            }
        }
        return ui
    }

    private fun buildOrchestrator(hasCards: Boolean): SearchOrchestrator {
        val context = binding.root.context
        val immediate = listOf(
            MathSearchProvider(),
            appProvider,
            com.supernova.launcher.search.FolderSearchProvider(folderHits, folderIcon),
            com.supernova.launcher.search.CardSearchProvider { viewModel.feedCards() },
            WebSearchProvider(
                provider = { WebProvider.fromName(viewModel.state.value.prefs.searchWebProvider) },
                chips = { SearchChip.decode(viewModel.state.value.prefs.searchChips) }
            )
        )
        val async = listOf(
            ShortcutSearchProvider(
                context = context,
                matchedApps = { query ->
                    appProvider.search(query).mapNotNull { (it as? SearchResult.App)?.app }
                },
                userForSerial = { viewModel.appCatalog().userForSerial(it) }
            ),
            ContactSearchProvider(context.applicationContext) {
                viewModel.state.value.prefs.searchContactCards
            }
        )
        return SearchOrchestrator(
            immediateProviders = immediate,
            asyncProviders = async,
            scope = scope,
            isEnabled = { id -> providerEnabled(id, hasCards) }
        )
    }

    private fun providerEnabled(id: String, hasCards: Boolean): Boolean {
        val prefs = viewModel.state.value.prefs
        return when (id) {
            MathSearchProvider.ID -> prefs.searchCalculator
            ShortcutSearchProvider.ID -> prefs.searchShortcuts
            com.supernova.launcher.search.FolderSearchProvider.ID -> prefs.searchFolders
            com.supernova.launcher.search.CardSearchProvider.ID -> prefs.searchCards && hasCards
            ContactSearchProvider.ID -> prefs.searchContacts
            else -> true
        }
    }

    private fun onQueryChanged(surface: Surface, query: String) {
        if (query.isBlank()) {
            surface.orchestrator.cancel()
            surface.lastResults = emptyList()
            surface.ghost.text = null
            surface.onEmptyQuery(surface)
        } else {
            styleSurface(surface)
            surface.orchestrator.search(query) { results ->
                surface.lastResults = results
                surface.adapter.submitResults(
                    results, query.trim(), topHit(results, query.trim()),
                    viewModel.state.value.prefs.searchAppsOneRow
                )
                showCompletion(surface, query, results)
            }
        }
    }

    // The list's colors and column count, per surface and only when the
    // style actually changed: the plate's text and accent, and the POPUP
    // surface for cards (custom and the top hit), as in the feed.
    private fun styleSurface(surface: Surface) {
        val prefs = viewModel.state.value.prefs
        val columns = gridColumns()
        val key = listOf(
            prefs.styleConfig, prefs.paletteSource, prefs.paletteSeed, prefs.accentColor,
            prefs.labelSize, prefs.labelColor, prefs.labelFont, prefs.labelConfig, columns
        )
        if (key == surface.appliedStyle) return
        surface.appliedStyle = key
        val styles = Styles.resolve(binding.root.context, prefs)
        val plate = styles.of(SurfaceId.DRAWER)
        val popup = styles.of(SurfaceId.POPUP)
        surface.adapter.columns = columns
        surface.adapter.setAppearance(prefs, plate.accent, plate.text, popup.surface, popup.text)
        surface.cards?.setAppearance(popup.surface, popup.text, popup.accent)
    }

    // Inline completion: when the best match starts with what was
    // typed, its remaining letters appear after the caret, dimmed. Enter
    // already launches that app. The ghost keeps the typed letters as
    // transparent text so the remainder lands exactly where the field's
    // own text ends; it stays hidden when the line would not fit, since the
    // field would scroll and the ghost would not.
    private fun showCompletion(surface: Surface, query: String, results: List<SearchResult>) {
        val ghost = surface.ghost
        val app = (results.firstOrNull { it is SearchResult.App } as? SearchResult.App)?.app
        val label = app?.label
        if (query.isEmpty() || label == null || label.length <= query.length ||
            !label.startsWith(query, ignoreCase = true)
        ) {
            ghost.text = null
            return
        }
        val full = query + label.substring(query.length)
        val available = ghost.width - ghost.paddingLeft - ghost.paddingRight
        if (available > 0 && ghost.paint.measureText(full) > available) {
            ghost.text = null
            return
        }
        ghost.text = android.text.SpannableString(full).apply {
            setSpan(
                android.text.style.ForegroundColorSpan(android.graphics.Color.TRANSPARENT),
                0, query.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    // Top hit: once the query is a few letters and the best match
    // starts with it, that app becomes a card carrying its own shortcuts.
    private fun topHit(results: List<SearchResult>, query: String): SearchResultsAdapter.TopHit? {
        if (query.length < TOP_HIT_MIN_CHARS) return null
        val app = (results.firstOrNull { it is SearchResult.App } as? SearchResult.App)?.app ?: return null
        if (!app.label.startsWith(query, ignoreCase = true)) return null
        val catalog = viewModel.appCatalog()
        val shortcuts = catalog.shortcutsFor(app.packageName, app.userSerial)
            .take(TOP_HIT_MAX_SHORTCUTS)
            .map { shortcut ->
                SearchResult.Shortcut(
                    packageName = app.packageName,
                    shortcutId = shortcut.id,
                    label = (shortcut.shortLabel ?: shortcut.longLabel ?: shortcut.id).toString(),
                    userSerial = app.userSerial,
                    icon = catalog.shortcutIcon(shortcut)
                )
            }
        return SearchResultsAdapter.TopHit(app, shortcuts)
    }

    // The app tiles follow the drawer's column setting; automatic means as
    // many 72dp cells as fit.
    private fun gridColumns(): Int {
        drawerColumns?.let { return it().coerceAtLeast(1) }
        val prefs = viewModel.state.value.prefs
        if (prefs.drawerColumns > 0) return prefs.drawerColumns
        val metrics = binding.root.resources.displayMetrics
        return (metrics.widthPixels / (72 * metrics.density)).toInt().coerceIn(3, 8)
    }

    // Enter launches the top app match when there is one, else falls through
    // to a web search - the drawer's enter-to-launch feel, extended.
    private fun onSearchAction(surface: Surface) {
        val query = surface.input.text?.toString().orEmpty().trim()
        if (query.isEmpty()) return
        val firstApp = surface.lastResults.firstOrNull { it is SearchResult.App } as? SearchResult.App
        if (firstApp != null) {
            onLaunchApp(null, firstApp.app)
            surface.afterLaunch()
        } else {
            val provider = WebProvider.fromName(viewModel.state.value.prefs.searchWebProvider)
            onOpenUri(provider.searchUrl(query))
            surface.dismiss()
        }
    }

    private fun adapterCallbacks(surface: Surface) = SearchResultsAdapter.Callbacks(
        onApp = { view, app ->
            onLaunchApp(view, app)
            surface.afterLaunch()
        },
        onShortcut = { view, shortcut ->
            onLaunchShortcut(view, shortcut)
            surface.afterLaunch()
        },
        onContact = { contact ->
            openContact(contact)
            surface.dismiss()
        },
        onContactCall = { number ->
            runCatching {
                binding.root.context.startActivity(
                    Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:$number"))
                )
            }
            surface.dismiss()
        },
        onContactMessage = { number ->
            runCatching {
                binding.root.context.startActivity(
                    Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:$number"))
                )
            }
            surface.dismiss()
        },
        onChip = { chip, query ->
            onOpenUri(chip.url(query))
            surface.dismiss()
        },
        onWeb = { web ->
            onOpenUri(web.provider.searchUrl(web.query))
            surface.dismiss()
        },
        onCopyCalculation = ::copyToClipboard,
        onFrequentApp = { view, app ->
            onLaunchApp(view, app)
            surface.afterLaunch()
        },
        onFolder = { folder ->
            onOpenFolder(folder)
            surface.dismiss()
        },
        onAppInfo = { app ->
            onAppInfo(app)
            surface.dismiss()
        },
        onAppLongPress = onAppLongPress,
        onShortcutLongPress = onShortcutLongPress,
        onFolderLongPress = onFolderLongPress
    )

    // The empty search window: suggested shortcuts, the recently opened
    // apps, then - under a hairline - the drawer's folders and every app
    // that isn't in one.
    private fun showZeroState() {
        val frequent = viewModel.recentApps(gridColumns())
        val shortcuts = SuggestedShortcuts.pills(viewModel)
        // Folder members live inside their folder, as in the drawer.
        val groups = viewModel.drawerFolderGroups().filter { viewModel.appsInGroup(it).isNotEmpty() }
        val inFolders = groups.filter { viewModel.leavesMainList(it) }.flatMapTo(HashSet()) { it.members }
        val folders = groups.map { group ->
            SearchResult.Folder(
                itemId = null, groupName = group.name, label = group.name,
                icon = folderIcon(com.supernova.launcher.search.FolderSearchProvider.Hit(null, group.name, group.name))
            )
        }
        val allApps = com.supernova.launcher.data.LabelOrder.sorted(
            viewModel.visibleApps().filterNot { viewModel.hiddenKey(it) in inFolders }
        ) { it.label }
        val surface = overlaySurface ?: return
        styleSurface(surface)
        surface.adapter.submitZeroState(shortcuts, frequent, allApps, folders)
    }

    private fun openContact(contact: SearchResult.Contact) {
        val uri = ContactsContract.Contacts.getLookupUri(contact.contactId, contact.lookupKey)
        runCatching {
            binding.root.context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        }
    }

    private fun copyToClipboard(value: String) {
        val context = binding.root.context
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("calculation", value))
        Toast.makeText(context, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
    }

    private fun applyStyle(ui: ViewSearchOverlayBinding) {
        val prefs = viewModel.state.value.prefs
        val key = listOf(
            prefs.styleConfig, prefs.paletteSource, prefs.paletteSeed, prefs.accentColor,
            prefs.labelSize, prefs.labelColor, prefs.labelFont, prefs.searchWindowMode
        )
        if (key == appliedStyle) return
        appliedStyle = key
        val styles = Styles.resolve(binding.root.context, prefs)
        // Search window: the plate wears the drawer's surface and the
        // pill on it the search bar's, so the two read as bar-on-drawer the
        // way the drawer's own field does. A style-picker alpha below 1.0 is
        // a deliberate user choice and passes through.
        // WINDOW mode (D2) floats the panel as a rounded inset card instead
        // of the full-screen sheet.
        val resolved = styles.of(SurfaceId.DRAWER)
        val bar = styles.of(SurfaceId.SEARCH_BAR)
        val windowMode = windowMode()
        val params = ui.root.layoutParams as? android.view.ViewGroup.MarginLayoutParams
        if (params != null) {
            val side = if (windowMode) binding.root.dp(14f).toInt() else 0
            params.leftMargin = side
            params.rightMargin = side
            params.topMargin = if (windowMode) binding.root.dp(48f).toInt() else 0
            params.bottomMargin = if (windowMode) binding.root.dp(160f).toInt() else 0
            ui.root.layoutParams = params
        }
        if (windowMode) {
            ui.root.background = com.supernova.launcher.ui.theme.SurfaceShapes.background(
                resolved.surface, null, null, binding.root.dp(24f)
            )
            ui.root.clipToOutline = true
        } else {
            ui.root.setBackgroundColor(resolved.surface)
            ui.root.clipToOutline = false
        }
        ui.searchPill.background = com.supernova.launcher.ui.theme.SurfaceShapes.background(
            bar.surface, null, null, binding.root.dp(24f)
        )
        ui.searchInput.setTextColor(bar.text)
        ui.searchInput.setHintTextColor((0x99 shl 24) or (bar.text and 0xFFFFFF))
        ui.searchGhost.setTextColor((0x80 shl 24) or (bar.text and 0xFFFFFF))
        ui.searchGhost.typeface = ui.searchInput.typeface
        val iconTint = ColorStateList.valueOf(bar.text)
        ImageViewCompat.setImageTintList(ui.searchIcon, iconTint)
        ImageViewCompat.setImageTintList(ui.searchClear, iconTint)
        overlaySurface?.let { surface ->
            surface.appliedStyle = null
            styleSurface(surface)
        }
    }

    // IME_FLAG_NO_PERSONALIZED_LEARNING is applied before focus, so the flag
    // is present when the input connection starts.
    private fun applyImeOptions(ui: ViewSearchOverlayBinding) {
        val privateKeyboard = viewModel.state.value.prefs.searchPrivateKeyboard
        val target = EditorInfo.IME_ACTION_SEARCH or
            (if (privateKeyboard) EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING else 0)
        if (ui.searchInput.imeOptions != target) {
            ui.searchInput.imeOptions = target
        }
    }

    private fun imm(): InputMethodManager? =
        binding.root.context.getSystemService(InputMethodManager::class.java)


    private companion object {
        const val TOP_HIT_MIN_CHARS = 3
        const val TOP_HIT_MAX_SHORTCUTS = 6
    }
}
