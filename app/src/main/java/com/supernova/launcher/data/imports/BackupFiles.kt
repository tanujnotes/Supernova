package com.supernova.launcher.data.imports

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

// A picked backup file, told apart by what is in it: Supernova's own backup
// is a JSON document, another launcher's an archive of its layout database
// and preferences.
internal sealed interface PickedBackup {
    class Own(val json: String) : PickedBackup
    class Other(val layout: ForeignLayout) : PickedBackup
}

// The file is no backup this can read, or too big to be one.
internal class UnreadableBackupException(message: String) : Exception(message)

internal object BackupFiles {

    fun read(context: Context, uri: Uri): PickedBackup {
        // The picker's own provider can refuse a file it listed (one outside
        // the folders it may read): said plainly, with what gets around it.
        val raw = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
            ?: throw UnreadableBackupException("can't open the file; a copy of it in Downloads may open")
        BufferedInputStream(raw).use { stream ->
            stream.mark(ARCHIVE_MARK.size)
            val head = ByteArray(ARCHIVE_MARK.size)
            val read = stream.read(head)
            stream.reset()
            if (read == head.size && head.contentEquals(ARCHIVE_MARK)) {
                val layout = BackupLayouts.layout(archive(context, stream))
                    ?: throw UnreadableBackupException("no home screen layout in the file")
                return PickedBackup.Other(layout)
            }
            return PickedBackup.Own(bytes(stream, OWN_LIMIT).toString(Charsets.UTF_8))
        }
    }

    // The archive's layout tables, preferences and details. Only those are
    // read: a wallpaper or a screenshot is passed over, and nothing is
    // written under a name the archive chose.
    private fun archive(context: Context, stream: InputStream): BackupContents {
        val dir = File(context.cacheDir, "backup-import-${System.nanoTime()}")
        try {
            check(dir.mkdirs()) { "can't make room to read the file" }
            val parts = Parts(dir)
            ZipInputStream(stream).use { zip ->
                generateSequence { zip.nextEntry }.forEachIndexed { index, entry ->
                    if (index >= ENTRY_LIMIT) throw UnreadableBackupException("too many files in the backup")
                    if (!entry.isDirectory) parts.take(entry.name.substringAfterLast('/').lowercase(), zip)
                }
            }
            val tables = LinkedHashMap<String, List<Row>>()
            parts.databases.forEach { file -> tables(file).forEach { (name, rows) -> tables.putIfAbsent(name, rows) } }
            return BackupContents(tables, parts.prefs, parts.info, parts.settings)
        } finally {
            dir.listFiles()?.forEach { it.delete() }
            dir.delete()
        }
    }

    // What is kept of an archive as its entries go by.
    private class Parts(private val dir: File) {
        val databases = ArrayList<File>()
        val prefs = LinkedHashMap<String, String>()
        var info: ByteArray? = null
        var settings: ByteArray? = null

        fun take(name: String, stream: InputStream) {
            when {
                name.endsWith(".db") && databases.size < DATABASE_COUNT -> {
                    val file = File(dir, "layout-${databases.size}.db")
                    file.outputStream().use { copy(stream, it, DATABASE_LIMIT) }
                    databases += file
                }
                name.endsWith(".xml") -> prefs += PrefsXml.parse(bytes(stream, SMALL_LIMIT).toString(Charsets.UTF_8))
                name == "info.pb" -> info = bytes(stream, SMALL_LIMIT)
                name.endsWith(".preferences_pb") -> settings = bytes(stream, SMALL_LIMIT)
            }
        }
    }

    // Every layout table of the database, by lowercase name; none for a
    // file that is no database.
    private fun tables(file: File): Map<String, List<Row>> = runCatching {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val names = db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { cursor ->
                generateSequence { if (cursor.moveToNext()) cursor.getString(0) else null }.toList()
            }
            names.filter { it.lowercase() in BackupLayouts.TABLES }.associate { name ->
                val quoted = name.replace("\"", "\"\"")
                name.lowercase() to db.rawQuery("SELECT * FROM \"$quoted\" LIMIT $ROW_LIMIT", null).use(::rows)
            }
        }
    }.getOrDefault(emptyMap())

    private fun rows(cursor: Cursor): List<Row> {
        val columns = cursor.columnNames
        val out = ArrayList<Row>(cursor.count)
        while (cursor.moveToNext()) {
            val values = HashMap<String, Any?>(columns.size)
            columns.forEachIndexed { index, column ->
                values[column] = when (cursor.getType(index)) {
                    Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                    Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                    Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
                    Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index)
                    else -> null
                }
            }
            out += Row(values)
        }
        return out
    }

    private fun bytes(stream: InputStream, limit: Long): ByteArray =
        ByteArrayOutputStream().also { copy(stream, it, limit) }.toByteArray()

    private fun copy(from: InputStream, to: java.io.OutputStream, limit: Long) {
        val buffer = ByteArray(BUFFER)
        var total = 0L
        while (true) {
            val count = from.read(buffer)
            if (count < 0) return
            total += count
            if (total > limit) throw UnreadableBackupException("the file is too big for a backup")
            to.write(buffer, 0, count)
        }
    }

    // A zip archive's first bytes.
    private val ARCHIVE_MARK = "PK\u0003\u0004".toByteArray(Charsets.ISO_8859_1)
    private const val BUFFER = 64 * 1024
    private const val OWN_LIMIT = 64L * 1024 * 1024
    private const val DATABASE_LIMIT = 64L * 1024 * 1024
    private const val SMALL_LIMIT = 8L * 1024 * 1024
    private const val DATABASE_COUNT = 6
    private const val ENTRY_LIMIT = 20_000
    private const val ROW_LIMIT = 10_000
}
