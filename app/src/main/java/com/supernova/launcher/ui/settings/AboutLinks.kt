package com.supernova.launcher.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.core.net.toUri
import com.supernova.launcher.R
import com.supernova.launcher.SupernovaApp

// About's links and its What's new text.
object AboutLinks {

    // A browser for the link, or a short note when the phone has none.
    fun open(context: Context, url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.about_no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    // A new GitHub issue on the bug report form, its version and device
    // fields filled in (the form reads field values from the query by their
    // ids in .github/ISSUE_TEMPLATE/bug_report.yml). The person sees all of
    // it before anything is sent.
    fun newIssueUrl(context: Context): String {
        val version = SupernovaApp.versionLabel(context).orEmpty()
        val device = "${Build.MANUFACTURER} ${Build.MODEL}, " +
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        return context.getString(R.string.about_source_url) + "/issues/new?template=bug_report.yml" +
            "&version=" + android.net.Uri.encode(version) +
            "&device=" + android.net.Uri.encode(device)
    }

    // One line a report needs: Supernova's version, Android's version and
    // the phone's maker and model.
    fun deviceDetails(context: Context): String = context.getString(
        R.string.about_device_details,
        SupernovaApp.versionLabel(context).orEmpty(),
        Build.VERSION.RELEASE,
        Build.VERSION.SDK_INT,
        "${Build.MANUFACTURER} ${Build.MODEL}"
    )

    // The version row's tap and long press (#279), for a report started on GitHub
    // rather than from Report an issue. Android 13+ confirms a copy itself.
    fun copyDeviceDetails(context: Context) {
        context.getSystemService(android.content.ClipboardManager::class.java)
            ?.setPrimaryClip(android.content.ClipData.newPlainText("Supernova", deviceDetails(context)))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
        }
    }

    // This version's release notes, or the newest the app carries (a debug
    // build ahead of its notes). Null when it carries none.
    fun releaseNotes(context: Context): String? {
        val notes = runCatching { context.assets.list("") }.getOrNull().orEmpty()
            .mapNotNull { name -> NOTES.matchEntire(name)?.groupValues?.get(1)?.toLongOrNull()?.let { it to name } }
            .sortedByDescending { it.first }
        val code = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info)
        }.getOrDefault(0L)
        val name = notes.firstOrNull { it.first == code }?.second ?: notes.firstOrNull()?.second ?: return null
        return runCatching { context.assets.open(name).bufferedReader().use { it.readText().trim() } }.getOrNull()
    }

    private val NOTES = Regex("""(\d+)\.txt""")
}
