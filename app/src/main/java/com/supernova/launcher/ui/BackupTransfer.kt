package com.supernova.launcher.ui

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.supernova.launcher.R
import com.supernova.launcher.data.imports.PickedBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Backup export and import through the system file picker, for every screen
// that offers them (Settings, the first-run wizard). A field of the activity,
// so both pickers are registered before it starts.
// An import takes Supernova's own backup or another launcher's backup file,
// told apart by what is inside.
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

    // Any file: another launcher's backup has an extension of its own, which
    // the picker knows no type for.
    fun pickImportFile() = importLauncher.launch(arrayOf("*/*"))

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

    // The file is read before anything changes, so an unreadable one fails
    // here; a readable one is confirmed, since it replaces the layout (and,
    // for Supernova's own, every setting).
    private fun import(uri: Uri) {
        val app = activity.applicationContext
        activity.lifecycleScope.launch {
            val read = withContext(NonCancellable) { runCatching { viewModel().readBackupFile(uri) } }
            read.onFailure {
                Toast.makeText(
                    app, app.getString(R.string.backup_import_failed, it.message ?: "unknown"), Toast.LENGTH_LONG
                ).show()
            }
            val picked = read.getOrNull() ?: return@launch
            if (activity.isFinishing || activity.isDestroyed) return@launch
            val effect = activity.getString(
                if (picked is PickedBackup.Own) R.string.import_confirm_own else R.string.import_confirm_other
            )
            val message = displayName(uri)?.let { "$it\n\n$effect" } ?: effect
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.restore_backup_q)
                .setMessage(message)
                .setPositiveButton(R.string.restore) { _, _ -> restore(picked) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun restore(picked: PickedBackup) {
        val app = activity.applicationContext
        activity.lifecycleScope.launch {
            val outcome = withContext(NonCancellable) {
                runCatching {
                    viewModel().importPicked(picked)
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
