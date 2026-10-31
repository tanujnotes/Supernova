package com.supernova.launcher

import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.children
import androidx.core.view.updateLayoutParams
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.supernova.launcher.ui.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import androidx.core.view.drawToBitmap
import androidx.core.view.isVisible
import com.supernova.launcher.ui.DragPayload
import com.supernova.launcher.ui.LiveDragController
import com.supernova.launcher.ui.WidgetController
import com.supernova.launcher.ui.clampAnchor
import com.supernova.launcher.ui.currentWorkspaceCanvas
import com.supernova.launcher.ui.screenRect
import com.supernova.launcher.ui.toLaunchableApp
import com.supernova.launcher.ui.WorkspaceCanvas
import com.supernova.launcher.data.ContainerType
import com.supernova.launcher.data.DrawerGroup
import com.supernova.launcher.data.ItemType
import com.supernova.launcher.data.LauncherPrefs
import com.supernova.launcher.data.LaunchableApp
import com.supernova.launcher.data.LauncherItem
import com.supernova.launcher.data.LauncherRepository
import com.supernova.launcher.data.WorkspaceGeometry
import com.supernova.launcher.databinding.ActivityLauncherBinding
import com.supernova.launcher.databinding.ItemIconBinding
import com.supernova.launcher.gesture.Gesture
import com.supernova.launcher.gesture.GestureAction
import com.supernova.launcher.gesture.GestureBinding
import com.supernova.launcher.gesture.GestureDefaults
import com.supernova.launcher.gesture.HomeButtonAction
import com.supernova.launcher.gesture.HomeGestureDetector
import com.google.android.material.snackbar.Snackbar
import com.supernova.launcher.notification.SupernovaNotificationListener
import com.supernova.launcher.ui.Animations
import com.supernova.launcher.ui.AppEditSheet
import com.supernova.launcher.ui.DesktopPreviewsOverlay
import com.supernova.launcher.ui.LaunchAnimation
import com.supernova.launcher.ui.PopupExtraRow
import com.supernova.launcher.ui.PopupRows
import com.supernova.launcher.ui.HomeOrientation
import com.supernova.launcher.ui.StatusBarIcons
import com.supernova.launcher.ui.WallpaperScrollMode
import com.supernova.launcher.ui.SupernovaPopupMenu
import com.supernova.launcher.ui.snapToHalfCell
import com.supernova.launcher.ui.BadgeDiff
import com.supernova.launcher.ui.BadgeDrawable
import com.supernova.launcher.ui.BadgeLook
import com.supernova.launcher.ui.BadgeStyle
import com.supernova.launcher.ui.FolderEditSheet
import com.supernova.launcher.ui.FolderPreviewStyle
import com.supernova.launcher.ui.LabelStyler
import com.supernova.launcher.ui.SwipeArrowDrawable
import com.supernova.launcher.ui.FolderIconDrawable
import com.supernova.launcher.ui.FolderMemberAdapter
import com.supernova.launcher.ui.FolderPopup
import com.supernova.launcher.ui.LauncherUiState
import com.supernova.launcher.ui.LauncherViewModel
import com.supernova.launcher.ui.BottomChrome
import com.supernova.launcher.ui.BottomChrome.BarSlot
import com.supernova.launcher.ui.PageTransformers
import com.supernova.launcher.ui.pagePaddingVPx
import com.supernova.launcher.ui.CustomCardEditorSheet
import com.supernova.launcher.ui.DockBgStyle
import com.supernova.launcher.ui.DrawerController
import com.supernova.launcher.ui.FeedController
import com.supernova.launcher.ui.IndicatorStyle
import com.supernova.launcher.ui.SearchBarConfig
import com.supernova.launcher.ui.SearchBarPlacement
import com.supernova.launcher.ui.SearchController
import com.supernova.launcher.ui.ScrollEffect
import com.supernova.launcher.ui.WorkspacePagerAdapter
import com.supernova.launcher.ui.theme.AccentColor
import com.supernova.launcher.ui.theme.ThemeEngine
import com.supernova.launcher.widget.WidgetResizeFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class LauncherActivity : AppCompatActivity(), WorkspacePagerAdapter.Host, LiveDragController.Host, WidgetController.Host {

    private lateinit var binding: ActivityLauncherBinding
    private val viewModel: LauncherViewModel by viewModels { LauncherViewModel.Factory(appGraph) }

    override lateinit var workspaceAdapter: WorkspacePagerAdapter
    private lateinit var drawer: DrawerController
    private lateinit var drag: LiveDragController
    private lateinit var widgets: WidgetController
    private lateinit var search: SearchController
    private lateinit var feed: FeedController
    // Swipe-up drawer pull (a finger-tracking open): armed on a down
    // that may become a swipe-up, active once it does. See trackDrawerPull.
    private var drawerPullArmed = false
    private var drawerPullActive = false
    private var drawerPullDownX = 0f
    private var drawerPullDownY = 0f
    private var drawerPullVelocity: android.view.VelocityTracker? = null
    // A drawer left open behind a launched app because drawerAutoClose is off.
    // Screen-off resets the home screen, but honours that pref: locking the
    // phone while inside the launched app must not close the drawer the user
    // is coming back to. Cleared once the launcher is resumed.
    private var drawerKeptBehindLaunch = false
    // Circle's and Adaptive icon's launcher-side part (launchWithAnimation).
    private val launchPrelude by lazy { com.supernova.launcher.ui.LaunchPrelude(binding.root) }
    // Shared by the feed and the drawer: one condition monitor, one answer to
    // "which cards are activated right now".
    private lateinit var cardActivation: com.supernova.launcher.feed.CardActivation
    private val gestureDetector by lazy {
        HomeGestureDetector(this, ::runGesture, ::workspaceCanScroll, ::pressOnItem)
    }

    private val launchStartMs = SystemClock.elapsedRealtime()

    private lateinit var editMode: com.supernova.launcher.ui.HomeEditController
    private lateinit var pageDrag: com.supernova.launcher.ui.PageDragController
    // Reorder preview ("make space"): while a drag hovers over an
    // occupied spot, the blocking items animate aside after a short pause to
    // show the layout a drop would produce, and animate back if the drag
    // moves on. The solved arrangement itself is recomputed at drop time.
    private val reorderHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingReorder: Runnable? = null
    private var reorderHoverKey: ReorderHoverKey? = null
    private var reorderCanvas: ViewGroup? = null
    private var reorderCellW = 1
    private var reorderCellH = 1

    // Material fast-out-slow-in: preview slides accelerate away and settle
    // gently instead of moving linearly.
    private val reorderInterpolator = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)

    private data class ReorderHoverKey(
        val canvas: ViewGroup,
        val page: Int,
        val targetLeft: Float,
        val targetTop: Float,
        val folder: Boolean
    )
    private var homeMenuDialog: com.google.android.material.bottomsheet.BottomSheetDialog? = null
    private val folderPopup by lazy { FolderPopup(this) }
    // The open folder card, if any. A PopupWindow outlives a home press on its
    // own, so onNewIntent has to close it like every other overlay.
    private var activeFolderPopup: android.widget.PopupWindow? = null
    // The open folder's members, so a badge moving redraws them too.
    private var activeFolderAdapter: FolderMemberAdapter? = null
    // True from onStart until the activity is resumed. Pressing home in
    // another app restarts the launcher, so onStart runs before the home
    // intent is delivered; pressing home on the launcher itself only pauses
    // and resumes it, with no onStart in between. That difference is the only
    // reliable read of "was the user already looking at the home screen" by
    // the time onNewIntent runs - the Lifecycle state is STARTED either way,
    // because the temporary pause has already been dispatched.
    private var startedSinceResume = false
    private var appliedScrollEffect: ScrollEffect? = null
    // Item currently being repositioned via tap-to-place move mode.
    private var moveModeItemId: String? = null

    // X of the last touch-down, in window coordinates - dispatchTouchEvent
    // sees every event, and the activity's own view IS the window, so ev.x
    // needs no conversion. Read by the item menus when they open. A drawer
    // folder's card, its own full-screen window, sets it too (routeItemGesture).
    private var menuTouchX = 0f
    // Edit mode's "+" page asked for a real page, maybe with a pick from the
    // sheet to land on it. Once the model carries the page, edit mode closes
    // there; the pick waits one emission more, for the current page to be
    // the new one (observeState).
    private class PendingEditAddPage(val page: Int, val then: (() -> Unit)?) {
        var landed = false
    }
    private var pendingEditAddPage: PendingEditAddPage? = null
    // Pages just removed from under the pager: once the adapter has the new
    // count, the pager goes to `page`, where what was in view now is.
    private class PageAfterRemoval(val pageCount: Int, val page: Int)
    private var pendingPageAfterRemoval: PageAfterRemoval? = null
    // A removal's Undo is waiting: empty pages stay until it goes, so the
    // item it would put back still has its page to go back to.
    private var undoSnackbar: Snackbar? = null
    // Transient dock hide from the TOGGLE_DOCK action; mirrors dockAutoClosed
    // (visibility only, reserved margins stay), never persisted.
    private var dockToggledOff = false

    // Guard so system-chrome window calls run only when the prefs change.
    private var appliedSystemChrome: List<Any>? = null

    // Save-APK flow: the SAF launcher is registered up front; the popup row
    // parks the source path here until the document is picked.
    private data class PendingApk(val sourceDir: String, val split: Boolean)
    private var pendingApk: PendingApk? = null

    private var appliedAccent: String = "BLUE"
    private var lastRenderedState: LauncherUiState? = null
    // What the dock currently shows; see dockSignature. Null forces a rebuild.
    private var renderedDock: List<Any?>? = null
    // The badge state the icons were last drawn from, so a replay of the same
    // state - which arrives on every return to the home screen - does nothing.
    private var renderedBadges: com.supernova.launcher.notification.BadgeState? = null
    // Dynamic badges' pictures, rendered once per notification.
    private val badgeFaces by lazy { com.supernova.launcher.ui.BadgeFaces(applicationContext) }
    private var reportedFullyDrawn = false
    private var lastWallpaperPages = -1

    // Which dock page is showing (transient) and the applied dock chrome guard.
    private var dockPage = 0
    private var appliedDockChrome: List<Any>? = null

    // The window shape the chrome was last laid out against. The activity
    // handles orientation and size changes itself (see the manifest's
    // configChanges), and onConfigurationChanged fires for changes that move
    // none of these - so the relayout is gated on one of them actually moving.
    private var lastConfigOrientation = 0
    private var lastConfigWidthDp = 0
    private var lastConfigHeightDp = 0
    // The dock's laid-out height. It grows with the icon scale and the label
    // line(s) rather than sitting at a constant, so everything stacked above
    // it measures off this; the layout listener in setupDock keeps it current
    // and the chrome guard carries it. 0 until the first layout, which reads
    // as the old design height so the first pass is spaced sensibly - and
    // re-spaced from the real height before it draws.
    private var dockHeightPx = 0
    // Width an icon column gets in the dock: the slot the LinearLayout hands
    // each item, which is the dock's width split dockCount ways. A dock full
    // of icons can make that narrower than the icon box, so viewForItem sizes
    // dock icons against it instead of letting them overflow the slot.
    private var dockSlotWidthPx = 0
    // System bar + cutout insets. A launcher draws its wallpaper edge to edge
    // and pads its own chrome out of the bars; this is the single source those
    // paddings read, so a bar appearing or hiding re-lays everything out.
    private var systemInsets: Insets = Insets.NONE
    private var dockTouchDownX = Float.NaN
    private var dockTouchDownY = Float.NaN
    private var dockTouchDownAt = 0L
    // Overlay auto-close: hidden after a launch until home is re-entered.
    private var dockAutoClosed = false

    // Wallpaper zoom-out while the drawer or a folder is open (API 30+).
    private var wallpaperZoomAnimator: android.animation.ValueAnimator? = null
    private var wallpaperZoomTarget = 0f
    private var wallpaperZoomValue = 0f
    // Some OEM wallpaper services throw; stop calling after the first failure.
    private var wallpaperZoomBroken = false

    private val createShortcutLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) result.data?.let(::handleCreateShortcutResult)
        }

    // An icon swipe waiting on an app's shortcut screen (the action picker's
    // Shortcuts tab), and where its answer goes.
    private class PendingSwipeShortcut(val itemId: String, val down: Boolean, val onPicked: (String?) -> Unit)
    private var pendingSwipeShortcut: PendingSwipeShortcut? = null

    private val createSwipeShortcutLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val pending = pendingSwipeShortcut ?: return@registerForActivityResult
            pendingSwipeShortcut = null
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val created = result.data?.let { com.supernova.launcher.ui.CreatedShortcuts.read(this, it) }
            if (created == null) {
                Toast.makeText(this, R.string.shortcut_unusable, Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            val target = when (created) {
                is com.supernova.launcher.ui.CreatedShortcut.Pinned -> created.target
                is com.supernova.launcher.ui.CreatedShortcut.Legacy -> created.intentUri
            }
            viewModel.setSwipeAction(pending.itemId, target, created.label, pending.down)
            pending.onPicked(created.label)
        }

    private val requestHomeRoleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Result is informational; the user may decline and we never re-prompt automatically.
    }

    // Labs' Export PNG: the image is drawn when the row is tapped and waits
    // in the view model while the picker asks where it goes, so a recreate
    // behind the picker (a theme flip at sunset) keeps it. The picker has
    // made the file by the time it answers: when the process died meanwhile
    // and the image with it, that empty file goes again.
    private val exportPngLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val bitmap = viewModel.pendingExportPng
        viewModel.pendingExportPng = null
        if (uri == null) return@registerForActivityResult
        if (bitmap == null) {
            runCatching { android.provider.DocumentsContract.deleteDocument(contentResolver, uri) }
            Toast.makeText(this, R.string.export_png_lost, Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    } ?: error("no output stream")
                }
            }.onSuccess {
                Toast.makeText(this@LauncherActivity, R.string.export_png_saved, Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(
                    this@LauncherActivity,
                    getString(R.string.export_png_failed, it.message ?: "unknown"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // The item as it sits on the home screen, on a transparent background.
    private fun addExportPngRow(menu: SupernovaPopupMenu, view: View, name: String) {
        if (!viewModel.state.value.prefs.labsExportPng) return
        menu.addRow(getString(R.string.export_png), R.drawable.ic_menu_export) {
            viewModel.pendingExportPng = runCatching { view.drawToBitmap() }.getOrNull() ?: return@addRow
            val file = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "supernova" }
            exportPngLauncher.launch("$file.png")
        }
    }

    // Gallery picker for per-app custom icons. The launcher must be registered
    // up front; the sheet that wants the result parks its callback here.
    private var photoPickCallback: ((android.net.Uri?) -> Unit)? = null
    private val pickPhotoLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            photoPickCallback?.invoke(uri)
            photoPickCallback = null
        }

    // The system file picker reaches every storage provider and file manager,
    // where the photo picker shows only the media library.
    private val pickPhotoFileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            photoPickCallback?.invoke(uri)
            photoPickCallback = null
        }

    private fun pickPhoto(onPicked: (android.net.Uri?) -> Unit) {
        val sources = arrayOf(getString(R.string.photo_from_gallery), getString(R.string.wallpaper_from_files))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.choose_photo)
            .setItems(sources) { _, which ->
                photoPickCallback = onPicked
                runCatching {
                    if (which == 0) {
                        pickPhotoLauncher.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    } else {
                        pickPhotoFileLauncher.launch(arrayOf("image/*"))
                    }
                }.onFailure { Toast.makeText(this, R.string.launch_failed, Toast.LENGTH_SHORT).show() }
            }
            .show()
    }

    private val exportBackupLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            runCatching {
                val payload = viewModel.exportBackup()
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                    writer.write(payload)
                }
            }.onSuccess {
                Toast.makeText(this@LauncherActivity, R.string.backup_exported, Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(
                    this@LauncherActivity,
                    getString(R.string.backup_export_failed, it.message ?: "unknown"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // A wallpaper image from any folder: the system file picker reaches
    // every storage provider and file manager, where the photo picker shows
    // only the media library.
    private val pickWallpaperFileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) cropAndSetWallpaper(uri)
        }

    private val importBackupLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            runCatching {
                val raw = contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                    reader.readText()
                }.orEmpty()
                viewModel.importBackup(raw)
            }.onSuccess { result ->
                Toast.makeText(this@LauncherActivity, result.summary(resources), Toast.LENGTH_LONG).show()
            }.onFailure {
                Toast.makeText(
                    this@LauncherActivity,
                    getString(R.string.backup_import_failed, it.message ?: "unknown"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private val saveApkLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")
    ) { uri ->
        val pending = pendingApk
        pendingApk = null
        if (uri == null || pending == null) return@registerForActivityResult
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openOutputStream(uri)?.use { output ->
                        java.io.File(pending.sourceDir).inputStream().use { it.copyTo(output) }
                    }
                }
            }
            val message = when {
                result.isFailure -> R.string.apk_save_failed
                // Split apps: base.apk alone is partial - say so honestly.
                pending.split -> R.string.apk_saved_base
                else -> R.string.apk_saved
            }
            Toast.makeText(this@LauncherActivity, message, Toast.LENGTH_SHORT).show()
        }
    }

    // Desktop previews overlay (DESKTOP_PREVIEWS action). Lazy: costs nothing
    // until the action first runs; views exist only while shown.
    private val desktopPreviews by lazy {
        DesktopPreviewsOverlay(this, binding.root, object : DesktopPreviewsOverlay.Host {
            override fun pageCount(): Int = workspaceAdapter.realPageCount
            override fun currentPage(): Int = viewModel.state.value.currentPage
            override fun defaultPage(): Int = viewModel.state.value.model.defaultPage
            override fun isPageEmpty(page: Int): Boolean = viewModel.workspaceItems(page).isEmpty()
            override fun pageViewFor(page: Int): View? = attachedPageView(page)
            override fun accentColor(): Int = getColor(R.color.accent)
            override fun jumpToPage(page: Int) {
                binding.workspacePager.setCurrentItem(workspaceAdapter.centerPosition(page), true)
            }
            override fun addPage() {
                viewModel.addPage()
                refreshPreviewsSoon()
            }
            override fun removePage(page: Int) {
                viewModel.removePage(page)
                refreshPreviewsSoon()
            }
            override fun movePage(from: Int, to: Int) {
                viewModel.movePage(from, to)
                refreshPreviewsSoon()
            }
            override fun setDefaultPage(page: Int) {
                viewModel.setDefaultPage(page)
                refreshPreviewsSoon()
            }
        })
    }

    // Page ops round-trip through DataStore; rebuild once the model lands.
    private fun refreshPreviewsSoon() {
        lifecycleScope.launch {
            delay(200)
            if (desktopPreviews.isShowing) desktopPreviews.rebuild()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        viewModel.onDisplayDensity(resources.displayMetrics.densityDpi)
        appliedAccent = viewModel.state.value.prefs.accentColor
        ThemeEngine.applyAccent(this, AccentColor.fromName(appliedAccent))
        binding = ActivityLauncherBinding.inflate(layoutInflater)
        setContentView(binding.root)
        rememberConfiguration(resources.configuration)
        setUpEdgeToEdge()
        // A theme flip recreates this activity with no pref changing.
        viewModel.refreshThemedIcons()

        widgets = WidgetController(this, binding, viewModel, host = this)

        // Without this, back falls through to the system's task-root handling,
        // which re-delivers a home intent - a full onNewIntent reset just to
        // close the drawer. A launcher always consumes back itself.
        onBackPressedDispatcher.addCallback(this) {
            when {
                ::editMode.isInitialized && editMode.isOpen -> editMode.exit()
                desktopPreviews.isShowing -> desktopPreviews.dismiss()
                ::search.isInitialized && search.isOpen -> search.hide()
                // Feed before drawer: the pane overlays the workspace, so back
                // must close it before it can ever reach the (hidden) drawer.
                ::feed.isInitialized && feed.isOpen -> feed.close()
                ::drawer.isInitialized && drawer.isExpanded -> drawer.hide()
                moveModeItemId != null -> moveModeItemId = null
                else -> Unit
            }
        }

        setupWorkspace()
        setupDrawer()
        setupSearch()
        setupFeed()
        setupDock()
        drag = LiveDragController(binding, viewModel, host = this)
        setupEditMode()
        setupHomeGestures()
        observeState()
        maybeShowOnboarding()
        widgets.sweepOrphanIds()
        observeBadges()
        restoreDockPage()

        // Wallpaper-derived palettes go stale without any prefs emission, so
        // re-resolve and re-apply the styled chrome when the wallpaper changes.
        WallpaperManager.getInstance(this).addOnColorsChangedListener(
            wallpaperColorsListener, android.os.Handler(mainLooper)
        )

        // Registered for the activity's whole lifetime rather than between
        // onStart and onStop: the broadcast races onStop when the screen turns
        // off on the launcher itself, and a lock made from inside a launched
        // app has to reach this too.
        androidx.core.content.ContextCompat.registerReceiver(
            this, screenOffReceiver,
            android.content.IntentFilter(Intent.ACTION_SCREEN_OFF),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    // Short-and-wide window. Read from the live configuration rather than
    // cached, so a rotation handled in place is reflected immediately.
    private val isLandscape: Boolean
        get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    override fun landscape(): Boolean = isLandscape

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val moved = newConfig.orientation != lastConfigOrientation ||
            newConfig.screenWidthDp != lastConfigWidthDp ||
            newConfig.screenHeightDp != lastConfigHeightDp
        rememberConfiguration(newConfig)
        if (!moved) return
        relayoutForConfiguration()
    }

    private fun rememberConfiguration(config: Configuration) {
        lastConfigOrientation = config.orientation
        lastConfigWidthDp = config.screenWidthDp
        lastConfigHeightDp = config.screenHeightDp
    }

    // A rotation (or a multi-window resize) with the activity kept alive.
    // Everything below is either measured in pixels against the old window or
    // anchored to a view that has just moved, so it is torn down and rebuilt
    // rather than left pointing at the previous geometry.
    private fun relayoutForConfiguration() {
        // Overlays positioned against the old window. A PopupWindow does not
        // reposition itself, and a resize frame's span limits were worked out
        // in the old cells.
        homeMenuDialog?.dismiss()
        homeMenuDialog = null
        drag.activeItemPopup?.dismiss()
        drag.activeItemPopup = null
        activeFolderPopup?.dismiss()
        activeFolderPopup = null
        widgets.dismissResizeFrame()
        if (desktopPreviews.isShowing) desktopPreviews.dismiss()
        // Edit mode's page zoom, sheet height and chip row were all worked
        // out for the old window; left open, the page sat off-centre under
        // a sheet covering half a landscape screen.
        if (::editMode.isInitialized && editMode.isOpen) editMode.exit()
        // A drag in flight holds cell arithmetic from the old grid; the
        // preview offsets it applied are cleared with it.
        cancelReorderPreview(animate = false)
        moveModeItemId = null

        // Cached pixel geometry, and every guard that would otherwise skip
        // the re-apply because the prefs themselves did not change.
        dockHeightPx = 0
        dockSlotWidthPx = 0
        appliedDockChrome = null
        renderedDock = null
        appliedSystemChrome = null
        widgets.clearReportedSizes()

        val state = viewModel.state.value
        applySystemChrome(state.prefs)
        applyWindowInsets()
        renderDock(state)
        // Icons are placed from normalized coordinates read off the live
        // canvas, and widgets from cell sizes; both change with the window.
        workspaceAdapter.invalidateAll()
        if (::drawer.isInitialized) drawer.onWindowResized()
        if (::search.isInitialized) search.onWindowResized()
        if (::feed.isInitialized) feed.applyGestureExclusion(state.prefs)
        // The wallpaper's scroll step is per-page across the new width.
        lastWallpaperPages = -1
        binding.workspacePager.post {
            updateWallpaperOffset(binding.workspacePager.currentItem.toFloat())
        }
    }

    private fun observeBadges() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SupernovaNotificationListener.badgeState.collect { badges ->
                    val previous = renderedBadges
                    // Recorded even with badges off, so switching them on
                    // diffs against what is really on screen rather than
                    // against nothing.
                    renderedBadges = badges
                    // A StateFlow replays its value to every new collector,
                    // and this one is re-collected on every onStart: without
                    // this, every return to the home screen tore the whole
                    // workspace down and re-inflated it, during the
                    // return-home animation.
                    if (previous == badges) return@collect
                    val prefs = viewModel.state.value.prefs
                    if (!prefs.badges) return@collect
                    val changed = BadgeDiff.changedKeys(previous?.counts.orEmpty(), badges.counts).toMutableSet()
                    // A new notification can change a Dynamic badge's
                    // picture without changing its count.
                    if (BadgeStyle.effective(prefs) == BadgeStyle.DYNAMIC) {
                        changed += BadgeDiff.changedValues(previous?.faces.orEmpty(), badges.faces)
                    }
                    if (changed.isEmpty()) return@collect
                    applyBadgeChanges(changed)
                }
            }
        }
    }

    // Redraw only what these apps' badges are actually on. "changed" holds the
    // listener's "$userHash/$packageName" keys, which is also how withBadge
    // looks a count up.
    private fun applyBadgeChanges(changed: Set<String>) {
        val prefs = viewModel.state.value.prefs

        fun carries(item: LauncherItem): Boolean {
            val pkg = item.packageName ?: return false
            // Per-app badge filter: an item excluded there never shows one,
            // so its count moving changes nothing on screen.
            if (prefs.badgeApps.isNotEmpty() &&
                "${item.userSerial}/$pkg/${item.className.orEmpty()}" !in prefs.badgeApps
            ) {
                return false
            }
            val user = viewModel.appCatalog().userForSerial(item.userSerial) ?: Process.myUserHandle()
            return "${user.hashCode()}/$pkg" in changed
        }

        // A folder sums its members' counts, so a member changing is the
        // folder changing - but only while folder badges are on.
        fun affected(item: LauncherItem): Boolean =
            if (item.itemType == ItemType.FOLDER) {
                prefs.folderBadges && viewModel.folderMembers(item.id).any(::carries)
            } else {
                carries(item)
            }

        workspaceAdapter.invalidatePagesWith(::affected)
        if (viewModel.dockItems().any(::affected)) {
            renderDock(viewModel.state.value, force = true)
        }
        drawer.onBadgeChanged()
        activeFolderAdapter?.refreshIcons()
    }

    // Phase 8 first-run gate. GRANDFATHERING: existing users must never see
    // the wizard - an install that already has layout items or has been
    // through the old home prompt predates onboarding, so onboarding_done is
    // written silently and control falls through to the normal home-role
    // prompt. Items the wizard planted itself as it opened don't count. Until
    // the wizard finishes or is skipped (both write the flag), this re-checks
    // on every launcher create, so pressing Home mid-wizard isn't a trap: it
    // simply re-opens next launch.
    private fun maybeShowOnboarding() {
        lifecycleScope.launch {
            if (!viewModel.onboardingDone()) {
                val predates = viewModel.hasModelItems() && !viewModel.defaultLayoutSeeded()
                if (predates || viewModel.homePromptShown()) {
                    viewModel.markOnboardingDone()
                } else {
                    startActivity(
                        Intent(this@LauncherActivity, com.supernova.launcher.onboarding.OnboardingActivity::class.java)
                    )
                    return@launch
                }
            }
            maybePromptDefaultHome()
        }
    }

    private fun maybePromptDefaultHome() {
        if (!HomeRole.isNeeded(this)) return
        val request = HomeRole.requestIntent(this) ?: return
        lifecycleScope.launch {
            // The onboarding wizard owns the ROLE_HOME ask until it completes;
            // finishing or skipping it writes homePromptShown too, so this
            // prompt can never double-ask afterwards.
            if (!viewModel.onboardingDone()) return@launch
            if (viewModel.homePromptShown()) return@launch
            viewModel.markHomePromptShown()
            requestHomeRoleLauncher.launch(request)
        }
    }

    private val wallpaperColorsListener =
        WallpaperManager.OnColorsChangedListener { _, which ->
            if (which and WallpaperManager.FLAG_SYSTEM != 0) {
                com.supernova.launcher.ui.theme.Styles.onWallpaperChanged()
                appliedDockChrome = null
                // Palette-derived colors moved under everything the dock
                // draws, and none of that is in its signature.
                renderedDock = null
                applyDockChrome(viewModel.state.value.prefs)
                if (::drawer.isInitialized) drawer.invalidateStyle()
                if (::search.isInitialized) search.invalidateStyle()
                if (::feed.isInitialized) feed.invalidateStyle()
                workspaceAdapter.invalidateAll()
            }
        }

    override fun onDestroy() {
        // A recreate (a theme flip, an accent change) can come with the feed
        // or the drawer's cards showing; their media, calendar and condition
        // listeners are registered with the system and would keep this
        // activity alive for good.
        if (::feed.isInitialized) feed.release()
        if (::drawer.isInitialized) drawer.releaseCards()
        if (::cardActivation.isInitialized) cardActivation.dispose()
        WallpaperManager.getInstance(this).removeOnColorsChangedListener(wallpaperColorsListener)
        runCatching { unregisterReceiver(screenOffReceiver) }
        super.onDestroy()
    }

    // Refreshes dynamic calendar icons when the day rolls over while the
    // launcher is visible; a no-op for packs without calendar icons.
    private val dateChangeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) = redrawForNewDay()
    }

    // The day the calendar icons were drawn for. The receiver only listens
    // while the launcher is started, and midnight usually passes with the
    // screen off, so onStart catches up when this has fallen behind.
    private var iconsDrawnFor: java.time.LocalDate = java.time.LocalDate.now()

    private fun redrawForNewDay() {
        iconsDrawnFor = java.time.LocalDate.now()
        viewModel.appCatalog().onDayChanged()
        workspaceAdapter.invalidateAll()
        // onDayChanged evicts the calendar icons without touching the
        // prefs, the model or the icon generation, so the dock's
        // signature is unmoved and it has to be told.
        renderDock(viewModel.state.value, force = true)
        drawer.notifyContentChanged()
    }

    // Screen off: put the home screen back to its default page with everything
    // closed, so unlocking lands on the home screen rather than on whatever was
    // open when the screen went dark, as Launcher3 does.
    private val screenOffReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            resetToHome(animate = false, keepDrawer = drawerKeptBehindLaunch)
        }
    }

    override fun onStart() {
        super.onStart()
        startedSinceResume = true
        widgets.startListening()
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        androidx.core.content.ContextCompat.registerReceiver(
            this, dateChangeReceiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        if (java.time.LocalDate.now() != iconsDrawnFor) redrawForNewDay()
    }

    override fun onStop() {
        // The launched app covers the launcher now: its circle can go, and
        // the lifted icon comes back.
        launchPrelude.clear()
        if (::drag.isInitialized) drag.cancel()
        widgets.stopListening()
        runCatching { unregisterReceiver(dateChangeReceiver) }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // Cleared after onNewIntent, which runs first on the way back in.
        startedSinceResume = false
        // The launcher is what the user is looking at again, so a drawer still
        // open is one they came back to, not one parked behind an app.
        drawerKeptBehindLaunch = false
        reopenDockIfAutoClosed()
    }

    override fun onPause() {
        // Home pressed while a circle was still filling, say: that launch is
        // off. One already asked for carries on.
        launchPrelude.cancelPending()
        super.onPause()
    }

    override fun onPostResume() {
        super.onPostResume()
        Log.i("Supernova", "startup_ms=${SystemClock.elapsedRealtime() - launchStartMs}")
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::pageDrag.isInitialized && pageDrag.isActive) {
            pageDrag.onTouch(ev)
            return true
        }
        if (::drag.isInitialized && drag.routeGesture(ev, window = null)) return true
        // An active drawer pull owns the stream until the finger lifts; it
        // runs before the visibility gate below, which the pull itself trips.
        if (drawerPullActive) {
            drawerPullVelocity?.addMovement(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> drawer.pullTo(drawerPullDownY - ev.y)
                MotionEvent.ACTION_UP -> finishDrawerPull(cancelled = false)
                MotionEvent.ACTION_CANCEL -> finishDrawerPull(cancelled = true)
                else -> Unit
            }
            return true
        }
        // isVisible, not isExpanded: the sheet leaves EXPANDED the moment a
        // closing swipe starts, and the still-observing gesture detector
        // would read that swipe as the home swipe-down binding.
        if ((::drawer.isInitialized && drawer.isVisible) ||
            (::search.isInitialized && search.isOpen) ||
            // Edit mode: sheet drags and chip taps must not read as gestures.
            (::editMode.isInitialized && editMode.isOpen) ||
            // Desktop previews: its thumbnails take their own taps and
            // drags; a swipe there pulled the shade or opened the drawer
            // behind them, and a double tap ran the double-tap action.
            desktopPreviews.isShowing
        ) {
            return super.dispatchTouchEvent(ev)
        }
        // A drag on a widget resize frame's handle is the frame's alone, as a
        // live drag is the drag layer's: nothing below may read it. On the
        // first page the feed would take a rightward one for its pull, and
        // the drawer pull an upward one, each cancelling the frame a few
        // pixels in; a downward one would fire the swipe-down on the lift.
        if (widgets.trackResizeDrag(ev)) return super.dispatchTouchEvent(ev)
        if (::feed.isInitialized) {
            when (feed.onTouch(ev)) {
                // The feed owns the rest of this stream (edge-swipe drag).
                FeedController.TouchResult.CONSUMED -> return true
                // Takeover moment: cancel the views underneath so the pager
                // and icons drop the gesture, then drive the pane instead.
                FeedController.TouchResult.TAKE_OVER -> {
                    val cancel = MotionEvent.obtain(ev)
                    cancel.action = MotionEvent.ACTION_CANCEL
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                    return true
                }
                FeedController.TouchResult.PASS -> Unit
            }
            // An open feed pane handles its own touches; home gestures and
            // dock swipes stay out of it like they do for drawer/search.
            if (feed.isOpen) return super.dispatchTouchEvent(ev)
        }
        // A widget scrolling its own content owns the vertical stream. The
        // detector observes without consuming, so otherwise a drag inside a
        // list widget also read as the home swipe-up/down binding and fired
        // the drawer or the shade the moment the finger lifted.
        if (widgets.trackWidgetScroll(ev)) {
            drawerPullArmed = false
            return super.dispatchTouchEvent(ev)
        }
        // Swipe-up bound to the drawer: once the drag reads as a swipe-up the
        // sheet comes in under the finger instead of on the lift.
        if (trackDrawerPull(ev)) {
            val cancel = MotionEvent.obtain(ev)
            cancel.action = MotionEvent.ACTION_CANCEL
            super.dispatchTouchEvent(cancel)
            gestureDetector.onTouchEvent(cancel)
            cancel.recycle()
            return true
        }
        // Gestures observe every event but never consume them, so drags,
        // page swipes and icon taps keep working underneath.
        gestureDetector.onTouchEvent(ev)
        trackDockSwipe(ev)
        return super.dispatchTouchEvent(ev)
    }

    // A quick horizontal flick across the dock switches dock pages. Observed,
    // never consumed: slower or shorter motion stays a tap/long-press.
    // Arms on a down that could become a swipe-up while the drawer is the
    // swipe-up binding; returns true at the moment the drag qualifies, when
    // the caller cancels the views underneath and the pull takes the stream.
    private fun trackDrawerPull(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drawerPullArmed = drawerPullAllowed()
                if (drawerPullArmed) {
                    drawerPullDownX = ev.x
                    drawerPullDownY = ev.y
                    drawerPullVelocity?.recycle()
                    drawerPullVelocity = android.view.VelocityTracker.obtain().also { it.addMovement(ev) }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> drawerPullArmed = false
            MotionEvent.ACTION_MOVE -> if (drawerPullArmed) {
                drawerPullVelocity?.addMovement(ev)
                val dx = ev.x - drawerPullDownX
                val dy = ev.y - drawerPullDownY
                val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
                if (-dy > slop && -dy > kotlin.math.abs(dx) * DRAWER_PULL_BIAS) {
                    // The second press of a double tap. A bound double-tap
                    // swipe up owns it outright: the pull took it at the
                    // slop, long before the swipe's own distance, so that
                    // gesture could never fire. A bound double tap keeps it
                    // until the finger has gone a swipe's distance, past a
                    // tap's drift.
                    if (gestureDetector.inDoubleTap) {
                        val bindings = GestureDefaults.decode(viewModel.state.value.prefs.gestureBindings)
                        if (bindings[Gesture.DOUBLE_TAP_SWIPE_UP]?.action != GestureAction.NONE) {
                            drawerPullArmed = false
                            return false
                        }
                        if (bindings[Gesture.DOUBLE_TAP]?.action != GestureAction.NONE &&
                            -dy < gestureDetector.minSwipeDistance
                        ) {
                            return false
                        }
                    }
                    drawerPullArmed = false
                    // A long-press may have armed an item drag since the down.
                    if (drag.isArmed || drag.activeItemPopup != null) return false
                    drawerPullActive = true
                    drawer.beginPull()
                    drawer.pullTo(-dy)
                    // The Open App Drawer haptic: one tick as the swipe
                    // takes the drawer.
                    if (viewModel.state.value.prefs.drawerSwipeHaptic) {
                        binding.root.performHapticFeedback(
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                android.view.HapticFeedbackConstants.GESTURE_START
                            } else {
                                android.view.HapticFeedbackConstants.VIRTUAL_KEY
                            }
                        )
                    }
                    return true
                }
                if (dy > slop || (kotlin.math.abs(dx) > slop && kotlin.math.abs(dx) > -dy)) {
                    drawerPullArmed = false
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                drawerPullArmed = false
                drawerPullVelocity?.recycle()
                drawerPullVelocity = null
            }
            else -> Unit
        }
        return false
    }

    // An icon, folder or widget on the page in view, or a docked item, under
    // a press. Everything else - empty cells, the dock's background, the
    // strip between - is home screen, where a double tap counts.
    private fun pressOnItem(rawX: Float, rawY: Float): Boolean {
        val x = rawX.toInt()
        val y = rawY.toInt()
        binding.workspacePager.currentWorkspaceCanvas()?.let { canvas ->
            for (c in 0 until canvas.childCount) {
                val child = canvas.getChildAt(c)
                if (child is WidgetResizeFrame || !child.isShown || child.getTag(R.id.tag_item_id) == null) continue
                if (child.screenRect().contains(x, y)) return true
            }
        }
        if (binding.dockBar.isShown) {
            for (i in 0 until binding.dockBar.childCount) {
                val slot = binding.dockBar.getChildAt(i) as? ViewGroup ?: continue
                for (c in 0 until slot.childCount) {
                    val child = slot.getChildAt(c)
                    if (child.getTag(R.id.tag_item_id) != null && child.screenRect().contains(x, y)) return true
                }
            }
        }
        return false
    }

    private fun drawerPullAllowed(): Boolean {
        if (!::drawer.isInitialized || drawer.isVisible) return false
        if (::feed.isInitialized && feed.isOpen) return false
        if (::search.isInitialized && search.isOpen) return false
        if (::editMode.isInitialized && editMode.isOpen) return false
        if (desktopPreviews.isShowing) return false
        if (drag.isArmed || drag.activeItemPopup != null) return false
        return GestureDefaults.swipeUpOpensDrawer(viewModel.state.value.prefs.gestureBindings)
    }

    private fun finishDrawerPull(cancelled: Boolean) {
        drawerPullActive = false
        val tracker = drawerPullVelocity
        var velocity = 0f
        if (tracker != null) {
            tracker.computeCurrentVelocity(1000)
            velocity = tracker.yVelocity
            tracker.recycle()
        }
        drawerPullVelocity = null
        drawer.endPull(velocity, cancelled)
    }

    // Whether a swipe from (x, y) may pull the feed in like a pager page:
    // first page, no wrap-around, above the dock, nothing else armed.
    private fun feedPullAllowed(x: Float, y: Float): Boolean {
        val prefs = viewModel.state.value.prefs
        if (!prefs.feedEnabled) return false
        if (prefs.infiniteScroll && workspaceAdapter.realPageCount > 1) return false
        if (workspaceAdapter.realPage(binding.workspacePager.currentItem) != 0) return false
        if (drag.isArmed || drag.activeItemPopup != null) return false
        if (desktopPreviews.isShowing) return false
        if (::editMode.isInitialized && editMode.isOpen) return false
        val dockTop = if (binding.dockBar.visibility == View.VISIBLE) binding.dockBar.top else binding.root.height
        return y >= binding.workspacePager.top && y < dockTop && x >= 0f
    }

    private fun onDockTouchDown(ev: MotionEvent) {
        val inDock = ev.y >= binding.dockBar.top && ev.y <= binding.dockBar.bottom
        // A flick across a paged dock turns its page; the home swipe
        // left/right bindings fired on the same lift and opened their apps
        // instead. The detector saw this down first and cleared its claims,
        // so this one stands for the gesture.
        if (inDock) gestureDetector.suppressSwipe(up = false, down = false, sideways = true)
        dockTouchDownX = if (inDock) ev.x else Float.NaN
        dockTouchDownY = ev.y
        dockTouchDownAt = ev.eventTime
    }

    private fun trackDockSwipe(ev: MotionEvent) {
        val prefs = viewModel.state.value.prefs
        if (!prefs.dockEnabled || prefs.dockPages <= 1) return
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> onDockTouchDown(ev)
            MotionEvent.ACTION_UP -> {
                if (dockTouchDownX.isNaN()) return
                val dx = ev.x - dockTouchDownX
                val dy = ev.y - dockTouchDownY
                dockTouchDownX = Float.NaN
                val quick = ev.eventTime - dockTouchDownAt < 350
                if (quick && kotlin.math.abs(dx) > dp(56) && kotlin.math.abs(dy) < dp(48)) {
                    val pages = prefs.dockPages
                    val next = dockPage + if (dx < 0) 1 else -1
                    dockPage = if (prefs.dockInfiniteScroll) next.mod(pages) else next.coerceIn(0, pages - 1)
                    if (prefs.dockRememberPosition) viewModel.persistDockPage(dockPage)
                    renderDock(viewModel.state.value)
                }
            }
        }
    }

    private fun setupWorkspace() {
        workspaceAdapter = WorkspacePagerAdapter(this)
        binding.workspacePager.adapter = workspaceAdapter
        // Build the neighbour page before the fling starts instead of during
        // it, and stop the default cross-fade on page rebinds.
        binding.workspacePager.offscreenPageLimit = 1
        (binding.workspacePager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView)?.apply {
            itemAnimator = null
            // The pager's list takes focus when the drawer's search field
            // lets it go (a drag out of search results, say), and with keys
            // typed it drew the system's focus highlight: a grey wash over
            // the whole home screen until the drawer next opened. The
            // settings root's reason (8dc7642).
            defaultFocusHighlightEnabled = false
        }
        binding.workspacePager.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (::editMode.isInitialized && editMode.isOpen) editMode.onPageChanged()
                val pages = workspaceAdapter.realPageCount
                val addPage = workspaceAdapter.isAddPage(position)
                // Edit mode's add-page slot reports as the page past the
                // last: the model's current page stays a real one, and the
                // clock rule sees the slot as away from home.
                val page = workspaceAdapter.realPage(position).coerceAtMost(pages - 1)
                viewModel.setCurrentPage(page)
                // Leaving the page takes an open resize frame down with it.
                widgets.activeResizeFrame?.let { frame ->
                    val framePage = viewModel.state.value.model.items.firstOrNull { it.id == frame.itemId }?.page
                    if (addPage || framePage != page) widgets.dismissResizeFrame()
                }
                binding.workspacePager.announceForAccessibility(
                    if (addPage) getString(R.string.add_page) else getString(R.string.page_desc, page + 1, pages)
                )
            }

            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                val realPage = workspaceAdapter.realPage(position)
                updateWallpaperOffset(realPage + positionOffset)
                binding.pageIndicator.onPageScrolled(realPage, positionOffset)
                workspaceAdapter.claimWidgetsInView()
            }

            override fun onPageScrollStateChanged(state: Int) {
                binding.pageIndicator.onScrollStateChanged(state)
                val idle = state == androidx.viewpager2.widget.ViewPager2.SCROLL_STATE_IDLE
                // The page effects scale, fade and rotate whole pages; a
                // hardware layer per page while they move draws each page
                // once and transforms the texture, instead of re-rasterising
                // every icon and widget on every frame. Dropped again at rest
                // so widgets and badges keep repainting normally.
                setPageHardwareLayers(!idle && appliedScrollEffect != ScrollEffect.NONE)
                if (idle) {
                    workspaceAdapter.onPagerIdle(binding.workspacePager)
                    removeEmptyPages()
                }
            }
        })
    }

    private var pageHardwareLayers = false

    private fun setPageHardwareLayers(on: Boolean) {
        if (on == pageHardwareLayers) return
        pageHardwareLayers = on
        val recycler = binding.workspacePager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView ?: return
        val type = if (on) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_NONE
        for (index in 0 until recycler.childCount) {
            recycler.getChildAt(index).setLayerType(type, null)
        }
    }

    // Parallax for live and static wallpapers: report our scroll position as a
    // 0..1 offset so the wallpaper pans with the pages.
    private fun updateWallpaperOffset(scrollPosition: Float) {
        val mode = WallpaperScrollMode.fromPrefs(viewModel.state.value.prefs)
        if (mode == WallpaperScrollMode.OFF) return
        val token = binding.root.windowToken ?: return
        val pages = workspaceAdapter.realPageCount
        val forward = if (pages <= 1) 0.5f else (scrollPosition / (pages - 1)).coerceIn(0f, 1f)
        val offset = if (mode == WallpaperScrollMode.REVERSE) 1f - forward else forward
        runCatching {
            val manager = WallpaperManager.getInstance(this)
            // Steps depend only on the page count; sending them per scroll
            // frame was a binder call per frame.
            if (pages != lastWallpaperPages) {
                lastWallpaperPages = pages
                manager.setWallpaperOffsetSteps(if (pages <= 1) 0f else 1f / (pages - 1), 0f)
            }
            manager.setWallpaperOffsets(token, offset, 0f)
        }
    }

    private fun setupDrawer() {
        cardActivation = com.supernova.launcher.feed.CardActivation(this) { viewModel.feedCards() }
        drawer = DrawerController(
            binding = binding,
            viewModel = viewModel,
            decorateIcon = { icon, app -> withBadge(icon, app.packageName, app.className, app.userSerial) },
            onAppClick = { view, app -> launchApp(app, view) },
            onAppLongPress = ::longPressNewApp,
            onWebSearch = ::launchWebSearch,
            onChipSearch = { chip, query ->
                openUriBlind(chip.url(query))
                hideDrawer()
            },
            // Deferred: `search` is initialized right after the drawer, and
            // this only runs on a user tap.
            onGlobalSearch = {
                hideDrawer()
                search.open()
            },
            buildFolderIcon = ::drawerFolderIcon,
            decorateFolderIcon = ::withFolderBadge,
            onFolderClick = { view, group, members -> showDrawerFolderPopup(view, group, members) },
            onFolderLongPress = ::longPressDrawerFolder,
            cards = object : DrawerController.CardHost {
                // Launches from a drawer card close the drawer like an app tap.
                private val binder = cardBinder(afterLaunch = { hideDrawer() })

                override fun onShown() = cardActivation.acquire(this)

                override fun onHidden() = cardActivation.release(this)

                override fun activatedCards() = cardActivation.activated()

                override fun cardView(
                    parent: android.view.ViewGroup,
                    card: com.supernova.launcher.feed.FeedCard
                ): View {
                    val popup = com.supernova.launcher.ui.theme.Styles
                        .resolve(this@LauncherActivity, viewModel.state.value.prefs)
                        .of(com.supernova.launcher.ui.theme.SurfaceId.POPUP)
                    binder.setAppearance(popup.surface, popup.text, popup.accent)
                    val binding = binder.inflate(parent)
                    binder.bind(binding, card)
                    return binding.root
                }
            }
        )
        cardActivation.addListener { if (drawer.isVisible) drawer.refreshCards() }
        // Covers every close path (back, close button, drag-dismiss); opening
        // is also triggered directly from openDrawer for immediacy.
        drawer.onOpenStateChanged = { open ->
            animateWallpaperZoom(if (open) 1f else 0f)
        }
    }

    private fun setupSearch() {
        search = SearchController(
            binding = binding,
            viewModel = viewModel,
            scope = lifecycleScope,
            onLaunchApp = { view, app -> launchApp(app, view) },
            onLaunchShortcut = { view, shortcut ->
                launchShortcut(shortcut.packageName, shortcut.shortcutId, shortcut.userSerial, view)
            },
            onOpenUri = ::openUriBlind,
            onAppInfo = { app ->
                val user = viewModel.appCatalog().userForSerial(app.userSerial)
                    ?: android.os.Process.myUserHandle()
                runCatching {
                    getSystemService(android.content.pm.LauncherApps::class.java).startAppDetailsActivity(
                        android.content.ComponentName(app.packageName, app.className), user, null, null
                    )
                }
            },
            folderHits = {
                val items = viewModel.state.value.model.items
                    .filter { it.itemType == ItemType.FOLDER }
                    .map { com.supernova.launcher.search.FolderSearchProvider.Hit(it.id, null, it.label) }
                val groups = viewModel.drawerFolderGroups()
                    .map { com.supernova.launcher.search.FolderSearchProvider.Hit(null, it.name, it.name) }
                items + groups
            },
            folderIcon = { hit ->
                hit.itemId?.let { id ->
                    viewModel.state.value.model.items.firstOrNull { it.id == id }
                        ?.let(::folderPreviewDrawable)
                } ?: hit.groupName?.let { name ->
                    viewModel.drawerFolderGroups().firstOrNull { it.name == name }?.let { group ->
                        drawerFolderIcon(group, viewModel.appsInGroup(group))
                    }
                }
            },
            onOpenFolder = { folder ->
                if (folder.itemId != null) {
                    viewModel.state.value.model.items.firstOrNull { it.id == folder.itemId }
                        ?.let { showFolderPopup(binding.workspacePager, it) }
                } else {
                    viewModel.drawerFolderGroups().firstOrNull { it.name == folder.groupName }
                        ?.let { showDrawerFolderPopup(binding.workspacePager, it, viewModel.appsInGroup(it)) }
                }
            },
            // A card launch from search hides the overlay, as every result does.
            cardBinder = cardBinder(afterLaunch = { search.hide() }),
            drawerColumns = { drawer.columns() },
            // A widget card it showed took the feed card's widget view.
            onHidden = { if (::feed.isInitialized && feed.isOpen) feed.rebindCards() },
            // Search's apps, shortcuts and drawer folders are drawer icons
            // too, in the drawer's field or the search window alike.
            onAppLongPress = ::longPressNewApp,
            onShortcutLongPress = { view, shortcut ->
                longPressNewShortcut(
                    view,
                    DragPayload.DrawerShortcut(
                        shortcut.packageName, shortcut.shortcutId, shortcut.userSerial, shortcut.label, shortcut.icon
                    )
                )
            },
            onFolderLongPress = { view, folder ->
                viewModel.drawerFolderGroups().firstOrNull { it.name == folder.groupName }?.let { group ->
                    longPressDrawerFolder(view, group, viewModel.appsInGroup(group))
                }
            }
        )
        // The drawer search is the search window: the drawer's field feeds
        // the same results (top hit, bold tiles, "Search with") and completes
        // inline. An app or shortcut launched from there behaves like any
        // drawer tap; everything else closes the drawer on its way out.
        drawer.externalSearch = search.attachDrawer(
            input = binding.drawerSearch,
            ghost = binding.drawerSearchGhost,
            results = binding.drawerSearchResults,
            cards = cardBinder(afterLaunch = { hideDrawer() }),
            afterLaunch = {},
            dismiss = { hideDrawer() }
        )
        // The home bar opens the drawer, not the search window: the drawer
        // arrives with its own field focused and the keyboard up, whatever
        // the drawer's keyboard setting. The bar's search chip and the
        // gestures still reach the global overlay.
        binding.desktopSearchBar.onTap = { openDrawer(forSearch = true) }
        binding.desktopSearchBar.onLongPress = { anchor -> showSearchBarMenu(anchor) }
    }

    // In-card grant buttons on the feed's calendar/contacts cards; a grant
    // refreshes the still-open feed so the card upgrades in place.
    private val requestFeedCalendarPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted && ::feed.isInitialized) feed.refreshSources()
        }

    private val requestFeedContactsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted && ::feed.isInitialized) feed.refreshSources()
        }

    private fun setupFeed() {
        feed = FeedController(
            binding = binding,
            viewModel = viewModel,
            scope = lifecycleScope,
            activation = cardActivation,
            cardBinder = cardBinder(afterLaunch = null),
            canPullFromWorkspace = ::feedPullAllowed,
            itemDragArmed = { drag.isArmed || drag.isActive },
            host = object : FeedController.Host {
                override fun onOpenSearch() {
                    search.open()
                }

                override fun launchApp(anchor: View?, app: LaunchableApp) {
                    this@LauncherActivity.launchApp(app, anchor)
                }

                override fun launchTarget(
                    type: com.supernova.launcher.feed.FeaturedType,
                    target: String,
                    anchor: View?
                ) = launchCardTarget(type, target)

                override fun requestCalendarPermission() {
                    requestFeedCalendarPermission.launch(android.Manifest.permission.READ_CALENDAR)
                }

                override fun requestContactsPermission() {
                    requestFeedContactsPermission.launch(android.Manifest.permission.READ_CONTACTS)
                }

                override fun openCalendarEvent(eventId: Long) {
                    runCatching {
                        startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                android.content.ContentUris.withAppendedId(
                                    android.provider.CalendarContract.Events.CONTENT_URI, eventId
                                )
                            )
                        )
                    }
                }

                override fun openContact(
                    contact: com.supernova.launcher.feed.ContactsCardSource.Starred,
                    anchor: View
                ) {
                    val uri = android.provider.ContactsContract.Contacts.getLookupUri(
                        contact.contactId, contact.lookupKey
                    )
                    runCatching {
                        android.provider.ContactsContract.QuickContact.showQuickContact(
                            this@LauncherActivity, anchor, uri,
                            android.provider.ContactsContract.QuickContact.MODE_LARGE, null
                        )
                    }.onFailure {
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    }
                }

                override fun editCard(card: com.supernova.launcher.feed.FeedCard?) = editFeedCard(card)

                override fun openFeedSettings() {
                    startActivity(
                        Intent(this@LauncherActivity, SettingsActivity::class.java)
                            .putExtra(SettingsActivity.EXTRA_SECTION_TITLE_RES, R.string.settings_section_feed)
                    )
                }
            }
        )
        // Card edits re-arm the shared monitor (new Wi-Fi/Bluetooth needs)
        // and refresh whichever surface is showing cards. The feed collects
        // the same emission itself.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.map { it.model.feedCards }.distinctUntilChanged().collect {
                    cardActivation.refresh()
                    if (drawer.isVisible) drawer.refreshCards()
                }
            }
        }
        // A launch from the drawer leaves it open behind the app (unless Close
        // drawer after launching is on), so its frequent row catches up here
        // rather than on the next open. The feed pane watches for itself.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.launchGeneration.collect {
                    if (drawer.isVisible) drawer.refreshFrequentRow()
                }
            }
        }

        // Page 0's left edge draws no overscroll glow: a clearly horizontal
        // drag there is the feed pull (FeedController.onTouch takes the
        // stream before the pager can), and anything less horizontal is a
        // vertical gesture that merely drifted - which must NOT open the
        // feed. The old snap-open on overscroll fired on exactly that drift.
        (binding.workspacePager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView)
            ?.edgeEffectFactory = object : androidx.recyclerview.widget.RecyclerView.EdgeEffectFactory() {
            override fun createEdgeEffect(
                view: androidx.recyclerview.widget.RecyclerView,
                direction: Int
            ): android.widget.EdgeEffect {
                if (direction != DIRECTION_LEFT) return super.createEdgeEffect(view, direction)
                return object : android.widget.EdgeEffect(view.context) {
                    override fun onPull(deltaDistance: Float) = Unit

                    override fun onPull(deltaDistance: Float, displacement: Float) = Unit
                }
            }
        }
    }

    // Search targets (web engines, chips) launch blind: no <queries> entry,
    // no handler probing - a missing handler just no-ops.
    private fun openUriBlind(uri: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri)))
        }
    }

    private fun launchWebSearch(query: String) {
        val search = Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, query)
        runCatching { startActivity(search) }.onFailure {
            val fallback = Intent(
                Intent.ACTION_VIEW,
                android.net.Uri.parse("https://www.google.com/search?q=${android.net.Uri.encode(query)}")
            )
            runCatching { startActivity(fallback) }
        }
    }

    private fun setupDock() {
        // The dock sizes itself to its icon columns, so its height is only
        // known once it has been laid out - and the workspace, indicator and
        // an above-dock search bar are all spaced off that height. Reading it
        // back here keeps them in step with a label that wrapped, an icon
        // scale change or a font-size change, whatever caused it. The margins
        // can't be rewritten from inside a layout pass, so dockChromeWatch
        // applies them after it, before the pass draws.
        binding.dockBar.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val height = bottom - top
            if (height > 0 && height != dockHeightPx) {
                dockHeightPx = height
                dockChromePending = true
                val observer = binding.root.viewTreeObserver
                observer.removeOnPreDrawListener(dockChromeWatch)
                observer.addOnPreDrawListener(dockChromeWatch)
            }
        }
    }

    // A dock height that moved in a layout pass leaves everything spaced off
    // it - the workspace, the page indicator, an above-dock search bar - laid
    // out against the old height, and a posted re-space came too late: the
    // frame drew first. On a cold start the dock fills in the same pass page
    // 0 first binds, and the post only ran after the next frame as well, so
    // the page and its indicator drew twice sized for the empty dock before
    // jumping up. Instead the chrome is re-spaced between layout and draw and
    // the frame dropped, so the pass re-runs against the new margins before
    // anything reaches the screen.
    //
    // Only a height that really moved holds a frame, and at most
    // MAX_DOCK_CHROME_HOLDS in a row: a cold start can move it in back-to-back
    // passes (the empty dock, then its icons), but a height still moving
    // after that is not settling, and holding every frame would freeze the
    // screen.
    private var dockChromePending = false
    private var dockChromeHolds = 0
    private val dockChromeWatch = object : ViewTreeObserver.OnPreDrawListener {
        override fun onPreDraw(): Boolean {
            if (dockChromePending) {
                dockChromePending = false
                applyDockChrome(viewModel.state.value.prefs)
                if (dockChromeHolds < MAX_DOCK_CHROME_HOLDS) {
                    dockChromeHolds++
                    return false
                }
            }
            // Laid out against the chrome as it stands (or out of holds).
            binding.root.viewTreeObserver.removeOnPreDrawListener(this)
            dockChromeHolds = 0
            return true
        }
    }

    // dockRememberPosition: pull the persisted page back in (0 when the pref
    // is off); renderDock clamps it against the current page count.
    private fun restoreDockPage() {
        lifecycleScope.launch {
            val saved = viewModel.restoredDockPage()
            if (saved > 0 && saved != dockPage) {
                dockPage = saved
                val state = viewModel.state.value
                // Not yet bindable: the state emission that flips iconsReady
                // renders the dock on this page anyway.
                if (state.iconsReady) renderDock(state)
            }
        }
    }

    private fun setupHomeGestures() {
        // Recognition lives in HomeGestureDetector; dispatchTouchEvent feeds it.
    }

    private fun runGesture(gesture: Gesture): Boolean {
        val prefs = viewModel.state.value.prefs
        val bound = GestureDefaults.decode(prefs.gestureBindings)[gesture] ?: GestureBinding(GestureAction.NONE)
        // The Expand notifications haptic: a tick as the swipe pulls the
        // shade down.
        if (gesture == Gesture.SWIPE_DOWN && bound.action == GestureAction.EXPAND_NOTIFICATIONS && prefs.swipeDownHaptic) {
            binding.root.performHapticFeedback(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    android.view.HapticFeedbackConstants.GESTURE_START
                } else {
                    android.view.HapticFeedbackConstants.VIRTUAL_KEY
                }
            )
        }
        return runAction(bound)
    }

    // The single action dispatcher: gestures and the home-button re-press
    // both resolve to a GestureBinding and land here.
    private fun runAction(bound: GestureBinding): Boolean {
        return when (bound.action) {
            GestureAction.NONE -> false
            GestureAction.OPEN_DRAWER -> {
                openDrawer()
                true
            }
            GestureAction.EXPAND_NOTIFICATIONS -> {
                expandNotificationShade()
                true
            }
            GestureAction.OPEN_SETTINGS -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            GestureAction.LOCK_SCREEN -> lockScreen()
            GestureAction.DEFAULT_PAGE -> {
                val model = viewModel.state.value.model
                binding.workspacePager.setCurrentItem(
                    workspaceAdapter.centerPosition(model.defaultPage.coerceIn(0, model.pageCount - 1)),
                    true
                )
                true
            }
            GestureAction.OPEN_SEARCH -> {
                search.open()
                true
            }
            GestureAction.SEARCH_QUERY -> {
                val query = bound.payload
                if (query.isNullOrBlank()) false else { search.open(query); true }
            }
            GestureAction.RECENTS -> globalAction(SupernovaAccessibilityService.showRecents())
            GestureAction.QUICK_SETTINGS -> globalAction(SupernovaAccessibilityService.showQuickSettings())
            GestureAction.ASSISTANT -> {
                runCatching { startActivity(Intent(Intent.ACTION_ASSIST)) }
                    .onFailure { Toast.makeText(this, R.string.assistant_unavailable, Toast.LENGTH_SHORT).show() }
                true
            }
            GestureAction.LAUNCH_APP -> {
                launchGestureApp(bound.payload)
                true
            }
            GestureAction.LAUNCH_SHORTCUT -> {
                launchGestureShortcut(bound.payload)
                true
            }
            GestureAction.LAUNCH_INTENT -> {
                bound.payload?.let { launchIntentUri(it, null) }
                bound.payload != null
            }
            GestureAction.TOGGLE_STATUS_BAR -> {
                viewModel.setHideStatusBar(!viewModel.state.value.prefs.hideStatusBar)
                true
            }
            GestureAction.TOGGLE_DOCK -> {
                toggleDock()
                true
            }
            GestureAction.JUMP_TO_PAGE -> {
                bound.payload?.toIntOrNull()?.let { page ->
                    val pages = viewModel.state.value.model.pageCount
                    binding.workspacePager.setCurrentItem(
                        workspaceAdapter.centerPosition(page.coerceIn(0, pages - 1)), true
                    )
                }
                true
            }
            GestureAction.DESKTOP_PREVIEWS -> {
                desktopPreviews.show()
                true
            }
            GestureAction.OPEN_FEED -> {
                if (viewModel.state.value.prefs.feedEnabled) {
                    feed.open()
                } else {
                    Toast.makeText(this, R.string.feed_disabled_hint, Toast.LENGTH_SHORT).show()
                }
                true
            }
            GestureAction.VOICE_SEARCH -> {
                startVoiceSearch()
                true
            }
        }
    }

    // Fire-and-forget voice search: web voice search, else the voice command
    // assistant, else a toast. Blind-start each so a missing app can't crash.
    private fun startVoiceSearch() {
        val intents = listOf(
            Intent(android.speech.RecognizerIntent.ACTION_WEB_SEARCH),
            Intent(Intent.ACTION_VOICE_COMMAND),
            Intent(Intent.ACTION_ASSIST)
        )
        for (intent in intents) {
            val ok = runCatching {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (ok) return
        }
        Toast.makeText(this, R.string.voice_search_unavailable, Toast.LENGTH_SHORT).show()
    }

    // Recents and quick settings both need the accessibility service, same as
    // lock; tell the user rather than failing silently.
    private fun globalAction(performed: Boolean): Boolean {
        if (!performed) {
            Toast.makeText(this, R.string.action_needs_accessibility, Toast.LENGTH_LONG).show()
            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        return true
    }

    // Payload is "pkg/cls@userSerial".
    private fun launchGestureApp(payload: String?) {
        val (component, serial) = com.supernova.launcher.data.LaunchTarget.split(payload ?: return)
        val pkg = component.substringBefore('/')
        val cls = component.substringAfter('/', "")
        if (pkg.isEmpty() || cls.isEmpty()) return
        launchApp(LaunchableApp(pkg, cls, "", serial))
    }

    // Payload is "pkg|shortcutId@userSerial".
    private fun launchGestureShortcut(payload: String?) {
        val (body, serial) = com.supernova.launcher.data.LaunchTarget.split(payload ?: return)
        val pkg = body.substringBefore('|')
        val shortcutId = body.substringAfter('|', "")
        if (pkg.isEmpty() || shortcutId.isEmpty()) return
        launchShortcut(pkg, shortcutId, serial)
    }

    // Screen lock needs an accessibility service on API 28+; without it we
    // tell the user rather than silently doing nothing.
    private fun lockScreen(): Boolean {
        if (viewModel.state.value.prefs.lockMethod == "DEVICE_ADMIN") {
            if (SupernovaDeviceAdmin.lock(this)) return true
            // Selected but not yet granted: send the user to enable it.
            promptDeviceAdmin()
            return true
        }
        val locked = SupernovaAccessibilityService.lockScreen()
        if (!locked) {
            Toast.makeText(this, R.string.lock_needs_accessibility, Toast.LENGTH_LONG).show()
            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        return true
    }

    private fun promptDeviceAdmin() {
        val intent = Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(
                android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                SupernovaDeviceAdmin.component(this)
            )
            .putExtra(
                android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                getString(R.string.lock_admin_explanation)
            )
        runCatching { startActivity(intent) }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    // The stateIn placeholder would paint an empty workspace
                    // for one frame before the real layout decodes.
                    if (!state.loaded) return@collect
                    ThemeEngine.applyMode(this@LauncherActivity, state.prefs)
                    if (state.prefs.accentColor != appliedAccent) {
                        appliedAccent = state.prefs.accentColor
                        recreate()
                        return@collect
                    }
                    pendingEditAddPage?.let { pending ->
                        if (pending.landed && state.currentPage == pending.page) {
                            pendingEditAddPage = null
                            pending.then?.invoke()
                        }
                    }
                    // A page swipe emits a state differing only in currentPage;
                    // everything below is page-agnostic, so skip it and leave
                    // the settle animation's frames alone.
                    val last = lastRenderedState
                    lastRenderedState = state
                    if (last != null && onlyPageMoved(last, state)) return@collect
                    // Disable-animations forces the NONE transformer path.
                    applyScrollEffect(
                        if (Animations.enabled(state.prefs)) {
                            ScrollEffect.fromName(state.prefs.scrollEffect)
                        } else {
                            ScrollEffect.NONE
                        }
                    )
                    applySystemChrome(state.prefs)
                    feed.onPrefs(state.prefs)
                    // Everything above needs no icons, so it runs as soon as
                    // the persisted state decodes. Everything below binds
                    // icons: doing that before the catalog is configured and
                    // the first frame's icons are warmed renders them on this
                    // thread, and with an icon pack draws stock icons that
                    // are evicted moments later. The generation going 0 -> 1
                    // brings us straight back here.
                    if (!state.iconsReady) return@collect
                    if (!reportedFullyDrawn) {
                        reportedFullyDrawn = true
                        runCatching { reportFullyDrawn() }
                    }
                    drawer.onState(state)
                    // Search lists what the apps, the hidden set, app edits
                    // and the drawer's groups made; a change to any of them
                    // (Hide, Edit or Rename from one of its own menus, an
                    // install) runs a showing query again.
                    if (last != null && (last.apps !== state.apps ||
                            last.prefs.hiddenApps != state.prefs.hiddenApps ||
                            last.prefs.drawerGroups != state.prefs.drawerGroups ||
                            last.model.appOverrides != state.model.appOverrides)
                    ) {
                        search.refresh()
                    }
                    workspaceAdapter.refresh()
                    workspaceAdapter.recenterIfNeeded(binding.workspacePager)
                    drag.carryOverToNewPage()
                    pendingPageAfterRemoval?.let { pending ->
                        pendingPageAfterRemoval = null
                        if (workspaceAdapter.realPageCount == pending.pageCount) showPageAfterRemoval(pending.page)
                    }
                    // Edit mode's Remove page follows the page emptying or filling.
                    if (::editMode.isInitialized) editMode.onPageChanged()
                    // Snapshot once the real layout has arrived, so a bad edit
                    // later in the session is recoverable.
                    if (state.model.items.isNotEmpty()) viewModel.writeAutoBackupOnce()
                    // Its own color, or the labels' as the swipe arrow has.
                    binding.pageIndicator.configure(
                        IndicatorStyle.fromName(state.prefs.pageIndicatorStyle),
                        workspaceAdapter.realPageCount,
                        state.prefs.pageIndicatorAutoHide,
                        LabelStyler.parseColor(
                            state.prefs.pageIndicatorColor.ifEmpty { state.prefs.labelColor },
                            getColor(R.color.onWallpaper)
                        ),
                        Animations.factor(state.prefs)
                    )
                    // Edit mode's "+" page asked for a page; it exists now, so
                    // leave edit mode on it. When not looping the pager already
                    // sits at its position - the slot became the page - so no
                    // scroll event tells the indicator and the wallpaper.
                    pendingEditAddPage?.let { pending ->
                        if (!pending.landed && pending.page < workspaceAdapter.realPageCount) {
                            pending.landed = true
                            if (pending.then == null) pendingEditAddPage = null
                            editMode.exit()
                            binding.workspacePager.setCurrentItem(
                                workspaceAdapter.centerPosition(pending.page), false
                            )
                            viewModel.setCurrentPage(pending.page)
                            binding.pageIndicator.onPageScrolled(pending.page, 0f)
                            updateWallpaperOffset(pending.page.toFloat())
                        }
                    }
                    renderDock(state)
                }
            }
        }
    }

    private fun applyScrollEffect(effect: ScrollEffect) {
        if (effect == appliedScrollEffect) return
        appliedScrollEffect = effect
        // Transformers only reset the properties they mutate, so the previous
        // effect's leftovers (rotation, alpha, ...) must be cleared by hand.
        (binding.workspacePager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView)?.let { recycler ->
            for (index in 0 until recycler.childCount) {
                PageTransformers.resetTransforms(recycler.getChildAt(index))
            }
        }
        binding.workspacePager.setPageTransformer(PageTransformers.forEffect(effect))
    }

    // Status bar visibility/icon tint, nav bar transparency and rotation
    // (Part E). Guarded so repeat emissions don't touch the window.
    private fun applySystemChrome(prefs: LauncherPrefs) {
        val key = listOf(
            prefs.hideStatusBar, prefs.darkStatusIcons, prefs.transparentNav,
            prefs.transparentStatus, prefs.homeRotation,
            prefs.homeOrientation
        )
        if (appliedSystemChrome == key) return
        appliedSystemChrome = key

        applyStatusBarVisibility(prefs.hideStatusBar)

        val night = resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        // AUTO follows the theme (light theme = light bar = dark icons),
        // which is what the DayNight theme did before this pref existed.
        WindowInsetsControllerCompat(window, binding.root).isAppearanceLightStatusBars =
            when (StatusBarIcons.fromName(prefs.darkStatusIcons)) {
                StatusBarIcons.AUTO -> !night
                StatusBarIcons.LIGHT -> true
                StatusBarIcons.DARK -> false
            }

        window.statusBarColor =
            if (prefs.transparentStatus) android.graphics.Color.TRANSPARENT else android.graphics.Color.BLACK
        // Same contrast scrim story as the nav bar below, on the other edge.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = !prefs.transparentStatus
        }
        window.navigationBarColor =
            if (prefs.transparentNav) android.graphics.Color.TRANSPARENT else android.graphics.Color.BLACK
        // Without this the system paints its own translucent scrim over a
        // transparent nav bar (API 29+ contrast enforcement), leaving a dim
        // strip instead of true wallpaper.
        // Contrast scrim control is Android 10+; Android 9 keeps its default.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = !prefs.transparentNav
        }
        // From targetSdk 35 the window colours above are ignored (the bars
        // are always transparent), so the opaque choice is drawn by hand.
        binding.statusBarBand.isVisible = !prefs.transparentStatus && !prefs.hideStatusBar
        binding.navBarBand.isVisible = !prefs.transparentNav

        // The grid is dp/weight based (WorkspaceGeometry normalized floats),
        // so landscape renders without dedicated layouts - functional, not
        // tuned. The manifest doesn't pin orientation; this is the one pin.
        // DEFAULT keeps the old homeRotation=false pin exactly; the forced
        // axes use the sensor variants so the 180-degree flip still works.
        requestedOrientation = when (HomeOrientation.fromPrefs(prefs)) {
            HomeOrientation.DEFAULT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            HomeOrientation.AUTO_ROTATE -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            HomeOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            HomeOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    private fun applyStatusBarVisibility(hide: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = window.insetsController ?: return
            if (hide) {
                controller.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(android.view.WindowInsets.Type.statusBars())
            } else {
                controller.show(android.view.WindowInsets.Type.statusBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (hide) {
                window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_FULLSCREEN
            } else {
                window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN.inv()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // A launched app can restore the status bar; re-hide on return.
        if (hasFocus && viewModel.state.value.prefs.hideStatusBar) {
            applyStatusBarVisibility(true)
        }
    }

    // Whether the workspace pager can still move in a direction (+1 = next
    // page). Horizontal edge-swipe gestures fire only when it can't.
    private fun workspaceCanScroll(direction: Int): Boolean {
        val pages = workspaceAdapter.realPageCount
        if (viewModel.state.value.prefs.infiniteScroll && pages > 1) return true
        val page = workspaceAdapter.realPage(binding.workspacePager.currentItem)
        return if (direction > 0) page < pages - 1 else page > 0
    }

    // force: the dock's content changed without anything in its signature
    // moving. Two callers need it - a badge count, and the day rolling over
    // under a calendar icon - because neither lives in the prefs or the model.
    private fun renderDock(state: LauncherUiState, force: Boolean = false) {
        val key = dockSignature(state)
        if (!force && renderedDock == key) return
        renderedDock = key
        val prefs = state.prefs
        binding.dockBar.removeAllViews()
        if (!prefs.dockEnabled) {
            applyDockChrome(prefs)
            return
        }

        if (dockPage >= prefs.dockPages) dockPage = prefs.dockPages - 1
        val dockByRank = viewModel.dockItems().associateBy { it.rank }
        val rankBase = dockPage * prefs.dockCount
        // What each icon column has to work with, so the icon can be sized to
        // its slot rather than overflowing it. The dock's laid-out width is
        // authoritative once there is one; before that the window minus the
        // dock's own margins is the same number.
        dockSlotWidthPx = dockContentWidth(prefs) / prefs.dockCount.coerceAtLeast(1)
        // An empty dock still has to be a drop target, so it never collapses
        // below a bare icon column even with every slot empty.
        binding.dockBar.minimumHeight = dockIconBoxPx(prefs) +
            dp(DOCK_ITEM_PADDING_V_DP * 2 + DOCK_SLOT_PADDING_V_DP * 2)

        repeat(prefs.dockCount) { slotIndex ->
            val rank = rankBase + slotIndex
            val slot = FrameLayout(this).apply {
                // No horizontal inset: the LinearLayout already spaces the
                // slots evenly, so padding here only narrowed the label - the
                // reason "Chrome" used to render as "Chro".
                setPadding(0, dp(DOCK_SLOT_PADDING_V_DP), 0, dp(DOCK_SLOT_PADDING_V_DP))
                setOnClickListener {
                    val movingId = moveModeItemId ?: return@setOnClickListener
                    moveModeItemId = null
                    viewModel.moveItemToDock(movingId, rank)
                }
                // The drag layer turns "the slot under the finger" into a rank.
                setTag(R.id.tag_dock_rank, rank)
            }

            dockByRank[rank]?.let { item ->
                val itemView = viewForItem(item)
                if (itemView.parent != null) {
                    (itemView.parent as? ViewGroup)?.removeView(itemView)
                }
                // Same as the workspace rebind: never inherit the
                // drag-source hiding on a reused view - but an item whose
                // drop is still settling stays hidden until it lands.
                itemView.alpha = if (item.id == drag.settlingItemId) 0f else 1f
                slot.addView(
                    itemView,
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                )
            }

            binding.dockBar.addView(
                slot,
                android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            )
        }
        // The slots go in at MATCH_PARENT under a wrap_content dock, so they
        // come out uniform at the tallest column's height and every icon lines
        // up whether or not its neighbour's label wrapped. The chrome goes on
        // after the slots: the height it spaces from is theirs.
        applyDockChrome(prefs)
    }

    // Everything the dock draws from. renderDock tears the row down with
    // removeAllViews and rebuilds every slot, and it runs on every state
    // emission that gets past observeState's guard - so dragging an icon
    // around page 3 re-inflated the dock on every model write.
    //
    // The whole prefs object goes in rather than a list of the dock-relevant
    // fields: a pref change is rare and user-driven, so rebuilding on all of
    // them costs nothing, while missing one would leave the dock stale. The
    // laid-out width is in for the same reason - the icon box is sized from
    // it, and the first render can happen before the row has been measured.
    private fun dockSignature(state: LauncherUiState): List<Any?> {
        val items = viewModel.dockItems().joinToString(";") { item ->
            "${item.id}:${item.rank}:${item.label}:${item.packageName}/${item.className}" +
                // The swipes decide the icon's touch handling and its mark.
                ":${item.swipeAction}:${item.swipeActionDown}:${item.swipeToOpen}" +
                FolderIconDrawable.customIconKey(item) +
                // A folder's icon is drawn from its members, which are not
                // dock items themselves, or from its own custom icon.
                if (item.itemType == ItemType.FOLDER) {
                    viewModel.folderMembers(item.id)
                        .joinToString(",", "[", "]") { "${it.id}:${it.packageName}/${it.className}" }
                } else {
                    ""
                }
        }
        return listOf(
            state.prefs,
            state.iconGeneration,
            dockPage,
            drag.settlingItemId,
            isLandscape,
            binding.dockBar.width,
            items
        )
    }

    // How wide the bottom chrome is allowed to grow in landscape: the
    // display's short edge (the width this same chrome has in portrait) with
    // some slack, so it uses the extra room without spreading across it. 0 in
    // portrait, and whenever the window is already narrower than the cap.
    private fun landscapeChromeWidthPx(): Int {
        if (!isLandscape) return 0
        val metrics = resources.displayMetrics
        val available = metrics.widthPixels - systemInsets.left - systemInsets.right
        val cap = (minOf(metrics.widthPixels, metrics.heightPixels) * LANDSCAPE_CHROME_WIDTH).toInt()
        return if (cap in 1 until available) cap else 0
    }

    // The dock's margin at each side. A dock without a background spans the
    // width, so its slots stand on the grid's columns; a
    // background floats 10dp in from the edges unless it runs edge to edge.
    private fun dockSideMarginPx(prefs: LauncherPrefs, edgeToEdge: Boolean): Int =
        if (edgeToEdge || DockBgStyle.fromName(prefs.dockBgStyle) == DockBgStyle.NONE) 0 else dp(10)

    // Width the dock has for its slots: its laid-out width once it has one,
    // else the window minus the margins applyDockChrome is about to give it.
    private fun dockContentWidth(prefs: LauncherPrefs): Int {
        val laidOut = binding.dockBar.width - binding.dockBar.paddingLeft - binding.dockBar.paddingRight
        if (laidOut > 0) return laidOut
        val sideMargins = 2 * (dockSideMarginPx(prefs, false) + dp(prefs.dockPaddingH)) +
            systemInsets.left + systemInsets.right
        val window = landscapeChromeWidthPx().takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels
        return (window - sideMargins).coerceAtLeast(1)
    }

    // The icon box a dock item draws in. The scale rides in the box size
    // rather than in scaleX/scaleY so the row measures what it draws, and the
    // box never outgrows its slot - a seven-icon dock hands out slots narrower
    // than the 42dp base, and an oversized icon used to be clipped to them.
    override fun dockIconBoxPx(prefs: LauncherPrefs): Int {
        val scale = if (prefs.dockMatchDesktop) prefs.iconScale else prefs.dockIconScale
        val box = (dp(ICON_BASE_DP) * scale).toInt()
        val slotLimit = dockSlotWidthPx - dp(DOCK_ITEM_PADDING_H_DP * 2)
        return if (slotLimit > 0) minOf(box, slotLimit) else box
    }

    // The icon box a desktop item draws in, sized for the same reason. It has
    // no slot to stay inside: the canvas places an item by its centre, and a
    // big icon's item may be larger than its cell.
    override fun desktopIconBoxPx(prefs: LauncherPrefs): Int = (dp(ICON_BASE_DP) * prefs.iconScale).toInt()

    // The room a desktop icon takes at its own size: its box inside the
    // item's padding, over a line of label when labels show. A cell smaller
    // than that shrinks the icon into it (WorkspaceCanvas.IconFit).
    private var desktopNeedPrefs: LauncherPrefs? = null
    private var desktopNeed = android.util.Size(0, 0)
    override fun desktopIconNeedPx(prefs: LauncherPrefs): android.util.Size {
        if (prefs !== desktopNeedPrefs) {
            val box = desktopIconBoxPx(prefs) + dp(ICON_ITEM_PADDING_DP) * 2
            val label = if (prefs.showLabels) {
                val probe = android.widget.TextView(this)
                LabelStyler.apply(
                    probe, prefs, onWallpaper = true, context = com.supernova.launcher.ui.LabelContext.DESKTOP
                )
                probe.lineHeight
            } else {
                0
            }
            desktopNeed = android.util.Size(box, box + label)
            desktopNeedPrefs = prefs
        }
        return desktopNeed
    }

    // Dock visibility, background, scrims, the desktop search bar, and the
    // workspace/indicator insets that depend on them; guarded so repeat
    // emissions don't rebuild drawables.
    // Edge-to-edge: the window draws behind the status and navigation bars so
    // the wallpaper is unbroken. Nothing is consumed here - the drawer sheet,
    // the search overlay, the feed pane and the edit-mode overlay all run their
    // own listeners downstream of this one.
    private fun setUpEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Without SHORT_EDGES the window stops at the cutout in landscape,
        // leaving a bare strip down the camera edge. The listener below already
        // unions displayCutout() in, so content still clears the notch.
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            if (bars != systemInsets) {
                systemInsets = bars
                applyWindowInsets()
            }
            insets
        }
    }

    // Fans the current insets out to the chrome that is always present. The
    // dock, workspace, indicator and desktop bar take theirs inside
    // applyDockChrome, which already owns every margin on those views.
    private fun applyWindowInsets() {
        binding.scrimTop.updateLayoutParams { height = dp(96) + systemInsets.top }
        binding.scrimBottom.updateLayoutParams { height = dp(96) + systemInsets.bottom }
        binding.statusBarBand.updateLayoutParams { height = systemInsets.top }
        binding.navBarBand.updateLayoutParams { height = systemInsets.bottom }
        if (::drawer.isInitialized) drawer.setSystemInsets(systemInsets)
        binding.dropTargetBar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = systemInsets.top + dp(16)
        }
        // applyDockChrome's guard key carries systemInsets, so this re-applies.
        applyDockChrome(viewModel.state.value.prefs)
    }

    private fun applyDockChrome(prefs: LauncherPrefs) {
        val key = listOf(
            prefs.dockEnabled, prefs.dockBgStyle, prefs.dockBgOpacity,
            prefs.dockOverlay, prefs.showScrims,
            prefs.styleConfig, prefs.paletteSource, prefs.paletteSeed,
            prefs.searchBarConfig, prefs.searchBarWidgetId,
            prefs.dockPaddingH, prefs.dockPaddingV,
            prefs.dockBgBehindNav, prefs.dockBgPadding,
            swipeArrowWanted(prefs), prefs.labelColor,
            IndicatorStyle.fromName(prefs.pageIndicatorStyle) != IndicatorStyle.NONE, prefs.desktopPaddingV,
            systemInsets, dockHeightPx, isLandscape
        )
        if (appliedDockChrome == key) return
        appliedDockChrome = key

        binding.dockBar.visibility =
            if (prefs.dockEnabled && !dockAutoClosed && !dockToggledOff) View.VISIBLE else View.GONE
        syncSwipeArrow(prefs)
        binding.scrimTop.visibility = if (prefs.showScrims) View.VISIBLE else View.GONE
        binding.scrimBottom.visibility = if (prefs.showScrims) View.VISIBLE else View.GONE
        // Desktop search bar placement. DOCK_ABOVE/DOCK_BELOW only exist
        // relative to a visible dock; PERSISTENT degrades to the bare bottom
        // when the dock is off. WIDGET is a later phase and renders nothing.
        val barConfig = SearchBarConfig.decode(prefs.searchBarConfig).desktop
        val slot = when (SearchBarPlacement.fromName(barConfig.placement)) {
            SearchBarPlacement.NONE, SearchBarPlacement.WIDGET -> null
            SearchBarPlacement.DOCK_ABOVE -> if (prefs.dockEnabled) BarSlot.ABOVE_DOCK else null
            SearchBarPlacement.DOCK_BELOW -> if (prefs.dockEnabled) BarSlot.BELOW_DOCK else null
            SearchBarPlacement.PERSISTENT ->
                if (prefs.dockEnabled) BarSlot.BELOW_DOCK else BarSlot.BOTTOM
        }
        // A Replace widget takes the bar's slot; the built-in bar steps aside
        // but keeps driving the slot's geometry below.
        val barWidget = if (slot == null) null else widgets.bindSearchBarWidget(prefs)
        binding.desktopSearchWidget.visibility =
            if (barWidget == null) View.GONE else View.VISIBLE
        binding.desktopSearchBar.visibility =
            if (slot == null || barWidget != null) View.GONE else View.VISIBLE
        if (slot != null) {
            binding.desktopSearchBar.apply(
                barConfig,
                com.supernova.launcher.ui.theme.Styles.resolve(this, prefs)
                    .of(com.supernova.launcher.ui.theme.SurfaceId.SEARCH_BAR)
            )
        }
        // Behind-nav mode drops the side and bottom margins so the background
        // slab runs flush to the window's bottom edge.
        val dockBehindNav = prefs.dockBgBehindNav &&
            DockBgStyle.fromName(prefs.dockBgStyle) != DockBgStyle.NONE &&
            slot != BarSlot.BELOW_DOCK
        // Un-inset background: the slab spans the full width and rests on the
        // navigation bar rather than floating clear of it. Behind-nav is the
        // stronger version of the same thing, so it wins.
        val dockFullBleed = !prefs.dockBgPadding && !dockBehindNav &&
            DockBgStyle.fromName(prefs.dockBgStyle) != DockBgStyle.NONE &&
            slot != BarSlot.BELOW_DOCK
        binding.dockBar.setPadding(
            dp(prefs.dockPaddingH) + if (dockBehindNav) systemInsets.left else 0,
            dp(prefs.dockPaddingV),
            dp(prefs.dockPaddingH) + if (dockBehindNav) systemInsets.right else 0,
            dp(prefs.dockPaddingV) + if (dockBehindNav) systemInsets.bottom else 0
        )
        val density = resources.displayMetrics.density
        val arrowWanted = swipeArrowWanted(prefs)
        val arrow = if (arrowWanted) {
            SwipeArrowDrawable(density, LabelStyler.parseColor(prefs.labelColor, getColor(R.color.onWallpaper)))
        } else {
            null
        }
        // Heights above the window bottom for the dock, the bar, the arrow,
        // the page indicator and the workspace's floor (BottomChrome). Before
        // the dock's first layout the old fixed bar height stands in;
        // behind-nav folds the navigation bar into the dock's own padding, so
        // the laid-out height counts it already.
        val chrome = BottomChrome.layout(
            BottomChrome.Spec(
                density = density,
                landscape = isLandscape,
                navInset = systemInsets.bottom,
                dockEnabled = prefs.dockEnabled,
                dockOverlay = prefs.dockOverlay,
                dockBehindNav = dockBehindNav,
                dockFullBleed = dockFullBleed,
                dockHeight = dockHeightPx.takeIf { it > 0 } ?: dp(72),
                dockIconInset = binding.dockBar.paddingTop + dp(DOCK_SLOT_PADDING_V_DP + DOCK_ITEM_PADDING_V_DP),
                dockBare = DockBgStyle.fromName(prefs.dockBgStyle) == DockBgStyle.NONE,
                slot = slot,
                arrowWanted = arrowWanted,
                arrowHeight = arrow?.intrinsicHeight ?: 0,
                indicatorOn = IndicatorStyle.fromName(prefs.pageIndicatorStyle) != IndicatorStyle.NONE,
                pagePadding = pagePaddingVPx(prefs.desktopPaddingV, density, isLandscape)
            )
        )
        val dockParams = binding.dockBar.layoutParams as ViewGroup.MarginLayoutParams
        dockParams.bottomMargin = chrome.dockBottom
        val dockSideMargin = dockSideMarginPx(prefs, dockBehindNav || dockFullBleed)
        dockParams.leftMargin = if (dockBehindNav) 0 else dockSideMargin + systemInsets.left
        dockParams.rightMargin = if (dockBehindNav) 0 else dockSideMargin + systemInsets.right
        // Landscape: the slots share the bar's width by weight, so a bar that
        // spans a window twice as wide leaves the same icons stranded a
        // half-screen apart. Cap the bar near its portrait width and centre
        // it, so the dock reads as one row rather than a scattered line. The
        // edge-to-edge background styles are the exception - a slab that
        // stops short of the edges is not the thing they are asking for.
        val dockCap = landscapeChromeWidthPx()
        if (dockCap > 0 && !dockBehindNav && !dockFullBleed) {
            dockParams.width = dockCap
            (dockParams as? androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams)
                ?.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        } else {
            dockParams.width = ViewGroup.LayoutParams.MATCH_PARENT
            (dockParams as? androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams)
                ?.gravity = Gravity.BOTTOM
        }
        binding.dockBar.layoutParams = dockParams

        val barParams = binding.desktopSearchBar.layoutParams as ViewGroup.MarginLayoutParams
        barParams.bottomMargin = chrome.barBottom
        barParams.leftMargin = dp(16) + systemInsets.left
        barParams.rightMargin = dp(16) + systemInsets.right
        // Same landscape cap the dock takes: a search field running the full
        // width of a landscape window is a very long, very empty box.
        val barWidth = if (dockCap > 0) dockCap else ViewGroup.LayoutParams.MATCH_PARENT
        val barGravity =
            if (dockCap > 0) Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL else Gravity.BOTTOM
        barParams.width = barWidth
        (barParams as? androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams)
            ?.gravity = barGravity
        binding.desktopSearchBar.layoutParams = barParams
        binding.desktopSearchWidget.layoutParams =
            (binding.desktopSearchWidget.layoutParams as ViewGroup.MarginLayoutParams).apply {
                bottomMargin = barParams.bottomMargin
                leftMargin = barParams.leftMargin
                rightMargin = barParams.rightMargin
                width = barWidth
                (this as? androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams)
                    ?.gravity = barGravity
            }

        if (arrow != null) {
            binding.dockSwipeIndicator.setImageDrawable(arrow)
            binding.dockSwipeIndicator.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = chrome.arrowBottom
            }
        }
        val pagerParams = binding.workspacePager.layoutParams as ViewGroup.MarginLayoutParams
        pagerParams.bottomMargin = chrome.pagerBottom
        pagerParams.topMargin = systemInsets.top
        pagerParams.leftMargin = systemInsets.left
        pagerParams.rightMargin = systemInsets.right
        binding.workspacePager.layoutParams = pagerParams
        val indicatorParams = binding.pageIndicator.layoutParams as ViewGroup.MarginLayoutParams
        indicatorParams.bottomMargin = chrome.indicatorBottom
        indicatorParams.leftMargin = systemInsets.left
        indicatorParams.rightMargin = systemInsets.right
        binding.pageIndicator.layoutParams = indicatorParams

        // The Home surface: a color over the wallpaper, under everything on the
        // home screen. Transparent until one is picked, so the wallpaper shows.
        binding.root.setBackgroundColor(
            com.supernova.launcher.ui.theme.Styles.resolve(this, prefs)
                .of(com.supernova.launcher.ui.theme.SurfaceId.HOME).surface
        )
        val resolvedDock = com.supernova.launcher.ui.theme.Styles.resolve(this, prefs)
            .of(com.supernova.launcher.ui.theme.SurfaceId.DOCK)
        binding.dockBar.background = when (DockBgStyle.fromName(prefs.dockBgStyle)) {
            DockBgStyle.NONE -> null
            DockBgStyle.PILL ->
                dockBackgroundDrawable(resolvedDock, dp(34).toFloat(), dockBehindNav || dockFullBleed)
            DockBgStyle.ROUNDED ->
                dockBackgroundDrawable(resolvedDock, dp(14).toFloat(), dockBehindNav || dockFullBleed)
            DockBgStyle.SQUARE -> dockBackgroundDrawable(resolvedDock, 0f, dockBehindNav || dockFullBleed)
        }
    }

    private fun dockBackgroundDrawable(
        resolved: com.supernova.launcher.ui.theme.ResolvedSurface,
        defaultRadius: Float,
        squareBottom: Boolean = false
    ): android.graphics.drawable.Drawable {
        val density = resources.displayMetrics.density
        // Behind-nav squares the bottom corners of the default shape; radii
        // the user set through the style picker are respected as-is.
        val radii = resolved.cornerRadiiDp?.let { dpArr -> FloatArray(4) { dpArr[it] * density } }
            ?: if (squareBottom) floatArrayOf(defaultRadius, defaultRadius, 0f, 0f) else null
        return com.supernova.launcher.ui.theme.SurfaceShapes.background(
            resolved.surface,
            radii,
            resolved.cornerCuts,
            defaultRadius
        )
    }

    private fun openDrawer(forSearch: Boolean = false) {
        animateWallpaperZoom(1f)
        drawer.open(forSearch)
    }

    // Ramp the wallpaper zoom toward target (0 = normal, 1 = zoomed out).
    // Zooming in requires the pref; restoring to 0 is always allowed so a
    // mid-open pref flip can't strand a zoomed wallpaper.
    override fun animateWallpaperZoom(target: Float) {
        if (wallpaperZoomBroken) return
        val method = com.supernova.launcher.ui.WallpaperZoom.method ?: return
        val prefs = viewModel.state.value.prefs
        if (target > 0f && (!prefs.wallpaperZoom || !Animations.enabled(prefs))) return
        val token = binding.root.windowToken ?: return
        if (target == wallpaperZoomTarget) return
        wallpaperZoomTarget = target
        wallpaperZoomAnimator?.cancel()
        val manager = WallpaperManager.getInstance(this)
        if (!Animations.enabled(prefs)) {
            // Restore-to-0 still applies (a mid-open pref flip can't strand a
            // zoomed wallpaper), just without the ramp.
            wallpaperZoomValue = target
            try {
                method.invoke(manager, token, target)
            } catch (_: Exception) {
                wallpaperZoomBroken = true
            }
            return
        }
        wallpaperZoomAnimator = android.animation.ValueAnimator.ofFloat(wallpaperZoomValue, target).apply {
            duration = 250
            addUpdateListener { animator ->
                wallpaperZoomValue = animator.animatedValue as Float
                try {
                    method.invoke(manager, token, wallpaperZoomValue)
                } catch (_: Exception) {
                    // Some OEM wallpaper services throw; stop retrying.
                    wallpaperZoomBroken = true
                    cancel()
                }
            }
            start()
        }
    }

    @SuppressLint("WrongConstant", "PrivateApi")
    private fun expandNotificationShade() {
        val statusBarService = getSystemService("statusbar") ?: return
        val statusBarManagerClass = runCatching {
            Class.forName("android.app.StatusBarManager")
        }.getOrElse { error ->
            Log.w("Supernova", "Unable to resolve StatusBarManager", error)
            return
        }

        val candidateMethods = listOf("expandNotificationsPanel", "expand")
        var lastError: Throwable? = null

        for (methodName in candidateMethods) {
            val method = runCatching {
                statusBarManagerClass.getMethod(methodName)
            }.getOrNull() ?: runCatching {
                statusBarManagerClass.getDeclaredMethod(methodName).apply { isAccessible = true }
            }.getOrNull()

            if (method != null) {
                val result = runCatching { method.invoke(statusBarService) }
                if (result.isSuccess) {
                    return
                }
                lastError = result.exceptionOrNull()
            }
        }

        Log.w("Supernova", "Unable to expand notification shade from launcher", lastError)
    }

    private fun hideDrawer(instant: Boolean = false) {
        drawer.hide(instant)
    }

    // Everything the home screen can have standing over its default page:
    // menus, popups, edit mode, the page previews, a widget resize frame, the
    // feed, the drawer, search and move mode - then the page itself. Two
    // callers: the home press, and the screen turning off.
    //
    // Returns whether any of it was actually open, which the home press needs
    // to tell a press with a job to do from a second press with none.
    // keepDrawer spares the drawer alone (the drawerAutoClose pref), and
    // animate = false makes the whole reset instant, for a screen nobody is
    // looking at.
    private fun resetToHome(animate: Boolean, keepDrawer: Boolean): Boolean {
        var closed = false
        homeMenuDialog?.let {
            it.dismiss()
            closed = true
        }
        homeMenuDialog = null
        drag.activeItemPopup?.let {
            it.dismiss()
            closed = true
        }
        drag.activeItemPopup = null
        activeFolderPopup?.let {
            it.dismiss()
            closed = true
        }
        activeFolderPopup = null
        if (::editMode.isInitialized && editMode.isOpen) {
            editMode.exit()
            closed = true
        }
        if (desktopPreviews.isShowing) {
            desktopPreviews.dismiss()
            closed = true
        }
        if (widgets.dismissResizeFrame()) closed = true
        reopenDockIfAutoClosed()
        if (::feed.isInitialized && feed.isOpen) {
            feed.close()
            closed = true
        }
        if (::drawer.isInitialized && drawer.isExpanded) {
            closed = true
            if (!keepDrawer) hideDrawer(instant = !animate)
        }
        if (::search.isInitialized && search.isOpen) {
            search.hide()
            closed = true
        }
        if (moveModeItemId != null) {
            moveModeItemId = null
            closed = true
        }
        binding.workspacePager.setCurrentItem(defaultPagePosition(), animate)
        return closed
    }

    // Where the model's default page sits in the pager (centred when looping).
    private fun defaultPagePosition(): Int {
        val model = viewModel.state.value.model
        return workspaceAdapter.centerPosition(model.defaultPage.coerceIn(0, model.pageCount - 1))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home press re-delivers MAIN/HOME to the singleTask instance: reset to
        // the default page with drawer and menus closed, like every launcher does.
        // cameFromBackground separates the two presses that land here: one made
        // on the home screen itself, and one made in another app to get back to
        // it. Only the first is a home press "on" the launcher.
        val cameFromBackground = startedSinceResume
        // drawerAutoClose off keeps a drawer that stayed open behind a
        // launched app open on return home; a home press while the launcher
        // itself is frontmost still resets it like any launcher.
        val keepDrawerOpen = !viewModel.state.value.prefs.drawerAutoClose && cameFromBackground
        // Read before the reset moves the pager off it.
        val wasOnDefaultPage = binding.workspacePager.currentItem == defaultPagePosition()
        // Whether this press had a job of its own. Anything dismissed by the
        // reset is what the press was for, so the home-button action stays out
        // of it.
        val closed = resetToHome(animate = true, keepDrawer = keepDrawerOpen)
        // The double-home action: pressing home when there was nothing to
        // close and nowhere to scroll runs the configured action instead of
        // no-opping. homeButtonAction supersedes the legacy homeSearch flag
        // ("" derives from it), and dispatch reuses the gesture action path.
        // Three things have to hold for the press to count as that second
        // press: the launcher was already what the user was looking at (a
        // press made in another app is how you GET home, not a press on it),
        // it was already showing the main page, and nothing above needed
        // closing - a drag in flight included.
        val prefs = viewModel.state.value.prefs
        val reachedHome = wasOnDefaultPage || !prefs.homeButtonOnMainPage
        if (!cameFromBackground && reachedHome && !closed && !drag.isActive) {
            val action = HomeButtonAction.effective(prefs.homeButtonAction, prefs.homeSearch)
            if (action != GestureAction.NONE) runAction(GestureBinding(action))
        }
    }

    private fun launchApp(app: LaunchableApp, anchor: View? = null) {
        launchWithAnimation(anchor) { bounds, options ->
            runCatching {
                viewModel.appCatalog().launch(app, bounds, options)
            }.onSuccess {
                viewModel.noteLaunch(app)
                maybeAutoCloseDock()
                maybeAutoCloseDrawer()
            }.onFailure {
                Toast.makeText(this, getString(R.string.unable_to_launch, app.label), Toast.LENGTH_SHORT).show()
            }.isSuccess
        }
    }

    // drawerAutoClose: the drawer slides away behind the opening app or
    // shortcut; off keeps it open, so returning to the launcher (back/recents)
    // lands back in the drawer - and the screen turning off while that app is
    // in front leaves it alone too. A launch with no drawer open (home, dock,
    // gestures, the feed) leaves this nothing to do.
    private fun maybeAutoCloseDrawer() {
        if (!::drawer.isInitialized || !drawer.isExpanded) return
        if (viewModel.state.value.prefs.drawerAutoClose) {
            hideDrawer()
        } else {
            drawerKeptBehindLaunch = true
        }
    }

    // Overlay-only auto-close: the dock hides after a launch and reappears
    // when home is re-entered (onNewIntent/onResume).
    private fun maybeAutoCloseDock() {
        val prefs = viewModel.state.value.prefs
        if (!prefs.dockEnabled || !prefs.dockOverlay || !prefs.dockAutoClose) return
        dockAutoClosed = true
        binding.dockBar.visibility = View.GONE
        syncSwipeArrow(prefs)
    }

    private fun reopenDockIfAutoClosed() {
        if (!dockAutoClosed) return
        dockAutoClosed = false
        if (viewModel.state.value.prefs.dockEnabled && !dockToggledOff) {
            binding.dockBar.visibility = View.VISIBLE
        }
        syncSwipeArrow(viewModel.state.value.prefs)
    }

    // TOGGLE_DOCK action: transient visibility flip, mirroring the overlay
    // auto-close behavior (reserved margins stay, so no workspace relayout).
    private fun toggleDock() {
        if (!viewModel.state.value.prefs.dockEnabled) return
        dockToggledOff = !dockToggledOff
        binding.dockBar.visibility =
            if (dockToggledOff || dockAutoClosed) View.GONE else View.VISIBLE
        syncSwipeArrow(viewModel.state.value.prefs)
    }

    // The swipe indicator belongs to a swipe up that opens the drawer, and
    // points at the dock: it is only wanted with both.
    private fun swipeArrowWanted(prefs: LauncherPrefs): Boolean =
        prefs.drawerSwipeIndicator && prefs.dockEnabled &&
            GestureDefaults.swipeUpOpensDrawer(prefs.gestureBindings)

    // It hides and comes back with the dock (auto-close, the dock toggle),
    // keeping its strip as the dock keeps its margins.
    private fun syncSwipeArrow(prefs: LauncherPrefs) {
        binding.dockSwipeIndicator.visibility =
            if (swipeArrowWanted(prefs) && binding.dockBar.visibility == View.VISIBLE) View.VISIBLE else View.GONE
    }

    // The app-open transition. Single choke point: every app, shortcut and
    // intent launch routes through here, so the launchAnimation pref and the
    // disable-animations switch apply everywhere. [launch] starts the app
    // with the icon's screen bounds and the transition's options, and says
    // whether it did. Circle and Adaptive icon play their launcher-side part
    // first (LaunchPrelude) and launch as it ends.
    private fun launchWithAnimation(item: View?, launch: (Rect?, android.os.Bundle?) -> Boolean) {
        // A tap while another launch's prelude plays.
        if (launchPrelude.isBusy) return
        val prefs = viewModel.state.value.prefs
        // With animations off: no transition at all.
        val none = if (Animations.enabled(prefs)) null else
            android.app.ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle()
        // From the icon, as Launcher3's opens, rather than the whole item: a
        // desktop icon spans its cell, label and all.
        val anchor = item?.findViewById<View>(R.id.icon) ?: item?.findViewById<View>(R.id.appIcon) ?: item
        if (anchor == null || anchor.width == 0 || anchor.height == 0) {
            launch(null, none)
            return
        }
        val bounds = anchor.screenRect()
        if (none != null) {
            launch(bounds, none)
            return
        }
        val style = LaunchAnimation.fromName(prefs.launchAnimation)
        val snapshot = if (style == LaunchAnimation.CIRCLE || style == LaunchAnimation.ADAPTIVE_ICON) {
            runCatching { anchor.drawToBitmap() }.getOrNull()
        } else {
            null
        }
        if (snapshot == null) {
            launch(bounds, runCatching { launchOptions(style, anchor) }.getOrNull())
            return
        }
        val root = IntArray(2).also { binding.root.getLocationOnScreen(it) }
        val rect = android.graphics.RectF(bounds).apply { offset(-root[0].toFloat(), -root[1].toFloat()) }
        if (style == LaunchAnimation.CIRCLE) {
            val color = viewModel.appCatalog().dominantColorOf(snapshot) ?: android.graphics.Color.WHITE
            launchPrelude.circle(rect.centerX(), rect.centerY(), color, Animations.duration(prefs, CIRCLE_FILL_MS)) {
                if (!launch(bounds, launchOptions(style, anchor))) launchPrelude.clear()
            }
        } else {
            // The press ripple would stay behind as a gray tile where the
            // lifted icon was.
            item?.let {
                it.isPressed = false
                it.jumpDrawablesToCurrentState()
            }
            // The tapped item's height stands in for Launcher3's cell height.
            val cellHeight = (item ?: anchor).height.toFloat()
            launchPrelude.liftIcon(anchor, rect, snapshot, cellHeight, Animations.factor(prefs)) { from ->
                val scaleUp = android.app.ActivityOptions.makeScaleUpAnimation(
                    binding.root, from.left, from.top, from.width(), from.height()
                ).toBundle()
                if (!launch(bounds, scaleUp)) launchPrelude.clear()
            }
        }
    }

    // Each style's options. Circle's is the fade the
    // app opens with once its circle has filled the screen; Adaptive icon
    // falls back to the plain scale-up when its icon can't be pictured.
    private fun launchOptions(style: LaunchAnimation, anchor: View): android.os.Bundle = when (style) {
        LaunchAnimation.SYSTEM, LaunchAnimation.REVEAL -> android.app.ActivityOptions
            .makeClipRevealAnimation(anchor, 0, 0, anchor.width, anchor.height)
        LaunchAnimation.ADAPTIVE_ICON, LaunchAnimation.ZOOM -> android.app.ActivityOptions
            .makeScaleUpAnimation(anchor, 0, 0, anchor.width, anchor.height)
        LaunchAnimation.CIRCLE -> android.app.ActivityOptions
            .makeCustomAnimation(this, R.anim.launch_fade_enter, R.anim.launch_fade_exit)
        LaunchAnimation.SLIDE_UP -> android.app.ActivityOptions
            .makeCustomAnimation(this, R.anim.launch_slide_up_enter, R.anim.launch_slide_up_exit)
        LaunchAnimation.BLINK -> android.app.ActivityOptions
            .makeCustomAnimation(this, R.anim.launch_blink_enter, R.anim.launch_blink_exit)
        LaunchAnimation.SLIDE_LEFT -> android.app.ActivityOptions
            .makeCustomAnimation(this, R.anim.launch_slide_left_enter, R.anim.launch_slide_left_exit)
    }.toBundle()

    private fun launchItem(item: LauncherItem, anchor: View? = null) {
        when (item.itemType) {
            ItemType.FOLDER -> showFolderPopup(anchor ?: binding.root, item)
            ItemType.WIDGET -> Unit
            ItemType.APP -> item.toLaunchableApp()?.let { launchApp(it, anchor) }
            ItemType.SHORTCUT -> {
                val uri = item.intentUri
                when {
                    uri != null -> {
                        val action = com.supernova.launcher.gesture.ActionShortcut.decode(uri)
                        if (action != null) runAction(action) else launchIntentUri(uri, anchor)
                    }
                    item.packageName != null && item.shortcutId != null ->
                        launchShortcut(item.packageName, item.shortcutId, item.userSerial, anchor)
                    else -> item.toLaunchableApp()?.let { launchApp(it, anchor) }
                }
            }
        }
    }

    // Long-press on a recent search. Pinning keeps the query as a home icon
    // that re-runs it - "make shortcuts from my recent searches".
    // The search-bar long press: swap the bar for a search widget, or jump
    // to the bar's own settings.
    private fun showSearchBarMenu(anchor: View) {
        val menu = newItemPopup()
        drag.activeItemPopup = menu
        menu.addRow(getString(R.string.search_bar_replace), R.drawable.ic_menu_resize) {
            widgets.showSearchWidgetPicker()
        }
        if (viewModel.state.value.prefs.searchBarWidgetId != 0) {
            menu.addRow(getString(R.string.search_bar_restore), R.drawable.ic_menu_remove) {
                widgets.restoreBuiltInSearchBar()
            }
        }
        menu.addRow(getString(R.string.search_bar_settings), R.drawable.ic_tab_settings) {
            startActivity(
                Intent(this, SettingsActivity::class.java)
                    .putExtra(SettingsActivity.EXTRA_OPEN_SEARCH_BAR, true)
            )
        }
        menu.show(anchor, menuTouchX)
        menu.makeModal()
    }

    private fun launchShortcut(
        packageName: String,
        shortcutId: String,
        userSerial: Long,
        anchor: View? = null
    ) {
        val launcherApps = getSystemService(LauncherApps::class.java) ?: return
        val user = viewModel.appCatalog().userForSerial(userSerial) ?: Process.myUserHandle()
        launchWithAnimation(anchor) { bounds, options ->
            runCatching {
                launcherApps.startShortcut(packageName, shortcutId, bounds, options, user)
            }.onSuccess {
                maybeAutoCloseDock()
                maybeAutoCloseDrawer()
            }.onFailure {
                Toast.makeText(this, R.string.shortcut_failed, Toast.LENGTH_SHORT).show()
            }.isSuccess
        }
    }

    // Fires an arbitrary intent stored on an item (an activity chosen from the
    // picker, or a legacy created shortcut). Reuses the app-launch reveal.
    private fun launchIntentUri(uri: String, anchor: View?) {
        val intent = runCatching { Intent.parseUri(uri, Intent.URI_INTENT_SCHEME) }.getOrNull()
        if (intent == null) {
            Toast.makeText(this, R.string.launch_failed, Toast.LENGTH_SHORT).show()
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        launchWithAnimation(anchor) { bounds, options ->
            intent.sourceBounds = bounds
            runCatching { startActivity(intent, options) }
                .onSuccess { maybeAutoCloseDock() }
                .onFailure { Toast.makeText(this, R.string.launch_failed, Toast.LENGTH_SHORT).show() }
                .isSuccess
        }
    }

    // Icon for a SHORTCUT item carrying an intentUri: a launcher-action tile by
    // action, or the snapshot icon activities/legacy shortcuts stored. Null for
    // a plain app shortcut, which falls back to the app's own icon.
    private fun intentItemIcon(item: LauncherItem): android.graphics.drawable.Drawable? {
        val catalog = viewModel.appCatalog()
        item.intentUri?.let { uri ->
            com.supernova.launcher.gesture.ActionShortcut.decode(uri)?.let { binding ->
                customFolderIcon(item.iconBase64, item.customIconPack, item.customIconName, item.iconRaw)
                    ?.let { return it }
                getDrawable(com.supernova.launcher.ui.ActionIcons.of(binding.action))?.let {
                    return catalog.renderActionTile(it)
                }
            }
        }
        val stored = item.iconBase64?.takeIf { it.isNotEmpty() }
            ?.let { com.supernova.launcher.data.GalleryIconCodec.decode(it) } ?: return null
        // An app shortcut's picture (a Fossify contact's photo) is drawn
        // across the shape the same way every time; through glass it came
        // out whole, small or half cleared, depending on the photo.
        val picture = if (item.shortcutId != null) catalog.renderShortcutPicture(stored) else null
        return picture ?: catalog.renderThemedDrawable(android.graphics.drawable.BitmapDrawable(resources, stored))
    }

    // The resolved POPUP surface styles every SupernovaPopupMenu.
    private fun popupSurface(): com.supernova.launcher.ui.theme.ResolvedSurface =
        com.supernova.launcher.ui.theme.Styles.resolve(this, viewModel.state.value.prefs)
            .of(com.supernova.launcher.ui.theme.SurfaceId.POPUP)

    // forItem: an app's, folder's or widget's own menu, whose actions can be
    // a row of icons (popupActionIcons); the smaller menus keep their labels.
    private fun newItemPopup(forItem: Boolean = false): SupernovaPopupMenu {
        val menu = SupernovaPopupMenu(
            this, popupSurface(), Animations.enabled(viewModel.state.value.prefs),
            cardBackground = viewModel.state.value.prefs.popupCardBg,
            style = com.supernova.launcher.ui.PopupMenuStyle.fromName(viewModel.state.value.prefs.popupStyle),
            groupShortcuts = viewModel.state.value.prefs.popupGroupShortcuts
        )
        menu.actionIcons = forItem && viewModel.state.value.prefs.popupActionIcons
        // Same dismiss contract PopupMenu had; the armed-drag interplay
        // (activeItemPopup dismissal in routeItemGesture) relies on it.
        menu.setOnDismissListener { if (drag.activeItemPopup === menu) drag.activeItemPopup = null }
        return menu
    }

    // Up to two notification lines on top of the popup, gated by the pref and
    // by notification access (previews are empty without the listener).
    private fun addNotificationRows(menu: SupernovaPopupMenu, packageName: String?, userSerial: Long) {
        packageName ?: return
        if (!viewModel.state.value.prefs.popupNotifications) return
        val user = viewModel.appCatalog().userForSerial(userSerial) ?: Process.myUserHandle()
        SupernovaNotificationListener.previewsFor(packageName, user.hashCode()).forEach { preview ->
            menu.addNotification(preview.title, preview.text) {
                // The app's own tap intent; a stale one just no-ops.
                runCatching { preview.contentIntent?.send() }
            }
        }
    }

    // The configurable extra rows (popupItemsHome/popupItemsDrawer). HIDE is
    // added by the drawer menu itself so it keeps its historical position.
    private fun addExtraRows(menu: SupernovaPopupMenu, extras: Set<PopupExtraRow>, app: LaunchableApp) {
        // Opens the app's market:// page, which any installed store can
        // take, so the row names no store.
        if (PopupExtraRow.PLAY_STORE in extras) {
            menu.addRow(getString(R.string.popup_row_store), R.drawable.ic_play_store) {
                openPlayStore(app.packageName)
            }
        }
        if (PopupExtraRow.CLOSE_APP in extras) {
            menu.addRow(getString(R.string.popup_row_close_app), R.drawable.ic_menu_close_app) {
                closeApp(app.packageName)
            }
        }
        if (PopupExtraRow.RELAUNCH in extras) {
            menu.addRow(getString(R.string.popup_row_relaunch), R.drawable.ic_menu_relaunch) {
                relaunchApp(app)
            }
        }
        if (PopupExtraRow.SAVE_APK in extras) {
            menu.addRow(getString(R.string.popup_row_save_apk), R.drawable.ic_menu_save) {
                saveApk(app.packageName)
            }
        }
    }

    private fun shortcutIcon(shortcut: ShortcutInfo): android.graphics.drawable.Drawable? =
        runCatching {
            getSystemService(LauncherApps::class.java)
                ?.getShortcutBadgedIconDrawable(shortcut, resources.displayMetrics.densityDpi)
        }.getOrNull()

    // What a new item's menu closes as it sends the item home, and what its
    // drag leaves for the home screen: the drawer, the search window, and the
    // feed that search was opened over - the item lands on a home page.
    override fun closeItemSources() {
        // A folder card is a window of its own: a shortcut dragged out of a
        // member's menu left it standing over the home screen. Not the card
        // a drag is carried by, which closes with the drop.
        activeFolderPopup?.takeUnless { drag.ownsGestureIn(it) }?.dismiss()
        hideDrawer()
        if (::search.isInitialized && search.isOpen) search.hide()
        if (::feed.isInitialized && feed.isOpen) feed.close()
    }

    // The installed app a package and user resolve to (a shortcut's owner).
    override fun installedApp(packageName: String, userSerial: Long): LaunchableApp? =
        viewModel.state.value.apps.firstOrNull { it.packageName == packageName && it.userSerial == userSerial }

    // A drawer shortcut's home item, worked out as it is placed: filed under
    // the activity the shortcut belongs to (else its app), with the
    // shortcut's own icon kept inline as a pin request's is
    // (handleCreateShortcutResult) - without it the item drew its app's icon
    // - and pinned to this launcher (pinShortcut).
    private class ShortcutItem(val owner: LaunchableApp, val iconBase64: String?)

    private fun shortcutItem(shortcut: DragPayload.DrawerShortcut): ShortcutItem {
        val info = shortcutInfo(shortcut.packageName, shortcut.shortcutId, shortcut.userSerial)
        val className = info?.activity?.className
            ?: installedApp(shortcut.packageName, shortcut.userSerial)?.className.orEmpty()
        val icon = info?.let(::shortcutOwnIcon) ?: shortcut.icon
        pinShortcut(shortcut.packageName, shortcut.shortcutId, shortcut.userSerial)
        return ShortcutItem(
            LaunchableApp(shortcut.packageName, className, shortcut.label, shortcut.userSerial),
            icon?.let { com.supernova.launcher.data.GalleryIconCodec.encode(it) }
        )
    }

    private fun shortcutInfo(packageName: String, shortcutId: String, userSerial: Long): ShortcutInfo? {
        val launcherApps = getSystemService(LauncherApps::class.java) ?: return null
        val user = viewModel.appCatalog().userForSerial(userSerial) ?: Process.myUserHandle()
        val query = LauncherApps.ShortcutQuery()
            .setPackage(packageName)
            .setShortcutIds(listOf(shortcutId))
            .setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
            )
        return runCatching { launcherApps.getShortcuts(query, user)?.firstOrNull() }.getOrNull()
    }

    // A shortcut on the home screen is pinned to this launcher, as a pin
    // request's is and as Launcher3 pins its own: an app can drop a dynamic
    // shortcut at any time, and an unpinned home item then no longer starts.
    // pinShortcuts sets the package's whole pinned list, so it is the list
    // already pinned plus this one. Never unpinned here: an undone removal
    // would find a pin-only shortcut gone.
    private fun pinShortcut(packageName: String, shortcutId: String, userSerial: Long) {
        val launcherApps = getSystemService(LauncherApps::class.java) ?: return
        val user = viewModel.appCatalog().userForSerial(userSerial) ?: Process.myUserHandle()
        runCatching {
            val query = LauncherApps.ShortcutQuery()
                .setPackage(packageName)
                .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
            val pinned = launcherApps.getShortcuts(query, user).orEmpty().map { it.id }
            if (shortcutId !in pinned) launcherApps.pinShortcuts(packageName, pinned + shortcutId, user)
        }
    }

    private fun shortcutOwnIcon(shortcut: ShortcutInfo): android.graphics.drawable.Drawable? =
        runCatching {
            getSystemService(LauncherApps::class.java)
                ?.getShortcutIconDrawable(shortcut, resources.displayMetrics.densityDpi)
        }.getOrNull()

    // source: the drawer folder's window the app was long-pressed in, if any.
    // It closes with the drawer when the app goes home, before Edit (as a
    // focusable window it would keep the sheet's field from the keyboard),
    // after Hide, which takes the app out of it, and after a shortcut the
    // menu opens on a locked desktop if the Close folder when launching
    // setting is on, as after the card's own launches.
    private fun showDrawerItemMenu(
        anchor: View,
        app: LaunchableApp,
        source: android.widget.PopupWindow? = null
    ): SupernovaPopupMenu {
        val menu = newItemPopup(forItem = true)
        addNotificationRows(menu, app.packageName, app.userSerial)
        val extras = PopupRows.decode(
            viewModel.state.value.prefs.popupItemsDrawer, PopupRows.drawerDefaults
        )

        // Drawer menu: Add to home, App info, Uninstall, Edit (+
        // Hide). Placement into the dock, opening, and widgets are all
        // drag/tap gestures, so they leave the menu. Extra rows stay opt-in.
        // Nothing of a private space app goes home (longPressNewApp).
        val homeClosed = desktopLocked || app.privateSpace
        if (!homeClosed) {
            menu.addRow(getString(R.string.add_to_home), R.drawable.ic_menu_add) {
                val page = viewModel.state.value.currentPage
                val free = viewModel.firstFreeWorkspaceCell(page)
                if (free == null) {
                    Toast.makeText(this, R.string.no_room_on_page, Toast.LENGTH_SHORT).show()
                } else {
                    viewModel.addAppToWorkspace(app, page, free.first, free.second)
                    source?.dismiss()
                    closeItemSources()
                }
            }
        }
        menu.addRow(getString(R.string.app_info), R.drawable.ic_menu_info) {
            viewModel.appCatalog().openAppInfo(app.packageName, app.className, app.userSerial)
        }
        if (PopupExtraRow.UNINSTALL in extras) {
            menu.addRow(getString(R.string.uninstall), R.drawable.ic_menu_uninstall) {
                startActivity(viewModel.appCatalog().uninstallIntent(app.packageName, app.userSerial))
            }
        }
        menu.addRow(getString(R.string.edit), R.drawable.ic_menu_edit) {
            source?.dismiss()
            AppEditSheet.show(this, lifecycleScope, viewModel, app, ::pickPhoto)
        }
        // A hidden app only gets here through search, whatever the row setting.
        if (viewModel.isAppHidden(app)) {
            menu.addRow(getString(R.string.unhide_app), R.drawable.ic_menu_hide) {
                viewModel.setAppHidden(app, false)
                source?.dismiss()
            }
        } else if (PopupExtraRow.HIDE in extras) {
            menu.addRow(getString(R.string.hide_app), R.drawable.ic_menu_hide) {
                viewModel.setAppHidden(app, true)
                source?.dismiss()
            }
        }
        addExtraRows(menu, extras, app)

        // Drawer semantics: tapping a shortcut pins it to the workspace. A
        // locked desktop takes nothing new, so there the row opens the
        // shortcut instead, as a home icon's menu does, and the card or
        // search window it came from closes as after
        // any launch from them.
        shortcutsFor(app.packageName, app.userSerial).take(4).forEach { shortcut ->
            menu.addShortcut(
                shortcut.shortLabel ?: shortcut.longLabel ?: shortcut.id,
                shortcutIcon(shortcut),
                onLongPress = shortcutDrag(menu, app.packageName, shortcut, app.userSerial).takeUnless { homeClosed }
            ) {
                if (homeClosed) {
                    launchShortcut(app.packageName, shortcut.id, app.userSerial, anchor)
                    if (viewModel.state.value.prefs.folderCloseOnLaunch) source?.dismiss()
                    if (::search.isInitialized && search.isOpen) search.hide()
                    return@addShortcut
                }
                val page = viewModel.state.value.currentPage
                val free = viewModel.firstFreeWorkspaceCell(page)
                if (free == null) {
                    Toast.makeText(this, R.string.no_room_on_page, Toast.LENGTH_SHORT).show()
                } else {
                    viewModel.addShortcutToWorkspace(
                        app = app,
                        shortcutId = shortcut.id,
                        label = shortcut.shortLabel?.toString() ?: app.label,
                        page = page,
                        cellX = free.first,
                        cellY = free.second,
                        iconBase64 = shortcutOwnIcon(shortcut)?.let { com.supernova.launcher.data.GalleryIconCodec.encode(it) }
                    )
                    pinShortcut(app.packageName, shortcut.id, app.userSerial)
                    source?.dismiss()
                    closeItemSources()
                }
            }
        }
        menu.show(anchor, menuTouchX)
        return menu
    }

    // An app shortcut long-pressed in search:
    // Add to Home and its app's App info. A drag pins it where it lands.
    private fun showShortcutItemMenu(anchor: View, shortcut: DragPayload.DrawerShortcut): SupernovaPopupMenu {
        val menu = newItemPopup()
        if (!desktopLocked && !viewModel.isPrivateSerial(shortcut.userSerial)) {
            menu.addRow(getString(R.string.add_to_home), R.drawable.ic_menu_add) {
                val page = viewModel.state.value.currentPage
                val free = viewModel.firstFreeWorkspaceCell(page)
                if (free == null) {
                    Toast.makeText(this, R.string.no_room_on_page, Toast.LENGTH_SHORT).show()
                } else {
                    val item = shortcutItem(shortcut)
                    viewModel.addShortcutToWorkspace(
                        item.owner, shortcut.shortcutId, shortcut.label, page, free.first, free.second,
                        iconBase64 = item.iconBase64
                    )
                    closeItemSources()
                }
            }
        }
        installedApp(shortcut.packageName, shortcut.userSerial)?.let { app ->
            menu.addRow(getString(R.string.app_info), R.drawable.ic_menu_info) {
                viewModel.appCatalog().openAppInfo(app.packageName, app.className, app.userSerial)
            }
        }
        menu.show(anchor, menuTouchX)
        return menu
    }

    // A drawer folder long-pressed: Add to Home puts a copy of it on the
    // home screen (a drag does too, where it lands); Edit picks its apps and
    // Rename names it, as its open card's edit button and title do.
    private fun showDrawerFolderMenu(anchor: View, group: DrawerGroup, apps: List<LaunchableApp>): SupernovaPopupMenu {
        val menu = newItemPopup()
        if (!desktopLocked && apps.isNotEmpty()) {
            menu.addRow(getString(R.string.add_to_home), R.drawable.ic_menu_add) {
                val page = viewModel.state.value.currentPage
                val free = viewModel.firstFreeWorkspaceCell(page)
                if (free == null) {
                    Toast.makeText(this, R.string.no_room_on_page, Toast.LENGTH_SHORT).show()
                } else {
                    viewModel.addFolderToWorkspace(group.name, apps, page, free.first, free.second)
                    closeItemSources()
                }
            }
        }
        // A drawer folder lists its apps in the drawer's order, so it has no
        // order of its own to Sort.
        menu.addRow(getString(R.string.select_apps), R.drawable.ic_menu_select_apps) { showDrawerGroupChecklist(group) }
        menu.addRow(getString(R.string.edit), R.drawable.ic_menu_edit) {
            com.supernova.launcher.ui.FolderEditSheet
                .showDrawerFolder(this, lifecycleScope, viewModel, group, ::pickPhoto)
        }
        menu.show(anchor, menuTouchX)
        return menu
    }

    // A shortcut held in an item's menu drags out of it onto the home screen
    // or the dock, as one from search does. The menu's window carries the rest
    // of that gesture, so it hands its touches to the drag engine while the
    // drag is the engine's; the menu stays the active one until the drag
    // starts, and then hides and closes with the drop. Private space
    // shortcuts stay in the menu: on the home screen they would show while
    // the space is locked. The interceptor only hands a drag to the engine;
    // a tap still reaches the row as a click.
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun shortcutDrag(
        menu: SupernovaPopupMenu,
        packageName: String,
        shortcut: ShortcutInfo,
        userSerial: Long
    ): ((View) -> Unit)? {
        if (viewModel.isPrivateSerial(userSerial)) return null
        val window = menu.window
        window.setTouchInterceptor { _, ev ->
            drag.ownsGestureIn(window) && drag.routeGesture(ev, window)
        }
        return { row ->
            val label = (shortcut.shortLabel ?: shortcut.longLabel ?: shortcut.id).toString()
            val payload = DragPayload.DrawerShortcut(
                packageName, shortcut.id, userSerial, label, shortcutIcon(shortcut)
            )
            drag.arm(row, payload, window = window, menu = null)
            drag.activeItemPopup = menu
        }
    }

    // The long-presses of every new-item surface - the drawer's grid, recent
    // row and folders, search's results, a drawer folder's apps - end here:
    // the item's menu while the finger is held, and a drag to the home
    // screen once it moves. The drawer's keyboard goes first: otherwise the
    // menu opens over it and only slides down a moment later, when the
    // finger lifts and the menu takes focus - the menu appearing to jump for
    // no reason the user can see. The drawer is adjustNothing, so nothing
    // under the menu moves when the keyboard goes.
    private fun onlyPageMoved(last: LauncherUiState, state: LauncherUiState): Boolean =
        last.model === state.model && last.prefs === state.prefs && last.apps === state.apps &&
            last.iconGeneration == state.iconGeneration && last.privateSpace == state.privateSpace

    private fun longPressNewApp(view: View, app: LaunchableApp) {
        drawer.hideKeyboardIfShowing()
        // A private space app stays in the drawer: on the home screen it
        // would show while the space is locked.
        drag.arm(view, DragPayload.DrawerApp(app), draggable = !app.privateSpace) { showDrawerItemMenu(view, app) }
    }

    private fun longPressNewShortcut(view: View, shortcut: DragPayload.DrawerShortcut) {
        drawer.hideKeyboardIfShowing()
        drag.arm(view, shortcut, draggable = !viewModel.isPrivateSerial(shortcut.userSerial)) {
            showShortcutItemMenu(view, shortcut)
        }
    }

    private fun longPressDrawerFolder(view: View, group: DrawerGroup, apps: List<LaunchableApp>) {
        drawer.hideKeyboardIfShowing()
        // Search finds a folder whose apps are all hidden too; lifted, it
        // closed the drawer only to be refused where it was dropped.
        drag.arm(view, DragPayload.DrawerFolder(group.name, apps), draggable = apps.isNotEmpty()) {
            showDrawerFolderMenu(view, group, apps)
        }
    }

    private fun showWorkspaceItemMenu(anchor: View, item: LauncherItem): SupernovaPopupMenu {
        val locked = desktopLocked
        val menu = newItemPopup(forItem = true)
        if (item.itemType == ItemType.APP || item.itemType == ItemType.SHORTCUT) {
            addNotificationRows(menu, item.packageName, item.userSerial)
        }

        when (item.itemType) {
            ItemType.FOLDER -> {
                // Remove, Select apps, Sort, Edit. The icon is changed in
                // Edit, as an app's is.
                if (!locked) {
                    menu.addRow(getString(R.string.remove), R.drawable.ic_menu_remove_item) {
                        removeItemWithUndo(item)
                    }
                    menu.addRow(getString(R.string.select_apps), R.drawable.ic_menu_select_apps) {
                        showFolderChecklist(item)
                    }
                    // An A-Z folder shows its apps sorted whatever their order.
                    if (!viewModel.state.value.prefs.folderSortAz) {
                        menu.addRow(getString(R.string.sort), R.drawable.ic_menu_sort) {
                            viewModel.sortFolder(item.id)
                        }
                    }
                }
                // Folder Edit: name, icon, Swipe to open folder and the swipe
                // actions in one sheet, as an app's Edit holds its label, icon
                // and swipes - and like an app's, it keeps the name and icon
                // on a locked desktop.
                menu.addRow(getString(R.string.edit), R.drawable.ic_menu_edit) {
                    FolderEditSheet.show(
                        this, lifecycleScope, viewModel, item, ::pickPhoto,
                        onEditSwipe = { down, onPicked -> showSwipeActionPicker(item, down) { onPicked(it) } }
                    )
                }
                addExportPngRow(menu, anchor, item.label)
            }
            ItemType.WIDGET -> {
                if (!locked) {
                    val bounds = widgets.spanBounds(item)
                    if (bounds.canResizeH || bounds.canResizeV) {
                        menu.addRow(getString(R.string.widget_resize), R.drawable.ic_menu_resize) {
                            widgets.showResizeFrame(item, anchor)
                        }
                    }
                    menu.addRow(getString(R.string.padding), R.drawable.ic_menu_padding) {
                        widgets.showPaddingDialog(item, anchor)
                    }
                    // The quickmenu pair, for a widget that overlaps other
                    // items: which of them draws on top and takes the touch.
                    // Each row is offered only when it would change something.
                    val stacking = viewModel.stacking(item)
                    if (stacking.above) {
                        menu.addRow(getString(R.string.bring_to_front), R.drawable.ic_menu_to_front) {
                            viewModel.restackItem(item.id, toFront = true)
                        }
                    }
                    if (stacking.below) {
                        menu.addRow(getString(R.string.send_to_back), R.drawable.ic_menu_to_back) {
                            viewModel.restackItem(item.id, toFront = false)
                        }
                    }
                    menu.addRow(getString(R.string.remove), R.drawable.ic_menu_remove_item) {
                        removeItemWithUndo(item)
                    }
                }
                // The widget menu has the provider's App info, locked or not,
                // as an app's menu does.
                item.widgetProvider?.let(ComponentName::unflattenFromString)?.let { provider ->
                    menu.addRow(getString(R.string.app_info), R.drawable.ic_menu_info) {
                        // The provider names the package and, through the
                        // user, the profile its details open in.
                        viewModel.appCatalog().openAppInfo(provider.packageName, provider.className, item.userSerial)
                    }
                }
                val widgetLabel = item.appWidgetId?.let(widgets::label)
                addExportPngRow(menu, anchor, widgetLabel ?: item.label)
            }
            ItemType.APP,
            ItemType.SHORTCUT -> {
                // Four rows: Remove, Widgets, App info, Edit. Everything
                // configurable (swipe gestures, label, icon) lives in Edit; the
                // per-context "extra" rows are opt-in through settings.
                if (!locked) {
                    menu.addRow(getString(R.string.remove), R.drawable.ic_menu_remove_item) {
                        removeItemWithUndo(item)
                    }
                }
                if (item.itemType == ItemType.APP && !locked) {
                    menu.addRow(getString(R.string.widgets_row), R.drawable.ic_menu_widgets) {
                        widgets.showPickerFor(item.packageName)
                    }
                }
                // A launcher action or a picked activity has no package, and
                // the row did nothing for it.
                item.packageName?.let { pkg ->
                    menu.addRow(getString(R.string.app_info), R.drawable.ic_menu_info) {
                        viewModel.appCatalog().openAppInfo(pkg, item.className, item.userSerial)
                    }
                }
                menu.addRow(getString(R.string.edit), R.drawable.ic_menu_edit) {
                    showItemEditSheet(item, allowSwipe = !locked)
                }
                item.toLaunchableApp()?.let { app ->
                    addExtraRows(
                        menu,
                        PopupRows.decode(
                            viewModel.state.value.prefs.popupItemsHome, PopupRows.homeDefaults
                        ),
                        app
                    )
                }
                addExportPngRow(menu, anchor, item.label)
                // Workspace semantics: tapping a shortcut launches it.
                item.packageName?.let { pkg ->
                    shortcutsFor(pkg, item.userSerial).take(4).forEach { shortcut ->
                        menu.addShortcut(
                            shortcut.shortLabel ?: shortcut.longLabel ?: shortcut.id,
                            shortcutIcon(shortcut),
                            onLongPress = shortcutDrag(menu, pkg, shortcut, item.userSerial).takeUnless { locked }
                        ) { launchShortcut(pkg, shortcut.id, item.userSerial) }
                    }
                }
            }
        }

        if (!menu.hasRows) {
            Toast.makeText(this, R.string.desktop_locked_toast, Toast.LENGTH_SHORT).show()
            return menu
        }

        menu.show(anchor, menuTouchX)
        return menu
    }

    // Edit for a placed workspace item: the app-level fields (label, icon)
    // plus per-item swipe gestures, so the popup menu stays minimal.
    private fun showItemEditSheet(item: LauncherItem, allowSwipe: Boolean) {
        // A launcher action has a name and an icon of its own, as a folder has.
        if (com.supernova.launcher.gesture.ActionShortcut.decode(item.intentUri) != null) {
            com.supernova.launcher.ui.FolderEditSheet.show(
                this, lifecycleScope, viewModel, item, ::pickPhoto,
                onEditSwipe = { down, onPicked -> showSwipeActionPicker(item, down) { onPicked(it) } }
            )
        } else {
            showAppEditSheet(item, allowSwipe)
        }
    }

    private fun showAppEditSheet(item: LauncherItem, allowSwipe: Boolean) {
        val app = item.toLaunchableApp()
        // Anything but an app icon edits its own label, not its app's.
        val itemLabel = if (item.itemType == ItemType.APP) {
            null
        } else {
            AppEditSheet.ItemLabel(item.label) { viewModel.renameShortcut(item.id, it) }
        }
        if (app == null && itemLabel == null) return
        if (!allowSwipe) {
            AppEditSheet.show(this, lifecycleScope, viewModel, app, ::pickPhoto, itemLabel = itemLabel)
            return
        }
        AppEditSheet.show(
            this, lifecycleScope, viewModel, app, ::pickPhoto, itemLabel = itemLabel,
            // Read live so a re-open (or a just-committed pick) shows the
            // current binding, not the snapshot captured when the menu opened.
            swipeLabels = { down ->
                val live = viewModel.state.value.model.items.firstOrNull { it.id == item.id }
                if (down) live?.swipeLabelDown else live?.swipeLabel
            },
            onEditSwipe = { down, onPicked -> showSwipeActionPicker(item, down) { onPicked(it) } }
        )
    }

    // Widgets are exempt from undo: their host id is deleted on removal and
    // cannot be restored, so undoing would resurrect a dead placeholder.
    override fun removeItemWithUndo(item: LauncherItem) {
        if (item.itemType == ItemType.WIDGET) {
            // Its resize frame goes now rather than at the rebind: after a
            // drop on Remove the widget stays hidden, and a write landing
            // after the drag's end would bring the frame back around nothing.
            widgets.onWidgetItemRemoved(item)
            viewModel.removeItem(item.id)
            removeEmptyPages(emptied = item.desktopPage())
            return
        }
        // Capture before removal: the exact item plus folder members, so undo
        // restores the same ids into the same cells (or the nearest free).
        val members = if (item.itemType == ItemType.FOLDER) {
            viewModel.folderMembers(item.id)
        } else {
            emptyList()
        }
        viewModel.removeItem(item.id)
        if (!viewModel.state.value.prefs.undoRemove) {
            removeEmptyPages(emptied = item.desktopPage())
            return
        }
        val snackbar = Snackbar
            .make(binding.root, R.string.item_removed, UNDO_DURATION_MS)
            .setAction(R.string.undo) { viewModel.restoreItems(listOf(item) + members) }
        if (binding.dockBar.visibility == View.VISIBLE) {
            snackbar.anchorView = binding.dockBar
        }
        snackbar.addCallback(object : Snackbar.Callback() {
            override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                if (undoSnackbar !== transientBottomBar) return
                undoSnackbar = null
                if (event != DISMISS_EVENT_ACTION) removeEmptyPages(emptied = item.desktopPage())
            }
        })
        undoSnackbar = snackbar
        snackbar.show()
    }

    // Posted: a drag can end inside the pickup of the next one (its settle cut
    // short), and that drag is under way by the time this runs.
    override fun onDragFinished(sourcePage: Int?) {
        binding.root.post { removeEmptyPages(emptied = sourcePage) }
    }

    private fun LauncherItem.desktopPage(): Int? = page.takeIf { container == ContainerType.WORKSPACE }

    // Remove empty pages: every page left with nothing on it goes, but the
    // home page and the one in view - so a page being filled (edit mode's
    // "+", a widget's setup screen) is never taken from under it. Runs when
    // a drag ends, when the pager comes to rest and when an Undo times out;
    // not while anything is still moving items or pages about.
    //
    // emptied: a page the user just emptied (its last item removed or
    // dragged away). That one goes even while in view - it is not a page
    // being filled - and the pager steps back onto the page before it.
    private fun removeEmptyPages(emptied: Int? = null) {
        if (!emptyPagesRemovable()) return
        val inView = workspaceAdapter.realPage(binding.workspacePager.currentItem)
        val count = workspaceAdapter.realPageCount
        val keep = if (emptied == inView) emptySet() else setOf(inView)
        viewModel.removeEmptyPages(keep) { removed ->
            val remaining = count - removed.size
            val shifted = inView - removed.count { it < inView }
            val page = if (inView in removed) shifted - 1 else shifted
            followRemovedPages(remaining, page.coerceIn(0, remaining - 1))
        }
    }

    private fun emptyPagesRemovable(): Boolean =
        viewModel.state.value.prefs.removeEmptyPages &&
            !drag.isArmed && !drag.isActive && drag.settlingItemId == null &&
            !(::editMode.isInitialized && editMode.isOpen) &&
            !desktopPreviews.isShowing && pendingEditAddPage == null && undoSnackbar == null &&
            binding.workspacePager.scrollState == androidx.viewpager2.widget.ViewPager2.SCROLL_STATE_IDLE &&
            workspaceAdapter.realPageCount > 1 && !workspaceAdapter.isAddPage(binding.workspacePager.currentItem)

    // Pages before the one in view went, so it is further left now. The
    // adapter may have the new count already (then the pager moves at once)
    // or not yet (then the emission bringing it does; see observeState).
    private fun followRemovedPages(pageCount: Int, page: Int) {
        if (workspaceAdapter.realPageCount == pageCount) {
            showPageAfterRemoval(page)
        } else {
            pendingPageAfterRemoval = PageAfterRemoval(pageCount, page)
        }
    }

    // No scroll event tells the indicator and the wallpaper when the pager
    // jumps to a page showing what it already showed.
    private fun showPageAfterRemoval(page: Int) {
        binding.workspacePager.setCurrentItem(workspaceAdapter.centerPosition(page), false)
        viewModel.setCurrentPage(page)
        binding.pageIndicator.onPageScrolled(page, 0f)
        updateWallpaperOffset(page.toFloat())
        if (::editMode.isInitialized) editMode.onPageChanged()
    }

    private fun openPlayStore(packageName: String) {
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$packageName"))
            )
        }.onFailure {
            openUriBlind("https://play.google.com/store/apps/details?id=$packageName")
        }
    }

    private fun closeApp(packageName: String, toast: Boolean = true) {
        runCatching {
            getSystemService(android.app.ActivityManager::class.java)
                ?.killBackgroundProcesses(packageName)
        }
        if (toast) Toast.makeText(this, R.string.app_closed, Toast.LENGTH_SHORT).show()
    }

    private fun relaunchApp(app: LaunchableApp) {
        closeApp(app.packageName, toast = false)
        launchApp(app)
    }

    private fun saveApk(packageName: String) {
        val info = runCatching { packageManager.getApplicationInfo(packageName, 0) }.getOrNull()
        if (info == null) {
            Toast.makeText(this, R.string.apk_save_failed, Toast.LENGTH_SHORT).show()
            return
        }
        pendingApk = PendingApk(info.sourceDir, !info.splitSourceDirs.isNullOrEmpty())
        runCatching { saveApkLauncher.launch("$packageName.apk") }
            .onFailure { pendingApk = null }
    }

    // onPicked reports the committed label (null = cleared) so the Edit sheet
    // can refresh its row without reading the async model write back.
    // The action picker for an icon swipe: the launcher's actions (Jump to
    // page included), any app or one of its shortcuts, or an app's shortcut
    // screen. Targets are stored as LaunchTarget strings, action uris
    // (ActionShortcut) or intent URIs (CreatedShortcuts).
    private fun showSwipeActionPicker(item: LauncherItem, down: Boolean, onPicked: (String?) -> Unit = {}) {
        val actions = com.supernova.launcher.gesture.ActionShortcut.choices + GestureAction.JUMP_TO_PAGE
        com.supernova.launcher.ui.ActionPickerSheet.show(
            this,
            viewModel,
            getString(if (down) R.string.set_swipe_down_action else R.string.set_swipe_up_action),
            actions
        ) { pick ->
            fun save(target: String?, label: String?) {
                viewModel.setSwipeAction(item.id, target, label, down)
                onPicked(label)
            }
            when (pick) {
                com.supernova.launcher.ui.ActionPick.None -> save(null, null)
                is com.supernova.launcher.ui.ActionPick.Action ->
                    save(com.supernova.launcher.gesture.ActionShortcut.encode(pick.action), pick.label)
                is com.supernova.launcher.ui.ActionPick.Page -> {
                    val page = pick.index.toString()
                    val target = com.supernova.launcher.gesture.ActionShortcut.encode(GestureAction.JUMP_TO_PAGE, page)
                    save(target, pick.label)
                }
                // With the app's profile: a swipe can open a work app from a
                // personal icon, and used to open its personal copy.
                is com.supernova.launcher.ui.ActionPick.App -> {
                    val component = ComponentName(pick.app.packageName, pick.app.className).flattenToString()
                    save("$component@${pick.app.userSerial}", pick.app.label)
                }
                is com.supernova.launcher.ui.ActionPick.Shortcut ->
                    save("${pick.app.packageName}|${pick.shortcutId}@${pick.app.userSerial}", pick.label)
                is com.supernova.launcher.ui.ActionPick.Legacy -> {
                    pendingSwipeShortcut = PendingSwipeShortcut(item.id, down, onPicked)
                    val create = Intent(Intent.ACTION_CREATE_SHORTCUT).setComponent(pick.component)
                    runCatching { createSwipeShortcutLauncher.launch(create) }.onFailure {
                        pendingSwipeShortcut = null
                        Toast.makeText(this, R.string.launch_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // An app in a home folder: its home icon's rows, then the folder's own.
    private fun folderMemberMenu(
        folder: LauncherItem,
        member: LauncherItem,
        sortAz: Boolean,
        closeFolder: () -> Unit,
        applied: (com.supernova.launcher.data.LauncherModel) -> Unit
    ): SupernovaPopupMenu {
        val locked = desktopLocked
        val menu = newItemPopup()
        if (!locked) {
            menu.addRow(getString(R.string.remove), R.drawable.ic_menu_remove_item) {
                val before = viewModel.state.value.model
                // The folder's last app takes the folder with it.
                val last = viewModel.folderMembers(folder.id).size <= 1
                removeItemWithUndo(if (last) folder else member)
                applied(before)
            }
        }
        member.packageName?.let { pkg ->
            if (member.itemType == ItemType.APP && !locked) {
                menu.addRow(getString(R.string.widgets_row), R.drawable.ic_menu_widgets) {
                    closeFolder()
                    widgets.showPickerFor(pkg)
                }
            }
            menu.addRow(getString(R.string.app_info), R.drawable.ic_menu_info) {
                viewModel.appCatalog().openAppInfo(pkg, member.className, member.userSerial)
            }
        }
        menu.addRow(getString(R.string.edit), R.drawable.ic_menu_edit) {
            // The folder closes first: as a focusable window it
            // would keep the keyboard from the sheet's name field.
            closeFolder()
            showItemEditSheet(member, allowSwipe = false)
        }
        if (!locked) {
            menu.addRow(getString(R.string.remove_from_folder), R.drawable.ic_menu_add) {
                val page = pageForFolderMembers(folder)
                val free = viewModel.firstFreeWorkspaceCell(page)
                if (free == null) {
                    Toast.makeText(this, R.string.no_room_on_page, Toast.LENGTH_SHORT).show()
                } else {
                    val before = viewModel.state.value.model
                    // The move a drag onto that free cell makes, so a
                    // folder left with one app gives way to it here
                    // too.
                    viewModel.moveItemToWorkspace(member.id, page, free.first, free.second, mergeFolders = false)
                    applied(before)
                }
            }
            // The folder's own order, which an A-Z folder doesn't show.
            if (!sortAz) {
                menu.addRow(getString(R.string.move_up), R.drawable.ic_menu_swipe) {
                    val before = viewModel.state.value.model
                    viewModel.reorderFolderMember(folder.id, member.id, -1)
                    applied(before)
                }
                menu.addRow(getString(R.string.move_down), R.drawable.ic_menu_down) {
                    val before = viewModel.state.value.model
                    viewModel.reorderFolderMember(folder.id, member.id, 1)
                    applied(before)
                }
            }
        }
        return menu
    }

    private fun showFolderPopup(anchor: View, folder: LauncherItem) {
        var folderAdapter: FolderMemberAdapter? = null
        var popup: android.widget.PopupWindow? = null
        // A member's long-press menu, closed with the folder: Back, Home,
        // the screen going off and rotation all close the folder, and the
        // menu used to float on over the home screen, its rows still aimed
        // at the closed folder.
        var memberMenu: SupernovaPopupMenu? = null

        // The folder shows its members again once a change is saved - waited
        // for, not guessed at with a delay - and in the order it shows them:
        // re-listed in stored order, an A-Z folder jumped out of order after
        // every action.
        fun applied(before: com.supernova.launcher.data.LauncherModel) {
            lifecycleScope.launch {
                withTimeoutOrNull(FOLDER_SAVE_WAIT_MS) { viewModel.state.first { it.model !== before } }
                val remaining = viewModel.folderMembers(folder.id)
                when {
                    remaining.isEmpty() -> popup?.dismiss()
                    viewModel.state.value.prefs.folderSortAz ->
                        folderAdapter?.submit(com.supernova.launcher.data.LabelOrder.sorted(remaining) { it.label })
                    else -> folderAdapter?.submit(remaining)
                }
            }
        }

        val folderPrefs = viewModel.state.value.prefs
        val adapter = FolderMemberAdapter(
            appCatalog = viewModel.appCatalog(),
            showLabels = folderPrefs.folderLabels,
            labelPrefs = folderPrefs,
            iconScale = if (folderPrefs.folderMatchDesktop) folderPrefs.iconScale else folderPrefs.folderIconScale,
            onClick = {
                if (viewModel.state.value.prefs.folderCloseOnLaunch) popup?.dismiss()
                launchItem(it)
            },
            // An app in a home folder is a home icon like any other: its
            // menu while the finger is held, and a drag out onto
            // the home screen or the dock once it moves. The card is its own
            // window, so the gesture's touches come in through its
            // interceptor (below), not dispatchTouchEvent.
            onLongPress = { memberView, member ->
                val sortAz = viewModel.state.value.prefs.folderSortAz
                memberMenu?.dismiss()
                drag.arm(memberView, DragPayload.ExistingItem(member.id), window = popup) {
                    folderMemberMenu(folder, member, sortAz, { popup?.dismiss() }, ::applied).also {
                        it.show(memberView, menuTouchX)
                        memberMenu = it
                    }
                }
            }
        )
        adapter.decorateIcon = { icon, member ->
            withBadge(icon, member.packageName, member.className, member.userSerial)
        }
        adapter.shortcutIcon = { member -> member.takeIf { it.itemType == ItemType.SHORTCUT }?.let(::intentItemIcon) }
        folderAdapter = adapter

        animateWallpaperZoom(1f)
        val prefs = viewModel.state.value.prefs
        popup = folderPopup.show(
            anchor = anchor,
            title = folder.label,
            members = viewModel.folderMembers(folder.id),
            adapter = adapter,
            columnsOverride = prefs.folderColumns,
            sortAz = prefs.folderSortAz,
            mode = com.supernova.launcher.ui.FolderMode.fromName(prefs.folderMode),
            scroll = com.supernova.launcher.ui.FolderScroll.fromName(prefs.folderScroll),
            surfaceColor = com.supernova.launcher.ui.theme.Styles.resolve(this, prefs)
                .of(com.supernova.launcher.ui.theme.SurfaceId.FOLDER).surface,
            cornerRadiiDp = com.supernova.launcher.ui.theme.Styles.resolve(this, prefs)
                .of(com.supernova.launcher.ui.theme.SurfaceId.FOLDER).cornerRadiiDp,
            cornerCuts = com.supernova.launcher.ui.theme.Styles.resolve(this, prefs)
                .of(com.supernova.launcher.ui.theme.SurfaceId.FOLDER).cornerCuts,
            showTitle = prefs.folderShowName,
            showCount = prefs.folderShowCount,
            shadow = prefs.folderShadow,
            animate = Animations.enabled(prefs),
            speed = Animations.factor(prefs),
            onRename = { viewModel.renameFolder(folder.id, it) },
            // The title row's overflow: Select apps and Sort, as the folder's
            // long-press has them. Opened by a tap, so modal at once, and the
            // card's touch interceptor closes it on a tap outside it.
            onMenu = { button ->
                memberMenu?.dismiss()
                val menu = newItemPopup()
                menu.addRow(getString(R.string.select_apps), R.drawable.ic_menu_select_apps) {
                    // The folder closes first: as a focusable window it stays
                    // the IME target even under the dialog, so the checklist's
                    // rename field could never summon the keyboard while it
                    // was up.
                    popup?.dismiss()
                    showFolderChecklist(folder)
                }
                if (!viewModel.state.value.prefs.folderSortAz) {
                    menu.addRow(getString(R.string.sort), R.drawable.ic_menu_sort) {
                        val before = viewModel.state.value.model
                        viewModel.sortFolder(folder.id)
                        applied(before)
                    }
                }
                drag.activeItemPopup = menu
                menu.show(button)
                menu.makeModal()
                memberMenu = menu
            },
            onDismiss = {
                memberMenu?.dismiss()
                memberMenu = null
                // Closed under a held app or its drag (a home press, Back, a
                // rotation): the rest of that gesture would only ever have
                // come through this card, so it ends here.
                drag.onWindowDismissed(popup)
                activeFolderPopup = null
                activeFolderAdapter = null
                animateWallpaperZoom(0f)
            }
        )
        // Every touch on the card goes through the item gesture first: an
        // app held there, the drag it becomes, and the press that closes its
        // menu once modal, swallowed so it doesn't also launch the app it
        // lands on. A modal menu leaves outside taps to its host (see
        // makeModal), and the card's window is the host here: the activity
        // never sees taps in a folder.
        popup?.let { window -> window.setTouchInterceptor { _, ev -> drag.routeGesture(ev, window) } }
        activeFolderPopup = popup
        activeFolderAdapter = adapter
    }

    // The folder-popup Edit checklist: every visible app, checked = member.
    // Newly checked apps become fresh items inside the folder; unchecked
    // members move out to a free workspace cell (or are removed when no page
    // has room).
    private fun showFolderChecklist(folder: LauncherItem) {
        val apps = viewModel.visibleApps()
        if (apps.isEmpty()) return
        fun keyOf(pkg: String?, cls: String?, serial: Long) = "$serial/$pkg/$cls"
        val memberByKey = HashMap<String, LauncherItem>()
        // App icons only. A pinned shortcut carries its app's package and
        // class, and ticked the app as if it were in the folder - unticking
        // it then took the shortcut out.
        viewModel.folderMembers(folder.id).filter { it.itemType == ItemType.APP }.forEach { member ->
            memberByKey.putIfAbsent(keyOf(member.packageName, member.className, member.userSerial), member)
        }
        val checked = apps
            .map { keyOf(it.packageName, it.className, it.userSerial) in memberByKey }
            .toBooleanArray()

        val rowAdapter = object : android.widget.ArrayAdapter<LaunchableApp>(this, R.layout.row_folder_check, apps) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = convertView ?: layoutInflater.inflate(R.layout.row_folder_check, parent, false)
                val app = apps[position]
                row.findViewById<android.widget.ImageView>(R.id.folderCheckIcon)
                    .setImageDrawable(viewModel.appCatalog().iconFor(app))
                row.findViewById<android.widget.CheckedTextView>(R.id.folderCheckLabel).apply {
                    text = app.label
                    isChecked = checked[position]
                }
                return row
            }
        }

        // Static title with a pencil at the end; tapping it swaps in the
        // rename field (committed on Done) and summons the keyboard.
        val titleText = android.widget.TextView(this).apply {
            text = folder.label.ifBlank { getString(R.string.folder) }
            setTextAppearance(R.style.TextAppearance_Supernova_DialogTitle)
        }
        val titleInput = android.widget.EditText(this).apply {
            setText(folder.label)
            hint = getString(R.string.folder_name)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            isSingleLine = true
            textSize = 18f
            visibility = View.GONE
        }
        val editTitleButton = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_menu_edit)
            imageTintList = android.content.res.ColorStateList.valueOf(titleText.currentTextColor)
            val ta = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
            background = ta.getDrawable(0)
            ta.recycle()
            contentDescription = getString(R.string.rename)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val titleWrap = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(20), dp(12), 0)
            addView(titleText, android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(titleInput, android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(editTitleButton)
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setCustomTitle(titleWrap)
            // Null click listener: taps must toggle, not dismiss - the real
            // handler goes on the list view below.
            .setAdapter(rowAdapter, null)
            .setPositiveButton(R.string.done) { _, _ ->
                val newName = titleInput.text?.toString()?.trim().orEmpty()
                if (newName.isNotBlank() && newName != folder.label) {
                    viewModel.renameFolder(folder.id, newName)
                }
                lifecycleScope.launch {
                    apps.forEachIndexed { index, app ->
                        val member = memberByKey[keyOf(app.packageName, app.className, app.userSerial)]
                        when {
                            checked[index] && member == null ->
                                viewModel.addAppToFolder(app, folder.id)
                            !checked[index] && member != null -> {
                                removeFolderMemberToFreeCell(member, pageForFolderMembers(folder))
                                // Let the model settle so the next free-cell
                                // lookup doesn't reuse the same cell.
                                delay(120)
                            }
                        }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.listView.setOnItemClickListener { _, _, position, _ ->
            checked[position] = !checked[position]
            rowAdapter.notifyDataSetChanged()
        }
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        editTitleButton.setOnClickListener {
            titleText.visibility = View.GONE
            editTitleButton.visibility = View.GONE
            titleInput.visibility = View.VISIBLE
            titleInput.requestFocus()
            titleInput.setSelection(titleInput.text?.length ?: 0)
            // Posted: asking for the IME in the same frame as the focus
            // switch loses the race and nothing appears.
            titleInput.post {
                getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    ?.showSoftInput(titleInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }
        dialog.show()
        // MUST come after show(): AlertController scans only its CONTENT view
        // for text editors when it builds the hierarchy during show(), and -
        // our editor living in the custom title - stamps FLAG_ALT_FOCUSABLE_IM
        // on the window, which makes the IME ignore every request. Clearing
        // any earlier gets overwritten.
        dialog.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
    }

    // Where a member taken out of a folder goes: the folder's page. A docked
    // folder's page is left over from before it was docked and may be gone,
    // so its members land on the page in view instead.
    private fun pageForFolderMembers(folder: LauncherItem): Int {
        val lastPage = (viewModel.state.value.model.pageCount - 1).coerceAtLeast(0)
        val page = if (folder.container == ContainerType.DOCK) {
            workspaceAdapter.realPage(binding.workspacePager.currentItem)
        } else {
            folder.page
        }
        return page.coerceIn(0, lastPage)
    }

    private fun removeFolderMemberToFreeCell(member: LauncherItem, preferredPage: Int) {
        val pageCount = viewModel.state.value.model.pageCount
        val pages = listOf(preferredPage) + (0 until pageCount).filter { it != preferredPage }
        for (page in pages) {
            val free = viewModel.firstFreeWorkspaceCell(page) ?: continue
            viewModel.removeFolderMember(member.id, page, free.first, free.second)
            return
        }
        // No page has room: drop the copy entirely rather than losing it in
        // an occupied cell.
        viewModel.removeItem(member.id)
    }

    // Drawer folders (FOLDER-type groups): the same popup chrome fed from
    // LaunchableApps via transient items - nothing here touches the model.
    private fun showDrawerFolderPopup(anchor: View, group: DrawerGroup, members: List<LaunchableApp>) {
        val prefs = viewModel.state.value.prefs
        val items = members.map { app ->
            LauncherItem(
                id = "drawer-folder/${app.userSerial}/${app.packageName}/${app.className}",
                itemType = ItemType.APP,
                label = app.label,
                packageName = app.packageName,
                className = app.className,
                userSerial = app.userSerial
            )
        }
        var popup: android.widget.PopupWindow? = null
        // An app's menu open over the card, if any: it closes with the card.
        var memberMenu: SupernovaPopupMenu? = null
        // The group's name as of now: a rename typed into the title is saved
        // as the popup closes, which Edit does before opening the checklist.
        var groupName = group.name
        val adapter = FolderMemberAdapter(
            appCatalog = viewModel.appCatalog(),
            showLabels = prefs.folderLabels,
            labelPrefs = prefs,
            iconScale = if (prefs.folderMatchDesktop) prefs.iconScale else prefs.folderIconScale,
            onClick = { item ->
                if (viewModel.state.value.prefs.folderCloseOnLaunch) popup?.dismiss()
                item.toLaunchableApp()?.let { launchApp(it) }
            },
            // An app in a drawer folder is a drawer icon like any other: its
            // menu, and a drag to the home screen once the finger moves. The
            // card is its own window, so the gesture's touches come in
            // through its interceptor (below), not dispatchTouchEvent.
            onLongPress = { memberView, item ->
                item.toLaunchableApp()?.let { app ->
                    drag.arm(memberView, DragPayload.DrawerApp(app), window = popup) {
                        showDrawerItemMenu(memberView, app, source = popup).also { memberMenu = it }
                    }
                }
            }
        )
        adapter.decorateIcon = { icon, member ->
            withBadge(icon, member.packageName, member.className, member.userSerial)
        }
        popup = folderPopup.show(
            anchor = anchor,
            title = group.name,
            members = items,
            adapter = adapter,
            columnsOverride = prefs.folderColumns,
            sortAz = prefs.folderSortAz,
            mode = com.supernova.launcher.ui.FolderMode.fromName(prefs.folderMode),
            scroll = com.supernova.launcher.ui.FolderScroll.fromName(prefs.folderScroll),
            surfaceColor = com.supernova.launcher.ui.theme.Styles.resolve(this, prefs)
                .of(com.supernova.launcher.ui.theme.SurfaceId.FOLDER).surface,
            cornerRadiiDp = com.supernova.launcher.ui.theme.Styles.resolve(this, prefs)
                .of(com.supernova.launcher.ui.theme.SurfaceId.FOLDER).cornerRadiiDp,
            cornerCuts = com.supernova.launcher.ui.theme.Styles.resolve(this, prefs)
                .of(com.supernova.launcher.ui.theme.SurfaceId.FOLDER).cornerCuts,
            showTitle = prefs.folderShowName,
            showCount = prefs.folderShowCount,
            shadow = prefs.folderShadow,
            animate = Animations.enabled(prefs),
            speed = Animations.factor(prefs),
            onRename = { newName ->
                val renamed = viewModel.renameGroup(group, newName)
                if (renamed == null) {
                    Toast.makeText(this, R.string.group_name_taken, Toast.LENGTH_SHORT).show()
                } else {
                    groupName = renamed
                }
            },
            onMenu = {
                // The group editor rewrites the members pref; the drawer
                // rebuilds from the emission, so close the stale popup.
                popup?.dismiss()
                // Under the name it has now. By the old one, after a rename
                // in the title, each tick re-created the old group - two
                // folders, and the renamed one never got the edits.
                showDrawerGroupChecklist(group, groupName)
            },
            onDismiss = {
                // A menu a shortcut is being dragged out of carries the rest
                // of that gesture, and closes with its drop.
                memberMenu?.takeUnless { drag.ownsGestureIn(it.window) }?.dismiss()
                memberMenu = null
                // Closed under a held app or its drag (a home press, Back, a
                // rotation): the rest of that gesture would only ever have
                // come through this card, so it ends here.
                drag.onWindowDismissed(popup)
                activeFolderPopup = null
                activeFolderAdapter = null
                // The drawer sheet is usually still open underneath; only
                // release the wallpaper zoom when it isn't.
                if (!drawer.isExpanded) animateWallpaperZoom(0f)
            }
        )
        // Every touch on the card goes through the item gesture first: an
        // app held there, the drag it becomes, and the press that closes its
        // menu once modal (without also launching the app it lands on).
        popup?.let { window -> window.setTouchInterceptor { _, ev -> drag.routeGesture(ev, window) } }
        activeFolderPopup = popup
        activeFolderAdapter = adapter
    }

    private fun showDrawerGroupChecklist(group: DrawerGroup, name: String = group.name) {
        val apps = viewModel.visibleApps()
        if (apps.isEmpty()) return
        val checked = apps.map { viewModel.isInGroup(it, group) }.toBooleanArray()
        val target = group.copy(name = name)
        MaterialAlertDialogBuilder(this)
            .setTitle(name)
            .setMultiChoiceItems(apps.map { it.label }.toTypedArray(), checked) { _, which, isChecked ->
                // Applied to the stored group, so successive checks compose.
                viewModel.setGroupMembership(target, apps[which], isChecked)
            }
            .setPositiveButton(R.string.done, null)
            .show()
    }

    private fun withBadge(
        icon: android.graphics.drawable.Drawable?,
        packageName: String?,
        className: String?,
        userSerial: Long
    ): android.graphics.drawable.Drawable? {
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
        return BadgeLook(prefs, resources.displayMetrics.density).wrap(icon, count, iconColor, face)
    }

    // Summed unread count over a folder's members, honoring the badge
    // allowlist; 0 unless folder badges are on.
    private fun folderBadgeCount(folder: LauncherItem): Int {
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
    private fun badgeDrawable(icon: android.graphics.drawable.Drawable, count: Int): android.graphics.drawable.Drawable =
        BadgeLook(viewModel.state.value.prefs, resources.displayMetrics.density).wrap(icon, count)

    // A drawer folder's icon with its members' summed count, when folder
    // badges are on.
    private fun withFolderBadge(
        icon: android.graphics.drawable.Drawable,
        members: List<LaunchableApp>
    ): android.graphics.drawable.Drawable {
        val prefs = viewModel.state.value.prefs
        if (!prefs.badges || !prefs.folderBadges) return icon
        val count = members.sumOf { app ->
            if (prefs.badgeApps.isNotEmpty() && "${app.userSerial}/${app.packageName}/${app.className}" !in prefs.badgeApps) {
                return@sumOf 0
            }
            val user = viewModel.appCatalog().userForSerial(app.userSerial) ?: Process.myUserHandle()
            SupernovaNotificationListener.countFor(app.packageName, user.hashCode())
        }
        return if (count > 0) badgeDrawable(icon, count) else icon
    }

    // A custom icon is fitted to the icon shape like any other, unless its
    // Edit has Reshape off: then it is drawn as it is.
    private fun folderPreviewDrawable(folder: LauncherItem): android.graphics.drawable.Drawable {
        customFolderIcon(folder.iconBase64, folder.customIconPack, folder.customIconName, folder.iconRaw)
            ?.let { return it }
        val memberIcons = viewModel.folderMembers(folder.id)
            // The dense styles (3x3, dense ring) show up to nine members.
            .take(9)
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

    private fun drawerFolderIcon(group: DrawerGroup, members: List<LaunchableApp>): android.graphics.drawable.Drawable =
        customFolderIcon(group.iconBase64, group.iconPack, group.iconName, group.iconRaw)
            ?: composedFolderIcon(members.mapNotNull { app -> viewModel.appCatalog().iconFor(app) })

    private fun customFolderIcon(
        photo: String?,
        pack: String?,
        name: String?,
        raw: Boolean
    ): android.graphics.drawable.Drawable? {
        val catalog = viewModel.appCatalog()
        val picture = photo?.takeIf { it.isNotEmpty() }
            ?.let { com.supernova.launcher.data.GalleryIconCodec.decode(it) }
            ?.let { android.graphics.drawable.BitmapDrawable(resources, it) }
        return when {
            picture != null -> if (raw) picture else catalog.renderThemedDrawable(picture) ?: picture
            pack == null || name == null -> null
            raw -> catalog.packDrawable(pack, name)
            else -> catalog.renderPackDrawable(pack, name)
        }
    }

    // The one FolderIconDrawable factory: workspace/dock previews and drawer
    // folder entries all run through it so the folder-icon appearance prefs
    // (shape, outline, clip, window color) apply everywhere.
    private fun composedFolderIcon(icons: List<android.graphics.drawable.Drawable>): android.graphics.drawable.Drawable {
        val prefs = viewModel.state.value.prefs
        val resolvedFolder = com.supernova.launcher.ui.theme.Styles.resolve(this, prefs)
            .of(com.supernova.launcher.ui.theme.SurfaceId.FOLDER)
        return FolderIconDrawable(
            // The preview draws members at mini-cell bounds. The cache hands
            // out shared instances, and a Drawable has exactly one bounds -
            // mutating the shared one shrinks the same icon in the drawer.
            icons.take(9).map { icon -> icon.constantState?.newDrawable(resources)?.mutate() ?: icon },
            dp(48),
            style = FolderPreviewStyle.fromName(prefs.folderPreviewStyle),
            plateColor = if (prefs.folderMatchWindowColor) {
                resolvedFolder.surface
            } else {
                FolderIconDrawable.DEFAULT_PLATE_COLOR
            },
            shape = FolderIconDrawable.plateShape(prefs.folderIconShape, prefs),
            customSpec = com.supernova.launcher.data.CustomShapeSpec.decode(prefs.iconShapeCustom),
            outlineColor = if (prefs.folderIconOutline) resolvedFolder.accent else 0,
            outlineWidthPx = 2f * resources.displayMetrics.density,
            clipMembers = prefs.folderClipToBg,
            prioritizeFirst = prefs.folderPrioritizeFirst,
            glass = FolderIconDrawable.glassFor(prefs),
            shadow = prefs.iconShadow
        )
    }

    private fun shortcutsFor(packageName: String, userSerial: Long): List<ShortcutInfo> {
        val launcherApps = getSystemService(LauncherApps::class.java) ?: return emptyList()
        val user = viewModel.appCatalog().userForSerial(userSerial) ?: Process.myUserHandle()
        val query = LauncherApps.ShortcutQuery()
            .setPackage(packageName)
            .setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
            )

        return runCatching {
            launcherApps.getShortcuts(query, user) ?: emptyList()
        }.getOrElse { emptyList() }
    }

    private val desktopLocked: Boolean
        get() = viewModel.state.value.prefs.desktopLocked

    // ---- LiveDragController.Host: what the drag engine asks of the activity
    // beyond the functions above that it shares with the rest of the screen.

    override val drawerExpanded: Boolean
        get() = ::drawer.isInitialized && drawer.isExpanded

    override fun onGestureDown(ev: MotionEvent) {
        menuTouchX = ev.x
    }

    // Release the hierarchy under a drag that has just begun: the anchor
    // drops its press, the pager or list under it never reads the movement
    // as a scroll, and the gesture detector, which saw the same down, lets
    // go too.
    override fun cancelTouchesBelow(cancel: MotionEvent) {
        super.dispatchTouchEvent(cancel)
        gestureDetector.onTouchEvent(cancel)
    }

    override fun exitEditMode() {
        if (::editMode.isInitialized && editMode.isOpen) editMode.exit()
    }

    override fun dockIconTopInsetPx(): Int = dp(DOCK_SLOT_PADDING_V_DP + DOCK_ITEM_PADDING_V_DP)

    override fun desktopIconBasePx(): Int = dp(ICON_BASE_DP)

    override fun isSettling(itemId: String): Boolean = drag.isSettling(itemId)

    // False when the drop can't be taken: the picture then flies back.
    override fun canMergeIntoDock(payload: DragPayload, rank: Int): Boolean =
        dockMergeItem(payload, preview = true)?.let {
            com.supernova.launcher.data.DockFolders.target(viewModel.state.value.model, it, rank)
        } != null

    // The item a dock merge drops: the dragged one, or a new one for an app
    // or shortcut from the drawer. preview: only asking, so a shortcut isn't
    // pinned yet.
    private fun dockMergeItem(payload: DragPayload, preview: Boolean): LauncherItem? = when (payload) {
        is DragPayload.ExistingItem -> viewModel.state.value.model.items.firstOrNull { it.id == payload.itemId }
        is DragPayload.DrawerApp -> LauncherItem(
            id = java.util.UUID.randomUUID().toString(),
            itemType = ItemType.APP,
            container = ContainerType.DOCK,
            label = payload.app.label,
            packageName = payload.app.packageName,
            className = payload.app.className,
            userSerial = payload.app.userSerial
        )
        is DragPayload.DrawerShortcut -> {
            val shortcut = if (preview) null else shortcutItem(payload)
            LauncherItem(
                id = java.util.UUID.randomUUID().toString(),
                itemType = ItemType.SHORTCUT,
                container = ContainerType.DOCK,
                label = payload.label,
                packageName = shortcut?.owner?.packageName ?: payload.packageName,
                className = shortcut?.owner?.className,
                shortcutId = payload.shortcutId,
                userSerial = shortcut?.owner?.userSerial ?: payload.userSerial,
                iconBase64 = shortcut?.iconBase64
            )
        }
        else -> null
    }

    override fun handleDropToDock(payload: DragPayload, rank: Int, merge: Boolean): Boolean {
        if (desktopLocked) return false
        if (merge && canMergeIntoDock(payload, rank)) {
            dockMergeItem(payload, preview = false)?.let { viewModel.mergeIntoDock(it, rank) }
            return true
        }
        when (payload) {
            is DragPayload.ExistingItem -> {
                // A folder's app on a docked folder, its own included, can't
                // trade places with it - a folder goes into no folder - and
                // the move leaves both where they are.
                val model = viewModel.state.value.model
                if (LauncherRepository.movedToDock(model, payload.itemId, rank) === model) return false
                viewModel.moveItemToDock(payload.itemId, rank)
            }
            is DragPayload.DrawerApp, is DragPayload.DrawerShortcut, is DragPayload.DrawerFolder -> {
                // A drawer folder with none of its apps showing has nothing
                // to put there.
                if (payload is DragPayload.DrawerFolder && payload.apps.isEmpty()) return false
                val page = workspaceAdapter.realPage(binding.workspacePager.currentItem)
                // A new item onto an occupied slot sends that icon to the
                // home screen, which needs a free cell somewhere.
                val model = viewModel.state.value.model
                val occupied = model.items.any { it.container == ContainerType.DOCK && it.rank == rank }
                if (occupied && (0 until model.pageCount).none { viewModel.firstFreeWorkspaceCell(it) != null }) {
                    Toast.makeText(this, R.string.no_room_on_page, Toast.LENGTH_SHORT).show()
                    return false
                }
                when (payload) {
                    is DragPayload.DrawerApp -> viewModel.addAppToDock(payload.app, rank, page)
                    is DragPayload.DrawerShortcut -> {
                        val item = shortcutItem(payload)
                        viewModel.addShortcutToDock(item.owner, payload.shortcutId, payload.label, item.iconBase64, rank, page)
                    }
                    is DragPayload.DrawerFolder -> viewModel.addFolderToDock(payload.name, payload.apps, rank, page)
                    else -> Unit
                }
            }
            // Widgets don't dock.
            is DragPayload.NewWidget -> return false
        }
        return true
    }

    override fun state(): LauncherUiState = viewModel.state.value

    override fun viewForItem(item: LauncherItem): View {
        if (item.itemType == ItemType.WIDGET) {
            return widgets.buildWidgetView(item)
        }

        val iconBinding = ItemIconBinding.inflate(LayoutInflater.from(this))
        val app = item.toLaunchableApp()

        val icon = when (item.itemType) {
            ItemType.FOLDER -> folderPreviewDrawable(item)
            ItemType.WIDGET -> getDrawable(R.drawable.ic_widget)
            ItemType.APP -> app?.let { viewModel.appCatalog().iconFor(it) } ?: getDrawable(R.drawable.ic_supernova)
            ItemType.SHORTCUT -> intentItemIcon(item)
                ?: app?.let { viewModel.appCatalog().iconFor(it) } ?: getDrawable(R.drawable.ic_supernova)
        }

        val prefs = viewModel.state.value.prefs
        val override = viewModel.overrideFor(item)
        val inDock = item.container == ContainerType.DOCK
        val labelVisible = (if (inDock) prefs.dockLabels else prefs.showLabels) &&
            !(override?.hideLabel ?: false)
        val badged = if (item.itemType == ItemType.FOLDER) {
            val count = folderBadgeCount(item)
            if (count > 0 && icon != null) badgeDrawable(icon, count) else icon
        } else {
            withBadge(icon, item.packageName, item.className, item.userSerial)
        }
        iconBinding.icon.setImageDrawable(badged)
        if (inDock) {
            // The dock splits a fixed width dockCount ways, so scaleX/scaleY
            // here drew outside the slot and was clipped by it. Sizing the box
            // instead means the column measures what it draws, and the dock
            // grows to fit rather than shaving the icon's top and bottom off.
            val box = dockIconBoxPx(prefs)
            iconBinding.icon.updateLayoutParams { width = box; height = box }
            // Icons align across the row while labels grow downward, so a
            // wrapped label doesn't push its own icon out of line.
            iconBinding.root.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            iconBinding.root.setPadding(
                dp(DOCK_ITEM_PADDING_H_DP), dp(DOCK_ITEM_PADDING_V_DP),
                dp(DOCK_ITEM_PADDING_H_DP), dp(DOCK_ITEM_PADDING_V_DP)
            )
        } else {
            // Sized, not scaled, for the dock's reason: scaleX/scaleY drew a
            // big icon out past its box, where the item's padding cut it
            // square and the label, drawn after it, covered its bottom. The
            // item now measures what it draws, so the label sits below the
            // icon at any size. The label fills the item, which the page's
            // canvas widens to its cell (WorkspaceCanvas), so it runs the
            // cell's width, whatever the icon's size.
            val box = desktopIconBoxPx(prefs)
            iconBinding.icon.updateLayoutParams { width = box; height = box }
        }
        val displayLabel = override?.customLabel ?: item.label
        iconBinding.label.text = displayLabel
        LabelStyler.apply(
            iconBinding.label, prefs, onWallpaper = true,
            context = if (inDock) com.supernova.launcher.ui.LabelContext.DOCK
            else com.supernova.launcher.ui.LabelContext.DESKTOP
        )
        iconBinding.label.visibility = if (labelVisible) View.VISIBLE else View.GONE
        if (!inDock) iconBinding.root.setTag(R.id.tag_icon_fit, desktopIconFit(iconBinding, prefs))
        // Announce what the icon actually is; the label may be hidden.
        iconBinding.root.contentDescription = when (item.itemType) {
            ItemType.FOLDER -> getString(R.string.folder_desc, displayLabel)
            else -> displayLabel
        }
        iconBinding.icon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        // The workspace adapter tags its children the same way; the dock has
        // no adapter, and the settle reveal looks items up by this tag.
        iconBinding.root.setTag(R.id.tag_item_id, item.id)
        // A bound icon swipe is invisible otherwise; the mark rides as the
        // icon's foreground so it costs no layout and can't move the label.
        // A swipe-to-open folder has a swipe too.
        iconBinding.icon.foreground =
            if (prefs.iconSwipeIndicator && (item.swipeAction != null || item.swipeActionDown != null || item.swipeToOpen)) {
                com.supernova.launcher.ui.SwipeIndicatorDrawable(
                    resources.displayMetrics.density,
                    LabelStyler.parseColor(prefs.labelColor, getColor(R.color.onWallpaper))
                )
            } else {
                null
            }

        val swipeToOpen = item.itemType == ItemType.FOLDER && item.swipeToOpen
        iconBinding.root.setOnClickListener {
            if (handleMoveModeItemTap(item)) return@setOnClickListener
            if (swipeToOpen) launchFolderFirstItem(item, it) else launchItem(item, it)
        }
        iconBinding.root.setOnLongClickListener { v ->
            drag.arm(v, DragPayload.ExistingItem(item.id)) { showWorkspaceItemMenu(v, item) }
            true
        }
        attachIconSwipe(iconBinding.root, item)
        if (swipeToOpen) {
            // A tap no longer opens it, so a screen reader gets the swipe as
            // an action of its own.
            ViewCompat.addAccessibilityAction(iconBinding.root, getString(R.string.open_folder)) { view, _ ->
                showFolderPopup(view, item)
                true
            }
        }

        return iconBinding.root
    }

    // How a desktop icon shrinks into a cell smaller than it needs, which the
    // page's canvas decides: icon, label and padding by the same scale, and
    // the label on one line, so icons in a dense grid stay clear of their
    // neighbours. Scale 1 puts it back as bound.
    private fun desktopIconFit(iconBinding: ItemIconBinding, prefs: LauncherPrefs): WorkspaceCanvas.IconFit {
        val box = desktopIconBoxPx(prefs)
        val textPx = iconBinding.label.textSize
        val lines = iconBinding.label.maxLines
        val pad = iconBinding.root.paddingTop
        val need = desktopIconNeedPx(prefs)
        return WorkspaceCanvas.IconFit(need.width, need.height) { scale ->
            val size = (box * scale).roundToInt()
            iconBinding.icon.updateLayoutParams { width = size; height = size }
            iconBinding.label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, textPx * scale)
            iconBinding.label.maxLines = if (scale < 1f) 1 else lines
            val padding = (pad * scale).roundToInt()
            iconBinding.root.setPadding(padding, padding, padding, padding)
        }
    }

    // Wallpapers: the installed wallpaper apps, or an image from any folder.
    private fun showWallpaperSources() {
        val sources = arrayOf(getString(R.string.wallpaper_apps), getString(R.string.wallpaper_from_files))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.home_action_wallpaper)
            .setItems(sources) { _, which ->
                if (which == 0) {
                    runCatching { startActivity(Intent(Intent.ACTION_SET_WALLPAPER)) }
                } else {
                    runCatching { pickWallpaperFileLauncher.launch(arrayOf("image/*")) }
                        .onFailure { Toast.makeText(this, R.string.launch_failed, Toast.LENGTH_SHORT).show() }
                }
            }
            .show()
    }

    // A crop-and-set screen for the picked image, which sets it without the
    // launcher holding SET_WALLPAPER: the system's own where there is one, as
    // another launcher can take the action too. WallpaperManager's
    // getCropAndSetWallpaperIntent finds none on One UI. Where there is no
    // cropper at all, the apps that "Set as" an image take it.
    private fun cropAndSetWallpaper(uri: android.net.Uri) {
        val crop = Intent(android.app.WallpaperManager.ACTION_CROP_AND_SET_WALLPAPER)
            .setDataAndType(uri, "image/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val cropper = packageManager.queryIntentActivities(crop, 0)
            .sortedByDescending { it.activityInfo.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0 }
            .firstOrNull()?.activityInfo
        val started = cropper != null && runCatching {
            startActivity(crop.setClassName(cropper.packageName, cropper.name))
        }.isSuccess
        if (started) return
        val setAs = Intent(Intent.ACTION_ATTACH_DATA)
            .setDataAndType(uri, "image/*")
            .putExtra("mimeType", "image/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(Intent.createChooser(setAs, getString(R.string.home_action_wallpaper))) }
            .onFailure { Toast.makeText(this, R.string.launch_failed, Toast.LENGTH_SHORT).show() }
    }

    // Per-icon swipe: a short vertical flick on the icon runs its bound app
    // (up and down independently), while taps and long-presses keep working
    // through the normal listeners.
    private fun attachIconSwipe(view: View, item: LauncherItem) {
        // A swipe-to-open folder's swipe up is its own: it opens the folder,
        // whatever swipe-up action it still holds.
        val opensOnSwipe = item.itemType == ItemType.FOLDER && item.swipeToOpen
        val claimsUp = opensOnSwipe || item.swipeAction != null
        val claimsDown = item.swipeActionDown != null
        if (!claimsUp && !claimsDown) return
        var startY = 0f
        var startX = 0f
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    startX = event.rawX
                    // Both this listener and the home detector decide on the
                    // same lift, so the icon claims the directions it binds -
                    // otherwise one swipe ran the icon's action AND the home
                    // binding. dispatchTouchEvent feeds the detector before
                    // the press reaches the icon, so this lands after the
                    // detector has cleared the flags for this gesture.
                    gestureDetector.suppressSwipe(up = claimsUp, down = claimsDown)
                    // The drawer pull arms on the same down, before the press
                    // reaches the icon, and takes the stream once the finger
                    // rises past the slop: every icon's swipe up went to the
                    // drawer while a swipe up opens it. The icon takes it back.
                    if (claimsUp) drawerPullArmed = false
                }
                MotionEvent.ACTION_UP -> {
                    val dy = startY - event.rawY
                    val dx = abs(event.rawX - startX)
                    val up = claimsUp && dy > dp(48) && dy > dx
                    val down = claimsDown && -dy > dp(48) && -dy > dx
                    if (up || down) {
                        // The lift never reaches the icon's own handler, so its
                        // press state and pending long-press are cancelled by
                        // hand; a folder opening in place would keep the ripple.
                        val cancel = MotionEvent.obtain(event)
                        cancel.action = MotionEvent.ACTION_CANCEL
                        v.onTouchEvent(cancel)
                        cancel.recycle()
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        when {
                            up && opensOnSwipe -> showFolderPopup(v, item)
                            up -> launchSwipeAction(item, item.swipeAction, item.swipeLabel)
                            else -> launchSwipeAction(item, item.swipeActionDown, item.swipeLabelDown)
                        }
                        return@setOnTouchListener true
                    }
                }
            }
            false
        }
    }

    // A swipe-to-open folder's tap: the first app its window shows, launched
    // from the folder's icon. A folder with nothing to launch opens instead.
    private fun launchFolderFirstItem(folder: LauncherItem, anchor: View) {
        val first = viewModel.folderFirstMember(folder.id)
        if (first == null) {
            showFolderPopup(anchor, folder)
            return
        }
        launchItem(first, anchor)
    }

    private fun launchSwipeAction(item: LauncherItem, action: String?, label: String?) {
        action ?: return
        // A launcher-action uri runs through the shared action dispatcher.
        com.supernova.launcher.gesture.ActionShortcut.decode(action)?.let {
            runAction(it)
            return
        }
        // An intent an app's shortcut screen made (a direct dial, a route).
        if (com.supernova.launcher.ui.CreatedShortcuts.isIntentUri(action)) {
            launchIntentUri(action, null)
            return
        }
        // Shortcut targets are stored "pkg|shortcutId"; anything else is a
        // flattened component, as written before shortcuts were supported.
        // Either carries its app's profile ("@serial"); older ones don't,
        // and open in the swiped icon's.
        val (target, storedSerial) = com.supernova.launcher.data.LaunchTarget.parse(action)
        val serial = storedSerial ?: item.userSerial
        if (target.contains('|')) {
            launchShortcut(target.substringBefore('|'), target.substringAfter('|'), serial)
            return
        }
        val component = ComponentName.unflattenFromString(target) ?: return
        val app = LaunchableApp(component.packageName, component.className, label.orEmpty(), serial)
        launchApp(app)
    }

    override fun onCanvasTap(page: Int, cellX: Int, cellY: Int, workspaceX: Float, workspaceY: Float) {
        // A tap on empty space with a resize frame up only takes the frame
        // down. Here rather than in a click listener swapped onto the canvas
        // while the frame is up: that left the page with no listener once the
        // frame was gone, and a tap no longer closed edit mode.
        if (widgets.dismissResizeFrame()) return
        if (editMode.isOpen) {
            editMode.exit()
            return
        }
        val movingId = moveModeItemId ?: return
        moveModeItemId = null
        viewModel.moveItemToWorkspace(movingId, page, cellX, cellY, workspaceX, workspaceY)
    }

    // In move mode a tap on another icon merges (folder semantics); tapping the
    // moving item itself cancels. Returns true when the tap was consumed.
    private fun handleMoveModeItemTap(target: LauncherItem): Boolean {
        val movingId = moveModeItemId ?: return false
        moveModeItemId = null
        if (target.id == movingId) return true
        viewModel.moveItemToWorkspace(movingId, target.page, target.cellX, target.cellY)
        return true
    }

    // Empty-home actions: Widgets, Wallpapers, Settings. Page
    // management (add/remove/reorder/default) is the pinch/overview
    // (DESKTOP_PREVIEWS); backup lives in Settings.
    // Empty-space long-press: edit mode (zoomed page, home marker,
    // Wallpapers/Settings chips, peeking add sheet).
    override fun onCanvasLongPress() {
        if (editMode.isOpen) {
            startPageDrag()
            return
        }
        editMode.enter()
    }

    // A long-press on a page in edit mode: the rest of the stream moves the
    // page, so the pager and the canvas let it go.
    private fun startPageDrag() {
        if (desktopLocked || !pageDrag.start()) return
        val now = android.os.SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
    }

    // The live view rendering this page, when the pager still has one: with
    // infinite scroll, the copy nearest the one in view.
    private fun attachedPageView(page: Int): View? {
        val recycler = binding.workspacePager.getChildAt(0)
            as? androidx.recyclerview.widget.RecyclerView ?: return null
        return (0 until recycler.childCount)
            .map(recycler::getChildAt)
            .filter { child ->
                val position = recycler.getChildAdapterPosition(child)
                position != androidx.recyclerview.widget.RecyclerView.NO_POSITION &&
                    workspaceAdapter.realPage(position) == page && !workspaceAdapter.isAddPage(position)
            }
            .minByOrNull { kotlin.math.abs(it.left) }
    }

    // The add-page slot's "+" (and a pick from the sheet made on the slot):
    // one page is asked for; the model emission carrying it closes edit mode
    // on the new page and then runs the pick (observeState).
    private fun requestEditAddPage(then: (() -> Unit)?) {
        if (pendingEditAddPage != null) return
        val page = workspaceAdapter.realPageCount
        if (page >= LauncherRepository.MAX_PAGES) return
        pendingEditAddPage = PendingEditAddPage(page, then)
        viewModel.addPage()
    }

    override fun onAddPageTap() = requestEditAddPage(then = null)

    // Beside the "+", the slot taps like any page's empty space.
    override fun onAddPageBackgroundTap() {
        if (editMode.isOpen) editMode.exit()
    }

    private fun setupEditMode() {
        pageDrag = com.supernova.launcher.ui.PageDragController(
            binding.root, binding.workspacePager,
            object : com.supernova.launcher.ui.PageDragController.Host {
                override fun pageCount(): Int = workspaceAdapter.realPageCount
                override fun currentPage(): Int = workspaceAdapter.realPage(binding.workspacePager.currentItem)
                override fun pageView(page: Int): View? = attachedPageView(page)
                override fun animationMs(baseMs: Long): Long =
                    Animations.duration(viewModel.state.value.prefs, baseMs)
                override fun commit(from: Int, to: Int, done: () -> Unit) {
                    val before = viewModel.state.value.model
                    viewModel.movePage(from, to)
                    lifecycleScope.launch {
                        withTimeoutOrNull(FOLDER_SAVE_WAIT_MS) { viewModel.state.first { it.model !== before } }
                        binding.workspacePager.setCurrentItem(workspaceAdapter.centerPosition(to), false)
                        editMode.onPageChanged()
                        // The moved pages rebind on the next layout pass.
                        binding.root.postDelayed(done, PAGE_REBIND_WAIT_MS)
                    }
                }
            }
        )
        editMode = com.supernova.launcher.ui.HomeEditController(
            this, binding, viewModel,
            object : com.supernova.launcher.ui.HomeEditController.Host {
                // Read off the pager: the model's page trails a swipe by an
                // emission, and the add-page slot is never in it.
                override fun currentPage(): Int =
                    workspaceAdapter.realPage(binding.workspacePager.currentItem)
                override fun defaultPage(): Int = viewModel.state.value.model.defaultPage
                override fun setDefaultPage(page: Int) = viewModel.setDefaultPage(page)
                override fun openWallpapers() = showWallpaperSources()
                override fun openSettings() {
                    startActivity(Intent(this@LauncherActivity, SettingsActivity::class.java))
                }
                override fun pickWidget(info: AppWidgetProviderInfo) = widgets.onWidgetPicked(info)
                override fun dragWidget(info: AppWidgetProviderInfo, preview: View): Boolean =
                    drag.armWidget(info, preview)
                override fun pickShortcut(target: com.supernova.launcher.ui.ShortcutTarget) =
                    pickShortcutTarget(target)
                override fun cellSizePx(): Pair<Int, Int> = currentCellSizePx()
                override fun grid(): Pair<Int, Int> =
                    viewModel.state.value.prefs.let { it.gridCols to it.gridRows }
                override fun cancelPageDrag() = pageDrag.cancel()
                override fun setAddPageSlot(show: Boolean) =
                    workspaceAdapter.setAddPageSlot(show, binding.workspacePager)
                override fun onAddPage(): Boolean =
                    workspaceAdapter.isAddPage(binding.workspacePager.currentItem)
                override fun addPageFromSlot(then: (() -> Unit)?) = requestEditAddPage(then)
                override fun canRemoveCurrentPage(): Boolean =
                    workspaceAdapter.realPageCount > 1 && viewModel.workspaceItems(currentPage()).isEmpty()
                // The page before it takes its place in view, the first page
                // the page after.
                override fun removeCurrentPage() {
                    val page = currentPage()
                    val count = workspaceAdapter.realPageCount
                    viewModel.removePage(page) { followRemovedPages(count - 1, (page - 1).coerceAtLeast(0)) }
                }
            }
        )
    }

    // A shortcut row from the add sheet: an app's CREATE_SHORTCUT flow, a
    // launcher action placed as an icon, or the any-activity picker.
    override fun pickShortcutTarget(target: com.supernova.launcher.ui.ShortcutTarget) {
        when (target) {
            is com.supernova.launcher.ui.ShortcutTarget.Create -> {
                val intent = Intent(Intent.ACTION_CREATE_SHORTCUT)
                    .setClassName(target.packageName, target.activityName)
                runCatching { createShortcutLauncher.launch(intent) }
                    .onFailure { Toast.makeText(this, R.string.launch_failed, Toast.LENGTH_SHORT).show() }
            }
            is com.supernova.launcher.ui.ShortcutTarget.Action -> addIntentItemAtFreeCell(
                com.supernova.launcher.gesture.ActionShortcut.encode(target.action),
                getString(com.supernova.launcher.ui.settings.EnumLabels.of(target.action)),
                iconBase64 = null
            )
            com.supernova.launcher.ui.ShortcutTarget.Activities -> showActivitiesPicker()
        }
    }

    // Commits a drop; false means nothing was written (the picture flies
    // back instead of settling).
    override fun onDropToWorkspace(
        payload: DragPayload,
        page: Int,
        cellX: Int,
        cellY: Int,
        workspaceX: Float,
        workspaceY: Float,
        rawCellX: Float,
        rawCellY: Float
    ): Boolean {
        cancelReorderPreview(animate = false)
        if (desktopLocked) return false
        val model = viewModel.state.value.model
        val prefs = viewModel.state.value.prefs
        when (payload) {
            is DragPayload.ExistingItem -> {
                val movingItem = model.items.firstOrNull { it.id == payload.itemId } ?: return false
                val widget = movingItem.itemType == ItemType.WIDGET
                if (folderHover(payload, page, rawCellX, rawCellY)) {
                    // Centre-of-icon drop: the classic merge path, with the
                    // icon the hover showed, not whatever is stored at the
                    // drop cell.
                    val mergeTarget = folderTargetId(page, rawCellX, rawCellY, movingItem.id)
                    // A folder's app dropped back on its folder stays where
                    // it was, and flies back in.
                    if (mergeTarget != null && mergeTarget == movingItem.folderId) return false
                    viewModel.moveItemToWorkspace(
                        payload.itemId, page, cellX, cellY, workspaceX, workspaceY, mergeTargetId = mergeTarget
                    )
                    pulseFolderTarget(page, rawCellX, rawCellY, movingItem.id)
                    return true
                }
                val target = if (widget) {
                    widgetDropRect(movingItem, cellX, cellY, rawCellX, rawCellY)
                } else {
                    iconRectAt(workspaceX, workspaceY, prefs.gridCols, prefs.gridRows)
                }
                val (spanX, spanY) = if (widget) WorkspaceGeometry.wholeSpans(target) else 1 to 1
                val moves = WorkspaceGeometry.solveReorder(
                    model, page, target, prefs.gridCols, prefs.gridRows, setOf(movingItem.id)
                )
                if (moves == null && !pageHasRoom(model, page, target, spanX, spanY, setOf(movingItem.id))) {
                    Toast.makeText(this, R.string.no_room_on_page, Toast.LENGTH_SHORT).show()
                    return false
                }
                if (widget) {
                    viewModel.moveWidget(payload.itemId, page, target, moves.orEmpty())
                } else {
                    viewModel.moveItemToWorkspace(
                        payload.itemId, page, cellX, cellY, workspaceX, workspaceY, moves.orEmpty(), mergeFolders = false
                    )
                }
                return true
            }
            is DragPayload.DrawerApp, is DragPayload.DrawerShortcut, is DragPayload.DrawerFolder -> {
                if (payload is DragPayload.DrawerFolder && payload.apps.isEmpty()) return false
                // Only an app or a shortcut merges; folderHover is false for
                // a folder.
                if (folderHover(payload, page, rawCellX, rawCellY)) {
                    val mergeTargetId = folderTargetId(page, rawCellX, rawCellY, excludeId = null)
                    when (payload) {
                        is DragPayload.DrawerApp -> viewModel.addAppToWorkspace(
                            payload.app, page, cellX, cellY, workspaceX, workspaceY, mergeTargetId = mergeTargetId
                        )
                        is DragPayload.DrawerShortcut -> {
                            val item = shortcutItem(payload)
                            viewModel.addShortcutToWorkspace(
                                item.owner, payload.shortcutId, payload.label, page, cellX, cellY,
                                workspaceX, workspaceY, item.iconBase64, mergeTargetId = mergeTargetId
                            )
                        }
                        else -> return false
                    }
                    pulseFolderTarget(page, rawCellX, rawCellY, excludeId = null)
                    return true
                }
                val target = iconRectAt(workspaceX, workspaceY, prefs.gridCols, prefs.gridRows)
                val moves = WorkspaceGeometry.solveReorder(
                    model, page, target, prefs.gridCols, prefs.gridRows, emptySet()
                )
                if (moves == null && !pageHasRoom(model, page, target, 1, 1, emptySet())) {
                    Toast.makeText(this, R.string.no_room_on_page, Toast.LENGTH_SHORT).show()
                    return false
                }
                val displace = moves.orEmpty()
                when (payload) {
                    is DragPayload.DrawerApp -> viewModel.addAppToWorkspace(
                        payload.app, page, cellX, cellY, workspaceX, workspaceY, displace, mergeFolders = false
                    )
                    is DragPayload.DrawerShortcut -> {
                        val item = shortcutItem(payload)
                        viewModel.addShortcutToWorkspace(
                            item.owner, payload.shortcutId, payload.label, page, cellX, cellY,
                            workspaceX, workspaceY, item.iconBase64, displace, mergeFolders = false
                        )
                    }
                    is DragPayload.DrawerFolder -> viewModel.addFolderToWorkspace(
                        payload.name, payload.apps, page, cellX, cellY, workspaceX, workspaceY, displace
                    )
                    else -> Unit
                }
                return true
            }
            is DragPayload.NewWidget -> {
                val anchorX = clampAnchor(cellX, payload.spanX, prefs.gridCols)
                val anchorY = clampAnchor(cellY, payload.spanY, prefs.gridRows)
                val target = WorkspaceGeometry.cellRect(anchorX, anchorY, payload.spanX, payload.spanY)
                // No arrangement makes room: the drop is refused before any
                // widget id is allocated, so cancelling costs nothing.
                val moves = WorkspaceGeometry.solveReorder(
                    model, page, target, prefs.gridCols, prefs.gridRows, emptySet()
                ) ?: run {
                    Toast.makeText(this, R.string.no_room_on_page, Toast.LENGTH_SHORT).show()
                    return false
                }
                widgets.bind(payload.info, WidgetController.Target.Workspace(page, anchorX, anchorY, moves))
                return true
            }
        }
    }

    // Whether a drop that can't push its neighbours aside still has a free
    // spot on the page: the repository then places it at the nearest one,
    // and refuses it only when there is none. Refused, the picture used to
    // settle into the occupied cell and vanish - a drawer app never added,
    // a moved icon popping back to where it was.
    // Where a placed widget dropped here lands, in cells, keeping its size:
    // its top-left on the hovered cell, or with subgrid positioning on the
    // nearest half cell. rawCellX is the drop point, half a cell in from the
    // picture's top-left.
    private fun widgetDropRect(
        item: LauncherItem,
        cellX: Int,
        cellY: Int,
        rawCellX: Float,
        rawCellY: Float
    ): WorkspaceGeometry.CellRect {
        val prefs = viewModel.state.value.prefs
        val rect = WorkspaceGeometry.itemRect(item, prefs.gridCols, prefs.gridRows)
        val width = rect.right - rect.left
        val height = rect.bottom - rect.top
        fun anchor(cell: Int, raw: Float, size: Float, count: Int): Float {
            val most = (count - size).coerceAtLeast(0f)
            val start = if (prefs.subgrid) ((raw - 0.5f) * 2).roundToInt() / 2f else cell.toFloat()
            return start.coerceIn(0f, most)
        }
        val left = anchor(cellX, rawCellX, width, prefs.gridCols)
        val top = anchor(cellY, rawCellY, height, prefs.gridRows)
        return WorkspaceGeometry.CellRect(left, top, left + width, top + height)
    }

    private fun pageHasRoom(
        model: com.supernova.launcher.data.LauncherModel,
        page: Int,
        target: WorkspaceGeometry.CellRect,
        spanX: Int,
        spanY: Int,
        excludeIds: Set<String>
    ): Boolean {
        if (WorkspaceGeometry.allowPlacementOverlap) return true
        val prefs = viewModel.state.value.prefs
        return WorkspaceGeometry.nearestFreeCell(
            model, page, (target.left + target.right) / 2f, (target.top + target.bottom) / 2f,
            spanX, spanY, prefs.gridCols, prefs.gridRows, excludeIds
        ) != null
    }

    // Folder merge feedback: the icon the drop landed on swells for a beat.
    private fun pulseFolderTarget(page: Int, rawCellX: Float, rawCellY: Float, excludeId: String?) {
        val prefs = viewModel.state.value.prefs
        if (!Animations.enabled(prefs)) return
        val targetId = folderTargetId(page, rawCellX, rawCellY, excludeId) ?: return
        drag.forEachWorkspaceIcon { _, view ->
            if (view.getTag(R.id.tag_item_id) != targetId) return@forEachWorkspaceIcon
            val duration = Animations.duration(prefs, FOLDER_PULSE_MS)
            view.animate().scaleX(FOLDER_PULSE_SCALE).scaleY(FOLDER_PULSE_SCALE).setDuration(duration)
                .withEndAction {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(duration).start()
                }
                .start()
        }
    }

    // The one-cell square an icon occupies when dropped at a normalized
    // position - the same rect WorkspaceGeometry.itemRect derives for it.
    private fun iconRectAt(workspaceX: Float, workspaceY: Float, cols: Int, rows: Int): WorkspaceGeometry.CellRect {
        val cx = workspaceX * cols
        val cy = workspaceY * rows
        return WorkspaceGeometry.CellRect(cx - 0.5f, cy - 0.5f, cx + 0.5f, cy + 0.5f)
    }

    // The centre-zone rule: hovering the middle of a folder-eligible item
    // still means "merge into a folder"; anywhere else means "make space".
    private fun folderHover(payload: DragPayload, page: Int, rawCellX: Float, rawCellY: Float): Boolean {
        val excludeId: String?
        when (payload) {
            is DragPayload.DrawerApp, is DragPayload.DrawerShortcut -> excludeId = null
            // A folder goes into no folder, and makes none with an icon.
            is DragPayload.DrawerFolder -> return false
            is DragPayload.ExistingItem -> {
                val item = viewModel.state.value.model.items.firstOrNull { it.id == payload.itemId } ?: return false
                if (item.itemType != ItemType.APP && item.itemType != ItemType.SHORTCUT) return false
                excludeId = item.id
            }
            is DragPayload.NewWidget -> return false
        }
        return folderTargetId(page, rawCellX, rawCellY, excludeId) != null
    }

    // The folder-eligible item whose centre zone the hover is in, if any.
    private fun folderTargetId(page: Int, rawCellX: Float, rawCellY: Float, excludeId: String?): String? {
        val prefs = viewModel.state.value.prefs
        return viewModel.state.value.model.items.firstOrNull { other ->
            other.container == ContainerType.WORKSPACE && other.page == page && other.id != excludeId &&
                (other.itemType == ItemType.APP || other.itemType == ItemType.SHORTCUT || other.itemType == ItemType.FOLDER) &&
                run {
                    val r = WorkspaceGeometry.itemRect(other, prefs.gridCols, prefs.gridRows)
                    abs((r.left + r.right) / 2f - rawCellX) <= FOLDER_CENTER_ZONE &&
                        abs((r.top + r.bottom) / 2f - rawCellY) <= FOLDER_CENTER_ZONE
                }
        }?.id
    }

    // The rect a drop at this hover position would occupy, plus the dragged
    // item to exclude from collision - null when the payload can't reorder.
    override fun hoverTargetRect(
        payload: DragPayload,
        cellX: Int,
        cellY: Int,
        rawCellX: Float,
        rawCellY: Float
    ): Pair<WorkspaceGeometry.CellRect, Set<String>>? {
        val prefs = viewModel.state.value.prefs
        val cols = prefs.gridCols
        val rows = prefs.gridRows
        fun snappedIconRect(): WorkspaceGeometry.CellRect {
            // Mirrors the adapter's placement(): subgrid snaps to half-cell
            // centres, otherwise the drop centres on the hovered cell.
            val wx = if (prefs.subgrid) snapToHalfCell((rawCellX / cols).coerceIn(0f, 1f), cols) else (cellX + 0.5f) / cols
            val wy = if (prefs.subgrid) snapToHalfCell((rawCellY / rows).coerceIn(0f, 1f), rows) else (cellY + 0.5f) / rows
            return iconRectAt(wx, wy, cols, rows)
        }
        fun widgetRect(spanX: Int, spanY: Int) = WorkspaceGeometry.cellRect(
            clampAnchor(cellX, spanX, cols), clampAnchor(cellY, spanY, rows), spanX, spanY
        )
        return when (payload) {
            is DragPayload.DrawerApp, is DragPayload.DrawerShortcut, is DragPayload.DrawerFolder ->
                snappedIconRect() to emptySet()
            is DragPayload.ExistingItem -> {
                val item = viewModel.state.value.model.items.firstOrNull { it.id == payload.itemId } ?: return null
                if (item.itemType == ItemType.WIDGET) {
                    widgetDropRect(item, cellX, cellY, rawCellX, rawCellY) to setOf(item.id)
                } else {
                    snappedIconRect() to setOf(item.id)
                }
            }
            is DragPayload.NewWidget -> widgetRect(payload.spanX, payload.spanY) to emptySet()
        }
    }

    override fun onDragOverWorkspace(
        payload: DragPayload,
        page: Int,
        canvas: ViewGroup,
        cellWidth: Int,
        cellHeight: Int,
        cellX: Int,
        cellY: Int,
        rawCellX: Float,
        rawCellY: Float
    ) {
        if (desktopLocked) return
        val (target, exclude) = hoverTargetRect(payload, cellX, cellY, rawCellX, rawCellY) ?: return
        val folder = folderHover(payload, page, rawCellX, rawCellY)
        val key = ReorderHoverKey(canvas, page, target.left, target.top, folder)
        if (key == reorderHoverKey) return
        pendingReorder?.let(reorderHandler::removeCallbacks)
        pendingReorder = null
        if (reorderCanvas !== canvas) previewReorder(emptyMap())
        reorderHoverKey = key
        reorderCanvas = canvas
        reorderCellW = cellWidth
        reorderCellH = cellHeight
        if (folder) {
            previewReorder(emptyMap())
            return
        }
        val run = Runnable {
            val prefs = viewModel.state.value.prefs
            val moves = WorkspaceGeometry.solveReorder(
                viewModel.state.value.model, page, target, prefs.gridCols, prefs.gridRows, exclude
            )
            previewReorder(moves.orEmpty().associate { it.itemId to (it.dCellX to it.dCellY) })
        }
        pendingReorder = run
        reorderHandler.postDelayed(run, REORDER_HOVER_DELAY_MS)
    }

    override fun cancelReorderPreview(animate: Boolean) {
        pendingReorder?.let(reorderHandler::removeCallbacks)
        pendingReorder = null
        reorderHoverKey = null
        previewReorder(emptyMap(), animate)
        reorderCanvas = null
    }

    // Shifts the hovered page's children by cell deltas (empty map reverts).
    // Only the translation moves; the model stays untouched until the drop.
    // Deltas are applied RELATIVE to each child's resting translation, the
    // base captured the first time the preview moves it. The canvas lays
    // icons out on their point and widgets on their cells, so that rest is
    // zero after a bind - but the base, not zero, is what a revert returns to.
    private fun previewReorder(offsets: Map<String, Pair<Int, Int>>, animate: Boolean = true) {
        val canvas = reorderCanvas ?: return
        val useAnim = animate && Animations.enabled(viewModel.state.value.prefs)
        for (i in 0 until canvas.childCount) {
            val child = canvas.getChildAt(i)
            val id = child.getTag(R.id.tag_item_id) as? String ?: continue
            val delta = offsets[id]
            val storedBase = child.getTag(R.id.tag_preview_base) as? PointF
            // Never touched by the preview and not shifting now: leave it
            // alone rather than trusting translation zero to be its home.
            if (delta == null && storedBase == null) continue
            val base = storedBase
                ?: PointF(child.translationX, child.translationY).also { child.setTag(R.id.tag_preview_base, it) }
            val tx = base.x + (delta?.first ?: 0) * reorderCellW.toFloat()
            val ty = base.y + (delta?.second ?: 0) * reorderCellH.toFloat()
            if (child.translationX == tx && child.translationY == ty) continue
            if (useAnim) {
                child.animate().translationX(tx).translationY(ty)
                    .setDuration(Animations.duration(viewModel.state.value.prefs, REORDER_ANIM_MS)).setInterpolator(reorderInterpolator).start()
            } else {
                child.animate().cancel()
                child.translationX = tx
                child.translationY = ty
            }
        }
    }

    // Places an intent-backed item on the first free cell of the current page.
    private fun addIntentItemAtFreeCell(intentUri: String, label: String, iconBase64: String?) {
        val page = viewModel.state.value.currentPage
        val free = viewModel.firstFreeWorkspaceCell(page, 1, 1)
        if (free == null) {
            Toast.makeText(this, R.string.no_room_on_page, Toast.LENGTH_SHORT).show()
            return
        }
        viewModel.addIntentItemToWorkspace(intentUri, label, iconBase64, page, free.first, free.second)
    }

    // Any exported activity as an icon. Pick an app, then one of its activities;
    // the launch intent is stored as an intentUri and the icon is snapshotted.
    private fun showActivitiesPicker() {
        val apps = com.supernova.launcher.data.LabelOrder.sorted(viewModel.visibleApps()) { it.label }
        if (apps.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_category_activities)
            .setItems(apps.map { it.label }.toTypedArray()) { _, which ->
                showActivitiesForApp(apps[which])
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showActivitiesForApp(app: LaunchableApp) {
        val pm = packageManager
        val activities = runCatching {
            pm.getPackageInfo(app.packageName, android.content.pm.PackageManager.GET_ACTIVITIES)
                .activities.orEmpty()
                .filter { it.exported && it.isEnabled }
        }.getOrDefault(emptyList())
            .let { found -> com.supernova.launcher.data.LabelOrder.sorted(found) { it.loadLabel(pm) } }

        if (activities.isEmpty()) {
            Toast.makeText(this, R.string.no_activities, Toast.LENGTH_SHORT).show()
            return
        }
        // Most activities inherit the app label; append the short class name
        // so multiple activities of one app are distinguishable.
        val names = activities.map { activity ->
            val activityLabel = activity.loadLabel(pm).toString()
            val shortClass = activity.name.substringAfterLast('.')
            if (activityLabel.isBlank() || activityLabel == app.label) {
                "${app.label}: $shortClass"
            } else {
                activityLabel
            }
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(app.label)
            .setItems(names) { _, which ->
                val activity = activities[which]
                val intent = Intent()
                    .setComponent(ComponentName(activity.packageName, activity.name))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val icon = runCatching { activity.loadIcon(pm) }.getOrNull()
                val icon64 = icon?.let { com.supernova.launcher.data.GalleryIconCodec.encode(it) }
                addIntentItemAtFreeCell(intent.toUri(Intent.URI_INTENT_SCHEME), names[which], icon64)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun handleCreateShortcutResult(data: Intent) {
        // Newer apps return a pinnable shortcut request; accepting it pins the
        // shortcut to us, so it launches reliably through startShortcut.
        val launcherApps = getSystemService(LauncherApps::class.java)
        val pin = runCatching { launcherApps?.getPinItemRequest(data) }.getOrNull()
        if (pin != null && pin.isValid &&
            pin.requestType == LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT
        ) {
            val shortcut = pin.shortcutInfo
            if (shortcut != null && pin.accept()) {
                val label = (shortcut.shortLabel ?: shortcut.longLabel ?: shortcut.`package`).toString()
                val icon = runCatching {
                    launcherApps.getShortcutIconDrawable(shortcut, resources.displayMetrics.densityDpi)
                }.getOrNull()
                val icon64 = icon?.let { com.supernova.launcher.data.GalleryIconCodec.encode(it) }
                val serial = getSystemService(android.os.UserManager::class.java)
                    ?.getSerialNumberForUser(shortcut.userHandle) ?: 0L
                addPinnedShortcutAtFreeCell(
                    shortcut.`package`, shortcut.id, shortcut.activity?.className, serial, label, icon64
                )
            }
            return
        }

        // Legacy result: an intent to fire plus a name and an optional icon.
        @Suppress("DEPRECATION")
        val shortcutIntent = data.getParcelableExtra<Intent>(Intent.EXTRA_SHORTCUT_INTENT) ?: return
        val name = data.getStringExtra(Intent.EXTRA_SHORTCUT_NAME).orEmpty()
        addIntentItemAtFreeCell(
            shortcutIntent.toUri(Intent.URI_INTENT_SCHEME), name, extractLegacyShortcutIcon(data)
        )
    }

    private fun addPinnedShortcutAtFreeCell(
        packageName: String,
        shortcutId: String,
        className: String?,
        userSerial: Long,
        label: String,
        iconBase64: String?
    ) {
        val page = viewModel.state.value.currentPage
        val free = viewModel.firstFreeWorkspaceCell(page, 1, 1)
        if (free == null) {
            Toast.makeText(this, R.string.no_room_on_page, Toast.LENGTH_SHORT).show()
            return
        }
        viewModel.addShortcutToWorkspace(
            LaunchableApp(packageName, className.orEmpty(), label, userSerial),
            shortcutId, label, page, free.first, free.second, iconBase64 = iconBase64, mergeFolders = false
        )
    }

    @Suppress("DEPRECATION")
    private fun extractLegacyShortcutIcon(data: Intent): String? {
        data.getParcelableExtra<android.graphics.Bitmap>(Intent.EXTRA_SHORTCUT_ICON)?.let {
            return com.supernova.launcher.data.GalleryIconCodec.encode(it)
        }
        val iconRes = data.getParcelableExtra<Intent.ShortcutIconResource>(Intent.EXTRA_SHORTCUT_ICON_RESOURCE)
            ?: return null
        val drawable = runCatching {
            val res = packageManager.getResourcesForApplication(iconRes.packageName)
            val id = res.getIdentifier(iconRes.resourceName, null, null)
            if (id != 0) res.getDrawable(id, null) else null
        }.getOrNull() ?: return null
        return com.supernova.launcher.data.GalleryIconCodec.encode(drawable)
    }

    // ---- Custom cards: shared launch / icon / label / menu routes --------

    private fun editFeedCard(card: com.supernova.launcher.feed.FeedCard?) {
        CustomCardEditorSheet.show(
            this, viewModel, card,
            onPickWidget = { cardId -> widgets.showPickerForCard(cardId) }
        )
    }

    private fun launchCardTarget(type: com.supernova.launcher.feed.FeaturedType, target: String) {
        when (type) {
            com.supernova.launcher.feed.FeaturedType.APP -> launchGestureApp(target)
            com.supernova.launcher.feed.FeaturedType.SHORTCUT -> launchGestureShortcut(target)
            com.supernova.launcher.feed.FeaturedType.WIDGET -> Unit
        }
    }

    // One binder per surface: the look is shared, the after-launch step is
    // not (search and the drawer close themselves, the feed stays).
    private fun cardBinder(afterLaunch: (() -> Unit)?): com.supernova.launcher.ui.CardViewBinder =
        com.supernova.launcher.ui.CardViewBinder(
            com.supernova.launcher.ui.CardViewBinder.Callbacks(
                onLaunchTarget = { type, target, _ ->
                    launchCardTarget(type, target)
                    afterLaunch?.invoke()
                },
                widgetView = widgets::cardWidgetView,
                iconForTarget = ::iconForCardTarget,
                labelForTarget = ::labelForCardTarget,
                onMenu = { card, anchor -> showCardMenu(card, anchor) },
                onHistory = { card, _ -> showCardHistory(card) }
            )
        )

    // The history mark: when the card last activated (its conditions all
    // held), or why it never will.
    private fun showCardHistory(card: com.supernova.launcher.feed.FeedCard) {
        val text = when {
            !card.hasConditions -> getString(R.string.card_history_none)
            else -> cardActivation.lastActivatedAt(card.id)?.let { at ->
                getString(
                    R.string.card_history_at,
                    android.text.format.DateUtils.getRelativeTimeSpanString(
                        at, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS
                    )
                )
            } ?: getString(R.string.card_history_never)
        }
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun appForCardTarget(target: String): LaunchableApp? {
        val (body, serial) = com.supernova.launcher.data.LaunchTarget.split(target)
        val pkg = if (body.contains('|')) body.substringBefore('|') else body.substringBefore('/')
        val cls = if (body.contains('|')) "" else body.substringAfter('/', "")
        if (pkg.isEmpty()) return null
        // Shortcut targets fall back to their app: the catalog resolves by
        // package when the class is absent.
        return viewModel.state.value.apps.firstOrNull {
            it.packageName == pkg && (cls.isEmpty() || it.className == cls) &&
                it.userSerial == serial
        } ?: LaunchableApp(pkg, cls, "", serial)
    }

    private fun iconForCardTarget(target: String): android.graphics.drawable.Drawable? {
        val app = appForCardTarget(target) ?: return null
        val body = com.supernova.launcher.data.LaunchTarget.split(target).first
        if (body.contains('|')) {
            val id = body.substringAfter('|')
            viewModel.shortcutsFor(app.packageName, app.userSerial)
                .firstOrNull { it.id == id }
                ?.let { shortcut -> viewModel.appCatalog().shortcutIcon(shortcut)?.let { return it } }
        }
        return viewModel.appCatalog().iconFor(app)
    }

    // App label, or "App: shortcut" for a shortcut target; null when the
    // target no longer resolves (uninstalled).
    private fun labelForCardTarget(target: String): String? {
        val app = appForCardTarget(target) ?: return null
        val appLabel = app.label.takeIf { it.isNotBlank() }
            ?: viewModel.state.value.apps.firstOrNull {
                it.packageName == app.packageName && it.userSerial == app.userSerial
            }?.label
        val body = com.supernova.launcher.data.LaunchTarget.split(target).first
        if (!body.contains('|')) return appLabel
        val id = body.substringAfter('|')
        val shortcut = viewModel.shortcutsFor(app.packageName, app.userSerial).firstOrNull { it.id == id }
        val shortcutLabel = (shortcut?.shortLabel ?: shortcut?.longLabel)?.toString()
        return when {
            shortcutLabel != null && appLabel != null -> "$appLabel: $shortcutLabel"
            else -> shortcutLabel ?: appLabel
        }
    }

    // The card menu: Settings (edit) and Pin.
    private fun showCardMenu(card: com.supernova.launcher.feed.FeedCard, anchor: View) {
        val menu = android.widget.PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, R.string.card_menu_edit)
        menu.menu.add(0, 2, 1, if (card.pinned) R.string.card_menu_unpin else R.string.card_menu_pin)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> editFeedCard(card)
                2 -> viewModel.setFeedCardPinned(card.id, !card.pinned)
            }
            true
        }
        menu.show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        widgets.onActivityResult(requestCode, resultCode, data)
    }

    // ---- WidgetController.Host

    override val activeResizeFrame: WidgetResizeFrame?
        get() = widgets.activeResizeFrame

    override val feedOpen: Boolean
        get() = ::feed.isInitialized && feed.isOpen

    override fun cancelGesture(cancel: MotionEvent) {
        gestureDetector.onTouchEvent(cancel)
    }

    override fun onWidgetLongPress(view: View, item: LauncherItem) {
        drag.arm(view, DragPayload.ExistingItem(item.id)) { showWorkspaceItemMenu(view, item) }
    }

    override fun onSearchBarWidgetLongPress(anchor: View) = showSearchBarMenu(anchor)

    override fun onPageBound(canvas: ViewGroup) = widgets.onPageBound(canvas)

    override fun reportWidgetSize(item: LauncherItem, view: View) = widgets.reportWidgetSize(item, view)

    override fun currentCellSizePx(): Pair<Int, Int> {
        val prefs = viewModel.state.value.prefs
        // Mirrors WorkspacePagerAdapter: pages carry the desktopPadding prefs.
        val width = (binding.workspacePager.width.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels) - dp(prefs.desktopPaddingH) * 2
        val height = (binding.workspacePager.height.takeIf { it > 0 }
            ?: resources.displayMetrics.heightPixels) - dp(prefs.desktopPaddingV) * 2
        return max(1, width / max(1, prefs.gridCols)) to max(1, height / max(1, prefs.gridRows))
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.launcher_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_add_widget -> {
                editMode.enter()
                true
            }
            R.id.action_export_backup -> {
                exportBackupLauncher.launch("supernova-backup-${System.currentTimeMillis()}.json")
                true
            }
            R.id.action_import_backup -> {
                importBackupLauncher.launch(arrayOf("application/json", "text/plain"))
                true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }





    companion object {
        // Same vertical bias the gesture detector uses for swipe-up/down.
        private const val DRAWER_PULL_BIAS = 1.2f
        // How long the Circle takes to fill the screen before the app opens.
        private const val CIRCLE_FILL_MS = 280L
        // How long a folder member action waits for its change to be saved.
        private const val FOLDER_SAVE_WAIT_MS = 1_000L
        private const val PAGE_REBIND_WAIT_MS = 100L

        // Reorder: how long a drag must hover one spot before items move
        // aside, how fast they slide, and how large the folder-merge centre
        // zone of an icon is (in cells, each side of the centre).
        private const val REORDER_HOVER_DELAY_MS = 250L
        private const val REORDER_ANIM_MS = 180L
        private const val FOLDER_CENTER_ZONE = 0.35f
        private const val FOLDER_PULSE_SCALE = 1.18f
        private const val FOLDER_PULSE_MS = 90L

        // Undo snackbar lifetime after a removal.
        private const val UNDO_DURATION_MS = 4000

        // The icon box and item padding item_icon declares, and the padding a
        // dock icon column keeps around it instead. Kept here because the
        // dock derives its own height from them rather than from a fixed bar
        // height, and a drop's settle works out where a desktop item's icon
        // will sit from them.
        private const val ICON_BASE_DP = 48
        // item_icon's padding, all round.
        private const val ICON_ITEM_PADDING_DP = 4
        private const val DOCK_ITEM_PADDING_H_DP = 2
        private const val DOCK_ITEM_PADDING_V_DP = 4
        private const val DOCK_SLOT_PADDING_V_DP = 2

        // The dim behind a dialog that previews on the home screen.
        private const val PREVIEW_DIALOG_DIM = 0.15f

        // Frames dockChromeWatch may drop in a row while the dock's height
        // settles.
        private const val MAX_DOCK_CHROME_HOLDS = 2

        // Landscape cap on the dock and desktop search bar, as a multiple of
        // the display's short edge: wide enough to use the extra room, narrow
        // enough that five dock icons still read as a row.
        private const val LANDSCAPE_CHROME_WIDTH = 1.35f
    }
}
