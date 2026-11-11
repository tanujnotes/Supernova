package com.supernova.launcher.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.supernova.launcher.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Backup export and import through the system file picker, for every screen
// that offers them (Settings, the first-run wizard). A field of the activity,
// so both pickers are registered before it starts.
//
// Both run to the end even if the screen is recreated meanwhile (a rotation,
// a dark-mode switch while a cloud file downloads): cancelled, an import
// reported itself failed and restored nothing. The file is read and written
// off the main thread: one in a cloud drive downloads as it is opened, and a
// slow network was an ANR.
class BackupTransfer(
    private val activity: ComponentActivity,
    private val viewModel: () -> LauncherViewModel,
    // After a backup has been restored, with the activity still alive.
    private val onImported: () -> Unit = {}
) {
    private val exportLauncher =
        activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) export(uri)
        }

    private val importLauncher =
        activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) import(uri)
        }

    fun pickExportFile() = exportLauncher.launch("supernova-backup-${System.currentTimeMillis()}.json")

    fun pickImportFile() = importLauncher.launch(arrayOf("application/json", "text/plain"))

    private fun export(uri: Uri) {
        val app = activity.applicationContext
        activity.lifecycleScope.launch {
            val outcome = withContext(NonCancellable) {
                runCatching {
                    val payload = viewModel().exportBackup()
                    withContext(Dispatchers.IO) {
                        val stream = checkNotNull(app.contentResolver.openOutputStream(uri)) { "can't open the file" }
                        stream.bufferedWriter().use { it.write(payload) }
                    }
                }
            }
            outcome.onSuccess {
                Toast.makeText(app, R.string.backup_exported, Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(
                    app, app.getString(R.string.backup_export_failed, it.message ?: "unknown"), Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun import(uri: Uri) {
        val app = activity.applicationContext
        activity.lifecycleScope.launch {
            val outcome = withContext(NonCancellable) {
                runCatching {
                    val raw = withContext(Dispatchers.IO) {
                        app.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                    }
                    viewModel().importBackup(raw)
                }
            }
            outcome.onSuccess { result ->
                Toast.makeText(app, result.summary(app.resources), Toast.LENGTH_LONG).show()
                if (!activity.isDestroyed) onImported()
            }.onFailure {
                Toast.makeText(
                    app, app.getString(R.string.backup_import_failed, it.message ?: "unknown"), Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
