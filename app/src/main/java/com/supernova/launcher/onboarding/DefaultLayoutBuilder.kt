package com.supernova.launcher.onboarding

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.Telephony
import com.supernova.launcher.SettingsActivity
import com.supernova.launcher.data.AppCatalog
import com.supernova.launcher.data.ContainerType
import com.supernova.launcher.data.DeviceProfile
import com.supernova.launcher.data.ItemType
import com.supernova.launcher.data.LaunchableApp
import com.supernova.launcher.data.LauncherItem
import java.util.UUID

// Builds the home screen a fresh install starts with, from its recipes
// (default_workspace layouts, in Launcher3's layout format). On a phone:
// phone, messages, maps, browser and camera in the dock, and on the first page
// the email app, the gallery and the app store along the bottom row with
// Supernova Settings in the top-right corner (default_workspace_5x5). On a
// tablet, which has no phone: email, calendar, gallery, app store, browser
// and camera in the dock and nothing on the page (default_workspace_6x5).
// Each place tries its sources in order and takes the first that resolves to
// an app with a launcher entry. A place nothing fills is left out - the dock
// closes up, a cell stays empty - never a placeholder.
//
// Where Android keeps a default for the job, the SMS app and the browser,
// that default goes first. Asking for the app category before it would, on
// a phone with two browsers, pick the preinstalled one over the one the user
// chose.
//
// LauncherViewModel.seedDefaultLayout plants it once per install (see
// StoreKeys.defaultLayoutSeeded), as the first-run wizard opens. The search
// bar under the dock is a preference, not an item, so it is the view
// model's to write.
object DefaultLayoutBuilder {

    // The resolver activity, handed back when the user has no default for an
    // intent and Android would show the chooser. Not a real app.
    private const val RESOLVER_PACKAGE = "android"

    // Where a place's app can come from: one of a recipe's <favorite>s.
    internal sealed interface Source {
        // The default SMS app, Android's SMS role.
        data object DefaultSms : Source

        // Supernova Settings' drawer entry. Supernova has a second one, the
        // launcher itself, so it is asked for by name.
        data object Self : Source

        // An app category, resolved Launcher3's way (resolvePackage).
        data class Category(val category: String) : Source

        // An intent: an action, with data or a MIME type.
        data class Action(val action: String, val data: String? = null, val type: String? = null) : Source
    }

    // One place of a recipe and its sources, best first. A null cell is the
    // dock, which fills in order. A cell counts from the far edge when
    // negative, as Launcher3's layout files do: -1 is the last column or row.
    internal class Slot(val cell: Pair<Int, Int>?, val sources: List<Source>)

    // A slot once resolved: the apps its sources came to, null where one
    // didn't resolve.
    internal class Place(val cell: Pair<Int, Int>?, val apps: Sequence<String?>)

    // Where an app landed: a dock rank, or a cell on the first page.
    internal data class Seat(val packageName: String, val rank: Int? = null, val x: Int = 0, val y: Int = 0)

    private val DIALER = listOf(
        Source.Action(Intent.ACTION_DIAL), Source.Action(Intent.ACTION_VIEW, "tel:123"),
        Source.Action(Intent.ACTION_CALL_BUTTON)
    )
    private val MESSAGES = listOf(
        Source.DefaultSms, Source.Category(Intent.CATEGORY_APP_MESSAGING),
        Source.Action(Intent.ACTION_VIEW, "sms:"), Source.Action(Intent.ACTION_VIEW, "smsto:"),
        Source.Action(Intent.ACTION_VIEW, "mms:"), Source.Action(Intent.ACTION_VIEW, "mmsto:")
    )
    private val MAPS = listOf(Source.Category(Intent.CATEGORY_APP_MAPS), Source.Category(Intent.CATEGORY_APP_MUSIC))
    private val BROWSER = listOf(
        Source.Action(Intent.ACTION_VIEW, "http://www.example.com"), Source.Category(Intent.CATEGORY_APP_BROWSER)
    )
    private val CAMERA = listOf(
        Source.Action(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), Source.Action(Intent.ACTION_CAMERA_BUTTON)
    )
    private val EMAIL = listOf(Source.Category(Intent.CATEGORY_APP_EMAIL), Source.Action(Intent.ACTION_VIEW, "mailto:"))
    private val CALENDAR = listOf(Source.Category(Intent.CATEGORY_APP_CALENDAR))
    private val GALLERY = listOf(
        Source.Category(Intent.CATEGORY_APP_GALLERY), Source.Action(Intent.ACTION_VIEW, type = "image/*")
    )
    private val MARKET = listOf(
        Source.Category(Intent.CATEGORY_APP_MARKET),
        Source.Action(Intent.ACTION_VIEW, "market://details?id=com.android.launcher")
    )

