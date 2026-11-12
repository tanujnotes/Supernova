package com.supernova.launcher

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.supernova.launcher.data.imports.BackupFiles
import com.supernova.launcher.data.imports.ForeignKind
import com.supernova.launcher.data.imports.ForeignPlace
import com.supernova.launcher.data.imports.PickedBackup
import com.supernova.launcher.data.imports.UnreadableBackupException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// A picked backup file read on a real device, where the archive and its
// database are: Supernova's own document, and each family of another
// launcher's archive. Files are made in the cache folder and read from there;
// nothing of the launcher's own is read or written.
@RunWith(AndroidJUnit4::class)
class BackupFilesTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "backup-files-test")

    @Before
    fun makeRoom() {
        clear()
        assertTrue(dir.mkdirs())
    }

    @After
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
        dir.delete()
    }

    private fun database(statements: List<String>): ByteArray {
        val file = File(dir, "made.db")
        file.delete()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db -> statements.forEach(db::execSQL) }
        return file.readBytes().also {
            file.delete()
            File(dir, "made.db-journal").delete()
        }
    }

    private fun archive(vararg entries: Pair<String, ByteArray>): Uri {
        val file = File(dir, "picked.backup")
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return Uri.fromFile(file)
    }

    private fun other(uri: Uri) = (BackupFiles.read(context, uri) as PickedBackup.Other).layout

    private val app = "#Intent;action=android.intent.action.MAIN;category=android.intent.category.LAUNCHER;" +
        "component=com.example.mail/.Main;end"

    @Test
    fun ownBackupComesBackAsItsText() {
        val file = File(dir, "own.json")
        file.writeText("  {\"version\":1}")
        val picked = BackupFiles.read(context, Uri.fromFile(file)) as PickedBackup.Own
        assertEquals("  {\"version\":1}", picked.json)
    }

    @Test
    fun placedItemsTableWithHalfCellsIsRead() {
        val db = database(
            listOf(
                "CREATE TABLE favorites (_id INTEGER PRIMARY KEY, title TEXT, intent TEXT, container INTEGER, " +
                    "screen INTEGER, cellX REAL, cellY REAL, spanX REAL, spanY REAL, itemType INTEGER, " +
                    "appWidgetProvider TEXT, icon BLOB, profileId INTEGER DEFAULT -1, rank INTEGER, zOrder INTEGER)",
                "INSERT INTO favorites VALUES (1, 'Mail', '$app', -100, 1, 2.5, 3.0, 1.0, 1.0, 0, NULL, x'010203', -1, 0, 0)",
                "INSERT INTO favorites VALUES (2, 'Tools', NULL, -101, 0, 4.0, 0.0, 1.0, 1.0, 2, NULL, NULL, -1, 0, 0)",
                "INSERT INTO favorites VALUES (3, 'Docs', '$app', 2, 0, 0.0, 0.0, 1.0, 1.0, 0, NULL, NULL, -1, 1, 0)",
                "INSERT INTO favorites VALUES (4, NULL, NULL, -100, 0, 0.0, 4.0, 4.0, 2.0, 4, 'com.w/com.w.P', NULL, -1, 0, 0)",
                "CREATE TABLE allapps (_id INTEGER PRIMARY KEY, componentName TEXT)"
            )
        )
        val prefs = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n" +
            "<string name=\"desktop_grid\">8x6 subgrid</string>\n<int name=\"dock_grid_cols\" value=\"6\" />\n</map>\n"
        val layout = other(
            archive("settings.xml" to prefs.toByteArray(), "notes.txt" to ByteArray(10), "layout.db" to db)
        )
        assertEquals(listOf(1L, 2L, 3L, 4L), layout.items.map { it.id })
        assertEquals(6, layout.grid.cols)
        assertEquals(8, layout.grid.rows)
        assertEquals(6, layout.grid.dockSlots)
        assertTrue(layout.grid.subgrid)
        val mail = layout.items[0]
        assertEquals(2.5f, mail.x)
        assertEquals(1, mail.page)
        assertEquals("com.example.mail.Main", mail.intent?.component?.second)
        assertEquals(3, mail.icon?.size)
        assertEquals(ForeignPlace.DOCK, layout.items[1].place)
        assertEquals(2L, layout.items[2].parentId)
        assertEquals("com.w/com.w.P", layout.items[3].widgetProvider)
    }

    @Test
    fun placedItemsTableWithWholeCellsIsRead() {
        val db = database(
            listOf(
                "CREATE TABLE favorites (_id INTEGER PRIMARY KEY, title TEXT, intent TEXT, container INTEGER, " +
                    "screen INTEGER, cellX INTEGER, cellY INTEGER, spanX INTEGER, spanY INTEGER, itemType INTEGER, " +
                    "appWidgetId INTEGER NOT NULL DEFAULT -1, icon BLOB, appWidgetProvider TEXT, " +
                    "profileId INTEGER DEFAULT 0, rank INTEGER NOT NULL DEFAULT 0)",
                "INSERT INTO favorites (_id, title, intent, container, screen, cellX, cellY, spanX, spanY, itemType) " +
                    "VALUES (1, 'Mail', '$app', -101, 2, 2, 0, 1, 1, 0)",
                "INSERT INTO favorites (_id, title, intent, container, screen, cellX, cellY, spanX, spanY, itemType) " +
                    "VALUES (2, 'Mail', '$app', -100, 7, 1, 2, 1, 1, 0)"
            )
        )
        // The backup's details: field 7 holds the grid, "columns,rows" and
        // the dock's slots.
        val grid = byteArrayOf(0x0a, 3) + "4,6".toByteArray() + byteArrayOf(0x10, 5)
        val info = byteArrayOf(0x3a, grid.size.toByte()) + grid
        val layout = other(archive("info.pb" to info, "wallpaper.png" to ByteArray(64), "launcher.db" to db))
        assertEquals(4, layout.grid.cols)
        assertEquals(6, layout.grid.rows)
        assertEquals(5, layout.grid.dockSlots)
        assertEquals(2f, layout.items[0].x)
        assertEquals(7, layout.items[1].page)
        assertEquals(0L, layout.items[1].userSerial)
    }

    @Test
    fun splitTablesAreRead() {
        val db = database(
            listOf(
                "CREATE TABLE HomeItem (id INTEGER PRIMARY KEY, idLaunchable INTEGER, idWidget INTEGER, screen INTEGER, " +
                    "configId INTEGER, cellX REAL, cellY REAL, spanX REAL, spanY REAL, zIndex INTEGER)",
                "CREATE TABLE Launchable (id INTEGER PRIMARY KEY, iconGroupId INTEGER, idParentFolderLaunchable INTEGER, " +
                    "position INTEGER)",
                "CREATE TABLE Action (idLaunchable INTEGER, actionId INTEGER, type INTEGER, intentUri TEXT, userId INTEGER, " +
                    "label TEXT, deepShortcutId TEXT)",
                "CREATE TABLE Widget (id INTEGER PRIMARY KEY, type INTEGER, appWidgetId INTEGER, parentId INTEGER, " +
                    "positionIndex INTEGER, provider TEXT, userId INTEGER)",
                "INSERT INTO Widget VALUES (1, 4, NULL, NULL, -1, 'com.launcher/com.launcher.ClockWidget', -1)",
                "INSERT INTO Widget VALUES (2, 4, 31, NULL, -1, 'com.w/com.w.P', 0)",
                "INSERT INTO HomeItem VALUES (3, NULL, 1, 0, 1, 0.0, 0.0, 4.0, 3.0, 0)",
                "INSERT INTO HomeItem VALUES (4, NULL, 2, 0, 1, 0.0, 3.0, 4.0, 1.0, 0)",
                "INSERT INTO HomeItem VALUES (1, 10, NULL, 0, 1, 1.0, 2.0, 1.0, 1.0, 0)",
                "INSERT INTO HomeItem VALUES (2, 11, NULL, 0, 1, 3.0, 2.0, 1.0, 1.0, 0)",
                "INSERT INTO Launchable VALUES (10, 0, NULL, -1)",
                "INSERT INTO Launchable VALUES (11, 0, NULL, -1)",
                "INSERT INTO Launchable VALUES (12, 0, 11, 0)",
                "INSERT INTO Action VALUES (10, 1, 7, '$app', 0, 'Mail', NULL)",
                "INSERT INTO Action VALUES (11, 1, 9, '#Intent;action=open.folder;end', -1, 'Work', NULL)",
                "INSERT INTO Action VALUES (12, 1, 7, '$app', 0, 'Docs', NULL)"
            )
        )
        val prefs = "<map><string name=\"launcher_profile_#0\">" +
            "{&quot;gridUserSettings&quot;:{&quot;columns&quot;:5,&quot;rows&quot;:7}}</string></map>"
        val layout = other(archive("databases/home.db" to db, "shared_prefs/settings.xml" to prefs.toByteArray()))
        assertEquals(listOf("Mail", "Work", "Docs", ""), layout.items.map { it.title })
        assertEquals("com.w/com.w.P", layout.items[3].widgetProvider)
        assertEquals(1, layout.unread)
        assertEquals(ForeignKind.FOLDER, layout.items[1].kind)
        assertEquals(layout.items[1].id, layout.items[2].parentId)
        assertEquals(5, layout.grid.cols)
        assertEquals(7, layout.grid.rows)
    }

    @Test
    fun anArchiveWithoutALayoutIsRefused() {
        val db = database(listOf("CREATE TABLE notes (id INTEGER PRIMARY KEY, body TEXT)"))
        assertThrows(UnreadableBackupException::class.java) {
            BackupFiles.read(context, archive("notes.db" to db, "broken.db" to ByteArray(100)))
        }
        // Nothing read is left behind.
        assertTrue(context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("backup-import-") })
    }
}
