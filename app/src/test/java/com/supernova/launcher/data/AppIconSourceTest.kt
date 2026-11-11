package com.supernova.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppIconSourceTest {

    private val chrome = LaunchableApp("com.android.chrome", "com.google.android.apps.chrome.Main", "Chrome", 10L)

    @Test
    fun nameRoundTripsTheSourceApp() {
        val name = AppIconSource.nameFor(chrome)
        assertEquals("10/com.android.chrome/com.google.android.apps.chrome.Main", name)
        val back = AppIconSource.appOf(name)!!
        assertEquals(chrome.packageName, back.packageName)
        assertEquals(chrome.className, back.className)
        assertEquals(chrome.userSerial, back.userSerial)
    }

    @Test
    fun innerClassNamesSurvive() {
        val app = LaunchableApp("com.a", "com.a.Outer\$Inner", "A")
        assertEquals("com.a.Outer\$Inner", AppIconSource.appOf(AppIconSource.nameFor(app))!!.className)
    }

    @Test
    fun malformedNamesResolveToNothing() {
        assertNull(AppIconSource.appOf("com.a/com.a.Main"))
        assertNull(AppIconSource.appOf("x/com.a/com.a.Main"))
        assertNull(AppIconSource.appOf("0//com.a.Main"))
        assertNull(AppIconSource.appOf("0/com.a/"))
    }

    // No installed package can be mistaken for the source.
    @Test
    fun packNameIsNoPackageName() {
        assertTrue(AppIconSource.isAppIcon(AppIconSource.PACK))
        assertFalse(AppIconSource.isAppIcon("com.android.chrome"))
        assertFalse(AppIconSource.isAppIcon(null))
        assertTrue(':' in AppIconSource.PACK)
    }

    @Test
    fun drawerFolderKeepsAnAppIconPick() {
        val group = DrawerGroup(
            "Web", setOf("0/com.a/com.a.Main"), 1, DrawerGroupType.FOLDER,
            iconPack = AppIconSource.PACK, iconName = AppIconSource.nameFor(chrome)
        )
        assertEquals(group, DrawerGroup.decode(group.encode()))
    }

    @Test
    fun resetCustomIconsClearsAppIconPicks() {
        val pick = AppOverride(iconPackPackage = AppIconSource.PACK, iconDrawableName = AppIconSource.nameFor(chrome))
        val model = LauncherModel(
            version = CURRENT_MODEL_VERSION,
            items = listOf(
                LauncherItem(
                    id = "folder", itemType = ItemType.FOLDER, label = "Web",
                    customIconPack = AppIconSource.PACK, customIconName = AppIconSource.nameFor(chrome)
                )
            ),
            appOverrides = mapOf("0/com.b/com.b.Main" to pick)
        )
        assertEquals(CustomIcons.Count(apps = 1, folders = 1), CustomIcons.count(model))
        val reset = CustomIcons.reset(model)
        assertTrue(reset.appOverrides.isEmpty())
        assertNull(reset.items.single().customIconPack)
        assertNull(reset.items.single().customIconName)
    }
}
