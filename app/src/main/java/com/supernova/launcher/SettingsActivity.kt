package com.supernova.launcher

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.toColorInt
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.supernova.launcher.data.CornerCut
import com.supernova.launcher.data.CustomShapeSpec
import com.supernova.launcher.data.DrawerGroup
import com.supernova.launcher.data.DrawerGroupType
import com.supernova.launcher.data.IconShape
import com.supernova.launcher.data.LaunchableApp
import com.supernova.launcher.data.LauncherPrefs
import com.supernova.launcher.data.LockMethod
import com.supernova.launcher.data.Pref
import com.supernova.launcher.data.TabFolders
import com.supernova.launcher.databinding.ActivitySettingsBinding
import com.supernova.launcher.databinding.DialogCustomShapeBinding
import com.supernova.launcher.databinding.DialogDesktopGridBinding
import com.supernova.launcher.databinding.DialogFolderIconBinding
import com.supernova.launcher.databinding.ItemIconPackChoiceBinding
import com.supernova.launcher.databinding.ItemIconShapeBinding
import com.supernova.launcher.databinding.SheetIconPackBinding
import com.supernova.launcher.databinding.SheetIconShapeBinding
import com.supernova.launcher.gesture.Gesture
import com.supernova.launcher.gesture.GestureAction
import com.supernova.launcher.gesture.GestureBinding
import com.supernova.launcher.gesture.GestureDefaults
import com.supernova.launcher.notification.SupernovaNotificationListener
import com.supernova.launcher.ui.Animations
import com.supernova.launcher.ui.ChoiceMenu
import com.supernova.launcher.ui.FolderPreviewStyle
import com.supernova.launcher.ui.LabelStyler
import com.supernova.launcher.ui.LauncherViewModel
import com.supernova.launcher.ui.dp
import com.supernova.launcher.ui.settings.ColorSheet
import com.supernova.launcher.ui.settings.EnumLabels
import com.supernova.launcher.ui.settings.PreviewController
import com.supernova.launcher.ui.settings.PreviewKind
import com.supernova.launcher.ui.settings.SettingItem
import com.supernova.launcher.ui.settings.SettingsAdapter
import com.supernova.launcher.ui.settings.SettingsCatalogue
import com.supernova.launcher.ui.settings.SettingsSearchIndex
import com.supernova.launcher.ui.settings.SettingsSection
import com.supernova.launcher.ui.settings.StylePickerSheet
import com.supernova.launcher.ui.textSizeOf
import com.supernova.launcher.ui.theme.AccentColor
import com.supernova.launcher.ui.theme.PaletteSourceMode
import com.supernova.launcher.ui.theme.Styles
import com.supernova.launcher.ui.theme.SurfaceId
import com.supernova.launcher.ui.theme.ThemeEngine
import com.supernova.launcher.widget.SupernovaWidgetHost
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private enum class Tab { SETTINGS, STYLE }

    private lateinit var binding: ActivitySettingsBinding
    private val viewModel: LauncherViewModel by viewModels { LauncherViewModel.Factory(appGraph) }
    // Folder icons for a tab's Select apps.
    private val itemIcons by lazy { com.supernova.launcher.ui.ItemIcons(this, viewModel) }

    // The Drawer groups page, in place of the list while it is open; made
    // on its first showing.
    private var groupsPane: com.supernova.launcher.ui.settings.groups.DrawerGroupsPane? = null

    // The group whose page was open before a recreate, to open again.
    private var restoredGroup: String? = null

    // A drawer folder's icon, from its page under Drawer groups: the gallery
    // and file pickers its Edit sheet asks for. Registered up front; the
    // sheet's callback is parked here until one answers.
    private var photoPickCallback: ((android.net.Uri?) -> Unit)? = null
    private val pickPhotoLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            photoPickCallback?.invoke(uri)
            photoPickCallback = null
        }
    private val pickPhotoFileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            photoPickCallback?.invoke(uri)
            photoPickCallback = null
        }
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
    private var appliedAccent: AccentColor = AccentColor.BLUE

    // Header search over the whole catalogue; the index resolves lazily on
    // first activation so plain settings visits never build it.
    private var searchActive = false
    // The Style tab was opened from its row in the Settings list, so Back
    // returns there rather than closing Settings.
    private var styleFromList = false
    private val searchIndex by lazy { SettingsSearchIndex.build(this, viewModel) }

    private val requestHomeRoleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        render()
    }

    private val backup = com.supernova.launcher.ui.BackupTransfer(this, { viewModel })

    // The Permissions page: a grant or refusal shows on the row at once. A
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
                com.supernova.launcher.ui.PermissionPrompts.openNotificationAccess(this)
            com.supernova.launcher.ui.settings.LabsPermission.ACCESSIBILITY ->
                runLogged("open accessibility settings") {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
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
                runLogged("ask for device admin") {
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
        runLogged("open app details") {
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
            runLogged("export icons") {
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
                viewModel.set(Pref.searchContacts, false)
                Toast.makeText(this, R.string.contacts_permission_needed, Toast.LENGTH_LONG).show()
            }
        }

    // The feed's calendar/contacts card toggles follow the same contract:
    // denial flips the pref back off so the toggle reflects reality.
    private val requestFeedCalendarPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                viewModel.set(Pref.feedShowCalendar, false)
                Toast.makeText(this, R.string.calendar_permission_needed, Toast.LENGTH_LONG).show()
            }
        }

    private val requestFeedContactsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                viewModel.set(Pref.feedShowContacts, false)
                Toast.makeText(this, R.string.contacts_permission_feed_needed, Toast.LENGTH_LONG).show()
            }
        }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            val parent = currentSection?.title?.let(subPageParents::get)
            when {
                searchActive -> setSearchActive(false)
                // The Style tab opened from its row in the list: back to that
                // row, as from any section.
                styleFromList && currentTab == Tab.STYLE && currentSection == null -> {
                    selectTab(Tab.SETTINGS)
                    scrollRootTo(R.string.style_tab)
                }
                // From a group's own page back to the groups.
                onGroupsPage() && groupsPane?.handleBack() == true -> Unit
                // Back onto the row that opened the page, not the top.
                parent != null -> navigateTo(parent, currentSection?.title ?: 0, flash = false)
                else -> {
                    val left = currentSection?.title
                    currentSection = null
                    render()
                    // The root list comes back on the section just left.
                    left?.let(::scrollRootTo)
                }
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
        ThemeEngine.applyAccent(this, appliedAccent)
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
            binding.settingsToolbar, binding.previewFrame, binding.rootTitle, binding.rootSearch,
            binding.settingsRecycler, binding.groupsPane, binding.settingsTabs
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
            pickChoice = {
                showChoice(getString(it.title), it.labels, it.selected, MenuExtras(swatches = it.swatches), it.onPick)
            },
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
            showWhatsNew = {
                MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.about_whats_new_title, SupernovaApp.versionLabel(this).orEmpty()))
                    .setMessage(
                        com.supernova.launcher.ui.settings.AboutLinks.releaseNotes(this)
                            ?: getString(R.string.about_whats_new_none)
                    )
                    .setPositiveButton(R.string.ok, null)
                    .show()
            },
            openPaletteDialog = ::showPaletteSourcePicker,
            openStylePicker = { surfaceId -> StylePickerSheet.show(this, viewModel, surfaceId) },
            showAutoBackups = ::showAutoBackups,
            editGesture = ::showActionPicker,
            pickLockMethod = ::showLockMethodPicker,
            editHiddenApps = ::showHiddenAppsPicker,
            exportBackup = backup::pickExportFile,
            importBackup = backup::pickImportFile,
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
            restartLauncher = {
                // It closes Settings and the home screen at once: asked first.
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.restart_launcher_q)
                    .setMessage(R.string.restart_launcher_message)
                    .setPositiveButton(R.string.restart) { _, _ -> restartLauncher() }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            },
            showCrashLog = ::showCrashLog,
            onBadgesEnabled = {
                com.supernova.launcher.ui.PermissionPrompts
                    .explainNotificationAccess(this, R.string.notification_access_badges)
            },
            onPopupNotificationsEnabled = {
                com.supernova.launcher.ui.PermissionPrompts
                    .explainNotificationAccess(this, R.string.notification_access_popup)
            },
            explainAccessibility = { com.supernova.launcher.ui.PermissionPrompts.explainAccessibility(this) },
            requestDeviceAdmin = ::launchDeviceAdminRequest,
            onSearchContactsEnabled = {
                if (checkSelfPermission(android.Manifest.permission.READ_CONTACTS) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    requestContactsPermission.launch(android.Manifest.permission.READ_CONTACTS)
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
                com.supernova.launcher.ui.CustomCardEditorSheet
                    .show(this, viewModel, card, undoAnchor = binding.settingsTabs)
            },
            openCards = { navigateTo(R.string.settings_section_cards, 0) },
            openNotificationAccess = { com.supernova.launcher.ui.PermissionPrompts.openNotificationAccess(this) },
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
            restoredGroup = state.getString(STATE_GROUP)
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
        // The pill around the field (its magnifier, its ends) focuses it too.
        binding.rootSearchPill.setOnClickListener {
            binding.rootSearchInput.requestFocus()
            getSystemService(InputMethodManager::class.java)?.showSoftInput(binding.rootSearchInput, 0)
        }
        binding.tabSettings.setOnClickListener { selectTab(Tab.SETTINGS) }
        binding.tabStyle.setOnClickListener { selectTab(Tab.STYLE) }
        // Each pill clips its press ripple to its own rounded shape.
        binding.tabSettings.clipToOutline = true
        binding.tabStyle.clipToOutline = true

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
                    groupsPane?.refresh()
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
    // The pages' cards stand out from it: the theme's surface colour
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
        groupsPane?.cardColor = groupCards.cardColor
    }

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
        groupsPane?.editing?.let { outState.putString(STATE_GROUP, it) }
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
        R.string.drawer_search_bar to R.string.settings_section_search,
        R.string.drawer_groups to R.string.settings_section_drawer
    ) + cardSections.associateWith { R.string.settings_section_cards }

    // The Drawer groups page is the pane's, not the list's. A search from
    // its toolbar shows results in the list, and Back returns to the pane.
    private fun onGroupsPage(): Boolean =
        currentSection?.title == R.string.drawer_groups && !searchActive && currentTab == Tab.SETTINGS

    private fun showGroupsPane(visible: Boolean) {
        binding.settingsRecycler.isVisible = !visible
        if (!visible) {
            // Left for another page, it opens on the groups next time.
            val stays = currentSection?.title == R.string.drawer_groups
            groupsPane?.let { it.show(false, it.editing.takeIf { stays }) }
            return
        }
        val pane = groupsPane ?: com.supernova.launcher.ui.settings.groups.DrawerGroupsPane(
            this, binding.groupsPane, viewModel, itemIcons,
            onTitleChanged = ::applyGroupsTitle,
            editFolderIcon = { group ->
                com.supernova.launcher.ui.FolderEditSheet
                    .showDrawerFolder(this, lifecycleScope, viewModel, group, ::pickPhoto)
            }
        ).also {
            groupsPane = it
            it.cardColor = groupCards.cardColor
        }
        pane.show(true, restoredGroup ?: pane.editing)
        restoredGroup = null
    }

    // A group's own page goes by the group's name.
    private fun applyGroupsTitle() {
        if (onGroupsPage()) binding.settingsTitle.text = groupsPane?.editing ?: getString(R.string.drawer_groups)
    }

    private fun pickPhoto(fromFiles: Boolean, onPicked: (android.net.Uri?) -> Unit) {
        photoPickCallback = onPicked
        runLogged("open photo picker") {
            if (fromFiles) {
                pickPhotoFileLauncher.launch(arrayOf("image/*"))
            } else {
                pickPhotoLauncher.launch(
                    androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            }
        }.onFailure { Toast.makeText(this, R.string.launch_failed, Toast.LENGTH_SHORT).show() }
    }

    private fun render() {
        binding.tabSettings.isSelected = currentTab == Tab.SETTINGS
        binding.tabStyle.isSelected = currentTab == Tab.STYLE
        val section = currentSection
        backCallback.isEnabled = section != null || searchActive || (styleFromList && currentTab == Tab.STYLE)
        // The Cards page adds from a FAB.
        binding.cardsFab.visibility =
            if (section?.title == R.string.settings_section_cards && !searchActive && currentTab == Tab.SETTINGS) {
                View.VISIBLE
            } else {
                View.GONE
            }

        applyHeader(section)
        showGroupsPane(onGroupsPage())

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
            adapter.submit(styleSection.items, viewModel.state.value.prefs, page = styleSection.title, tiled = true)
            return
        }

        if (section != null) binding.settingsTitle.text = getString(section.title)
        applyGroupsTitle()

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
        adapter.submit(items, viewModel.state.value.prefs, page = section?.title ?: 0, tiled = section == null)
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
            R.string.settings_group_app to listOf(
                R.string.settings_section_backup, R.string.permissions, R.string.about, R.string.labs
            )
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
                // The Style tab is reachable from the list too: it was looked
                // for here, among the other sections, and taken for gone.
                if (label == R.string.settings_group_customize) {
                    add(
                        SettingItem.SectionLink(
                            R.string.style_tab,
                            icon = R.drawable.ic_tab_style,
                            subtitle = { getString(R.string.section_style_subtitle) },
                            onClick = {
                                selectTab(Tab.STYLE)
                                styleFromList = true
                                render()
                            }
                        )
                    )
                }
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
        ROOT_SECTIONS[sectionTitle]?.first ?: 0

    // Parks the section's first visible Preview in the pinned pane. Returns
    // whether the pane took it; false hides the pane (root, search, Style).
    private fun bindPreviewPane(section: SettingsSection?): Boolean {
        val prefs = viewModel.state.value.prefs
        val preview = section?.items
            ?.firstOrNull { it is SettingItem.Preview && it.visibleIf(prefs) }
            as? SettingItem.Preview
        if (preview == null || searchActive || currentTab != Tab.SETTINGS) {
            binding.previewFrame.visibility = View.GONE
            binding.previewPane.showsWallpaper = false
            return false
        }
        binding.previewFrame.visibility = View.VISIBLE
        // Clipped to its rounded card, the wallpaper's hole included.
        binding.previewPane.clipToOutline = true
        binding.previewPane.showsWallpaper =
            preview.kind == PreviewKind.HOME || preview.kind == PreviewKind.SEARCH_BAR
        // Same guard as the in-list holder: never rebuild mid slider drag.
        if (!previews.isTracking) {
            val view = paneView ?: com.supernova.launcher.ui.settings.preview.SettingsPreviewView(this).also {
                paneView = it
                binding.previewPane.addView(
                    it,
                    // Centred in its card, however short the preview is.
                    android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                        android.view.Gravity.CENTER_VERTICAL
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
        ROOT_SECTIONS[sectionTitle]?.second?.let(::getString)

    private fun searchResultItems(): List<SettingItem> {
        // The pill is the search field under the large header; a section screen
        // searches from its toolbar.
        val input = if (currentSection == null) binding.rootSearchInput else binding.settingsSearchInput
        val query = input.text?.toString().orEmpty()
        val found = searchIndex.search(query, viewModel.state.value.prefs, limit = 40)
        // A query that finds nothing says so, rather than leave a blank list.
        if (found.isEmpty() && query.isNotBlank()) {
            return listOf(
                SettingItem.Row(
                    0,
                    onClick = {},
                    titleText = getString(R.string.settings_search_no_matches, query.trim()),
                    look = SettingItem.RowLook(tappable = false)
                )
            )
        }
        return found.map { entry ->
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

    // A row of the root list into view, a little below the top, unless it is
    // already among the first rows the open header shows.
    private fun scrollRootTo(@androidx.annotation.StringRes title: Int) {
        val position = adapter.positionOf(title)
        if (position < ROOT_ROWS_IN_VIEW) return
        binding.settingsAppBar.setExpanded(false, false)
        (binding.settingsRecycler.layoutManager as? LinearLayoutManager)
            ?.scrollToPositionWithOffset(position, dp(72))
    }

    private fun selectTab(tab: Tab) {
        styleFromList = false
        if (tab == currentTab) return
        // A section's search lives in its toolbar, which the other tab
        // doesn't show, so it ends here. A query in the pill stays: both
        // tabs wear the pill.
        if (currentSection != null) setSearchActive(false)
        currentTab = tab
        currentSection = null
        render()
    }

    private fun navigateTo(sectionTitleRes: Int, itemTitleRes: Int, flash: Boolean = true) {
        setSearchActive(false)
        if (flash) adapter.setHighlight(itemTitleRes)
        if (sectionTitleRes == styleSection.title) {
            currentSection = null
            if (currentTab != Tab.STYLE) selectTab(Tab.STYLE) else render()
        } else {
            if (currentTab != Tab.SETTINGS) selectTab(Tab.SETTINGS)
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
        val current = modes.indexOf(viewModel.state.value.prefs.paletteSource)

        showChoice(R.string.color_palette, labels, current) { which ->
            val mode = modes[which]
            viewModel.set(Pref.paletteSource, mode)
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
                    viewModel.set(Pref.paletteSeed, "#$digits")
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
        runLogged("open default home settings") { startActivity(HomeRole.homeSettingsIntent()) }
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
                    runLogged("open shortcut creator") {
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

    // Icon shape: a sheet of every shape drawn as itself, so the choice is
    // made by looking at the shapes rather than by reading their names.
    private fun showIconShapePicker() {
        val prefs = viewModel.state.value.prefs
        val current = prefs.iconShape
        val custom = CustomShapeSpec.decode(prefs.iconShapeCustom)
        val sheetBinding = SheetIconShapeBinding.inflate(layoutInflater)
        val sheet = com.supernova.launcher.ui.Sheets.open(this)
        val accent = com.google.android.material.color.MaterialColors.getColor(
            sheetBinding.root, androidx.appcompat.R.attr.colorAccent
        )
        val dark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val corner = dp(SHAPE_TILE_CORNER_DP).toFloat()
        IconShape.entries.forEach { shape ->
            val tile = ItemIconShapeBinding.inflate(layoutInflater, sheetBinding.shapeGrid, false)
            val selected = shape == current
            tile.shapeLabel.setText(EnumLabels.of(shape))
            tile.shapeLabel.setTextColor(getColor(if (selected) R.color.textPrimary else R.color.textSecondary))
            if (selected) tile.shapeLabel.typeface = android.graphics.Typeface.DEFAULT_BOLD
            tile.shapeSwatch.set(shape, custom, if (selected) accent else getColor(R.color.textPrimary))
            tile.shapeTile.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = corner
                if (selected) {
                    setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(
                        accent, if (dark) SHAPE_TINT_DARK else SHAPE_TINT_LIGHT
                    ))
                    setStroke(dp(2), accent)
                } else {
                    setColor(getColor(R.color.settingsPickerTile))
                }
            }
            tile.root.contentDescription = getString(EnumLabels.of(shape))
            tile.root.isSelected = selected
            tile.root.setOnClickListener {
                sheet.dismiss()
                // CUSTOM routes through the per-corner editor; only its OK
                // commits, so cancelling never half-applies the shape.
                if (shape == IconShape.CUSTOM) showCustomShapeEditor() else viewModel.setIconShape(shape)
            }
            sheetBinding.shapeGrid.addView(
                tile.root,
                android.widget.GridLayout.LayoutParams(
                    android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED),
                    android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                ).apply { width = 0 }
            )
        }
        sheet.setContentView(sheetBinding.root)
        sheet.show()
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
                viewModel.set(Pref.iconShapeCustom, current().encode())
                viewModel.setIconShape(IconShape.CUSTOM)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
        dialogBinding.subgridToggle.setOnClickListener { dialogBinding.subgridCheck.toggle() }
        refresh()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.desktop_grid)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.ok) { _, _ ->
                viewModel.set(Pref.gridRows, rows)
                viewModel.set(Pref.gridCols, cols)
                viewModel.set(Pref.subgrid, dialogBinding.subgridCheck.isChecked)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // The drawer twin of the desktop grid dialog, on the same layout: rows
    // and columns step down to 0 = Auto (both apply to the two grid styles); the subgrid checkbox has no drawer meaning
    // and hides. Only OK commits.
    private fun showDrawerGridDialog() {
        val dialogBinding = DialogDesktopGridBinding.inflate(layoutInflater)
        val prefs = viewModel.state.value.prefs
        var rows = prefs.drawerRows
        var cols = prefs.drawerColumns
        dialogBinding.gridPreview.cellColor = (0x30 shl 24) or (getColor(R.color.textPrimary) and 0xFFFFFF)
        dialogBinding.subgridRow.visibility = View.GONE
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
                viewModel.set(Pref.drawerRows, rows)
                viewModel.set(Pref.drawerColumns, cols)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // Folder icon appearance: shape + outline/clip/window-color toggles, all
    // edited against a live FolderIconDrawable preview of placeholder tiles.
    // Only OK commits the prefs.
    private fun showFolderIconEditor() {
        val dialogBinding = DialogFolderIconBinding.inflate(layoutInflater)
        val prefs = viewModel.state.value.prefs
        var shapeName = prefs.folderIconShape
        var plateColor = prefs.folderPlateColor
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
            // The plate's own colour only counts while it doesn't match.
            val colorShown = if (dialogBinding.matchColorCheck.isChecked) View.GONE else View.VISIBLE
            dialogBinding.colorRow.visibility = colorShown
            dialogBinding.colorDivider.visibility = colorShown
            dialogBinding.colorValue.text = plateColorLabel(plateColor)
            dialogBinding.folderIconPreview.setImageDrawable(
                com.supernova.launcher.ui.FolderIconDrawable(
                    sampleIcons,
                    dp(FOLDER_PREVIEW_DP),
                    style = prefs.folderPreviewStyle,
                    plateColor = com.supernova.launcher.ui.FolderIconDrawable.plateColor(
                        dialogBinding.matchColorCheck.isChecked, plateColor, resolved.surface
                    ),
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

        dialogBinding.shapeRow.setOnClickListener { row ->
            // Opened over the row's value, at its end, in the dialog's window.
            val value = dialogBinding.shapeValue
            val valueX = IntArray(2).also(value::getLocationInWindow)[0] + value.width / 2f
            val shapes = IconShape.entries.filter { it != IconShape.SYSTEM }
            val names = listOf("") + shapes.map { it.name }
            val labels = listOf<CharSequence>(getString(R.string.folder_shape_match_icons)) +
                shapes.map { getString(EnumLabels.of(it)) }
            val current = names.indexOf(shapeName).coerceAtLeast(0)
            val extras = MenuExtras(inDialog = row to valueX)
            showChoice(getString(R.string.folder_icon_shape), labels, current, extras) { which ->
                shapeName = names[which]
                refresh()
            }
        }
        dialogBinding.colorRow.setOnClickListener {
            val palette = com.supernova.launcher.ui.theme.PaletteFactory.resolve(
                this, prefs.paletteSource, prefs.paletteSeed,
                com.supernova.launcher.ui.theme.ThemeEngine.accentColor(this, prefs.accentColor)
            )
            ColorSheet(this, palette, com.supernova.launcher.ui.FolderIconDrawable.DEFAULT_PLATE_COLOR)
                .show(R.string.folder_plate_color, plateColor) { plateColor = it; refresh() }
        }
        listOf(
            dialogBinding.outlineRow to dialogBinding.outlineCheck,
            dialogBinding.clipRow to dialogBinding.clipCheck,
            dialogBinding.matchColorRow to dialogBinding.matchColorCheck
        ).forEach { (row, box) ->
            row.setOnClickListener { box.toggle() }
            box.setOnCheckedChangeListener { _, _ -> refresh() }
        }
        refresh()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.folder_icon_appearance)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.ok) { _, _ ->
                viewModel.set(Pref.folderIconShape, shapeName)
                viewModel.set(Pref.folderIconOutline, dialogBinding.outlineCheck.isChecked)
                viewModel.set(Pref.folderClipToBg, dialogBinding.clipCheck.isChecked)
                viewModel.set(Pref.folderMatchWindowColor, dialogBinding.matchColorCheck.isChecked)
                viewModel.set(Pref.folderPlateColor, plateColor)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // A curated color by name, any other by hex, and the opacity when it
    // isn't solid: "Red", "Red, 50%", "#3F51B5".
    private fun plateColorLabel(stored: String): String {
        if (stored.isEmpty()) return getString(R.string.color_default)
        val color = LabelStyler.parseColor(stored, com.supernova.launcher.ui.FolderIconDrawable.DEFAULT_PLATE_COLOR)
        val rgb = android.graphics.Color.rgb(
            android.graphics.Color.red(color), android.graphics.Color.green(color), android.graphics.Color.blue(color)
        )
        val named = SettingsCatalogue.CURATED_COLORS.firstOrNull {
            it.second.isNotEmpty() && it.second.toColorInt() == rgb
        }
        val name = named?.let { getString(it.first) } ?: String.format(java.util.Locale.ROOT, "#%06X", rgb and 0xFFFFFF)
        val opacity = ColorSheet.percent(color)
        return if (color == rgb) name else getString(R.string.color_with_opacity, name, opacity)
    }

    private fun showLockMethodPicker() {
        val methods = listOf(LockMethod.ACCESSIBILITY, LockMethod.DEVICE_ADMIN)
        val labels = arrayOf(
            getString(R.string.lock_method_accessibility),
            getString(R.string.lock_method_device_admin)
        )
        val current = methods.indexOf(viewModel.state.value.prefs.lockMethod).coerceAtLeast(0)
        showChoice(R.string.lock_method, labels, current) { which ->
            if (methods[which] != LockMethod.DEVICE_ADMIN || SupernovaDeviceAdmin.isActive(this)) {
                viewModel.set(Pref.lockMethod, methods[which])
                return@showChoice
            }
            // Device admin's catch is said before the system screen, and the
            // method is kept only once the admin is really on.
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.device_admin_confirm_title)
                .setMessage(R.string.lock_admin_note)
                .setPositiveButton(R.string.continue_label) { _, _ -> launchDeviceAdminRequest() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private val deviceAdminLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (SupernovaDeviceAdmin.isActive(this)) viewModel.set(Pref.lockMethod, LockMethod.DEVICE_ADMIN)
            render()
        }

    private fun launchDeviceAdminRequest() {
        val intent = Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, SupernovaDeviceAdmin.component(this))
            .putExtra(
                android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                getString(R.string.lock_admin_explanation)
            )
        runLogged("ask for device admin") { deviceAdminLauncher.launch(intent) }
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
                        runLogged("restore automatic backup") { viewModel.restoreAutoBackup(backups[which]) }
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
            runLogged("share backup") {
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
        // No crash, but the log may still hold failures the app carried on
        // from (Diagnostics), which are worth sending too.
        val recovered = count == 0 && SupernovaApp.crashLog(this).length() > 0
        if (count == 0 && !recovered) {
            Toast.makeText(this, R.string.crash_log_none, Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.crash_log)
            .setMessage(
                if (recovered) {
                    getString(R.string.crash_log_recovered_message)
                } else {
                    resources.getQuantityString(R.plurals.crash_log_message, count, count)
                }
            )
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
        runLogged("share crash log") {
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
                    val widgetIds = runLogged("read widget ids before reset") {
                        viewModel.persistedWidgetIds()
                    }.getOrNull().orEmpty()
                    runLogged("reset to defaults") { viewModel.resetToDefaults() }
                        .onSuccess {
                            // Free the host ids now instead of waiting for the
                            // next launcher create's orphan sweep; the host is
                            // addressed purely by id, so a local instance works.
                            runLogged("delete widget ids") {
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
        runLogged("restart launcher") {
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
            com.supernova.launcher.ui.PermissionPrompts
                .explainNotificationAccess(this, R.string.notification_access_badges)
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
            onSave = { changes ->
                changes.forEach { (app, isChecked) ->
                    val key = "${app.userSerial}/${app.packageName}/${app.className}"
                    if (isChecked) selected.add(key) else selected.remove(key)
                }
                viewModel.set(Pref.badgeApps, if (selected.containsAll(keys)) emptySet() else selected.toSet())
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
                viewModel.set(
                    Pref.searchChips,
                    com.supernova.launcher.search.SearchChip.encode(
                        all.filterIndexed { index, _ -> checked[index] }
                    )
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // The app pickers: search, ticked apps first, and nothing applied until
    // Save, which hands over only the ticks that changed.
    private fun showAppChecklist(
        title: CharSequence,
        apps: List<LaunchableApp>,
        checked: (LaunchableApp) -> Boolean,
        onSave: (Map<LaunchableApp, Boolean>) -> Unit
    ) {
        val states = apps.map(checked).toBooleanArray()
        val checklist = com.supernova.launcher.ui.AppChecklist(
            this, apps, states.copyOf(), viewModel.appCatalog()::iconFor, LaunchableApp::label
        )
        checklist.show(title) { ticks ->
            val changes = apps.indices.filter { ticks[it] != states[it] }.associate { apps[it] to ticks[it] }
            if (changes.isNotEmpty()) onSave(changes)
        }
    }

    private fun showHiddenAppsPicker() {
        showAppChecklist(
            title = getString(R.string.hidden_apps),
            apps = viewModel.state.value.apps,
            checked = { viewModel.isAppHidden(it) },
            onSave = { changes -> changes.forEach { (app, hidden) -> viewModel.setAppHidden(app, hidden) } }
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
    // How a choice menu opens beyond its options: from a row inside a dialog,
    // and with a colour dot per option.
    private class MenuExtras(val inDialog: Pair<View, Float>? = null, val swatches: List<Int?>? = null)

    private fun showChoice(
        title: CharSequence?,
        labels: List<CharSequence>,
        selected: Int,
        // inDialog: a row inside a dialog, and where it was pressed in the
        // dialog's window (hung off the settings row under the dialog, the
        // menu opened behind it); swatches: a colour dot per option.
        extras: MenuExtras = MenuExtras(),
        onPick: (Int) -> Unit
    ) {
        val inDialog = extras.inDialog
        val anchor = inDialog?.first ?: menuAnchor?.takeIf { it.isAttachedToWindow && it.isShown }
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
        // An unstyled popup is the theme's surface, the cards' own colour;
        // over the cards it takes the raised one, as dialogs and sheets do.
        val popup = Styles.resolve(this, viewModel.state.value.prefs).of(SurfaceId.POPUP)
        val surface = if (popup.surface == getColor(R.color.surface)) {
            com.supernova.launcher.ui.theme.ResolvedSurface(
                getColor(R.color.raised), popup.text, popup.accent, popup.cornerRadiiDp
            )
        } else {
            popup
        }
        ChoiceMenu.show(
            anchor = anchor,
            touchX = inDialog?.second ?: menuTouchX,
            surface = surface,
            labels = labels,
            selected = selected,
            animate = Animations.enabled(viewModel.state.value.prefs),
            alignToAnchorEnd = true,
            swatches = extras.swatches,
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
    ) = showChoice(getString(title), labels, selected, onPick = onPick)

    private fun showChoice(
        @androidx.annotation.StringRes title: Int,
        labels: Array<String>,
        selected: Int,
        onPick: (Int) -> Unit
    ) = showChoice(getString(title), labels.toList(), selected, onPick = onPick)

    private fun showChoice(
        title: CharSequence,
        labels: Array<String>,
        selected: Int,
        onPick: (Int) -> Unit
    ) = showChoice(title, labels.toList(), selected, onPick = onPick)

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

            val sheetBinding = SheetIconPackBinding.inflate(layoutInflater)
            val sheet = com.supernova.launcher.ui.Sheets.open(this@SettingsActivity)
            val list = sheetBinding.packList
            list.layoutManager = LinearLayoutManager(this@SettingsActivity)
            list.addItemDecoration(PackDividers(getColor(R.color.settingsDivider)))
            list.adapter = IconPackChoiceAdapter(packages, labels, current) { chosen ->
                viewModel.set(Pref.iconPack, chosen)
                sheet.dismiss()
            }
            sheet.setContentView(sheetBinding.root)
            sheet.show()
            list.scrollToPosition(current)
        }
    }

    // Hairlines between the pack rows, from the text's 24dp inset to the edge.
    private inner class PackDividers(color: Int) : androidx.recyclerview.widget.RecyclerView.ItemDecoration() {
        private val paint = android.graphics.Paint().apply { this.color = color }
        private val start = dp(PACK_TEXT_START_DP).toFloat()

        override fun onDraw(
            canvas: android.graphics.Canvas,
            parent: androidx.recyclerview.widget.RecyclerView,
            state: androidx.recyclerview.widget.RecyclerView.State
        ) {
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                if (parent.getChildAdapterPosition(child) == 0) continue
                val top = child.top + child.translationY
                canvas.drawRect(start, top, child.right.toFloat(), top + 1f, paint)
            }
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
            val chosen = position == selected
            holder.binding.packSelected.visibility = if (chosen) View.VISIBLE else View.INVISIBLE
            holder.binding.root.isSelected = chosen
            // The current pack wears the accent's faint wash.
            holder.binding.root.background = if (chosen) {
                val accent = com.google.android.material.color.MaterialColors.getColor(
                    holder.binding.root, androidx.appcompat.R.attr.colorAccent
                )
                androidx.core.graphics.ColorUtils.setAlphaComponent(accent, PACK_TINT).toDrawable()
            } else {
                null
            }
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
            val size = dp(PACK_PREVIEW_ICON_DP)
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
                textSizeOf(R.dimen.text_secondary)
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
                    // The box drawable has no inset of its own.
                    setPaddingRelative(dpPx(12), paddingTop, paddingEnd, paddingBottom)
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
                viewModel.set(Pref.popupItemsHome, com.supernova.launcher.ui.PopupRows.encode(homeSelected))
                viewModel.set(Pref.popupItemsDrawer, com.supernova.launcher.ui.PopupRows.encode(drawerSelected))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        // Each root section's icon and the blurb under its title.
        private val ROOT_SECTIONS: Map<Int, Pair<Int, Int>> = mapOf(
            R.string.settings_section_home to (R.drawable.ic_settings_home to R.string.section_home_subtitle),
            R.string.settings_section_dock to (R.drawable.ic_settings_dock to R.string.section_dock_subtitle),
            R.string.settings_section_drawer to (R.drawable.ic_settings_drawer to R.string.section_drawer_subtitle),
            R.string.settings_section_search to (R.drawable.ic_settings_search to R.string.section_search_subtitle),
            R.string.settings_section_feed to (R.drawable.ic_settings_feed to R.string.section_feed_subtitle),
            R.string.settings_section_cards to (R.drawable.ic_settings_cards to R.string.section_cards_subtitle),
            R.string.settings_section_folders to (R.drawable.ic_settings_folders to R.string.section_folders_subtitle),
            R.string.settings_section_icons to (R.drawable.ic_settings_icons to R.string.section_icons_subtitle),
            R.string.gestures to (R.drawable.ic_settings_gestures to R.string.section_gestures_subtitle),
            R.string.settings_section_backup to (R.drawable.ic_settings_backup to R.string.section_backup_subtitle),
            R.string.about to (R.drawable.ic_settings_about to R.string.section_about_subtitle),
            R.string.labs to (R.drawable.ic_settings_labs to R.string.section_labs_subtitle),
            R.string.permissions to (R.drawable.ic_settings_permissions to R.string.section_permissions_subtitle)
        )

        private const val STATE_TAB = "settings_tab"

        // Grouped pages' cards on a styled Settings surface: how far toward
        // white they are lifted from it, light or dark.
        private const val OPAQUE = 0xFF000000.toInt()
        private const val HALF = 0.5
        private const val CARD_LIFT_LIGHT = 0.7f
        private const val CARD_LIFT_DARK = 0.06f
        // The icon pack sheet: its rows' text inset, the preview icons, and
        // the current pack's accent wash.
        private const val PACK_TEXT_START_DP = 24
        private const val PACK_PREVIEW_ICON_DP = 28
        private const val PACK_TINT = 0x1A

        // The icon shape sheet's tiles: their corners, and the current one's
        // accent wash, light or dark.
        private const val SHAPE_TILE_CORNER_DP = 18
        private const val SHAPE_TINT_LIGHT = 0x1F
        private const val SHAPE_TINT_DARK = 0x33

        // The folder icon dialog's live preview, on its 128dp card.
        private const val FOLDER_PREVIEW_DP = 80


        private const val STATE_SECTION = "settings_section"
        private const val STATE_GROUP = "settings_group"
        private const val STATE_OPEN_ACCORDIONS = "settings_open_accordions"

        // How many of a pack's drawables the icon pack picker samples per row.
        // Enough to read the pack's style, few enough that the strip fits a
        // narrow phone without scrolling.
        private const val PREVIEW_ICONS = 5

        // Corner radius stepper increment in the custom shape editor.
        private const val RADIUS_STEP = 5

        // A permission refusal quicker than this never showed a dialog.
        private const val NO_DIALOG_MS = 400L
        // Rows the root list shows under its open header on a phone: one of
        // these needs no scroll to come back to.
        private const val ROOT_ROWS_IN_VIEW = 8

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
