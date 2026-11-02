package com.supernova.launcher.onboarding

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.supernova.launcher.appGraph
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.supernova.launcher.HomeRole
import com.supernova.launcher.R
import com.supernova.launcher.data.IconShape
import com.supernova.launcher.databinding.ActivityOnboardingBinding
import com.supernova.launcher.databinding.OnboardingPageDoneBinding
import com.supernova.launcher.databinding.OnboardingPageIconsBinding
import com.supernova.launcher.databinding.OnboardingPageRowsBinding
import com.supernova.launcher.databinding.OnboardingPageWelcomeBinding
import com.supernova.launcher.databinding.OnboardingRowDropdownBinding
import com.supernova.launcher.databinding.OnboardingRowSwitchBinding
import com.supernova.launcher.databinding.OnboardingShapeTileBinding
import com.supernova.launcher.ui.ChoiceMenu
import com.supernova.launcher.ui.DrawerBarPlacement
import com.supernova.launcher.ui.DrawerStyle
import com.supernova.launcher.ui.LauncherViewModel
import com.supernova.launcher.ui.SearchBarConfig
import com.supernova.launcher.ui.SearchBarPlacement
import com.supernova.launcher.ui.WideScreen
import com.supernova.launcher.ui.settings.EnumLabels
import com.supernova.launcher.ui.theme.AccentColor
import com.supernova.launcher.ui.theme.Styles
import com.supernova.launcher.ui.theme.SurfaceId
import com.supernova.launcher.ui.theme.ThemeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// The first-run wizard: a welcome page with Get started, Restore backup and
// Skip setup; Search, App drawer and Icon style pages holding the settings a
// new home screen most wants, each applied the moment it changes; and All
// done with Go home. Get started and Go home both ask to become the default
// home app.
//
// The fresh-install home screen is planted as the wizard opens, so it is
// there under the first run and the pages show the values the home screen
// really has. Finishing, skipping or restoring a backup writes
// onboarding_done and homePromptShown together, so the launcher's own home
// prompt never asks again. Settings' "Quick start" re-runs it with
// EXTRA_RERUN: the same pages over the current setup, back to Settings at the
// end.
class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding
    private val viewModel: LauncherViewModel by viewModels { LauncherViewModel.Factory(appGraph) }
    private val isRerun by lazy { intent.getBooleanExtra(EXTRA_RERUN, false) }
    private val pages = PagesAdapter()
    private var seedJob: Job? = null
    private var appliedAccent = ""

    // What Get started or Go home does once the default-home prompt answers.
    private var afterHomeRole: (() -> Unit)? = null

    // The Contact Search switch waiting on the permission it asked for.
    private var contactSwitch: MaterialSwitch? = null

    private val importBackupLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            val app = applicationContext
            lifecycleScope.launch {
                // Seen through even if this screen is recreated meanwhile (a
                // rotation, a dark-mode switch while a cloud file downloads):
                // cancelled, the import reported itself failed and restored
                // nothing. Off the main thread: a file in a cloud drive
                // downloads as it is opened, and a slow network was an ANR.
                val outcome = withContext(NonCancellable) {
                    runCatching {
                        val raw = withContext(Dispatchers.IO) {
                            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                        }
                        viewModel.importBackup(raw)
                    }
                }
                outcome.onSuccess { result ->
                    Toast.makeText(app, result.summary(app.resources), Toast.LENGTH_LONG).show()
                    // The backup brought its own home screen and settings:
                    // nothing is left to set up.
                    if (!isDestroyed) finishOnboarding()
                }.onFailure {
                    Toast.makeText(
                        app, app.getString(R.string.backup_import_failed, it.message ?: "unknown"), Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

    private val requestHomeRole =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val then = afterHomeRole
            afterHomeRole = null
            then?.invoke()
        }

    private val requestContacts =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            viewModel.setSearchContacts(granted)
            contactSwitch?.isChecked = granted
            contactSwitch = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appliedAccent = viewModel.state.value.prefs.accentColor
        ThemeEngine.applyAccent(this, AccentColor.fromName(appliedAccent))
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Edge to edge over the wallpaper: light system bar
        // icons on the scrim, and the pages clear of both bars.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams
                .LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            // A centred column on a tablet rather than rows stretched across
            // the whole window.
            val gutter = WideScreen.gutterPx(
                view.context, view.resources.displayMetrics.widthPixels - bars.left - bars.right
            )
            view.setPadding(bars.left + gutter, bars.top, bars.right + gutter, bars.bottom)
            insets
        }

        binding.onboardingPager.adapter = pages
        // The pages move on the buttons, not on a swipe.
        binding.onboardingPager.isUserInputEnabled = false
        binding.onboardingPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = updateChrome(position)
        })
        binding.onboardingBack.setOnClickListener { goTo(binding.onboardingPager.currentItem - 1) }
        binding.onboardingNext.setOnClickListener { goTo(binding.onboardingPager.currentItem + 1) }
        onBackPressedDispatcher.addCallback(this) {
            val page = binding.onboardingPager.currentItem
            when {
                page > PAGE_WELCOME -> goTo(page - 1)
                isRerun -> finish()
                // The first run can't be backed out of either; this points
                // at the way to skip it.
                else -> Toast.makeText(this@OnboardingActivity, R.string.onboarding_skip_hint, Toast.LENGTH_SHORT).show()
            }
        }
        updateChrome(binding.onboardingPager.currentItem)

        // The pages read the settings as they bind: once the stored ones have
        // loaded, bind again with them. The accent is the theme's, so a
        // stored one the screen didn't start with makes it again, as
        // Settings does; the view model, loaded, carries over.
        lifecycleScope.launch {
            val loaded = viewModel.state.first { it.loaded }
            if (loaded.prefs.accentColor != appliedAccent) {
                recreate()
                return@launch
            }
            pages.notifyItemRangeChanged(0, PAGE_COUNT)
        }
        if (!isRerun) {
            seedJob = lifecycleScope.launch {
                withContext(NonCancellable) { viewModel.seedDefaultLayout() }
                // The seed placed the search bar; show where.
                pages.notifyItemChanged(PAGE_SEARCH)
            }
        }
    }

    private fun goTo(page: Int) {
        if (page !in 0 until PAGE_COUNT) return
        binding.onboardingPager.setCurrentItem(page, true)
    }

    private fun updateChrome(position: Int) {
        binding.onboardingTopBar.visibility = if (position == PAGE_WELCOME) View.INVISIBLE else View.VISIBLE
        binding.onboardingNext.visibility =
            if (position == PAGE_WELCOME || position == PAGE_DONE) View.GONE else View.VISIBLE
        // A quarter a page.
        binding.onboardingProgress.setProgressCompat(position, true)
    }

    // Asks to become the default home app on Get started and again on
    // Go home, and carries on whatever the answer.
    private fun askHomeRoleThen(then: () -> Unit) {
        val request = if (HomeRole.isNeeded(this)) HomeRole.requestIntent(this) else null
        if (request == null) {
            then()
            return
        }
        afterHomeRole = then
        runCatching { requestHomeRole.launch(request) }.onFailure {
            afterHomeRole = null
            then()
        }
    }

    private fun finishOnboarding() {
        if (isRerun) {
            finish()
            return
        }
        lifecycleScope.launch {
            // Seen through even if the screen goes part way (Back, a
            // rotation): the gate flags are written in one edit, and a finish
            // cut short left the wizard to come back over a set-up screen.
            withContext(NonCancellable) {
                seedJob?.join()
                // A no-op once planted; covers a screen recreated before the
                // seed ran.
                viewModel.seedDefaultLayout()
                viewModel.markOnboardingComplete()
            }
            finish()
        }
    }

    // Welcome ------------------------------------------------------------

    private fun bindWelcome(view: View) {
        val page = OnboardingPageWelcomeBinding.bind(view)
        page.welcomeGetStarted.setOnClickListener {
            askHomeRoleThen { goTo(PAGE_SEARCH) }
        }
        page.welcomeRestore.setOnClickListener {
            importBackupLauncher.launch(arrayOf("application/json", "text/plain"))
        }
        page.welcomeSkip.setOnClickListener { finishOnboarding() }
    }

    // Search -------------------------------------------------------------

    private fun bindSearch(view: View) {
        val rows = pageRows(view, R.string.onboarding_search)
        val prefs = viewModel.state.value.prefs
        val modes = listOf("WINDOW", "IMMERSIVE")
        addDropdown(
            rows, R.string.onboarding_mode,
            listOf(getString(R.string.search_window_window), getString(R.string.search_window_immersive)),
            modes.indexOfFirst { it.equals(prefs.searchWindowMode, ignoreCase = true) }.coerceAtLeast(0)
        ) { viewModel.setSearchWindowMode(modes[it]) }
        addSwitch(
            rows, R.string.onboarding_shortcut_search, R.string.onboarding_shortcut_search_summary,
            prefs.searchShortcuts
        ) { checked, _ -> viewModel.setSearchShortcuts(checked) }
        addSwitch(
            rows, R.string.onboarding_contact_search, R.string.onboarding_contact_search_summary,
            prefs.searchContacts && contactsGranted()
        ) { checked, switch ->
            when {
                !checked -> viewModel.setSearchContacts(false)
                contactsGranted() -> viewModel.setSearchContacts(true)
                else -> {
                    // On once the permission comes back granted.
                    switch.isChecked = false
                    contactSwitch = switch
                    requestContacts.launch(Manifest.permission.READ_CONTACTS)
                }
            }
        }

        val config = SearchBarConfig.decode(prefs.searchBarConfig)
        addHeader(rows, R.string.onboarding_home_screen)
        val placements = listOf(
            SearchBarPlacement.NONE, SearchBarPlacement.DOCK_ABOVE,
            SearchBarPlacement.DOCK_BELOW, SearchBarPlacement.PERSISTENT
        )
        addDropdown(
            rows, R.string.onboarding_search_bar_placement,
            listOf(
                getString(R.string.onboarding_placement_none), getString(R.string.onboarding_placement_dock_above),
                getString(R.string.onboarding_placement_dock_below), getString(R.string.onboarding_placement_persistent)
            ),
            placements.indexOf(SearchBarPlacement.fromName(config.desktop.placement)).coerceAtLeast(0)
        ) { index ->
            val current = SearchBarConfig.decode(viewModel.state.value.prefs.searchBarConfig)
            viewModel.setSearchBarConfig(
                current.copy(desktop = current.desktop.copy(placement = placements[index].name)).encode()
            )
        }

        addHeader(rows, R.string.onboarding_app_drawer)
        val drawerPlacements = listOf(DrawerBarPlacement.HIDDEN, DrawerBarPlacement.TOP, DrawerBarPlacement.BOTTOM)
        addDropdown(
            rows, R.string.onboarding_search_bar_placement,
            listOf(
                getString(R.string.onboarding_placement_none), getString(R.string.onboarding_drawer_bar_top),
                getString(R.string.onboarding_drawer_bar_bottom)
            ),
            drawerPlacements.indexOf(DrawerBarPlacement.fromConfig(config.drawer)).coerceAtLeast(0)
        ) { index ->
            val placement = drawerPlacements[index]
            val current = SearchBarConfig.decode(viewModel.state.value.prefs.searchBarConfig)
            viewModel.setSearchBarConfig(
                current.copy(
                    drawer = current.drawer.copy(
                        placement = placement.name,
                        enabled = placement != DrawerBarPlacement.HIDDEN
                    )
                ).encode()
            )
        }
    }

    private fun contactsGranted(): Boolean =
        checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    // App drawer ---------------------------------------------------------

    private fun bindDrawer(view: View) {
        val rows = pageRows(view, R.string.onboarding_app_drawer)
        val prefs = viewModel.state.value.prefs
        val styles = listOf(DrawerStyle.VERTICAL_GRID, DrawerStyle.VERTICAL_LIST, DrawerStyle.PAGED_GRID)
        addDropdown(
            rows, R.string.onboarding_drawer_style,
            listOf(
                getString(R.string.onboarding_drawer_vertical), getString(R.string.onboarding_drawer_list),
                getString(R.string.onboarding_drawer_horizontal)
            ),
            styles.indexOf(DrawerStyle.fromName(prefs.drawerStyle)).coerceAtLeast(0)
        ) { viewModel.setDrawerStyle(styles[it].name) }
        addSwitch(
            rows, R.string.onboarding_show_keyboard, R.string.onboarding_show_keyboard_summary,
            prefs.drawerAutoKeyboard
        ) { checked, _ -> viewModel.setDrawerAutoKeyboard(checked) }
        addSwitch(
            rows, R.string.onboarding_frequent_apps, R.string.onboarding_frequent_apps_summary,
            prefs.drawerFrequentRow
        ) { checked, _ -> viewModel.setDrawerFrequentRow(checked) }
        addSwitch(
            rows, R.string.onboarding_smart_folders, R.string.onboarding_smart_folders_summary,
            prefs.smartFolders
        ) { checked, _ -> viewModel.setSmartFolders(checked) }
    }

    // Icon style ---------------------------------------------------------

    private fun bindIcons(view: View) {
        val page = OnboardingPageIconsBinding.bind(view)
        val prefs = viewModel.state.value.prefs
        var scale = prefs.iconScale
        var shape = IconShape.fromName(prefs.iconShape)
        val accent = ThemeEngine.accentColor(this, AccentColor.fromName(prefs.accentColor))

        // Four sample icons, in the shape and at the size chosen.
        val preview = page.iconPreviewRow
        preview.removeAllViews()
        val samples = PREVIEW_COLORS.map { color ->
            ShapeSwatchView(this).also { swatch ->
                swatch.color = color
                preview.addView(swatch, LinearLayout.LayoutParams(0, 0).apply {
                    marginStart = dp(PREVIEW_GAP_DP / 2)
                    marginEnd = dp(PREVIEW_GAP_DP / 2)
                })
            }
        }
        fun renderPreview() {
            val size = (dp(PREVIEW_BASE_DP) * scale).roundToInt()
            samples.forEach { swatch ->
                swatch.shape = shape
                swatch.layoutParams = swatch.layoutParams.apply { width = size; height = size }
            }
        }

        // Icon size: the settings slider's range and steps.
        val value = page.iconSizeValue
        val slider = page.iconSizeSlider
        fun percent() = ((scale * 100f).roundToInt() / SIZE_STEP * SIZE_STEP).coerceIn(SIZE_MIN, SIZE_MAX)
        fun renderSize() {
            value.text = getString(R.string.opacity_percent, percent())
            slider.value = percent().toFloat()
        }
        fun setSize(percent: Int) {
            val clamped = percent.coerceIn(SIZE_MIN, SIZE_MAX)
            scale = clamped / 100f
            viewModel.setIconScale(scale)
            renderSize()
            renderPreview()
        }
        slider.clearOnChangeListeners()
        slider.addOnChangeListener { _, newValue, fromUser ->
            if (fromUser) setSize(newValue.roundToInt())
        }
        page.iconSizeDown.setOnClickListener { setSize(percent() - SIZE_STEP) }
        page.iconSizeUp.setOnClickListener { setSize(percent() + SIZE_STEP) }

        // The shapes: the first five, then More for the rest.
        val grid = page.iconShapeRows
        grid.removeAllViews()
        val tiles = mutableListOf<Pair<OnboardingShapeTileBinding, (IconShape) -> Boolean>>()
        lateinit var renderTiles: () -> Unit
        fun pick(chosen: IconShape) {
            shape = chosen
            viewModel.setIconShape(chosen)
            renderPreview()
            renderTiles()
        }
        fun tile(row: LinearLayout, label: CharSequence, drawn: IconShape, more: Boolean, isSelected: (IconShape) -> Boolean, onTap: () -> Unit) {
            val tile = OnboardingShapeTileBinding.inflate(LayoutInflater.from(this), row, false)
            tile.tileShape.shape = drawn
            tile.tileShape.more = more
            tile.tileLabel.text = label
            tile.root.contentDescription = label
            tile.root.setOnClickListener { onTap() }
            row.addView(tile.root)
            tiles += tile to isSelected
        }
        fun shapeRow(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            weightSum = SHAPES_PER_ROW.toFloat()
            grid.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val first = shapeRow()
        MAIN_SHAPES.forEach { (candidate, labelRes) ->
            tile(first, getString(labelRes), candidate, false, { it == candidate }) { pick(candidate) }
        }
        val second = shapeRow()
        tile(second, getString(R.string.shape_flower), IconShape.FLOWER, false, { it == IconShape.FLOWER }) {
            pick(IconShape.FLOWER)
        }
        tile(second, getString(R.string.onboarding_shape_more), IconShape.HEXAGON, true, { it in MORE_SHAPES }) {
            showMoreShapes(accent, { shape }) { pick(it) }
        }
        renderTiles = {
            tiles.forEach { (tile, isSelected) ->
                val selected = isSelected(shape)
                tile.root.isSelected = selected
                tile.tileShape.color = if (selected) accent else SHAPE_GRAY
                tile.tileLabel.setTextColor(if (selected) accent else Color.WHITE)
            }
        }

        renderSize()
        renderPreview()
        renderTiles()
    }

    // The "More shapes" card: the rest of the shapes, four to a row.
    private fun showMoreShapes(accent: Int, current: () -> IconShape, onPick: (IconShape) -> Unit) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.onboarding_more_shapes)
            .setView(content)
            .create()
        MORE_SHAPES.chunked(SHAPES_PER_ROW).forEach { chunk ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_HORIZONTAL
                weightSum = SHAPES_PER_ROW.toFloat()
            }
            chunk.forEach { candidate ->
                val tile = OnboardingShapeTileBinding.inflate(LayoutInflater.from(this), row, false)
                val selected = candidate == current()
                tile.root.isSelected = selected
                tile.tileShape.apply {
                    shape = candidate
                    color = if (selected) accent else SHAPE_GRAY
                }
                tile.tileLabel.apply {
                    setText(EnumLabels.of(candidate))
                    setTextColor(if (selected) accent else getColor(R.color.textPrimary))
                    textSize = 15f
                }
                tile.root.contentDescription = getString(EnumLabels.of(candidate))
                tile.root.setOnClickListener {
                    onPick(candidate)
                    dialog.dismiss()
                }
                row.addView(tile.root)
            }
            content.addView(row)
        }
        dialog.show()
    }

    // All done -----------------------------------------------------------

    private fun bindDone(view: View) {
        OnboardingPageDoneBinding.bind(view).doneGoHome.setOnClickListener {
            askHomeRoleThen { finishOnboarding() }
        }
    }

    // Rows ---------------------------------------------------------------

    // A settings page's row list, titled, with any rows of a previous bind
    // cleared away.
    private fun pageRows(view: View, titleRes: Int): LinearLayout {
        val page = OnboardingPageRowsBinding.bind(view)
        page.pageTitle.setText(titleRes)
        val rows = page.pageRows
        while (rows.childCount > 1) rows.removeViewAt(rows.childCount - 1)
        return rows
    }

    private fun addHeader(rows: LinearLayout, titleRes: Int) {
        val header = LayoutInflater.from(this).inflate(R.layout.onboarding_row_header, rows, false) as TextView
        header.setText(titleRes)
        rows.addView(header)
    }

    private fun addDropdown(
        rows: LinearLayout,
        titleRes: Int,
        labels: List<CharSequence>,
        selected: Int,
        onPick: (Int) -> Unit
    ) {
        val row = OnboardingRowDropdownBinding.inflate(LayoutInflater.from(this), rows, false)
        row.rowTitle.setText(titleRes)
        val value = row.rowValue
        var current = selected
        // A screen reader reaches the value on its own, so it carries the
        // row's title with it.
        fun show() {
            value.text = labels[current]
            value.contentDescription = getString(R.string.onboarding_dropdown_description, getString(titleRes), labels[current])
        }
        show()
        // The menu opens at the finger, as the settings screen's do.
        var touchX = 0f
        value.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) touchX = event.rawX
            false
        }
        value.setOnClickListener {
            ChoiceMenu.show(
                value,
                touchX,
                Styles.resolve(this, viewModel.state.value.prefs).of(SurfaceId.POPUP),
                labels,
                current
            ) { which ->
                current = which
                show()
                onPick(which)
            }
        }
        rows.addView(row.root)
    }

    private fun addSwitch(
        rows: LinearLayout,
        titleRes: Int,
        summaryRes: Int,
        checked: Boolean,
        onChange: (Boolean, MaterialSwitch) -> Unit
    ) {
        val row = OnboardingRowSwitchBinding.inflate(LayoutInflater.from(this), rows, false)
        row.rowTitle.setText(titleRes)
        row.rowSummary.setText(summaryRes)
        val switch = row.rowSwitch
        switch.isChecked = checked
        row.root.setOnClickListener {
            switch.isChecked = !switch.isChecked
            onChange(switch.isChecked, switch)
        }
        rows.addView(row.root)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    // One fixed page per step; position doubles as the view type so each
    // layout inflates once and notifyItemChanged rebinds its values.
    private inner class PagesAdapter : RecyclerView.Adapter<PagesAdapter.Holder>() {

        inner class Holder(view: View) : RecyclerView.ViewHolder(view)

        override fun getItemCount(): Int = PAGE_COUNT

        override fun getItemViewType(position: Int): Int = position

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val layout = when (viewType) {
                PAGE_WELCOME -> R.layout.onboarding_page_welcome
                PAGE_ICONS -> R.layout.onboarding_page_icons
                PAGE_DONE -> R.layout.onboarding_page_done
                else -> R.layout.onboarding_page_rows
            }
            return Holder(layoutInflater.inflate(layout, parent, false))
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            when (position) {
                PAGE_WELCOME -> bindWelcome(holder.itemView)
                PAGE_SEARCH -> bindSearch(holder.itemView)
                PAGE_DRAWER -> bindDrawer(holder.itemView)
                PAGE_ICONS -> bindIcons(holder.itemView)
                else -> bindDone(holder.itemView)
            }
        }
    }

    companion object {
        const val EXTRA_RERUN = "rerun"

        private const val PAGE_WELCOME = 0
        private const val PAGE_SEARCH = 1
        private const val PAGE_DRAWER = 2
        private const val PAGE_ICONS = 3
        private const val PAGE_DONE = 4
        private const val PAGE_COUNT = 5

        // The Home screen settings' icon size slider.
        private const val SIZE_MIN = 70
        private const val SIZE_MAX = 200
        private const val SIZE_STEP = 5

        // Sample icons: flat cyan, white, green and magenta, drawn a
        // little under the real icon size.
        private val PREVIEW_COLORS = listOf(0xFF00B4D8.toInt(), 0xFFFFFFFF.toInt(), 0xFF00FF00.toInt(), 0xFFFF00FF.toInt())
        private const val PREVIEW_BASE_DP = 42
        private const val PREVIEW_GAP_DP = 16

        private const val SHAPES_PER_ROW = 4
        private const val SHAPE_GRAY = 0xFF808080.toInt()
        private val MAIN_SHAPES = listOf(
            IconShape.SYSTEM to R.string.onboarding_shape_default,
            IconShape.CIRCLE to R.string.onboarding_shape_round,
            IconShape.SQUIRCLE to R.string.shape_squircle,
            IconShape.ROUNDED_SQUARE to R.string.shape_rounded_square
        )
        // "More shapes", in order.
        private val MORE_SHAPES = listOf(
            IconShape.SQUARE, IconShape.TEARDROP, IconShape.PEBBLE, IconShape.VESSEL,
            IconShape.PENTAGON, IconShape.HEXAGON, IconShape.HEXAGON_ROUNDED, IconShape.HEPTAGON,
            IconShape.OCTAGON, IconShape.CROP
        )
    }
}