    // default_workspace_5x5 and default_workspace_6x5.
    internal fun recipe(tablet: Boolean): List<Slot> = if (tablet) {
        listOf(EMAIL, CALENDAR, GALLERY, MARKET, BROWSER, CAMERA).map { Slot(null, it) }
    } else {
        listOf(DIALER, MESSAGES, MAPS, BROWSER, CAMERA).map { Slot(null, it) } + listOf(
            Slot(-1 to 0, listOf(Source.Self)),
            Slot(0 to -1, EMAIL),
            Slot(1 to -1, GALLERY),
            Slot(-1 to -1, MARKET)
        )
    }

    fun buildItems(context: Context, catalog: AppCatalog, cols: Int, rows: Int, dockCount: Int): List<LauncherItem> {
        val pm = context.packageManager
        val apps = HashMap<String, LaunchableApp?>()
        fun launchable(pkg: String?): String? =
            pkg?.takeIf { apps.getOrPut(it) { catalog.mainActivityFor(it, userSerial = 0L) } != null }
        // The alias is named against the app's namespace, SettingsActivity's
        // package. Not R's: R8 renames R in release builds (to something like
        // "tz0"), while a class the manifest names keeps its name.
        val settingsAlias = SettingsActivity::class.java.name.substringBeforeLast('.') + ".SettingsLauncherAlias"
        fun resolve(source: Source): String? = launchable(
            when (source) {
                Source.DefaultSms -> runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()
                Source.Self -> catalog.mainActivityFor(context.packageName, userSerial = 0L, className = settingsAlias)
                    ?.let { settings ->
                        apps[settings.packageName] = settings
                        settings.packageName
                    }
                is Source.Category -> resolvePackage(pm, Intent(Intent.ACTION_MAIN).addCategory(source.category))
                is Source.Action -> resolvePackage(
                    pm,
                    // setData and setType each clear the other.
                    Intent(source.action).setDataAndType(source.data?.let(Uri::parse), source.type)
                )
            }
        )

        // The screen the install's grid was picked for: a tablet grid gets
        // the tablet layout.
        val places = recipe(DeviceProfile.isWide(context)).map { slot ->
            Place(slot.cell, slot.sources.asSequence().map(::resolve))
        }
        var rank = 0
        return arrange(places, cols, rows, dockCount).mapNotNull { seat ->
            val app = apps[seat.packageName] ?: return@mapNotNull null
            LauncherItem(
                id = UUID.randomUUID().toString(),
                itemType = ItemType.APP,
                container = if (seat.rank != null) ContainerType.DOCK else ContainerType.WORKSPACE,
                rank = if (seat.rank != null) rank++ else 0,
                cellX = seat.x,
                cellY = seat.y,
                label = app.label,
                packageName = app.packageName,
                className = app.className,
                userSerial = app.userSerial
            )
        }
    }

    // Who sits where, apart from the resolving: each place takes the first
    // of its apps that resolved, and an app takes one place only - one
    // package that is both the dialer and the messenger takes a single dock
    // slot rather than two copies of itself. The dock closes up over the
    // places left empty and stops at its slot count; a cell off the grid, or
    // one an earlier place already took, stays empty.
    internal fun arrange(places: List<Place>, cols: Int, rows: Int, dockCount: Int): List<Seat> {
        val placed = HashSet<String>()
        val taken = HashSet<Pair<Int, Int>>()
        var rank = 0
        return places.mapNotNull { place ->
            val pkg = place.apps.firstOrNull { it != null && it != RESOLVER_PACKAGE } ?: return@mapNotNull null
            if (pkg in placed) return@mapNotNull null
            val seat = when (val cell = place.cell) {
                null -> if (rank < dockCount) Seat(pkg, rank = rank++) else null
                else -> {
                    val x = if (cell.first < 0) cols + cell.first else cell.first
                    val y = if (cell.second < 0) rows + cell.second else cell.second
                    if (x in 0 until cols && y in 0 until rows && taken.add(x to y)) Seat(pkg, x = x, y = y) else null
                }
            }
            seat?.also { placed += pkg }
        }
    }

    // The app an intent opens, Launcher3's way: its default, or where there
    // is none and Android would ask, the one preinstalled app among those
    // that could - and nothing when there are several, rather than a guess.
    // What resolves to none of the candidates is the chooser, whatever
    // package a device keeps it in.
    private fun resolvePackage(pm: PackageManager, intent: Intent): String? = runCatching {
        val resolved = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
            ?: return@runCatching null
        val candidates = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo }
        if (candidates.any { it.packageName == resolved.packageName && it.name == resolved.name }) {
            return@runCatching resolved.packageName
        }
        candidates.map { it.packageName }
            .distinct()
            .filter { (pm.getApplicationInfo(it, 0).flags and ApplicationInfo.FLAG_SYSTEM) != 0 }
            .singleOrNull()
    }.getOrNull()
}
