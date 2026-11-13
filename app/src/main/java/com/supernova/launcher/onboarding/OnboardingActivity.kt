package com.supernova.launcher.onboarding

import android.app.WallpaperManager
import android.content.ComponentName
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.supernova.launcher.HomeRole
import com.supernova.launcher.R
import com.supernova.launcher.appGraph
import com.supernova.launcher.data.ContainerType
import com.supernova.launcher.data.CustomShapeSpec
import com.supernova.launcher.data.DeviceProfile
import com.supernova.launcher.data.IconRenderer
import com.supernova.launcher.data.ItemType
import com.supernova.launcher.data.LauncherModel
import com.supernova.launcher.data.LauncherPrefs
import com.supernova.launcher.data.Pref
import com.supernova.launcher.data.PrefWrite
import com.supernova.launcher.databinding.ActivityOnboardingBinding
import com.supernova.launcher.runLogged
import com.supernova.launcher.ui.LabelFont
import com.supernova.launcher.ui.LauncherViewModel
import com.supernova.launcher.ui.WideScreen
import com.supernova.launcher.ui.settings.EnumLabels
import com.supernova.launcher.ui.settings.preview.MiniGridView
import com.supernova.launcher.ui.theme.AccentColor
import com.supernova.launcher.ui.theme.ThemeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

