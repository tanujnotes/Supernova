package com.supernova.launcher

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.supernova.launcher.widget.SupernovaWidgetHost
import com.supernova.launcher.data.CornerCut
import com.supernova.launcher.data.CustomShapeSpec
import com.supernova.launcher.data.DrawerGroup
import com.supernova.launcher.data.DrawerGroupType
import com.supernova.launcher.data.IconShape
import com.supernova.launcher.data.LaunchableApp
import com.supernova.launcher.data.LauncherPrefs
import com.supernova.launcher.databinding.ActivitySettingsBinding
import com.supernova.launcher.databinding.DialogCustomShapeBinding
import com.supernova.launcher.databinding.DialogDesktopGridBinding
import com.supernova.launcher.databinding.DialogFolderIconBinding
import com.supernova.launcher.databinding.ItemIconPackChoiceBinding
import com.supernova.launcher.gesture.Gesture
import com.supernova.launcher.gesture.GestureAction
import com.supernova.launcher.gesture.GestureBinding
import com.supernova.launcher.gesture.GestureDefaults
import com.supernova.launcher.notification.SupernovaNotificationListener
import com.supernova.launcher.ui.SearchBarConfig
import com.supernova.launcher.ui.FolderPreviewStyle
import com.supernova.launcher.ui.LauncherViewModel
import com.supernova.launcher.ui.dp
import com.supernova.launcher.ui.settings.EnumLabels
import com.supernova.launcher.ui.settings.PreviewController
import com.supernova.launcher.ui.settings.PreviewKind
import com.supernova.launcher.ui.settings.SettingItem
import com.supernova.launcher.ui.settings.SettingsAdapter
import com.supernova.launcher.ui.Animations
import com.supernova.launcher.ui.settings.SettingsCatalogue
import com.supernova.launcher.ui.ChoiceMenu
import com.supernova.launcher.ui.settings.SettingsSearchIndex
import com.supernova.launcher.ui.settings.SettingsSection
import com.supernova.launcher.ui.settings.StylePickerSheet
import com.supernova.launcher.ui.theme.AccentColor
import com.supernova.launcher.ui.theme.PaletteSourceMode
import com.supernova.launcher.ui.theme.Styles
import com.supernova.launcher.ui.theme.SurfaceId
import com.supernova.launcher.ui.theme.ThemeEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private enum class Tab { SETTINGS, STYLE }

    private lateinit var binding: ActivitySettingsBinding
    private val viewModel: LauncherViewModel by viewModels { LauncherViewModel.Factory(appGraph) }
    private lateinit var adapter: SettingsAdapter

    // The settings row last tapped, and so what a choice menu opens under.
    // The card list the catalogue was last built from; a change rebuilds the
    // Cards section (its rows are the cards).
    private var renderedFeedCards: List<com.supernova.launcher.feed.FeedCard>? = null

    // Held as a field rather than threaded through SettingsCatalogue.Actions:
    // every action there is a plain () -> Unit, and the adapter reports the
    // row immediately before running one, so this is always the row whose
    // action is executing.
    private var menuAnchor: View? = null

    // Where in that row the finger went down, in window coordinates. A row
    // runs the full width of the screen with its current value at the far
    // end, so the menu opens from the touch rather than from the row's edge:
    // press the value and the menu appears beside the value.
    private var menuTouchX = 0f
    private lateinit var sections: List<SettingsSection>
    private lateinit var styleSection: SettingsSection
    // The cards under the root list's and the Style tab's rows.
    private lateinit var groupCards: com.supernova.launcher.ui.settings.SettingsGroupDecoration

    private var currentTab = Tab.SETTINGS

    // null = the section list; non-null = inside that section.
    private var currentSection: SettingsSection? = null
    private lateinit var previews: PreviewController
    // The one view living in previewPane; created on first use.
    private var paneView: com.supernova.launcher.ui.settings.preview.SettingsPreviewView? = null
    private var appliedAccent: String = "BLUE"

    // Header search over the whole catalogue; the index resolves lazily on
    // first activation so plain settings visits never build it.
    private var searchActive = false
    private val searchIndex by lazy { SettingsSearchIndex.build(this, viewModel) }

    private val requestHomeRoleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        render()
    }

    private val exportBackupLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            runCatching {
                val payload = viewModel.exportBackup()
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(payload) }
            }.onSuccess {
                Toast.makeText(this@SettingsActivity, R.string.backup_exported, Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(
                    this@SettingsActivity,
                    getString(R.string.backup_export_failed, it.message ?: "unknown"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // Labs' Permissions: a grant or refusal shows on the row at once. A
    // refusal that comes back at once came without a dialog: the system
    // won't ask again, and only App info can grant it now. One the user
    // gave in the dialog is left at that.
    private var labsRequestedAt = 0L
    private val labsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val noDialog = android.os.SystemClock.elapsedRealtime() - labsRequestedAt < NO_DIALOG_MS
            if (noDialog && results.isNotEmpty() && results.values.none { it }) openOwnAppInfo()
            render()
        }

    private fun manageLabsPermission(permission: com.supernova.launcher.ui.settings.LabsPermission) {
        val granted = permission.granted(this)
        when (permission) {
            com.supernova.launcher.ui.settings.LabsPermission.NOTIFICATIONS ->
                runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            com.supernova.launcher.ui.settings.LabsPermission.ACCESSIBILITY ->
                runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            com.supernova.launcher.ui.settings.LabsPermission.DEVICE_ADMIN -> if (granted) {
                // The app can give up its own admin; asking first, since
                // the lock method that needs it stops working.
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.device_admin_off_title)
                    .setMessage(R.string.device_admin_off_message)
                    .setPositiveButton(R.string.turn_off) { _, _ ->
                        getSystemService(android.app.admin.DevicePolicyManager::class.java)
                            ?.removeActiveAdmin(SupernovaDeviceAdmin.component(this))
                        // The admin goes a moment after the call returns.
                        binding.root.postDelayed({ render() }, 500)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            } else {
                runCatching {
                    startActivity(
                        Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                            .putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, SupernovaDeviceAdmin.component(this))
                            .putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.lock_admin_explanation))
                    )
                }
            }
            // A runtime permission can only be taken back in App info.
            else -> if (granted) {
                openOwnAppInfo()
            } else {
                labsRequestedAt = android.os.SystemClock.elapsedRealtime()
                labsPermissionLauncher.launch(permission.runtime)
            }
        }
    }

    private fun openOwnAppInfo() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.fromParts("package", packageName, null))
            )
        }
    }

    // Labs' Export icons: the zip goes wherever the picker says.
    private val exportIconsLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use {
                        com.supernova.launcher.data.IconExporter.export(this@SettingsActivity, it)
                    } ?: error("no output stream")
                }
            }.onSuccess { count ->
                Toast.makeText(
                    this@SettingsActivity,
                    resources.getQuantityString(R.plurals.labs_icons_exported, count, count),
                    Toast.LENGTH_SHORT
                ).show()
            }.onFailure {
                Toast.makeText(
                    this@SettingsActivity,
                    getString(R.string.labs_export_icons_failed, it.message ?: "unknown"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private val importBackupLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val app = applicationContext
        lifecycleScope.launch {
            // Seen through even if this screen is recreated meanwhile (a
            // rotation, a dark-mode switch while a cloud file downloads):
            // cancelled, the import reported itself failed and restored
            // nothing. Off the main thread: a file in a cloud drive
            // downloads as it is opened, and a slow network was an ANR.
            val outcome = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                runCatching {
                    val raw = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                    }
                    viewModel.importBackup(raw)
                }
            }
            outcome.onSuccess { result ->
                Toast.makeText(app, result.summary(app.resources), Toast.LENGTH_LONG).show()
            }.onFailure {
                Toast.makeText(
                    app, app.getString(R.string.backup_import_failed, it.message ?: "unknown"), Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // Contacts search is useless without the permission: a denied request
    // flips the pref back off so the toggle reflects reality.
    // A gesture waiting on an app's shortcut screen (the action picker's
    // Shortcuts tab).
    private var pendingGestureShortcut: Gesture? = null

    private val createGestureShortcut =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val gesture = pendingGestureShortcut ?: return@registerForActivityResult
            pendingGestureShortcut = null
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val created = result.data?.let { com.supernova.launcher.ui.CreatedShortcuts.read(this, it) }
            val binding = when (created) {
                is com.supernova.launcher.ui.CreatedShortcut.Pinned ->
                    GestureBinding(GestureAction.LAUNCH_SHORTCUT, payload = created.target, label = created.label)
                is com.supernova.launcher.ui.CreatedShortcut.Legacy ->
                    GestureBinding(GestureAction.LAUNCH_INTENT, payload = created.intentUri, label = created.label)
                null -> {
                    Toast.makeText(this, R.string.shortcut_unusable, Toast.LENGTH_SHORT).show()
                    return@registerForActivityResult
                }
            }
            viewModel.setGestureBinding(gesture, binding)
        }

    // The test badge's notification, on Android 13+ where posting one is a
    // runtime permission.
    private val requestPostNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                postTestBadge()
            } else {
                Toast.makeText(this, R.string.badge_test_blocked, Toast.LENGTH_LONG).show()
            }
        }

    private val requestContactsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                viewModel.setSearchContacts(false)
                Toast.makeText(this, R.string.contacts_permission_needed, Toast.LENGTH_LONG).show()
            }
        }

    // Night mode's Location enhancement follows the same contract. A grant
    // drops the sunsets worked out from the time zone and applies the theme
    // again from the phone's location.
    private val requestNightLocationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                com.supernova.launcher.ui.theme.NightWindow.invalidate()
                ThemeEngine.applyMode(this, viewModel.state.value.prefs)
            } else {
                viewModel.setThemeAutoLocation(false)
                Toast.makeText(this, R.string.theme_auto_location_needed, Toast.LENGTH_LONG).show()
            }
        }

    // The feed's calendar/contacts card toggles follow the same contract:
    // denial flips the pref back off so the toggle reflects reality.
    private val requestFeedCalendarPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                viewModel.setFeedShowCalendar(false)
                Toast.makeText(this, R.string.calendar_permission_needed, Toast.LENGTH_LONG).show()
            }
        }

    private val requestFeedContactsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                viewModel.setFeedShowContacts(false)
                Toast.makeText(this, R.string.contacts_permission_feed_needed, Toast.LENGTH_LONG).show()
            }
        }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            val parent = currentSection?.title?.let(subPageParents::get)
            if (searchActive) {
                setSearchActive(false)
            } else if (parent != null) {
                // Back onto the row that opened the page, not the top.
                val child = currentSection?.title ?: 0
                navigateTo(parent, child, flash = false)
            } else {
                currentSection = null
                render()
            }
        }
    }

    // Half the width landscape has beyond the capped content column, or 0
    // in portrait / when the window is already narrower than the cap.
    private fun contentGutter(bars: androidx.core.graphics.Insets): Int {
        val available = resources.displayMetrics.widthPixels - bars.left - bars.right
        return com.supernova.launcher.ui.WideScreen.gutterPx(this, available)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appliedAccent = viewModel.state.value.prefs.accentColor
        ThemeEngine.applyAccent(this, AccentColor.fromName(appliedAccent))
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Edge-to-edge, like the launcher window: the toolbar takes the status
        // bar and the tab bar takes the navigation bar, so the background runs
        // to both edges.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Reaches into the cutout in landscape; the inset listener below
        // unions displayCutout() so content still clears the notch.
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams
                .LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        // The Home screen page shows its preview icons on the real wallpaper:
        // its PreviewPane clears to whatever is under the
        // window, so the window has to be see-through and show the wallpaper
        // under itself. That holds for the whole visit rather than switching
        // on with the page, so the wallpaper is already up when the page
        // opens instead of being asked back as it draws. Everywhere else the
        // screen's own background covers it.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        window.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
        // Without this the system paints its own scrim over a transparent nav
        // bar, which would sit on top of the tab bar's extended background.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        // Dark clock and buttons over the light surfaces, light ones over the
        // dark; the theme's windowLightStatusBar does not survive the
        // edge-to-edge setup above on every device, so it is set here too.
        val lightBars = resources.getBoolean(R.bool.lightSystemBars)
        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = lightBars
            isAppearanceLightNavigationBars = lightBars
        }
        // The status bar inset goes on the root, not on the toolbar: the root
        // list hides the toolbar and shows its own big header instead, which
        // would otherwise ride up under the clock. The side insets go on the
        // children instead, so the tab bar's own background still reaches the
        // window edges past a landscape cutout. Each child's design padding is
        // captured first, because insets are re-applied on every change and
        // setPadding is absolute, not additive.
        val sideInsetViews = listOf(
            binding.settingsToolbar, binding.previewPane, binding.rootTitle, binding.rootSearch,
            binding.settingsRecycler, binding.settingsTabs
        )
        val basePadding = sideInsetViews.map { Triple(it.paddingLeft, it.paddingRight, it.paddingBottom) }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(top = bars.top)
            // Wide-screen gutter (landscape, or a tablet in either
            // orientation): the content column is capped and centred, see
            // WideScreen. The tab bar is excluded - its background is meant
            // to reach both window edges.
            val gutter = contentGutter(bars)
            sideInsetViews.forEachIndexed { index, child ->
                val (left, right, bottom) = basePadding[index]
                val column = if (child === binding.settingsTabs) 0 else gutter
                child.updatePadding(
                    left = left + bars.left + column,
                    right = right + bars.right + column
                )
                if (child === binding.settingsTabs) {
                    child.updatePadding(bottom = bottom + bars.bottom)
                }
            }
            insets
        }
        previews = PreviewController(viewModel)
        adapter = SettingsAdapter(previews)
        adapter.onRowTapped = { menuAnchor = it }

        val actions = SettingsCatalogue.Actions(
            pickChoice = { showChoice(it.title, it.labels, it.selected, it.onPick) },
            pickIconPack = ::showIconPackPicker,
            pickIconShape = ::showIconShapePicker,
            resetCustomIcons = ::confirmResetCustomIcons,
            exportIcons = { exportIconsLauncher.launch("supernova-icons.zip") },
            managePermission = ::manageLabsPermission,
            clearIconCache = {
                viewModel.clearIconCache()
                Toast.makeText(this, R.string.labs_icon_cache_cleared, Toast.LENGTH_SHORT).show()
            },
            pickDesktopGrid = ::showDesktopGridDialog,
            pickHideClock = ::showHideClockPicker,
            editSearchChips = ::showSearchChipsEditor,
            pickDrawerGrid = ::showDrawerGridDialog,
            editFolderIcon = ::showFolderIconEditor,
            editBadgedApps = ::showBadgedAppsPicker,
            postTestBadge = ::postTestBadge,
            editThemeSchedule = ::showThemeScheduleEditor,
            showLicense = {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.about_license)
                    .setMessage(R.string.about_license_text)
                    .setPositiveButton(R.string.ok, null)
                    .show()
            },
            openPaletteDialog = ::showPaletteSourcePicker,
            openStylePicker = { surfaceId -> StylePickerSheet.show(this, viewModel, surfaceId) },
            showAutoBackups = ::showAutoBackups,
            editGesture = ::showActionPicker,
            pickLockMethod = ::showLockMethodPicker,
            editHiddenApps = ::showHiddenAppsPicker,
            editDrawerGroups = ::showDrawerGroups,
            exportBackup = {
                exportBackupLauncher.launch("supernova-backup-${System.currentTimeMillis()}.json")
            },
            importBackup = {
                importBackupLauncher.launch(arrayOf("application/json", "text/plain"))
            },
            shareBackup = ::shareBackup,
            resetToDefaults = ::confirmResetToDefaults,
            quickStart = {
                // Re-run mode: the wizard skips its gate writes and just
                // finishes back here.
                startActivity(
                    Intent(this, com.supernova.launcher.onboarding.OnboardingActivity::class.java)
                        .putExtra(com.supernova.launcher.onboarding.OnboardingActivity.EXTRA_RERUN, true)
                )
            },
            restartLauncher = ::restartLauncher,
            showCrashLog = ::showCrashLog,
            onBadgesEnabled = {
                if (!SupernovaNotificationListener.isEnabled(this)) {
                    Toast.makeText(this, R.string.badges_need_access, Toast.LENGTH_LONG).show()
                    runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                }
            },
            onSearchContactsEnabled = {
                if (checkSelfPermission(android.Manifest.permission.READ_CONTACTS) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    requestContactsPermission.launch(android.Manifest.permission.READ_CONTACTS)
                }
            },
            setNightLocation = { enabled ->
                viewModel.setThemeAutoLocation(enabled)
                if (enabled && !com.supernova.launcher.ui.theme.NightWindow.hasLocationPermission(this)) {
                    requestNightLocationPermission.launch(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                }
            },
            editPopupRows = ::showPopupRowsDialog,
            onFeedCalendarEnabled = {
                if (checkSelfPermission(android.Manifest.permission.READ_CALENDAR) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    requestFeedCalendarPermission.launch(android.Manifest.permission.READ_CALENDAR)
                }
            },
            onFeedContactsEnabled = {
                if (checkSelfPermission(android.Manifest.permission.READ_CONTACTS) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    requestFeedContactsPermission.launch(android.Manifest.permission.READ_CONTACTS)
                }
            },
            editCard = { card ->
                com.supernova.launcher.ui.CustomCardEditorSheet.show(this, viewModel, card)
            },
            openCards = { navigateTo(R.string.settings_section_cards, 0) },
            openNotificationAccess = {
                runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            },
            cardPreview = { card, parent ->
                // The feed's own binder, inert: no launches, no menu, and a
                // widget-featured card shows without its widget.
                val binder = com.supernova.launcher.ui.CardViewBinder(
                    com.supernova.launcher.ui.CardViewBinder.Callbacks(
                        onLaunchTarget = { _, _, _ -> },
                        widgetView = { null },
                        iconForTarget = { target ->
                            com.supernova.launcher.ui.CustomCardEditorSheet.iconForTarget(viewModel, target)
                        },
                        labelForTarget = { target ->
                            com.supernova.launcher.ui.CustomCardEditorSheet.labelForTarget(viewModel, target)
                        },
                        onMenu = null
                    )
                )
                val popup = com.supernova.launcher.ui.theme.Styles
                    .resolve(this, viewModel.state.value.prefs)
                    .of(com.supernova.launcher.ui.theme.SurfaceId.POPUP)
                binder.setAppearance(popup.surface, popup.text, popup.accent)
                binder.inflate(parent).also { binder.bind(it, card) }.root
            },
            builtInPreview = { kind, parent ->
                com.supernova.launcher.ui.settings.CardPreviews.builtIn(kind, parent, viewModel.state.value.prefs)
            },
            openSection = { title -> navigateTo(title, 0) }
        )
        sections = SettingsCatalogue.sections(context = this, viewModel = viewModel, actions = actions)
        styleSection = SettingsCatalogue.styleSection(context = this, viewModel = viewModel, actions = actions)
        renderedFeedCards = viewModel.state.value.model.feedCards
        // A recreate (a theme or accent change) stays on the same page. The
        // tab bar restores its own selection, and the list has to agree.
        savedInstanceState?.let { state ->
            currentTab = if (state.getString(STATE_TAB) == Tab.STYLE.name) Tab.STYLE else Tab.SETTINGS
            val sectionTitle = state.getInt(STATE_SECTION, 0)
            currentSection = sections.firstOrNull { it.title == sectionTitle && sectionTitle != 0 }
            // Where it was on the page, too. The list and the app bar bring
            // back their own scroll, which a first showing of the page would
            // reset to the top.
            shownPage = currentTab to currentSection?.title
            // That scroll is a row position, so the accordions open then are
            // opened again before the first render lays the rows out.
            state.getIntArray(STATE_OPEN_ACCORDIONS)?.let { adapter.openAccordions = it }
        }

        binding.settingsRecycler.layoutManager = LinearLayoutManager(this)
        binding.settingsRecycler.adapter = adapter
        groupCards = com.supernova.launcher.ui.settings.SettingsGroupDecoration(this, adapter)
        binding.settingsRecycler.addItemDecoration(groupCards)
        binding.cardsFab.setOnClickListener {
            com.supernova.launcher.ui.CustomCardEditorSheet.show(this, viewModel, null)
        }
        // Watched rather than consumed: the row's own click listener still
        // runs, this only remembers where the press landed so the menu it
        // opens can appear there.
        binding.settingsRecycler.addOnItemTouchListener(
            object : androidx.recyclerview.widget.RecyclerView.SimpleOnItemTouchListener() {
                override fun onInterceptTouchEvent(
                    rv: androidx.recyclerview.widget.RecyclerView,
                    e: android.view.MotionEvent
                ): Boolean {
                    if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                        // Into window coordinates, which is what the menu
                        // places itself in; the list is not always at x = 0.
                        val location = IntArray(2)
                        rv.getLocationInWindow(location)
                        menuTouchX = e.x + location[0]
                    }
                    return false
                }
            }
        )
        binding.backButton.setOnClickListener {
            if (searchActive || currentSection != null) backCallback.handleOnBackPressed() else finish()
        }
        onBackPressedDispatcher.addCallback(this, backCallback)
        binding.settingsSearchButton.setOnClickListener { setSearchActive(!searchActive) }
        binding.settingsSearchInput.doAfterTextChanged {
            if (searchActive) render()
        }
        // The pill searches in place: a query turns the list into results and
        // emptying it brings the list back, with the keyboard left up for the
        // next try. Leaving search for good (back, a result) goes through
        // setSearchActive(false).
        binding.rootSearchInput.doAfterTextChanged { text ->
            binding.rootSearchClear.isVisible = !text.isNullOrEmpty()
            val hasQuery = !text.isNullOrBlank()
            if (hasQuery || searchActive) {
                searchActive = hasQuery
                render()
            }
        }
        binding.rootSearchInput.setOnEditorActionListener { view, actionId, _ ->
            if (actionId != android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
            getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(view.windowToken, 0)
            true
        }
        binding.rootSearchClear.setOnClickListener { binding.rootSearchInput.text?.clear() }
        // The pill around the field (its magnifier, its ends) focuses it too.
        binding.rootSearchPill.setOnClickListener {
            binding.rootSearchInput.requestFocus()
            getSystemService(InputMethodManager::class.java)?.showSoftInput(binding.rootSearchInput, 0)
        }
        binding.settingsTabs.setOnItemSelectedListener { item ->
            val tab = if (item.itemId == R.id.tabStyle) Tab.STYLE else Tab.SETTINGS
            if (tab != currentTab) {
                // A section's search lives in its toolbar, which the other
                // tab doesn't show, so it ends here. A query in the pill
                // stays: both tabs wear the pill.
                if (currentSection != null) setSearchActive(false)
                currentTab = tab
                currentSection = null
                render()
            }
            true
        }

        applySettingsSurface(viewModel.state.value.prefs)
        render()

        // A recreate keeps the intent it was opened with, deep link and all,
        // but it has moved on from there: it restores its own page instead.
        if (savedInstanceState == null) handleDeepLink(intent)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    // The Cards section's rows ARE the cards: a save, delete
                    // or pin from the editor rebuilds the catalogue so the
                    // list under the sheet is current when it closes.
                    if (state.model.feedCards != renderedFeedCards) {
                        renderedFeedCards = state.model.feedCards
                        val shownTitle = currentSection?.title
                        sections = SettingsCatalogue.sections(
                            context = this@SettingsActivity, viewModel = viewModel, actions = actions
                        )
                        if (shownTitle != null) {
                            currentSection = sections.firstOrNull { it.title == shownTitle }
                        }
                        // The root's rows capture their section objects, so
                        // it re-renders too: a cold start loads the cards
                        // after the root is up, and its Cards row would
                        // otherwise open the stale, empty section.
                        if (!searchActive) render()
                    }
                    adapter.updatePrefs(state.prefs)
                    bindPreviewPane(currentSection)
                    applySettingsSurface(state.prefs)
                    // Night mode recreates activities on its own; an accent
                    // change has to ask, and only when it really changed.
                    ThemeEngine.applyMode(this@SettingsActivity, state.prefs)
                    if (state.prefs.accentColor != appliedAccent) {
                        appliedAccent = state.prefs.accentColor
                        recreate()
                    }
                }
            }
        }
    }

    // The SETTINGS surface falls back to the theme background when unstyled.
    // The grouped pages' cards stand out from it: the theme's surface colour
    // over the theme background, else the styled surface lifted toward white
    // (a lot on a light one, a touch on a dark one).
    private fun applySettingsSurface(prefs: LauncherPrefs) {
        val surface = Styles.resolve(this, prefs).of(SurfaceId.SETTINGS).surface
        binding.root.setBackgroundColor(surface)
        groupCards.cardColor = when {
            surface == getColor(R.color.background) -> getColor(R.color.surface)
            androidx.core.graphics.ColorUtils.calculateLuminance(surface or OPAQUE) > HALF ->
                androidx.core.graphics.ColorUtils.blendARGB(surface, android.graphics.Color.WHITE, CARD_LIFT_LIGHT)
            else -> androidx.core.graphics.ColorUtils.blendARGB(surface, android.graphics.Color.WHITE, CARD_LIFT_DARK)
        }
        groupCards.dividerColor = getColor(R.color.settingsDivider)
        binding.settingsRecycler.invalidateItemDecorations()
    }

    // The Labs switch: hold volume down on the settings root. A short
    // press still turns the volume down, so the key is only taken there.
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN && labsKeyWatched()) {
            event.startTracking()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN && labsKeyWatched()) {
            val enable = !viewModel.state.value.prefs.labsEnabled
            viewModel.setLabsEnabled(enable)
            Toast.makeText(
                this, if (enable) R.string.labs_enabled else R.string.labs_disabled, Toast.LENGTH_SHORT
            ).show()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN && event.isTracking && labsKeyWatched()) {
            // A long press ends in a canceled up; anything else was a tap.
            if (!event.isCanceled) {
                getSystemService(android.media.AudioManager::class.java)?.adjustSuggestedStreamVolume(
                    android.media.AudioManager.ADJUST_LOWER,
                    android.media.AudioManager.USE_DEFAULT_STREAM_TYPE,
                    android.media.AudioManager.FLAG_SHOW_UI
                )
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun labsKeyWatched(): Boolean =
        currentTab == Tab.SETTINGS && currentSection == null && !searchActive

    override fun onResume() {
        super.onResume()
        // The default-home row depends on role state that can change while
        // the activity is backgrounded.
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_TAB, currentTab.name)
        currentSection?.let { outState.putInt(STATE_SECTION, it.title) }
        outState.putIntArray(STATE_OPEN_ACCORDIONS, adapter.openAccordions)
    }

    // Sections reached only from a built-in card's gear on the Cards page.
    private val cardSections = setOf(
        R.string.cards_media_title, R.string.feed_calendar_title, R.string.feed_contacts_title
    )

    // Pages opened from a row on another page rather than from the root,
    // and the page that row is on: back returns there.
    private val subPageParents = mapOf(
        R.string.desktop_search_bar to R.string.settings_section_search,
        R.string.drawer_search_bar to R.string.settings_section_search
    )

    private fun render() {
        val section = currentSection
        backCallback.isEnabled = section != null || searchActive
        // The Cards page adds from a FAB.
        binding.cardsFab.visibility =
            if (section?.title == R.string.settings_section_cards && !searchActive && currentTab == Tab.SETTINGS) {
                View.VISIBLE
            } else {
                View.GONE
            }

        applyHeader(section)

        // Search mode replaces whatever list was showing with flat results;
        // the rows are plain Rows, so the ordinary adapter renders them. Each
        // new query starts its results from the top.
        if (searchActive) {
            // Results span every section, so a section's pinned preview goes.
            bindPreviewPane(section)
            adapter.submit(searchResultItems(), viewModel.state.value.prefs)
            binding.settingsRecycler.scrollToPosition(0)
            return
        }

        if (currentTab == Tab.STYLE) {
            // A section's pinned preview stays behind with it.
            bindPreviewPane(null)
            adapter.submit(styleSection.items, viewModel.state.value.prefs, page = styleSection.title, grouped = true)
            return
        }

        if (section != null) binding.settingsTitle.text = getString(section.title)

        val items = if (section == null) {
            rootItems()
        } else {
            // The pinned pane owns the preview (F2); the list keeps the rest.
            if (bindPreviewPane(section)) {
                section.items.filterNot { it is SettingItem.Preview }
            } else {
                section.items
            }
        }
        if (section == null) bindPreviewPane(null)
        adapter.submit(items, viewModel.state.value.prefs, page = section?.title ?: 0, grouped = section == null)
    }

    // The root list, grouped: Set as default launcher on a card of its own,
    // then the sections in three labelled cards. A section no group names
    // (a new one) joins the last card, so it is never lost from the list.
    private fun rootItems(): List<SettingItem> {
        val shown = sections.filter {
            it.items.isNotEmpty() && it.title !in cardSections && it.title !in subPageParents
        }
        val groups = listOf(
            R.string.settings_group_screens to listOf(
                R.string.settings_section_home, R.string.settings_section_dock, R.string.settings_section_drawer,
                R.string.settings_section_folders, R.string.settings_section_feed
            ),
            R.string.settings_group_customize to listOf(
                R.string.settings_section_icons, R.string.gestures, R.string.settings_section_search,
                R.string.settings_section_cards
            ),
            R.string.settings_group_app to listOf(R.string.settings_section_backup, R.string.about, R.string.labs)
        )
        val grouped = groups.flatMap { it.second }.toSet()
        val unplaced = shown.filter { it.title !in grouped }
        return buildList {
            add(
                SettingItem.SectionLink(
                    R.string.set_default_launcher,
                    icon = R.drawable.ic_settings_default,
                    subtitle = { defaultLauncherLabel() },
                    onClick = {
                        if (isHomeRoleNeeded()) requestHomeRole() else openHomeSettings()
                    }
                )
            )
            groups.forEachIndexed { index, (label, titles) ->
                val members = titles.mapNotNull { title -> shown.firstOrNull { it.title == title } } +
                    if (index == groups.lastIndex) unplaced else emptyList()
                if (members.isEmpty()) return@forEachIndexed
                add(SettingItem.Header(label))
                members.forEach { add(sectionLink(it)) }
            }
        }
    }

    private fun sectionLink(s: SettingsSection): SettingItem.SectionLink =
        SettingItem.SectionLink(
            s.title,
            icon = sectionIcon(s.title),
            subtitle = { sectionSubtitle(s.title) },
            onClick = {
                currentSection = s
                render()
            },
            visibleIf = if (s.title == R.string.labs) {
                { prefs -> prefs.labsEnabled }
            } else {
                { true }
            }
        )

    // The root list and the Style tab wear the large header: a title that
    // scrolls away over the pinned search pill. A section screen wears the
    // toolbar (back, title, search button) instead. A different page starts
    // at its top with the title showing; searching does not count as one, so
    // the pill stays where it is while it is typed into.
    private var shownPage: Pair<Tab, Int?>? = null

    private fun applyHeader(section: SettingsSection?) {
        val root = section == null
        binding.rootTitle.isVisible = root
        binding.rootTitle.setText(if (currentTab == Tab.STYLE) R.string.style_tab else R.string.settings)
        binding.rootSearch.isVisible = root
        binding.settingsToolbar.isVisible = !root
        if (!root && binding.rootSearchInput.hasFocus()) {
            binding.rootSearchInput.clearFocus()
            getSystemService(InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(binding.rootSearchInput.windowToken, 0)
        }
        val page = currentTab to section?.title
        if (page != shownPage) {
            shownPage = page
            binding.settingsAppBar.setExpanded(true, false)
            binding.settingsRecycler.scrollToPosition(0)
        }
    }

    // Leading icons for the root list.
    @androidx.annotation.DrawableRes
    private fun sectionIcon(@androidx.annotation.StringRes sectionTitle: Int): Int =
        when (sectionTitle) {
            R.string.settings_section_home -> R.drawable.ic_settings_home
            R.string.settings_section_dock -> R.drawable.ic_settings_dock
            R.string.settings_section_drawer -> R.drawable.ic_settings_drawer
            R.string.settings_section_search -> R.drawable.ic_settings_search
            R.string.settings_section_feed -> R.drawable.ic_settings_feed
            R.string.settings_section_cards -> R.drawable.ic_settings_cards
            R.string.settings_section_folders -> R.drawable.ic_settings_folders
            R.string.settings_section_icons -> R.drawable.ic_settings_icons
            R.string.gestures -> R.drawable.ic_settings_gestures
            R.string.settings_section_backup -> R.drawable.ic_settings_backup
            R.string.about -> R.drawable.ic_settings_about
            R.string.labs -> R.drawable.ic_settings_labs
            else -> 0
        }

    // Parks the section's first visible Preview in the pinned pane. Returns
    // whether the pane took it; false hides the pane (root, search, Style).
    private fun bindPreviewPane(section: SettingsSection?): Boolean {
        val prefs = viewModel.state.value.prefs
        val preview = section?.items
            ?.firstOrNull { it is SettingItem.Preview && it.visibleIf(prefs) }
            as? SettingItem.Preview
        if (preview == null || searchActive || currentTab != Tab.SETTINGS) {
            binding.previewPane.visibility = View.GONE
            binding.previewPane.showsWallpaper = false
            return false
        }
        binding.previewPane.visibility = View.VISIBLE
        binding.previewPane.showsWallpaper =
            preview.kind == PreviewKind.HOME || preview.kind == PreviewKind.SEARCH_BAR
        // Same guard as the in-list holder: never rebuild mid slider drag.
        if (!previews.isTracking) {
            val view = paneView ?: com.supernova.launcher.ui.settings.preview.SettingsPreviewView(this).also {
                paneView = it
                binding.previewPane.addView(
                    it,
                    android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                    )
                )
            }
            previews.attach(view, preview.kind)
        }
        return true
    }

    // Root-row blurbs. Long on purpose so they render under the
    // title instead of as value pills.
    private fun sectionSubtitle(@androidx.annotation.StringRes sectionTitle: Int): CharSequence? =
        when (sectionTitle) {
            R.string.settings_section_home -> getString(R.string.section_home_subtitle)
            R.string.settings_section_dock -> getString(R.string.section_dock_subtitle)
            R.string.settings_section_drawer -> getString(R.string.section_drawer_subtitle)
            R.string.settings_section_search -> getString(R.string.section_search_subtitle)
            R.string.settings_section_feed -> getString(R.string.section_feed_subtitle)
            R.string.settings_section_cards -> getString(R.string.section_cards_subtitle)
            R.string.settings_section_folders -> getString(R.string.section_folders_subtitle)
            R.string.settings_section_icons -> getString(R.string.section_icons_subtitle)
            R.string.gestures -> getString(R.string.section_gestures_subtitle)
            R.string.settings_section_backup -> getString(R.string.section_backup_subtitle)
            R.string.about -> getString(R.string.section_about_subtitle)
            R.string.labs -> getString(R.string.section_labs_subtitle)
            else -> null
        }

    private fun searchResultItems(): List<SettingItem> {
        // The pill is the search field under the large header; a section screen
        // searches from its toolbar.
        val input = if (currentSection == null) binding.rootSearchInput else binding.settingsSearchInput
        val query = input.text?.toString().orEmpty()
        return searchIndex.search(query, limit = 40).map { entry ->
            // The entry's own name: a control found inside a row (the Label
            // card's size) is listed as itself and leads to that row.
            SettingItem.Row(
                entry.itemTitleRes,
                subtitle = { entry.sectionLabel },
                onClick = { navigateTo(entry.sectionTitleRes, entry.itemTitleRes) },
                titleText = entry.itemLabel
            )
        }
    }

    private fun setSearchActive(active: Boolean) {
        if (searchActive == active) return
        searchActive = active
        val imm = getSystemService(InputMethodManager::class.java)
        if (currentSection == null) {
            // The pill: typing is what starts a search there, so only the
            // way out needs doing. The flag is already down, so clearing the
            // text does not come back through the pill's text watcher.
            if (!active) {
                binding.rootSearchInput.text?.clear()
                binding.rootSearchInput.clearFocus()
                imm?.hideSoftInputFromWindow(binding.rootSearchInput.windowToken, 0)
            }
        } else {
            binding.settingsTitle.visibility = if (active) View.GONE else View.VISIBLE
            binding.settingsSearchInput.visibility = if (active) View.VISIBLE else View.GONE
            if (active) {
                binding.settingsSearchInput.requestFocus()
                imm?.showSoftInput(binding.settingsSearchInput, 0)
            } else {
                binding.settingsSearchInput.text?.clear()
                imm?.hideSoftInputFromWindow(binding.settingsSearchInput.windowToken, 0)
            }
        }
        render()
    }

    // Lands on the section (or the Style tab) holding the matched row, then
    // scrolls it into view; the adapter flashes it on its next bind.
    // singleTask in its own task: reopening Settings resumes the existing
    // instance, so a deep link arrives here and not through onCreate.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    // Deep link from the global search overlay: land on the section and flash
    // the matched row.
    private fun handleDeepLink(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_OPEN_SEARCH_BAR, false)) {
            navigateTo(R.string.desktop_search_bar, 0)
            return
        }
        val section = intent.getIntExtra(EXTRA_SECTION_TITLE_RES, 0)
        if (section != 0) navigateTo(section, intent.getIntExtra(EXTRA_HIGHLIGHT_TITLE_RES, 0))
    }

    private fun navigateTo(sectionTitleRes: Int, itemTitleRes: Int, flash: Boolean = true) {
        setSearchActive(false)
        if (flash) adapter.setHighlight(itemTitleRes)
        if (sectionTitleRes == styleSection.title) {
            currentSection = null
            if (currentTab != Tab.STYLE) {
                // The tab listener switches currentTab and renders.
                binding.settingsTabs.selectedItemId = R.id.tabStyle
            } else {
                render()
            }
        } else {
            if (currentTab != Tab.SETTINGS) {
                binding.settingsTabs.selectedItemId = R.id.tabSettings
            }
            currentSection = sections.firstOrNull { it.title == sectionTitleRes }
            render()
        }
        val position = adapter.positionOf(itemTitleRes)
        if (position >= 0) {
            (binding.settingsRecycler.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(position, dp(72))
        } else if (flash) {
            // The row is currently hidden by its visibleIf; landing on the
            // section without a flash is the honest outcome.
            adapter.clearHighlight()
        }
    }


    private fun showPaletteSourcePicker() {
        val modes = PaletteSourceMode.entries
        val labels = modes.map { getString(EnumLabels.of(it)) }.toTypedArray()
        val current = modes.indexOf(PaletteSourceMode.fromName(viewModel.state.value.prefs.paletteSource))

        showChoice(R.string.color_palette, labels, current) { which ->
            val mode = modes[which]
            viewModel.setPaletteSource(mode.name)
            if (mode == PaletteSourceMode.CUSTOM) promptPaletteSeed()
        }
    }

    private fun promptPaletteSeed() {
        val input = EditText(this).apply {
            hint = getString(R.string.palette_seed_hint)
            setText(viewModel.state.value.prefs.paletteSeed.removePrefix("#"))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.palette_seed_title)
            .setView(com.supernova.launcher.ui.DialogField.wrap(input))
            .setPositiveButton(R.string.save) { _, _ ->
                val digits = input.text?.toString().orEmpty().trim().removePrefix("#")
                if (digits.length == 6 && digits.toLongOrNull(16) != null) {
                    viewModel.setPaletteSeed("#$digits")
                } else {
                    Toast.makeText(this, R.string.palette_seed_invalid, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // Current default home's label, shown as the row's value pill.
    private fun defaultLauncherLabel(): CharSequence? = HomeRole.defaultHomeLabel(this)

    // Once we hold the default, the system picker is the only place to change
    // it; ACTION_HOME_SETTINGS is the closest deep link every OEM ships.
    private fun openHomeSettings() {
        runCatching { startActivity(HomeRole.homeSettingsIntent()) }
    }

    private fun isHomeRoleNeeded(): Boolean = HomeRole.isNeeded(this)

    private fun requestHomeRole() {
        HomeRole.requestIntent(this)?.let { requestHomeRoleLauncher.launch(it) }
    }

    // The action picker: the launcher's actions, any app (hidden ones
    // too) or one of its shortcuts, or an app's shortcut screen.
    private fun showActionPicker(gesture: Gesture) {
        com.supernova.launcher.ui.ActionPickerSheet.show(
            this, viewModel, getString(EnumLabels.of(gesture)), GestureDefaults.pickerActions
        ) { pick ->
            val binding = when (pick) {
                com.supernova.launcher.ui.ActionPick.None -> GestureBinding(GestureAction.NONE)
                is com.supernova.launcher.ui.ActionPick.Action -> GestureBinding(pick.action)
                is com.supernova.launcher.ui.ActionPick.Page ->
                    GestureBinding(GestureAction.JUMP_TO_PAGE, payload = pick.index.toString(), label = pick.label)
                is com.supernova.launcher.ui.ActionPick.App -> GestureBinding(
                    GestureAction.LAUNCH_APP,
                    payload = "${pick.app.packageName}/${pick.app.className}@${pick.app.userSerial}",
                    label = pick.app.label
                )
                is com.supernova.launcher.ui.ActionPick.Shortcut -> GestureBinding(
                    GestureAction.LAUNCH_SHORTCUT,
                    payload = "${pick.app.packageName}|${pick.shortcutId}@${pick.app.userSerial}",
                    label = pick.label
                )
                is com.supernova.launcher.ui.ActionPick.Legacy -> {
                    pendingGestureShortcut = gesture
                    runCatching {
                        createGestureShortcut.launch(Intent(Intent.ACTION_CREATE_SHORTCUT).setComponent(pick.component))
                    }.onFailure {
                        pendingGestureShortcut = null
                        Toast.makeText(this, R.string.launch_failed, Toast.LENGTH_SHORT).show()
                    }
                    return@show
                }
            }
            viewModel.setGestureBinding(gesture, binding)
        }
    }

    private fun showIconShapePicker() {
        val shapes = IconShape.entries
        val labels = shapes.map { getString(EnumLabels.of(it)) }.toTypedArray()
        val current = shapes.indexOf(IconShape.fromName(viewModel.state.value.prefs.iconShape))

        showChoice(R.string.icon_shape, labels, current) { which ->
            val shape = shapes[which]
            // CUSTOM routes through the per-corner editor; only its OK
            // commits, so cancelling never half-applies the shape.
            if (shape == IconShape.CUSTOM) {
                showCustomShapeEditor()
            } else {
                viewModel.setIconShape(shape)
            }
        }
    }

    // Per-corner editor for the CUSTOM icon shape: a 0-100% stepper plus a
    // Round/Squircle/Cut toggle per corner, previewed live. OK commits both
    // the spec and iconShape=CUSTOM together.
    private fun showCustomShapeEditor() {
        val dialogBinding = DialogCustomShapeBinding.inflate(layoutInflater)
        val spec = CustomShapeSpec.decode(viewModel.state.value.prefs.iconShapeCustom)
        // Working state, indexed in spec order [TL, TR, BR, BL].
        val radii = intArrayOf(spec.radiusAt(0), spec.radiusAt(1), spec.radiusAt(2), spec.radiusAt(3))
        val cuts = arrayOf(spec.cutAt(0), spec.cutAt(1), spec.cutAt(2), spec.cutAt(3))

        fun current() = CustomShapeSpec(radii.toList(), cuts.map { it.name })

        val values = listOf(
            dialogBinding.tlValue, dialogBinding.trValue, dialogBinding.brValue, dialogBinding.blValue
        )
        val cutToggles = listOf(
            dialogBinding.tlCut, dialogBinding.trCut, dialogBinding.brCut, dialogBinding.blCut
        )

        fun refresh() {
            values.forEachIndexed { index, view ->
                view.text = getString(R.string.opacity_percent, radii[index])
            }
            cutToggles.forEachIndexed { index, view ->
                view.text = getString(EnumLabels.of(cuts[index]))
            }
            dialogBinding.shapePreview.spec = current()
        }

        fun wireCorner(index: Int, minus: android.view.View, plus: android.view.View) {
            minus.setOnClickListener {
                radii[index] = (radii[index] - RADIUS_STEP).coerceAtLeast(0)
                refresh()
            }
            plus.setOnClickListener {
                radii[index] = (radii[index] + RADIUS_STEP).coerceAtMost(100)
                refresh()
            }
            cutToggles[index].setOnClickListener {
                cuts[index] = CornerCut.entries[(cuts[index].ordinal + 1) % CornerCut.entries.size]
                refresh()
            }
        }
        wireCorner(0, dialogBinding.tlMinus, dialogBinding.tlPlus)
        wireCorner(1, dialogBinding.trMinus, dialogBinding.trPlus)
        wireCorner(2, dialogBinding.brMinus, dialogBinding.brPlus)
        wireCorner(3, dialogBinding.blMinus, dialogBinding.blPlus)
        refresh()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.custom_shape_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.ok) { _, _ ->
                viewModel.setIconShapeCustom(current().encode())
                viewModel.setIconShape(IconShape.CUSTOM)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showHideClockPicker() {
        val modes = com.supernova.launcher.ui.HideClock.entries
        val labels = modes.map { getString(EnumLabels.of(it)) }.toTypedArray()
        val current = modes.indexOf(
            com.supernova.launcher.ui.HideClock.fromName(viewModel.state.value.prefs.hideClock)
        )

        showChoice(R.string.hide_clock, labels, current) { which ->
            viewModel.setHideClock(modes[which].name)
            // The permission only ever arrives over adb; picking an
            // active mode without it deserves the exact command.
            if (modes[which] != com.supernova.launcher.ui.HideClock.NEVER &&
                checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.hide_clock)
                    .setMessage(
                        getString(
                            R.string.hide_clock_permission_message,
                            "adb shell pm grant $packageName " +
                                "android.permission.WRITE_SECURE_SETTINGS"
                        )
                    )
                    .setPositiveButton(R.string.ok, null)
                    .show()
            }
        }
    }

    // Two chained time pickers: dark-from, then dark-until (D1).
    private fun showThemeScheduleEditor() {
        val prefs = viewModel.state.value.prefs
        android.app.TimePickerDialog(
            this,
            { _, startHour, startMin ->
                android.app.TimePickerDialog(
                    this,
                    { _, endHour, endMin ->
                        viewModel.setThemeSchedule(startHour * 60 + startMin, endHour * 60 + endMin)
                    },
                    prefs.themeScheduleEnd / 60, prefs.themeScheduleEnd % 60, true
                ).apply { setTitle(getString(R.string.theme_schedule_until)) }.show()
            },
            prefs.themeScheduleStart / 60, prefs.themeScheduleStart % 60, true
        ).apply { setTitle(getString(R.string.theme_schedule_from)) }.show()
    }

    // Rows and columns edit against local state with a live mini-grid; only
    // OK commits, so Cancel never half-applies a grid.
    private fun showDesktopGridDialog() {
        val dialogBinding = DialogDesktopGridBinding.inflate(layoutInflater)
        val prefs = viewModel.state.value.prefs
        var rows = prefs.gridRows
        var cols = prefs.gridCols
        dialogBinding.gridPreview.cellColor = (0x30 shl 24) or (getColor(R.color.textPrimary) and 0xFFFFFF)

        fun refresh() {
            dialogBinding.rowsValue.text = rows.toString()
            dialogBinding.colsValue.text = cols.toString()
            dialogBinding.gridPreview.set(rows, cols)
        }
        // A grid set while Big grid was on keeps its size as the cap, so "+"
        // never shrinks it.
        val most = com.supernova.launcher.data.GridLimits.desktop(prefs)
        val mostRows = maxOf(most, prefs.gridRows)
        val mostCols = maxOf(most, prefs.gridCols)
        dialogBinding.rowsUp.setOnClickListener { rows = (rows + 1).coerceAtMost(mostRows); refresh() }
        dialogBinding.rowsDown.setOnClickListener { rows = (rows - 1).coerceAtLeast(2); refresh() }
        dialogBinding.colsUp.setOnClickListener { cols = (cols + 1).coerceAtMost(mostCols); refresh() }
        dialogBinding.colsDown.setOnClickListener { cols = (cols - 1).coerceAtLeast(2); refresh() }
        dialogBinding.subgridCheck.isChecked = prefs.subgrid
        refresh()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.desktop_grid)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.ok) { _, _ ->
                viewModel.setGridRows(rows)
                viewModel.setGridCols(cols)
                viewModel.setSubgrid(dialogBinding.subgridCheck.isChecked)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // The drawer twin of the desktop grid dialog, on the same layout: rows
    // and columns step down to 0 = Auto (rows only apply to the paged style,
    // columns to the grid styles); the subgrid checkbox has no drawer meaning
    // and hides. Only OK commits.
    private fun showDrawerGridDialog() {
        val dialogBinding = DialogDesktopGridBinding.inflate(layoutInflater)
        val prefs = viewModel.state.value.prefs
        var rows = prefs.drawerRows
        var cols = prefs.drawerColumns
        dialogBinding.gridPreview.cellColor = (0x30 shl 24) or (getColor(R.color.textPrimary) and 0xFFFFFF)
        dialogBinding.subgridCheck.visibility = View.GONE
        val auto = getString(R.string.drawer_columns_auto)

        fun refresh() {
            dialogBinding.rowsValue.text = rows.takeIf { it > 0 }?.toString() ?: auto
            dialogBinding.colsValue.text = cols.takeIf { it > 0 }?.toString() ?: auto
            // Preview the effective grid; Auto renders a representative one.
            dialogBinding.gridPreview.set(
                rows.takeIf { it > 0 } ?: 6,
                cols.takeIf { it > 0 } ?: 4
            )
        }
        val mostRows = maxOf(com.supernova.launcher.data.GridLimits.drawerRows(prefs), prefs.drawerRows)
        val mostCols = maxOf(com.supernova.launcher.data.GridLimits.drawerColumns(prefs), prefs.drawerColumns)
        dialogBinding.rowsUp.setOnClickListener { rows = (rows + 1).coerceAtMost(mostRows); refresh() }
        dialogBinding.rowsDown.setOnClickListener { rows = (rows - 1).coerceAtLeast(0); refresh() }
        dialogBinding.colsUp.setOnClickListener { cols = (cols + 1).coerceAtMost(mostCols); refresh() }
        dialogBinding.colsDown.setOnClickListener { cols = (cols - 1).coerceAtLeast(0); refresh() }
        refresh()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.drawer_grid)
            .setMessage(R.string.drawer_grid_rows_note)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.ok) { _, _ ->
                viewModel.setDrawerRows(rows)
                viewModel.setDrawerColumns(cols)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // Folder icon appearance: shape + outline/clip/window-color toggles, all
    // edited against a live FolderIconDrawable preview of placeholder tiles.
    // Only OK commits the four prefs.
    private fun showFolderIconEditor() {
        val dialogBinding = DialogFolderIconBinding.inflate(layoutInflater)
        val prefs = viewModel.state.value.prefs
        var shapeName = prefs.folderIconShape
        dialogBinding.outlineCheck.isChecked = prefs.folderIconOutline
        dialogBinding.clipCheck.isChecked = prefs.folderClipToBg
        dialogBinding.matchColorCheck.isChecked = prefs.folderMatchWindowColor

        // Placeholder members: colored tiles, so the preview needs no app icons.
        val sampleIcons = listOf(0xFFEF5350, 0xFF42A5F5, 0xFF66BB6A, 0xFFFFCA28).map { color ->
            android.graphics.drawable.GradientDrawable().apply {
                setColor(color.toInt())
                cornerRadius = 8f * resources.displayMetrics.density
            }
        }
        val resolved = Styles.resolve(this, prefs).of(SurfaceId.FOLDER)

        fun refresh() {
            dialogBinding.shapeValue.text = if (shapeName.isEmpty()) {
                getString(R.string.folder_shape_match_icons)
            } else {
                getString(EnumLabels.of(IconShape.fromName(shapeName)))
            }
            dialogBinding.folderIconPreview.setImageDrawable(
                com.supernova.launcher.ui.FolderIconDrawable(
                    sampleIcons,
                    dp(96),
                    style = FolderPreviewStyle.fromName(prefs.folderPreviewStyle),
                    plateColor = if (dialogBinding.matchColorCheck.isChecked) {
                        resolved.surface
                    } else {
                        com.supernova.launcher.ui.FolderIconDrawable.DEFAULT_PLATE_COLOR
                    },
                    shape = com.supernova.launcher.ui.FolderIconDrawable.plateShape(shapeName, prefs),
                    customSpec = CustomShapeSpec.decode(prefs.iconShapeCustom),
                    outlineColor = if (dialogBinding.outlineCheck.isChecked) resolved.accent else 0,
                    outlineWidthPx = 2f * resources.displayMetrics.density,
                    clipMembers = dialogBinding.clipCheck.isChecked,
                    prioritizeFirst = prefs.folderPrioritizeFirst,
                    glass = com.supernova.launcher.ui.FolderIconDrawable.glassFor(prefs),
                    shadow = prefs.iconShadow
                )
            )
        }

        dialogBinding.shapeRow.setOnClickListener {
            val shapes = IconShape.entries.filter { it != IconShape.SYSTEM }
            val names = listOf("") + shapes.map { it.name }
            val labels = (listOf(getString(R.string.folder_shape_match_icons)) +
                shapes.map { getString(EnumLabels.of(it)) }).toTypedArray()
            val current = names.indexOf(shapeName).coerceAtLeast(0)
            showChoice(R.string.folder_icon_shape, labels, current) { which ->
                shapeName = names[which]
                refresh()
            }
        }
        listOf(dialogBinding.outlineCheck, dialogBinding.clipCheck, dialogBinding.matchColorCheck)
            .forEach { box -> box.setOnCheckedChangeListener { _, _ -> refresh() } }
        refresh()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.folder_icon_appearance)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.ok) { _, _ ->
                viewModel.setFolderIconShape(shapeName)
                viewModel.setFolderIconOutline(dialogBinding.outlineCheck.isChecked)
                viewModel.setFolderClipToBg(dialogBinding.clipCheck.isChecked)
                viewModel.setFolderMatchWindowColor(dialogBinding.matchColorCheck.isChecked)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showLockMethodPicker() {
        val methods = listOf("ACCESSIBILITY", "DEVICE_ADMIN")
        val labels = arrayOf(
            getString(R.string.lock_method_accessibility),
            getString(R.string.lock_method_device_admin)
        )
        val current = methods.indexOf(viewModel.state.value.prefs.lockMethod).coerceAtLeast(0)
        showChoice(R.string.lock_method, labels, current) { which ->
            viewModel.setLockMethod(methods[which])
            // Selecting device admin sends the user to grant it (a no-op if
            // already active); the actual lock happens on the gesture.
            if (methods[which] == "DEVICE_ADMIN" && !SupernovaDeviceAdmin.isActive(this)) {
                val intent = android.content.Intent(
                    android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN
                ).putExtra(
                    android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                    SupernovaDeviceAdmin.component(this)
                ).putExtra(
                    android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    getString(R.string.lock_admin_explanation)
                )
                runCatching { startActivity(intent) }
                Toast.makeText(this, R.string.lock_admin_note, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showAutoBackups() {
        val backups = viewModel.autoBackups()
        if (backups.isEmpty()) {
            Toast.makeText(this, R.string.no_auto_backups, Toast.LENGTH_SHORT).show()
            return
        }
        val format = java.text.DateFormat.getDateTimeInstance()
        val labels = backups.map { file ->
            val stamp = file.name.removePrefix("layout-").removeSuffix(".json").toLongOrNull()
            if (stamp != null) format.format(java.util.Date(stamp)) else file.name
        }.toTypedArray()

        showChoice(R.string.auto_backups, labels, -1) { which ->
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.restore_backup_q)
                .setMessage(labels[which])
                .setPositiveButton(R.string.restore) { _, _ ->
                    lifecycleScope.launch {
                        runCatching { viewModel.restoreAutoBackup(backups[which]) }
                            .onSuccess {
                                Toast.makeText(
                                    this@SettingsActivity,
                                    it.summary(resources),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            .onFailure {
                                Toast.makeText(
                                    this@SettingsActivity,
                                    getString(R.string.backup_import_failed, it.message ?: "unknown"),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    // Share a fresh export through a chooser. The payload is staged in
    // cacheDir/share, the only path the manifest FileProvider exposes.
    private fun shareBackup() {
        lifecycleScope.launch {
            runCatching {
                val payload = viewModel.exportBackup()
                val dir = java.io.File(cacheDir, "share").apply { mkdirs() }
                val file = java.io.File(dir, "backup-${System.currentTimeMillis()}.json")
                file.writeText(payload)
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    this@SettingsActivity, "$packageName.fileprovider", file
                )
                val send = Intent(Intent.ACTION_SEND)
                    .setType("application/json")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(Intent.createChooser(send, getString(R.string.share_backup)))
            }.onFailure {
                Toast.makeText(
                    this@SettingsActivity,
                    getString(R.string.backup_export_failed, it.message ?: "unknown"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // About > Crash log. Nothing to share: say so. Otherwise share it as a
    // file headed with the version and device a report needs, or clear it
    // once it has been sent so the next report holds only new crashes.
    private fun showCrashLog() {
        val count = SupernovaApp.crashCount(this)
        if (count == 0) {
            Toast.makeText(this, R.string.crash_log_none, Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.crash_log)
            .setMessage(resources.getQuantityString(R.plurals.crash_log_message, count, count))
            .setPositiveButton(R.string.crash_log_share_button) { _, _ -> shareCrashLog() }
            .setNeutralButton(R.string.crash_log_clear) { _, _ ->
                SupernovaApp.crashLog(this).delete()
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // Staged in cacheDir/share like the backup, the only path the manifest
    // FileProvider exposes.
    private fun shareCrashLog() {
        runCatching {
            val header = "Supernova ${SupernovaApp.versionLabel(this)}\n" +
                "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}), " +
                "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n"
            val dir = java.io.File(cacheDir, "share").apply { mkdirs() }
            val file = java.io.File(dir, "supernova-crash-log.txt")
            file.writeText(header + SupernovaApp.crashLog(this).readText())
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.crash_log_subject))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, getString(R.string.crash_log_share)))
        }.onFailure {
            Toast.makeText(this, getString(R.string.crash_log_share_failed, it.message ?: "unknown"), Toast.LENGTH_LONG)
                .show()
        }
    }

    private fun confirmResetCustomIcons() {
        if (viewModel.customIcons().total == 0) {
            Toast.makeText(this, R.string.reset_custom_icons_none_toast, Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.reset_custom_icons)
            .setMessage(R.string.reset_custom_icons_message)
            .setPositiveButton(R.string.reset) { _, _ -> viewModel.resetCustomIcons() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmResetToDefaults() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.reset_to_default)
            .setMessage(R.string.reset_to_default_message)
            .setPositiveButton(R.string.reset) { _, _ ->
                lifecycleScope.launch {
                    // Capture the doomed widget ids before the model goes away.
                    val widgetIds = runCatching { viewModel.persistedWidgetIds() }.getOrNull().orEmpty()
                    runCatching { viewModel.resetToDefaults() }
                        .onSuccess {
                            // Free the host ids now instead of waiting for the
                            // next launcher create's orphan sweep; the host is
                            // addressed purely by id, so a local instance works.
                            runCatching {
                                val host = android.appwidget.AppWidgetHost(
                                    applicationContext, SupernovaWidgetHost.HOST_ID
                                )
                                widgetIds.forEach(host::deleteAppWidgetId)
                            }
                            Toast.makeText(this@SettingsActivity, R.string.reset_done, Toast.LENGTH_SHORT).show()
                        }
                        .onFailure {
                            Toast.makeText(
                                this@SettingsActivity,
                                getString(R.string.reset_failed, it.message ?: "unknown"),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ProcessPhoenix-style restart: schedule the launcher's MAIN/HOME intent
    // a beat from now, then hard-exit the process. recreate() only rebuilds
    // one activity; a real restart needs a fresh process.
    private fun restartLauncher() {
        runCatching {
            val intent = Intent(this, LauncherActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            val pending = android.app.PendingIntent.getActivity(
                applicationContext,
                0,
                intent,
                android.app.PendingIntent.FLAG_CANCEL_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            getSystemService(android.app.AlarmManager::class.java)?.set(
                android.app.AlarmManager.RTC,
                System.currentTimeMillis() + 100,
                pending
            )
            finishAffinity()
            Runtime.getRuntime().exit(0)
        }
    }

    // Multi-select mirroring the hidden-apps picker. An empty stored set means
    // The Test notification badge. The badge only shows with notification
    // access, so that is asked for first, as switching badges on does.
    private fun postTestBadge() {
        if (!SupernovaNotificationListener.isEnabled(this)) {
            Toast.makeText(this, R.string.badges_need_access, Toast.LENGTH_LONG).show()
            runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPostNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val posted = com.supernova.launcher.notification.BadgeTestNotification.post(this)
        when {
            !posted -> Toast.makeText(this, R.string.badge_test_blocked, Toast.LENGTH_LONG).show()
            // Posted, but nothing will show it.
            !viewModel.state.value.prefs.badges ->
                Toast.makeText(this, R.string.badge_test_badges_off, Toast.LENGTH_LONG).show()
        }
    }

    // "badge everything", so the dialog opens fully checked and re-checking
    // every app normalizes back to empty (keeping new installs badged).
    private fun showBadgedAppsPicker() {
        val apps = viewModel.state.value.apps
        if (apps.isEmpty()) return
        val keys = apps.map { "${it.userSerial}/${it.packageName}/${it.className}" }
        val stored = viewModel.state.value.prefs.badgeApps
        val selected = (if (stored.isEmpty()) keys else stored).toMutableSet()
        showAppChecklist(
            title = getString(R.string.badged_apps),
            apps = apps,
            checked = { "${it.userSerial}/${it.packageName}/${it.className}" in selected },
            onToggle = { app, isChecked ->
                val key = "${app.userSerial}/${app.packageName}/${app.className}"
                if (isChecked) selected.add(key) else selected.remove(key)
                viewModel.setBadgeApps(if (selected.containsAll(keys)) emptySet() else selected.toSet())
            }
        )
    }

    // Multi-select over the whole chip set; the stored order follows the enum,
    // which is the order the row draws them in.
    private fun showSearchChipsEditor() {
        val all = com.supernova.launcher.search.SearchChip.entries
        val labels = all.map { getString(EnumLabels.of(it)) }.toTypedArray()
        val selected = com.supernova.launcher.search.SearchChip
            .decode(viewModel.state.value.prefs.searchChips)
        val checked = BooleanArray(all.size) { all[it] in selected }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.search_chips)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(R.string.ok) { _, _ ->
                viewModel.setSearchChips(
                    com.supernova.launcher.search.SearchChip.encode(
                        all.filterIndexed { index, _ -> checked[index] }
                    )
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // One line of the Drawer groups page: a section header, a group, or
    // the section's add row.
    private sealed class GroupRow {
        class Header(@androidx.annotation.StringRes val title: Int) : GroupRow()
        class Group(val group: DrawerGroup) : GroupRow()
        class Add(val type: DrawerGroupType) : GroupRow()
    }

    // The Drawer groups page: Tabs, then Folders, each group with the
    // apps it holds under its name, and an add row per section.
    private fun showDrawerGroups() {
        val groups = viewModel.drawerGroups()
        val rows = buildList {
            add(GroupRow.Header(R.string.group_tabs_header))
            groups.filter { it.type == DrawerGroupType.TAB }.forEach { add(GroupRow.Group(it)) }
            add(GroupRow.Add(DrawerGroupType.TAB))
            add(GroupRow.Header(R.string.group_folders_header))
            groups.filter { it.type == DrawerGroupType.FOLDER }.forEach { add(GroupRow.Group(it)) }
            add(GroupRow.Add(DrawerGroupType.FOLDER))
        }
        val labelsByKey = viewModel.state.value.apps.associate { viewModel.hiddenKey(it) to it.label }
        val adapter = object : android.widget.BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(position: Int) = rows[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun areAllItemsEnabled() = false
            override fun isEnabled(position: Int) = rows[position] !is GroupRow.Header
            override fun getViewTypeCount() = GROUP_ROW_TYPES
            override fun getItemViewType(position: Int) = when (rows[position]) {
                is GroupRow.Header -> 0
                is GroupRow.Group -> 1
                is GroupRow.Add -> 2
            }

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                when (val row = rows[position]) {
                    is GroupRow.Header -> (convertView ?: inflate(R.layout.row_group_header, parent)).apply {
                        (this as TextView).setText(row.title)
                    }
                    is GroupRow.Group -> (convertView ?: inflate(R.layout.row_group, parent)).apply {
                        findViewById<TextView>(R.id.groupName).text = row.group.name
                        findViewById<TextView>(R.id.groupSummary).text = groupSummary(row.group, labelsByKey)
                    }
                    is GroupRow.Add -> (convertView ?: inflate(R.layout.row_group_add, parent)).apply {
                        (this as TextView).setText(
                            if (row.type == DrawerGroupType.TAB) R.string.new_tab else R.string.new_folder_group
                        )
                    }
                }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.drawer_groups)
            .setAdapter(adapter) { _, which ->
                when (val row = rows[which]) {
                    is GroupRow.Group -> showGroupActions(row.group)
                    is GroupRow.Add -> promptNewGroup(row.type)
                    is GroupRow.Header -> Unit
                }
            }
            .setPositiveButton(R.string.done, null)
            .show()
    }

    private fun inflate(layout: Int, parent: ViewGroup): View = layoutInflater.inflate(layout, parent, false)

    // "Maps, Rapido, Uber", the line under a group; "Empty" for none.
    private fun groupSummary(group: DrawerGroup, labelsByKey: Map<String, String>): String {
        val names = group.members.mapNotNull { labelsByKey[it] }.sortedBy { it.lowercase() }
        if (names.isEmpty()) return getString(R.string.group_empty)
        val shown = names.take(GROUP_SUMMARY_NAMES).joinToString(", ")
        return if (names.size > GROUP_SUMMARY_NAMES) "$shown, …" else shown
    }

    // The menu on a group: its apps, its name, whether its apps stay in
    // the main list too, tab or folder, its place, and deleting it.
    private fun showGroupActions(group: DrawerGroup) {
        val keep = !viewModel.leavesMainList(group)
        val actions = listOf(
            getString(R.string.edit_group_apps),
            getString(R.string.group_rename),
            getString(if (keep) R.string.group_keep_in_main_on else R.string.group_keep_in_main_off),
            getString(
                if (group.type == DrawerGroupType.TAB) {
                    R.string.group_convert_to_folder
                } else {
                    R.string.group_convert_to_tab
                }
            ),
            getString(R.string.move_up),
            getString(R.string.move_down),
            getString(R.string.delete_group)
        )

        showChoice(group.name, actions, -1) { which ->
            when (which) {
                0 -> showGroupEditor(group)
                1 -> promptRenameGroup(group)
                2 -> {
                    viewModel.setGroupKeepInMain(group, !keep)
                    reopenGroupsSoon()
                }
                3 -> {
                    viewModel.setGroupType(
                        group,
                        if (group.type == DrawerGroupType.TAB) DrawerGroupType.FOLDER else DrawerGroupType.TAB
                    )
                    reopenGroupsSoon()
                }
                4 -> {
                    viewModel.moveGroup(group.name, -1)
                    reopenGroupsSoon()
                }
                5 -> {
                    viewModel.moveGroup(group.name, 1)
                    reopenGroupsSoon()
                }
                6 -> viewModel.removeGroup(group.name)
            }
        }
    }

    private fun promptRenameGroup(group: DrawerGroup) {
        val input = EditText(this).apply {
            hint = getString(R.string.group_name)
            setText(group.name)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.group_rename)
            .setView(com.supernova.launcher.ui.DialogField.wrap(input))
            .setPositiveButton(R.string.save) { _, _ ->
                if (viewModel.renameGroup(group, input.text?.toString().orEmpty()) == null) {
                    Toast.makeText(this, R.string.group_name_taken, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // Group mutations round-trip through DataStore; reopen the list after a
    // short beat so it reflects the new order.
    private fun reopenGroupsSoon() {
        lifecycleScope.launch {
            delay(150)
            showDrawerGroups()
        }
    }

    // Add drawer tab / Add drawer folder: a title, and whether the
    // group's apps stay in the main list as well (off by default).
    private fun promptNewGroup(type: DrawerGroupType) {
        val input = EditText(this).apply { hint = getString(R.string.group_name) }
        val dialogBinding = com.supernova.launcher.databinding.DialogNewGroupBinding.inflate(layoutInflater)
        dialogBinding.newGroupField.addView(com.supernova.launcher.ui.DialogField.wrap(input))
        val keep = dialogBinding.newGroupKeep
        val content = dialogBinding.root
        MaterialAlertDialogBuilder(this)
            .setTitle(if (type == DrawerGroupType.TAB) R.string.new_tab else R.string.new_folder_group)
            .setView(content)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text?.toString().orEmpty().trim()
                if (name.isEmpty()) return@setPositiveButton
                val created = viewModel.createGroup(name, type, keep.isChecked)
                if (created == null) {
                    Toast.makeText(this, R.string.group_name_taken, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                // Group starts empty; go straight to picking its apps. By the
                // stored name, which differs from the typed one for a name
                // with a '|' in it.
                lifecycleScope.launch {
                    delay(120)
                    viewModel.drawerGroups().firstOrNull { it.name == created }?.let(::showGroupEditor)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showGroupEditor(group: DrawerGroup) {
        showAppChecklist(
            title = group.name,
            apps = viewModel.visibleApps(),
            checked = { viewModel.isInGroup(it, group) },
            onToggle = { app, isChecked ->
                // Re-read the group each time so successive checks compose.
                val latest = viewModel.drawerGroups().firstOrNull { it.name == group.name } ?: group
                viewModel.setGroupMembership(latest, app, isChecked)
            }
        )
    }

    // The app pickers list each app with its icon and a checkbox; every
    // tick applies at once.
    private fun showAppChecklist(
        title: CharSequence,
        apps: List<LaunchableApp>,
        checked: (LaunchableApp) -> Boolean,
        onToggle: (LaunchableApp, Boolean) -> Unit
    ) {
        if (apps.isEmpty()) return
        val states = apps.map(checked).toBooleanArray()
        val adapter = object : android.widget.ArrayAdapter<LaunchableApp>(this, R.layout.row_folder_check, apps) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = convertView ?: layoutInflater.inflate(R.layout.row_folder_check, parent, false)
                val app = apps[position]
                row.findViewById<android.widget.ImageView>(R.id.folderCheckIcon)
                    .setImageDrawable(viewModel.appCatalog().iconFor(app))
                row.findViewById<android.widget.CheckedTextView>(R.id.folderCheckLabel).apply {
                    text = app.label
                    isChecked = states[position]
                }
                return row
            }
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setAdapter(adapter, null)
            .setPositiveButton(R.string.done, null)
            .create()
        dialog.listView.setOnItemClickListener { _, _, position, _ ->
            states[position] = !states[position]
            onToggle(apps[position], states[position])
            adapter.notifyDataSetChanged()
        }
        dialog.show()
    }

    private fun showHiddenAppsPicker() {
        showAppChecklist(
            title = getString(R.string.hidden_apps),
            apps = viewModel.state.value.apps,
            checked = { viewModel.isAppHidden(it) },
            onToggle = { app, hidden -> viewModel.setAppHidden(app, hidden) }
        )
    }

    // The picker shows what a pack looks like rather than only naming
    // it - a pack's name says nothing about its icons. Each row samples a few
    // drawables from the pack itself; the sampling is off the main thread
    // because it opens another app's resources, and the row draws as soon as
    // its own sample lands rather than waiting for every pack.
    // A settings choice opens as a menu under the row you tapped, not as
    // a dialog in the middle of a dimmed screen. Every choice picker on this
    // screen goes through here so they all behave that way. Without a live
    // anchor - a picker reached from inside a dialog, or one whose row has
    // scrolled off - it falls back to the dialog this used to be, because a
    // popup would open behind that dialog's window.
    private fun showChoice(
        title: CharSequence?,
        labels: List<CharSequence>,
        selected: Int,
        onPick: (Int) -> Unit
    ) {
        val anchor = menuAnchor?.takeIf { it.isAttachedToWindow && it.isShown }
        if (anchor == null) {
            // The title earns its place only here: a menu hanging off a row
            // is already labelled by the row.
            MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setSingleChoiceItems(
                    labels.map { it.toString() }.toTypedArray(), selected
                ) { dialog, which ->
                    dialog.dismiss()
                    onPick(which)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        ChoiceMenu.show(
            anchor = anchor,
            touchX = menuTouchX,
            surface = Styles.resolve(this, viewModel.state.value.prefs).of(SurfaceId.POPUP),
            labels = labels,
            selected = selected,
            animate = Animations.enabled(viewModel.state.value.prefs),
            onPick = onPick
        )
    }

    // Most pickers hold their options as an Array<String>, and name themselves
    // with a string resource, because that is what the dialog API this
    // replaced took. Both spellings pass straight through rather than being
    // converted at every call site.
    private fun showChoice(
        @androidx.annotation.StringRes title: Int,
        labels: List<CharSequence>,
        selected: Int,
        onPick: (Int) -> Unit
    ) = showChoice(getString(title), labels, selected, onPick)

    private fun showChoice(
        @androidx.annotation.StringRes title: Int,
        labels: Array<String>,
        selected: Int,
        onPick: (Int) -> Unit
    ) = showChoice(getString(title), labels.toList(), selected, onPick)

    private fun showChoice(
        title: CharSequence,
        labels: Array<String>,
        selected: Int,
        onPick: (Int) -> Unit
    ) = showChoice(title, labels.toList(), selected, onPick)

    private fun showIconPackPicker() {
        lifecycleScope.launch {
            val packs = viewModel.availableIconPacks()
            if (packs.isEmpty()) {
                Toast.makeText(this@SettingsActivity, R.string.icon_pack_none_installed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val packages = listOf("") + packs.map { it.packageName }
            val labels = listOf(getString(R.string.icon_pack_system)) + packs.map { it.label }
            val current = packages.indexOf(viewModel.state.value.prefs.iconPack).coerceAtLeast(0)

            val list = androidx.recyclerview.widget.RecyclerView(this@SettingsActivity).apply {
                layoutManager = LinearLayoutManager(this@SettingsActivity)
                clipToPadding = false
            }
            val dialog = MaterialAlertDialogBuilder(this@SettingsActivity)
                .setTitle(R.string.icon_pack)
                .setView(list)
                .setNegativeButton(R.string.cancel, null)
                .show()
            list.adapter = IconPackChoiceAdapter(packages, labels, current) { chosen ->
                viewModel.setIconPack(chosen)
                dialog.dismiss()
            }
            list.scrollToPosition(current)
        }
    }

    // Rows are (package, label) pairs with "" first for the system default.
    private inner class IconPackChoiceAdapter(
        private val packages: List<String>,
        private val labels: List<String>,
        private val selected: Int,
        private val onPick: (String) -> Unit
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<IconPackChoiceAdapter.Holder>() {

        // package -> the drawables sampled for its preview strip, so scrolling
        // back to a row doesn't re-open the pack's resources.
        private val samples = mutableMapOf<String, List<android.graphics.drawable.Drawable>>()

        inner class Holder(val binding: ItemIconPackChoiceBinding) :
            androidx.recyclerview.widget.RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): Holder =
            Holder(ItemIconPackChoiceBinding.inflate(layoutInflater, parent, false))

        override fun getItemCount(): Int = packages.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val packPackage = packages[position]
            holder.binding.packLabel.text = labels[position]
            holder.binding.packSelected.isChecked = position == selected
            holder.binding.root.setOnClickListener { onPick(packPackage) }

            val strip = holder.binding.packPreview
            strip.removeAllViews()
            // The system row has no pack to sample from.
            if (packPackage.isEmpty()) {
                strip.visibility = View.GONE
                return
            }
            samples[packPackage]?.let {
                fillPreview(strip, it)
                return
            }
            strip.visibility = View.GONE
            lifecycleScope.launch {
                val drawables = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    val names = viewModel.packDrawableNames(packPackage)
                    // Spread the sample across the pack instead of taking the
                    // first few: the list is alphabetical, so the front of it
                    // is whatever the pack named with digits and symbols -
                    // never a fair picture of the set.
                    val step = maxOf(1, names.size / PREVIEW_ICONS)
                    names.asSequence()
                        .filterIndexed { index, _ -> index % step == 0 }
                        .mapNotNull { viewModel.appCatalog().packDrawable(packPackage, it) }
                        .take(PREVIEW_ICONS)
                        .toList()
                }
                samples[packPackage] = drawables
                // The row may have been recycled onto another pack by now.
                if (holder.bindingAdapterPosition == position) fillPreview(strip, drawables)
            }
        }

        private fun fillPreview(
            strip: android.widget.LinearLayout,
            drawables: List<android.graphics.drawable.Drawable>
        ) {
            strip.removeAllViews()
            if (drawables.isEmpty()) {
                strip.visibility = View.GONE
                return
            }
            strip.visibility = View.VISIBLE
            val size = (36 * resources.displayMetrics.density).toInt()
            val gap = (8 * resources.displayMetrics.density).toInt()
            drawables.forEach { drawable ->
                strip.addView(
                    android.widget.ImageView(this@SettingsActivity).apply {
                        setImageDrawable(drawable)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    },
                    android.widget.LinearLayout.LayoutParams(size, size).apply { marginEnd = gap }
                )
            }
        }
    }

    // Popup menu row configuration: two checkbox groups (Home / Drawer), one
    // per optional row. OK commits both prefs; decode/encode round-trips the
    // "" = defaults convention via the NONE token.
    private fun showPopupRowsDialog() {
        val prefs = viewModel.state.value.prefs
        val homeSelected = com.supernova.launcher.ui.PopupRows
            .decode(prefs.popupItemsHome, com.supernova.launcher.ui.PopupRows.homeDefaults)
            .toMutableSet()
        val drawerSelected = com.supernova.launcher.ui.PopupRows
            .decode(prefs.popupItemsDrawer, com.supernova.launcher.ui.PopupRows.drawerDefaults)
            .toMutableSet()

        val density = resources.displayMetrics.density
        fun dpPx(value: Int) = (value * density).toInt()
        val column = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dpPx(20), dpPx(8), dpPx(20), dpPx(8))
        }

        fun header(titleRes: Int) {
            column.addView(android.widget.TextView(this).apply {
                text = getString(titleRes)
                setTextColor(getColor(R.color.accent))
                textSize = 13f
                setPadding(0, dpPx(12), 0, dpPx(4))
            })
        }

        fun checkboxes(
            choices: List<com.supernova.launcher.ui.PopupExtraRow>,
            selected: MutableSet<com.supernova.launcher.ui.PopupExtraRow>
        ) {
            choices.forEach { row ->
                column.addView(android.widget.CheckBox(this).apply {
                    text = getString(EnumLabels.of(row))
                    isChecked = row in selected
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) selected.add(row) else selected.remove(row)
                    }
                })
            }
        }

        header(R.string.settings_section_home)
        checkboxes(com.supernova.launcher.ui.PopupRows.homeChoices, homeSelected)
        header(R.string.settings_section_drawer)
        checkboxes(com.supernova.launcher.ui.PopupRows.drawerChoices, drawerSelected)

        val scroll = android.widget.ScrollView(this).apply { addView(column) }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.popup_menu_rows)
            .setView(scroll)
            .setPositiveButton(R.string.ok) { _, _ ->
                viewModel.setPopupItemsHome(com.supernova.launcher.ui.PopupRows.encode(homeSelected))
                viewModel.setPopupItemsDrawer(com.supernova.launcher.ui.PopupRows.encode(drawerSelected))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        private const val STATE_TAB = "settings_tab"

        // Grouped pages' cards on a styled Settings surface: how far toward
        // white they are lifted from it, light or dark.
        private const val OPAQUE = 0xFF000000.toInt()
        private const val HALF = 0.5
        private const val CARD_LIFT_LIGHT = 0.7f
        private const val CARD_LIFT_DARK = 0.06f

        // Member names shown under a drawer group before "…".
        private const val GROUP_SUMMARY_NAMES = 3

        // GroupRow's kinds: header, group, add row.
        private const val GROUP_ROW_TYPES = 3
        private const val STATE_SECTION = "settings_section"
        private const val STATE_OPEN_ACCORDIONS = "settings_open_accordions"

        // How many of a pack's drawables the icon pack picker samples per row.
        // Enough to read the pack's style, few enough that the strip fits a
        // narrow phone without scrolling.
        private const val PREVIEW_ICONS = 5

        // Corner radius stepper increment in the custom shape editor.
        private const val RADIUS_STEP = 5

        // A permission refusal quicker than this never showed a dialog.
        private const val NO_DIALOG_MS = 400L

        // Deep link from the global search overlay's settings results.
        // The home search bar's "Search bar settings" row opens the bar's
        // own page, not just the Search page that leads to it.
        const val EXTRA_OPEN_SEARCH_BAR = "extra_open_search_bar"
        const val EXTRA_SECTION_TITLE_RES = "extra_section_title_res"
        const val EXTRA_HIGHLIGHT_TITLE_RES = "extra_highlight_title_res"

        // Widest the settings content column grows in landscape, as a
        // multiple of the display's short edge - the width it has in
        // portrait, plus enough slack to use some of the extra room.
    }
}
