package com.supernova.launcher.ui.settings

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.supernova.launcher.data.LauncherPrefs
import com.supernova.launcher.ui.LaunchAnimation
import com.supernova.launcher.ui.ScrollEffect

// What a Preview row renders; each kind maps to one build/tick path in
// SettingsPreviewView. HOME and SEARCH_BAR are drawn over the wallpaper.
enum class PreviewKind { ICON_ROW, HOME, DOCK, FOLDER, DRAWER, SEARCH, SEARCH_BAR, DRAWER_SEARCH_BAR }

// One user-facing setting row. The catalogue declares these; the adapter
// renders them. Values are always read through the latest LauncherPrefs
// snapshot so rows never hold state of their own.
sealed class SettingItem {
    @get:StringRes
    abstract val title: Int
    abstract val visibleIf: (LauncherPrefs) -> Boolean

    // More names the settings search finds this row by: the controls a
    // row holds inside itself, which have no row of their own to match.
    open val searchTitles: List<Int> get() = emptyList()

    // Why the row can't do anything now, or null while it can: a row whose
    // effect waits on a setting elsewhere stays listed, greyed, with a line
    // saying what to turn on, rather than vanishing (blockedWhen).
    var blockedBy: (LauncherPrefs) -> Int? = { null }
        private set

    fun blockedWhen(reason: (LauncherPrefs) -> Int?): SettingItem = apply { blockedBy = reason }

    // The setting that hides the row while it is set one way, or null while
    // the row shows: the page leaves the row out, and the settings search
    // still lists it, says what brings it back and leads there (#133).
    // visibleIf stays for what no setting can change (Android version,
    // work apps), which the search leaves out.
    var hiddenBy: (LauncherPrefs) -> HiddenBy? = { null }
        private set

    fun hiddenWhen(reason: (LauncherPrefs) -> HiddenBy?): SettingItem = apply { hiddenBy = reason }

    fun shownIn(prefs: LauncherPrefs): Boolean = visibleIf(prefs) && hiddenBy(prefs) == null

    // How deep the row sits under the switch it depends on, 0 for none: a
    // Toggle followed by deeper rows opens and closes them like the Label
    // group, and they sit on its band (#227).
    var nestLevel: Int = 0
        private set

    fun nested(level: Int = 1): SettingItem = apply { nestLevel = level }

    // A row's hiding setting: its title, which way it needs to go, and its
    // page when that is not the row's own (0).
    class HiddenBy(@StringRes val setting: Int, val needs: Needs, @StringRes val page: Int = 0) {
        enum class Needs { ON, OFF, OTHER }
    }

    // More words the settings search finds the row by, space-separated in
    // one string ("landscape" finds Screen orientation); 0 for none.
    @StringRes
    var keywords: Int = 0
        private set

    fun searchableBy(@StringRes words: Int): SettingItem = apply { keywords = words }

    // The fixed sentence under a Row's title, for the settings search to
    // match after titles (#218); 0 for none. A Toggle's subtitle counts
    // without this. Never a value that changes with the settings.
    @StringRes
    var description: Int = 0
        private set

    fun describedBy(@StringRes text: Int): SettingItem = apply { description = text }

    // What the settings search reads as the row's description.
    @get:StringRes
    val searchDescription: Int
        get() = (this as? Toggle)?.subtitle?.takeIf { it != 0 } ?: description

    class Toggle(
        @StringRes override val title: Int,
        val get: (LauncherPrefs) -> Boolean,
        val set: (Boolean) -> Unit,
        override val visibleIf: (LauncherPrefs) -> Boolean = { true },
        // Most toggles carry an explanation under the title; 0 = none.
        @StringRes val subtitle: Int = 0,
        // A row that names something of the user's (a feed card): its own
        // title and subtitle text (title 0 keeps it out of search), and a
        // tap on the row that opens it while the switch alone toggles.
        val titleText: CharSequence? = null,
        val subtitleText: ((LauncherPrefs) -> CharSequence?)? = null,
        val onOpen: (() -> Unit)? = null
    ) : SettingItem()

    class Slider(
        @StringRes override val title: Int,
        val min: Int,
        val max: Int,
        val step: Int = 1,
        val get: (LauncherPrefs) -> Int,
        val set: (Int) -> Unit,
        val display: (Int) -> String,
        // Maps an uncommitted drag value onto a prefs snapshot so the section's
        // Preview row can render it without a DataStore round-trip.
        val preview: ((LauncherPrefs, Int) -> LauncherPrefs)? = null,
        override val visibleIf: (LauncherPrefs) -> Boolean = { true },
        // The max while Labs' Big grid size options is on; null = max.
        val bigGridMax: Int? = null,
        // A note under the bar; null = none.
        val note: ((LauncherPrefs) -> CharSequence?)? = null
    ) : SettingItem() {
        fun maxFor(prefs: LauncherPrefs): Int = bigGridMax?.takeIf { prefs.labsBigGrid } ?: max
    }