// The first-run wizard. A welcome page (Design my homescreen, Restore
// backup, Skip setup), then six choices - a starting look, a colour, the
// icons' shape and finish, how much a page holds, and the app names - each
// a few tiles under a live preview of the home screen over the real
// wallpaper, and last the finished home with Set as my home screen. Every
// choice is applied the moment it is tapped, so the preview is the home
// screen itself, and every one is a bundle of settings Settings already has
// (OnboardingChoices). Nothing asks for a permission or the keyboard.
//
// The fresh-install home screen is planted as the wizard opens, so it is
// there under the first run and in the preview; picking another grid
// plants it again on that grid. Finishing, skipping or restoring a backup
// writes onboarding_done and homePromptShown together, so the launcher's own
// home prompt never asks again. Settings' "Quick start" re-runs it with
// EXTRA_RERUN: the same pages over the current setup, back to Settings at
// the end.
class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding
    private val viewModel: LauncherViewModel by viewModels { LauncherViewModel.Factory(appGraph) }
    private val isRerun by lazy { intent.getBooleanExtra(EXTRA_RERUN, false) }
    private val profile by lazy { DeviceProfile.forDevice(this) }
    private val accents by lazy { AccentColor.entries.associateWith { ThemeEngine.accentColor(this, it) } }
    private var seedJob: Job? = null

    private var step = STEP_WELCOME

    // The look step's Shuffle was the last pick there, so it, not a look,
    // shows as chosen.
    private var shuffled = false

    // The current step's tiles.
    private var tiles = OnboardingTiles(this)

    // The current step's text that follows the settings (the colour's name,
    // the shape's).
    private var liveText: Pair<TextView, (LauncherPrefs) -> CharSequence>? = null

    // What Set as my home screen does once the default-home prompt answers.
    private var afterHomeRole: (() -> Unit)? = null

    // The backup brought its own home screen and settings: nothing is left
    // to set up.
    private val backup = com.supernova.launcher.ui.BackupTransfer(this, { viewModel }, onImported = ::finishOnboarding)

    private val requestHomeRole =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val then = afterHomeRole
            afterHomeRole = null
            then?.invoke()
        }

    private val prefs: LauncherPrefs get() = viewModel.state.value.prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeEngine.applyAccent(this, viewModel.state.value.prefs.accentColor)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        step = savedInstanceState?.getInt(KEY_STEP, STEP_WELCOME) ?: STEP_WELCOME
        shuffled = savedInstanceState?.getBoolean(KEY_SHUFFLED, false) ?: false
        setUpWindow()
        setUpPreview()

        binding.onboardingWelcome.welcomeGetStarted.setOnClickListener { goTo(STEP_LOOK) }
        binding.onboardingWelcome.welcomeRestore.setOnClickListener { backup.pickImportFile() }
        binding.onboardingWelcome.welcomeSkip.setOnClickListener { finishOnboarding() }
        binding.onboardingBack.setOnClickListener { goTo(step - 1) }
        binding.onboardingSkip.setOnClickListener { goTo(STEP_DONE) }
        binding.onboardingNext.setOnClickListener {
            if (step == STEP_DONE) askHomeRoleThen { finishOnboarding() } else goTo(step + 1)
        }
        onBackPressedDispatcher.addCallback(this) {
            when {
                step > STEP_WELCOME -> goTo(step - 1)
                isRerun -> finish()
                // The first run can't be backed out of either; this points
                // at the way to skip it.
                else -> Toast.makeText(this@OnboardingActivity, R.string.onboarding_skip_hint, Toast.LENGTH_SHORT)
                    .show()
            }
        }
        repeat(CHOICE_STEPS) {
            val params = LinearLayout.LayoutParams(dp(SEGMENT_DP), dp(SEGMENT_HEIGHT_DP))
            if (it > 0) params.marginStart = dp(SEGMENT_GAP_DP)
            binding.onboardingProgress.addView(View(this), params)
        }
        showStep(animate = false)

        if (!isRerun) {
            seedJob = lifecycleScope.launch {
                withContext(NonCancellable) { viewModel.seedDefaultLayout() }
            }
        }
        // The preview and the chosen tiles follow the settings and the layout
        // as they change.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state
                    .filter { it.loaded }
                    .map { Triple(it.prefs, it.model, it.iconGeneration) }
                    .distinctUntilChanged()
                    .collectLatest { (prefs, model, _) ->
                        val icons = withContext(Dispatchers.Default) { iconsFor(model) }
                        lastPreview = Triple(prefs, model, icons)
                        binding.onboardingPreview.bind(prefs, model, icons)
                        refreshChoices(prefs)
                    }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_STEP, step)
        outState.putBoolean(KEY_SHUFFLED, shuffled)
    }

    // Edge to edge: light system bar icons on the dark page, the pages clear
    // of both bars, and a centred column on a tablet.
    private fun setUpWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val width = view.resources.displayMetrics.widthPixels - bars.left - bars.right
            val gutter = WideScreen.gutterPx(view.context, width)
            binding.onboardingFlow.setPadding(bars.left + gutter, bars.top, bars.right + gutter, bars.bottom)
            binding.onboardingWelcome.root.setPadding(bars.left + gutter, bars.top, bars.right + gutter, bars.bottom)
            binding.onboardingPreview.topInset = bars.top
            binding.onboardingPreview.bottomInset = bars.bottom
            insets
        }
    }

    // The last state the preview drew, to draw again when its size lands.
    private var lastPreview: Triple<LauncherPrefs, LauncherModel, Map<String, Drawable>>? = null

    private fun setUpPreview() {
        val pane = binding.onboardingPreviewPane
        pane.showsWallpaper = true
        pane.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(PANE_RADIUS_DP).toFloat())
            }
        }
        pane.clipToOutline = true
        val preview = binding.onboardingPreview
        preview.pivotX = 0f
        preview.pivotY = 0f
        // The phone's outline, which shows while the whole screen is in view.
        preview.background = GradientDrawable().apply {
            cornerRadius = dp(FRAME_RADIUS_DP).toFloat()
            setStroke(dp(FRAME_STROKE_DP), FRAME_COLOR)
        }
        // The preview stands for the whole window, whatever the pane's size.
        binding.root.addOnLayoutChangeListener { root, _, _, _, _, _, _, _, _ ->
            if (preview.homeWidth == root.width && preview.homeHeight == root.height) return@addOnLayoutChangeListener
            preview.homeWidth = root.width
            preview.homeHeight = root.height
            lastPreview?.let { (prefs, model, icons) -> preview.bind(prefs, model, icons) }
            preview.requestLayout()
            root.post { applyCamera(animate = false) }
        }
        pane.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                pane.post { applyCamera(animate = false) }
            }
        }
    }

    // The whole screen in view, or up close to the bottom rows and the dock
    // for the steps about the icons themselves.
    private fun applyCamera(animate: Boolean) {
        val pane = binding.onboardingPreviewPane
        val preview = binding.onboardingPreview
        val paneEmpty = pane.width == 0 || pane.height == 0
        if (paneEmpty || preview.homeWidth == 0 || preview.homeHeight == 0) return
        val homeW = preview.homeWidth.toFloat()
        val homeH = preview.homeHeight.toFloat()
        val scale: Float
        val x: Float
        val y: Float
        if (step in CLOSE_STEPS) {
            scale = pane.width / homeW
            x = 0f
            y = pane.height - homeH * scale
        } else {
            scale = min(pane.width / homeW, pane.height / homeH) * FIT_MARGIN
            x = (pane.width - homeW * scale) / 2f
            y = (pane.height - homeH * scale) / 2f
        }
        if (animate) {
            preview.animate().scaleX(scale).scaleY(scale).translationX(x).translationY(y)
                .setDuration(CAMERA_MS).setInterpolator(DecelerateInterpolator(2f)).start()
        } else {
            preview.animate().cancel()
            preview.scaleX = scale
            preview.scaleY = scale
            preview.translationX = x
            preview.translationY = y
        }
    }

    // Each item's icon on the preview's page and in the dock, copies of the
    // launcher's own so the home screen's views keep theirs.
    private fun iconsFor(model: LauncherModel): Map<String, Drawable> {
        val catalog = viewModel.appCatalog()
        return model.items
            .filter { it.container == ContainerType.DOCK || it.container == ContainerType.WORKSPACE && it.page == 0 }
            .filter { it.itemType == ItemType.APP || it.itemType == ItemType.SHORTCUT }
            .mapNotNull { item ->
                val pkg = item.packageName ?: return@mapNotNull null
                val cls = item.className.takeIf { item.itemType == ItemType.APP }
                val app = catalog.mainActivityFor(pkg, item.userSerial, cls)
                    ?: catalog.mainActivityFor(pkg, item.userSerial)
                val icon = app?.let(catalog::iconFor) ?: return@mapNotNull null
                item.id to (icon.constantState?.newDrawable(resources)?.mutate() ?: icon)
            }
            .toMap()
    }

    private fun goTo(target: Int) {
        if (target !in STEP_WELCOME..STEP_DONE || target == step) return
        step = target
        showStep(animate = true)
    }

    private fun showStep(animate: Boolean) {
        val welcome = binding.onboardingWelcome.root
        if (step == STEP_WELCOME) {
            welcome.animate().cancel()
            welcome.visibility = View.VISIBLE
            welcome.alpha = 1f
        } else if (welcome.isVisible) {
            if (animate) {
                welcome.animate().alpha(0f).setDuration(FADE_MS)
                    .withEndAction { welcome.visibility = View.GONE }.start()
            } else {
                welcome.visibility = View.GONE
            }
        }
        if (step == STEP_WELCOME) return

        binding.onboardingSkip.visibility = if (step == STEP_DONE) View.INVISIBLE else View.VISIBLE
        binding.onboardingNext.setText(
            when {
                step != STEP_DONE -> R.string.next
                isRerun -> R.string.onboarding_done_rerun
                else -> R.string.onboarding_set_home
            }
        )
        for (index in 0 until binding.onboardingProgress.childCount) {
            binding.onboardingProgress.getChildAt(index).background = GradientDrawable().apply {
                cornerRadius = dp(SEGMENT_HEIGHT_DP).toFloat()
                setColor(if (index < step) Color.WHITE else getColor(R.color.onboardingTrack))
            }
        }
        buildStep()
        refreshChoices(prefs)
        applyCamera(animate)
        if (animate) {
            binding.onboardingPanel.alpha = 0f
            binding.onboardingPanel.animate().alpha(1f).setDuration(FADE_MS).start()
        }
        binding.onboardingTitle.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED)
    }

    private fun buildStep() {
        tiles = OnboardingTiles(this)
        liveText = null
        val options = binding.onboardingOptions
        options.removeAllViews()
        val content: View = when (step) {
            STEP_LOOK -> buildLooks()
            STEP_COLOR -> buildColors()
            STEP_SHAPE -> buildShapes()
            STEP_FINISH -> buildFinishes()
            STEP_DENSITY -> buildDensities()
            STEP_NAMES -> buildNames()
            else -> buildDone()
        }
        options.addView(content, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL
        ))
    }

    private fun setTexts(title: Int, subtitle: CharSequence) {
        binding.onboardingTitle.setText(title)
        binding.onboardingSubtitle.text = subtitle
    }

    private fun refreshChoices(prefs: LauncherPrefs) {
        for (choice in tiles.choices) {
            val chosen = choice.isChosen(prefs)
            choice.view.isSelected = chosen
            choice.view.contentDescription =
                if (chosen) getString(R.string.onboarding_selected, choice.label) else choice.label
            choice.onChosen(chosen)
        }
        liveText?.let { (view, text) -> view.text = text(prefs) }
    }

    // Writes a choice. A new grid on the first run plants the home screen
    // again for it, so its apps keep their places.
    private fun choose(writes: List<PrefWrite<*>>) {
        val current = prefs
        val gridMoves = writes.any {
            it.spec == Pref.gridCols && it.value != current.gridCols ||
                it.spec == Pref.gridRows && it.value != current.gridRows
        }
        viewModel.setAll(writes, replant = gridMoves && !isRerun)
    }

    // Look ---------------------------------------------------------------

    private fun buildLooks(): View {
        setTexts(R.string.onboarding_look_title, getString(R.string.onboarding_look_summary))
        val views = OnboardingChoices.Look.entries.map { look ->
            val minis = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(MINI_PAD_DP), dp(MINI_PAD_DP), dp(MINI_PAD_DP), dp(MINI_PAD_DP))
                background = roundedPlate(MINI_PLATE, dp(MINI_RADIUS_DP).toFloat())
            }
            repeat(2) { row ->
                val line = LinearLayout(this)
                repeat(2) { col ->
                    val swatch = ShapeSwatchView(this)
                    swatch.shape = look.shape
                    swatch.color = FinishSamples.color(look.finish, LOOK_COLORS[row * 2 + col])
                    line.addView(swatch, LinearLayout.LayoutParams(dp(MINI_DP), dp(MINI_DP)).apply {
                        if (col > 0) marginStart = dp(MINI_GAP_DP)
                    })
                }
                val params = tiles.wrap()
                if (row > 0) params.topMargin = dp(MINI_GAP_DP)
                minis.addView(line, params)
            }
            val holder = FrameLayout(this)
            holder.addView(minis, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
            ))
            tiles.tile(getString(look.labelRes), holder, LOOK_TILE_DP, { !shuffled && look.matches(it, profile) }) {
                shuffled = false
                choose(look.writes(prefs, profile))
            }
        }
        val column = tiles.vertical()
        column.addView(tiles.grid(LOOK_COLUMNS, views), tiles.matchWrap())
        val label = getString(R.string.onboarding_shuffle)
        val shuffle = tiles.tile(label, null, SHUFFLE_TILE_DP, { shuffled }, labelSp = SHUFFLE_SP) {
            shuffled = true
            choose(OnboardingChoices.shuffleWrites(prefs, profile, accents, Random.Default))
        }
        column.addView(shuffle, tiles.matchWrap().apply { topMargin = dp(OnboardingTiles.GAP_DP) })
        return column
    }

    // Colour -------------------------------------------------------------

    private val wallpaperColors: Pair<Int, Int>? by lazy {
        runCatching {
            WallpaperManager.getInstance(this).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)?.let { colors ->
                colors.primaryColor.toArgb() to (colors.secondaryColor ?: colors.primaryColor).toArgb()
            }
        }.getOrNull() ?: systemColors()
    }

    // Some wallpapers report no colours; the system palette, which Android
    // draws from the wallpaper, stands in.
    private fun systemColors(): Pair<Int, Int>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        getColor(android.R.color.system_accent1_300) to getColor(android.R.color.system_accent2_600)
    } else {
        null
    }

    private fun buildColors(): View {
        setTexts(R.string.onboarding_color_title, getString(R.string.onboarding_color_summary))
        val swatches = mutableListOf<View>()
        val wallpaper = wallpaperColors
        swatches += tiles.swatch(
            getString(R.string.palette_wallpaper),
            GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(wallpaper?.first ?: Color.GRAY, wallpaper?.second ?: Color.DKGRAY)
            ).apply { shape = GradientDrawable.OVAL },
            { OnboardingChoices.colourOf(it, accents) == null }
        ) { choose(OnboardingChoices.wallpaperWrites(wallpaper?.first, accents)) }
        AccentColor.entries.forEach { accent ->
            val color = accents.getValue(accent)
            swatches += tiles.swatch(
                getString(EnumLabels.of(accent)),
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                },
                { OnboardingChoices.colourOf(it, accents) == accent }
            ) { choose(OnboardingChoices.accentWrites(accent, color)) }
        }
        val column = tiles.vertical()
        column.addView(tiles.grid(COLOR_COLUMNS, swatches, gapDp = OnboardingTiles.GAP_DP), tiles.matchWrap())
        val name = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = NAME_SP
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        liveText = name to { prefs -> colourName(prefs) }
        column.addView(name, tiles.matchWrap().apply { topMargin = dp(OnboardingTiles.GAP_DP * 2) })
        return column
    }

    private fun colourName(prefs: LauncherPrefs): CharSequence =
        OnboardingChoices.colourOf(prefs, accents)?.let { getString(EnumLabels.of(it)) }
            ?: getString(R.string.palette_wallpaper)

    // Shape --------------------------------------------------------------

    private fun buildShapes(): View {
        setTexts(R.string.onboarding_shape_title, "")
        liveText = binding.onboardingSubtitle to { prefs -> getString(EnumLabels.of(prefs.iconShape)) }
        val accent = ThemeEngine.accentColor(this, prefs.accentColor)
        val views = OnboardingChoices.SHAPES.map { shape ->
            val swatch = ShapeSwatchView(this)
            swatch.shape = shape
            val holder = FrameLayout(this)
            holder.addView(swatch, FrameLayout.LayoutParams(dp(SHAPE_DP), dp(SHAPE_DP), Gravity.CENTER))
            val label = getString(EnumLabels.of(shape))
            val onChosen = { chosen: Boolean ->
                swatch.color = if (chosen) ColorUtils.blendARGB(accent, Color.WHITE, ACCENT_LIFT) else SHAPE_IDLE
            }
            val isChosen = { prefs: LauncherPrefs -> prefs.iconShape == shape }
            tiles.tile(label, holder, SHAPE_TILE_DP, isChosen, showLabel = false, onChosen = onChosen) {
                choose(OnboardingChoices.shapeWrites(shape))
            }
        }
        return tiles.grid(SHAPE_COLUMNS, views)
    }

    // Finish -------------------------------------------------------------

    private fun buildFinishes(): View {
        setTexts(R.string.onboarding_finish_title, getString(R.string.onboarding_finish_summary))
        val source = sampleIcon()
        val renderer = IconRenderer(this)
        val current = prefs
        val custom = CustomShapeSpec.decode(current.iconShapeCustom)
        val accent = ThemeEngine.accentColor(this, current.accentColor)
        val views = OnboardingChoices.Finish.entries.map { finish ->
            val image = ImageView(this)
            if (source != null) {
                val icon = runCatching {
                    renderer.render(source, shape = current.iconShape, customShape = custom, glass = finish.glass)
                }.getOrNull()
                image.setImageDrawable(icon)
                if (finish.themed) image.colorFilter = FinishSamples.duotone(accent)
            }
            // Glass shows what lies under it: the plate stands in for the
            // wallpaper.
            val plate = FrameLayout(this)
            plate.background = roundedPlate(MINI_PLATE, dp(MINI_RADIUS_DP).toFloat())
            plate.addView(image, FrameLayout.LayoutParams(dp(FINISH_ICON_DP), dp(FINISH_ICON_DP), Gravity.CENTER))
            val holder = FrameLayout(this)
            holder.addView(plate, FrameLayout.LayoutParams(dp(FINISH_PLATE_DP), dp(FINISH_PLATE_DP), Gravity.CENTER))
            val isChosen = { prefs: LauncherPrefs -> OnboardingChoices.Finish.of(prefs) == finish }
            tiles.tile(getString(finish.labelRes), holder, FINISH_TILE_DP, isChosen) {
                choose(finish.writes())
            }
        }
        return tiles.grid(FINISH_COLUMNS, views)
    }

    // The first dock app's own icon, unshaped, to show each finish on.
    private fun sampleIcon(): Drawable? {
        val items = viewModel.state.value.model.items
        val item = items.filter { it.container == ContainerType.DOCK && it.itemType == ItemType.APP }
            .minByOrNull { it.rank }
            ?: items.firstOrNull { it.itemType == ItemType.APP }
        val pkg = item?.packageName ?: return null
        return runCatching {
            val cls = item.className
            if (cls != null) {
                packageManager.getActivityIcon(ComponentName(pkg, cls))
            } else {
                packageManager.getApplicationIcon(pkg)
            }
        }.getOrNull()
    }

    // Density ------------------------------------------------------------

    private fun buildDensities(): View {
        setTexts(R.string.onboarding_density_title, getString(R.string.onboarding_density_summary))
        val views = OnboardingChoices.Density.entries.map { density ->
            val cols = density.cols(profile)
            val rows = density.rows(profile)
            val column = tiles.vertical()
            column.gravity = Gravity.CENTER_HORIZONTAL
            val mini = MiniGridView(this)
            mini.cellColor = MINI_GRID
            mini.set(rows, cols)
            column.addView(mini, LinearLayout.LayoutParams(dp(GRID_W_DP), dp(GRID_H_DP)))
            column.addView(TextView(this).apply {
                text = getString(R.string.onboarding_grid_size, cols, rows)
                setTextColor(getColor(R.color.onboardingSummary))
                textSize = SMALL_SP
            }, tiles.wrap().apply { topMargin = dp(OnboardingTiles.GAP_DP) })
            val isChosen = { prefs: LauncherPrefs -> OnboardingChoices.Density.of(prefs, profile) == density }
            tiles.tile(getString(density.labelRes), column, DENSITY_TILE_DP, isChosen) {
                choose(density.writes(profile))
            }
        }
        return tiles.grid(views.size, views)
    }

    // Names --------------------------------------------------------------

    private fun buildNames(): View {
        setTexts(R.string.onboarding_names_title, getString(R.string.onboarding_names_summary))
        val views = OnboardingChoices.Names.entries.map { names ->
            val sample = TextView(this).apply {
                setText(R.string.onboarding_names_sample)
                setTextColor(Color.WHITE)
                textSize = SAMPLE_SP
                gravity = Gravity.CENTER_HORIZONTAL
                typeface = when (names.font) {
                    LabelFont.CONDENSED -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
                    LabelFont.SERIF -> Typeface.SERIF
                    LabelFont.MONOSPACE -> Typeface.MONOSPACE
                    else -> Typeface.SANS_SERIF
                }
                if (names.font == null) {
                    alpha = HIDDEN_ALPHA
                    paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            tiles.tile(getString(names.labelRes), sample, NAMES_TILE_DP, { OnboardingChoices.Names.of(it) == names }) {
                choose(names.writes())
            }
        }
        return tiles.grid(views.size, views)
    }

    // Done ---------------------------------------------------------------

    private fun buildDone(): View {
        setTexts(R.string.onboarding_done_title, "")
        liveText = binding.onboardingSubtitle to { prefs -> summary(prefs) }
        return TextView(this).apply {
            if (!isRerun) setText(R.string.onboarding_done_later)
            setTextColor(getColor(R.color.onboardingSummary))
            textSize = SMALL_TEXT_SP
        }
    }

    // What the steps came to: shape, finish, colour and grid.
    private fun summary(prefs: LauncherPrefs): CharSequence = listOfNotNull(
        getString(EnumLabels.of(prefs.iconShape)),
        OnboardingChoices.Finish.of(prefs)?.let { getString(it.labelRes) },
        colourName(prefs),
        OnboardingChoices.Density.of(prefs, profile)?.let { getString(it.labelRes) }
    ).joinToString(SUMMARY_SEPARATOR)

    // Home role and finishing ---------------------------------------------

    // Asked once, on Set as my home screen, and carries on whatever the
    // answer.
    private fun askHomeRoleThen(then: () -> Unit) {
        val request = if (HomeRole.isNeeded(this)) HomeRole.requestIntent(this) else null
        if (request == null) {
            then()
            return
        }
        afterHomeRole = then
        runLogged("ask for home role") { requestHomeRole.launch(request) }.onFailure {
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        const val EXTRA_RERUN = "rerun"

        private const val KEY_STEP = "step"
        private const val KEY_SHUFFLED = "shuffled"

        private const val STEP_WELCOME = 0
        private const val STEP_LOOK = 1
        private const val STEP_COLOR = 2
        private const val STEP_SHAPE = 3
        private const val STEP_FINISH = 4
        private const val STEP_DENSITY = 5
        private const val STEP_NAMES = 6
        private const val STEP_DONE = 7
        private const val CHOICE_STEPS = 6

        // The steps that look closely at the icons in the dock (the names
        // step needs the page, where the names are).
        private val CLOSE_STEPS = setOf(STEP_COLOR, STEP_SHAPE, STEP_FINISH)

        private const val CAMERA_MS = 450L
        private const val FADE_MS = 200L
        private const val FIT_MARGIN = 0.94f

        private const val PANE_RADIUS_DP = 28
        private const val FRAME_RADIUS_DP = 40
        private const val FRAME_STROKE_DP = 3
        private const val FRAME_COLOR = 0x40FFFFFF

        private const val SEGMENT_DP = 22
        private const val SEGMENT_HEIGHT_DP = 4
        private const val SEGMENT_GAP_DP = 4

        private const val SMALL_SP = 12f
        private const val SMALL_TEXT_SP = 14f
        private const val NAME_SP = 14f

        private const val LOOK_COLUMNS = 4
        private const val LOOK_TILE_DP = 112
        private const val SHUFFLE_TILE_DP = 52
        private const val SHUFFLE_SP = 15f
        private const val MINI_DP = 22
        private const val MINI_GAP_DP = 5
        private const val MINI_PAD_DP = 8
        private const val MINI_RADIUS_DP = 12
        private const val MINI_PLATE = 0x33FFFFFF
        private val LOOK_COLORS = intArrayOf(
            0xFF2EAD5B.toInt(), 0xFF2F7DE1.toInt(), 0xFFD98A1F.toInt(), 0xFFD8503F.toInt()
        )

        private const val COLOR_COLUMNS = 6

        private const val SHAPE_COLUMNS = 5
        private const val SHAPE_TILE_DP = 54
        private const val SHAPE_DP = 30
        private const val SHAPE_IDLE = 0x9EFFFFFF.toInt()
        private const val ACCENT_LIFT = 0.25f

        private const val FINISH_COLUMNS = 5
        private const val FINISH_TILE_DP = 104
        private const val FINISH_PLATE_DP = 50
        private const val FINISH_ICON_DP = 36

        private const val DENSITY_TILE_DP = 132
        private const val GRID_W_DP = 54
        private const val GRID_H_DP = 66
        private const val MINI_GRID = 0x99FFFFFF.toInt()

        private const val NAMES_TILE_DP = 104
        private const val SAMPLE_SP = 28f
        private const val HIDDEN_ALPHA = 0.45f

        private const val SUMMARY_SEPARATOR = " · "
    }
}
