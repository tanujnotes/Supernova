package com.supernova.launcher.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppItemEditsTest {

    private val key = "0/com.a/com.a.Main"
    private val drawerIcon = AppOverride(iconPackPackage = "com.pack", iconDrawableName = "alexa")

    private fun app(id: String, container: ContainerType = ContainerType.WORKSPACE, edit: AppOverride? = null) =
        LauncherItem(
            id = id, itemType = ItemType.APP, container = container, label = "Settings",
            packageName = "com.a", className = "com.a.Main", appEdit = edit
        )

    @Test
    fun aCopyWithNoIconOfItsOwnDrawsTheDrawersIcon() {
        val shown = AppItemEdits.effective(app("a"), mapOf(key to drawerIcon.copy(customLabel = "Drawer")))

        // The icon follows the drawer; the drawer's name doesn't.
        assertEquals(drawerIcon, shown)
    }

    @Test
    fun aCopysOwnIconWinsOverTheDrawers() {
        val own = AppOverride(customLabel = "Home", iconPackPackage = "com.pack", iconDrawableName = "food")
        val shown = AppItemEdits.effective(app("a", edit = own), mapOf(key to drawerIcon))

        assertEquals(own, shown)
    }

    @Test
    fun aCopysOwnShapeAloneIsItsOwnIcon() {
        val own = AppOverride(iconShape = "CIRCLE")
        assertEquals(own, AppItemEdits.effective(app("a", edit = own), mapOf(key to drawerIcon)))
    }

    @Test
    fun aCopysNameAndHiddenLabelGoWithTheDrawersIcon() {
        val copy = app("a", edit = AppOverride(customLabel = "Mine", hideLabel = true))
        val shown = AppItemEdits.effective(copy, mapOf(key to drawerIcon))

        assertEquals(drawerIcon.copy(customLabel = "Mine", hideLabel = true), shown)
    }

    @Test
    fun nothingChangedEitherWayIsNull() {
        assertNull(AppItemEdits.effective(app("a"), emptyMap()))
        assertNull(AppItemEdits.effective(app("a").copy(itemType = ItemType.FOLDER), mapOf(key to drawerIcon)))
    }

    @Test
    fun savingTheDrawersIconUnchangedKeepsFollowingIt() {
        val stored = AppItemEdits.stored(drawerIcon.copy(customLabel = "Home"), drawerIcon)

        assertEquals(AppOverride(customLabel = "Home"), stored)
        assertNull(AppItemEdits.stored(drawerIcon, drawerIcon))
    }

    @Test
    fun savingAnotherIconMakesItTheCopysOwn() {
        val edit = drawerIcon.copy(iconShape = "CIRCLE")
        assertEquals(edit, AppItemEdits.stored(edit, drawerIcon))
        // The default icon over a drawer's custom one is the copy's own pick.
        assertEquals(AppOverride(hideLabel = true), AppItemEdits.stored(AppOverride(hideLabel = true), null))
    }

    @Test
    fun aNewlyPlacedCopyTakesTheDrawersHiddenLabel() {
        val before = LauncherModel(
            items = listOf(app("old")), appOverrides = mapOf(key to AppOverride(hideLabel = true))
        )
        val after = before.copy(items = before.items + app("new"))

        val stamped = AppItemEdits.stampNew(before, after).items.associateBy { it.id }

        assertEquals(AppOverride(hideLabel = true), stamped.getValue("new").appEdit)
        // One already placed keeps what it has.
        assertNull(stamped.getValue("old").appEdit)
    }

    @Test
    fun aNewlyPlacedCopyWithItsOwnEditKeepsIt() {
        val before = LauncherModel(appOverrides = mapOf(key to AppOverride(hideLabel = true)))
        val own = AppOverride(customLabel = "Mine")
        val after = before.copy(items = listOf(app("new", edit = own)))

        assertEquals(own, AppItemEdits.stampNew(before, after).items.single().appEdit)
    }

    @Test
    fun v5LayoutsGivePlacedCopiesTheSharedNameAndHiddenLabel() {
        val v5 = LauncherModel(
            version = 5,
            items = listOf(
                app("home"),
                app("dock", ContainerType.DOCK),
                app("inFolder", ContainerType.FOLDER),
                app("own", edit = AppOverride(customLabel = "Mine"))
            ),
            appOverrides = mapOf(key to drawerIcon.copy(customLabel = "Renamed", hideLabel = true))
        )

        val migrated = ModelMigrations.migrate(v5)
        val items = migrated.items.associateBy { it.id }

        assertEquals(CURRENT_MODEL_VERSION, migrated.version)
        assertEquals(AppOverride(customLabel = "Renamed", hideLabel = true), items.getValue("home").appEdit)
        assertEquals(AppOverride(customLabel = "Renamed", hideLabel = true), items.getValue("dock").appEdit)
        // A folder showed the item's own label all along.
        assertNull(items.getValue("inFolder").appEdit)
        assertEquals(AppOverride(customLabel = "Mine", hideLabel = true), items.getValue("own").appEdit)
        // Icons aren't copied: the copies keep drawing the drawer's.
        assertEquals(v5.appOverrides, migrated.appOverrides)
        assertEquals(
            drawerIcon.copy(customLabel = "Renamed", hideLabel = true),
            AppItemEdits.effective(items.getValue("home"), migrated.appOverrides)
        )
    }

    @Test
    fun v5LayoutsWithoutSharedNamesAreUnchanged() {
        val v5 = LauncherModel(version = 5, items = listOf(app("home")), appOverrides = mapOf(key to drawerIcon))
        assertEquals(v5.copy(version = CURRENT_MODEL_VERSION), ModelMigrations.migrate(v5))
    }

    @Test
    fun resetCustomIconsLeavesCopiesUnlessHomeScreenAppsAreTicked() {
        val model = LauncherModel(
            items = listOf(
                app("a", edit = AppOverride(customLabel = "Home", iconBase64 = "PIC")).copy(spanX = 2, spanY = 2)
            ),
            appOverrides = mapOf(key to drawerIcon)
        )

        assertEquals(CustomIcons.Count(apps = 1, folders = 0, placedApps = 1), CustomIcons.count(model))
        val drawerOnly = CustomIcons.reset(model, setOf(CustomIcons.Kind.APPS))
        assertEquals(model.items, drawerOnly.items)
        assertEquals(emptyMap<String, AppOverride>(), drawerOnly.appOverrides)

        val placed = CustomIcons.reset(model, setOf(CustomIcons.Kind.PLACED_APPS)).items.single()
        assertEquals(AppOverride(customLabel = "Home"), placed.appEdit)
        // A large icon stays large.
        assertEquals(2, placed.spanX)
    }

    @Test
    fun resetHiddenLabelsShowsCopiesLabelsToo() {
        val model = LauncherModel(
            items = listOf(
                app("hidden", edit = AppOverride(hideLabel = true)),
                app("iconToo", edit = AppOverride(hideLabel = true, iconBase64 = "PIC"))
            )
        )

        assertEquals(HiddenLabels.Count(apps = 2, shortcuts = 0), HiddenLabels.count(model))
        val items = HiddenLabels.reset(model).items.associateBy { it.id }
        assertNull(items.getValue("hidden").appEdit)
        assertEquals(AppOverride(iconBase64 = "PIC"), items.getValue("iconToo").appEdit)
    }

    @Test
    fun aCopysEditSurvivesABackup() {
        val json = Json { ignoreUnknownKeys = true }
        val model = LauncherModel(
            version = CURRENT_MODEL_VERSION,
            items = listOf(
                app("a", edit = AppOverride(customLabel = "Home", iconDrawableName = "x", iconPackPackage = "p"))
            )
        )

        assertEquals(model, ModelMigrations.migrate(json.decodeFromString<LauncherModel>(json.encodeToString(model))))
    }
}