    // Choice pickers, action buttons, and in-section links all render as a
    // tappable row with an optional current-value subtitle. A row for a
    // user-authored thing (a custom card) carries its name in titleText with
    // title = 0, which keeps it out of the settings search index.
    class Row(
        @StringRes override val title: Int,
        val subtitle: (LauncherPrefs) -> CharSequence? = { null },
        val onClick: () -> Unit,
        override val visibleIf: (LauncherPrefs) -> Boolean = { true },
        val titleText: CharSequence? = null,
        val look: RowLook = RowLook()
    ) : SettingItem() {
        fun withLook(look: RowLook): Row = Row(title, subtitle, onClick, visibleIf, titleText, look)
    }

    // How a Row dresses in its card: the icon in its leading tile, 0 for
    // none; a colour shown as a dot before its value; no chevron for an
    // action that runs on the tap; a warning (a card that needs access),
    // which tints the tile; and a status that reads as good news (a granted
    // permission), which colours the value under the title when it holds.
    // A notice (tappable = false) only explains; it takes no taps.
    class RowLook(
        @DrawableRes val icon: Int = 0,
        val swatch: ((LauncherPrefs) -> Int?)? = null,
        val chevron: Boolean = true,
        val warn: Boolean = false,
        val positive: (() -> Boolean)? = null,
        val tappable: Boolean = true
    )

    // An entry on the root list: a leading icon, a larger title,
    // and its description always under the title (never a value pill).
    class SectionLink(
        @StringRes override val title: Int,
        @DrawableRes val icon: Int,
        val subtitle: (LauncherPrefs) -> CharSequence? = { null },
        val onClick: () -> Unit,
        override val visibleIf: (LauncherPrefs) -> Boolean = { true }
    ) : SettingItem()

    // A group's label, over the card of rows after it. Ends any accordion
    // scope.
    class Header(
        @StringRes override val title: Int,
        override val visibleIf: (LauncherPrefs) -> Boolean = { true }
    ) : SettingItem()

    // A collapsible group toggle, heading a card of its own: the items after
    // it (until the next Header, Accordion, or Preview) hide while it is
    // collapsed.
    class Accordion(
        @StringRes override val title: Int,
        override val visibleIf: (LauncherPrefs) -> Boolean = { true }
    ) : SettingItem()

    // The Label group: a switch in its row shows or hides the labels,
    // and the rest of the row opens a band under it holding their size,
    // font, shadow and single-line options. It opens and closes like an
    // Accordion (same remembered state) but holds its controls itself, so
    // it hides no rows after it.
    class LabelGroup(
        @StringRes override val title: Int,
        val shown: (LauncherPrefs) -> Boolean,
        val setShown: (Boolean) -> Unit,
        val sizeMin: Int,
        val sizeMax: Int,
        val size: (LauncherPrefs) -> Int,
        val setSize: (Int) -> Unit,
        val sizeDisplay: (Int) -> String,
        val font: (LauncherPrefs) -> CharSequence,
        val pickFont: () -> Unit,
        val shadow: (LauncherPrefs) -> Boolean,
        val setShadow: (Boolean) -> Unit,
        val singleLine: (LauncherPrefs) -> Boolean,
        val setSingleLine: (Boolean) -> Unit,
        // As Slider.preview, for the size bar.
        val previewSize: ((LauncherPrefs, Int) -> LauncherPrefs)? = null,
        override val searchTitles: List<Int> = emptyList(),
        override val visibleIf: (LauncherPrefs) -> Boolean = { true }
    ) : SettingItem()

    // A Style-tab target row with a mini strip previewing its resolved
    // surface, text and accent (F6).
    class StyleTarget(
        @StringRes override val title: Int,
        val surface: com.supernova.launcher.ui.theme.SurfaceId,
        val onClick: () -> Unit,
        override val visibleIf: (LauncherPrefs) -> Boolean = { true }
    ) : SettingItem()

    // A live preview header; has no title of its own.
    class Preview(
        val kind: PreviewKind,
        override val visibleIf: (LauncherPrefs) -> Boolean = { true }
    ) : SettingItem() {
        override val title: Int = 0
    }
}

class SettingsSection(
    @StringRes val title: Int,
    val items: List<SettingItem>
)

// A single choice the activity shows for a row, as a menu under the row:
// the title, the labels in order, the one that holds now (-1 for none),
// and what a pick writes. The catalogue builds these; see Actions.pickChoice.
class Choice(
    @StringRes val title: Int,
    val labels: List<CharSequence>,
    val selected: Int,
    val onPick: (Int) -> Unit,
    // A colour per option, drawn as a dot before its label (a colour choice);
    // a null entry is an option with no colour of its own (Default).
    val swatches: List<Int?>? = null,
    // A motion setting's live preview: the choice opens as a sheet that
    // plays the option in force over its list (#260).
    val preview: ChoicePreview<*>? = null
)

// What a Choice's sheet previews: its options, in the order the sheet
// lists them.
sealed class ChoicePreview<T>(val values: List<T>) {
    object PageEffect : ChoicePreview<ScrollEffect>(ScrollEffect.entries)
    object Launch : ChoicePreview<LaunchAnimation>(LaunchAnimation.entries)
}
