package com.supernova.launcher.data

import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Process
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// Labs' Export icons: every launchable app's own icon, before any
// icon pack or shape, as a PNG, and an appfilter.xml mapping each component
// to its drawable - the file an icon pack is built around - so an icon
// theme maker starts from the phone's real apps.
object IconExporter {

    private const val ICON_PX = 192

    // How many apps it wrote.
    fun export(context: Context, out: OutputStream): Int {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val density = context.resources.displayMetrics.densityDpi
        val activities = launcherApps.getActivityList(null, Process.myUserHandle())
            .sortedBy { it.label.toString().lowercase() }
        val taken = mutableSetOf<String>()
        val filter = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n")
        var written = 0
        ZipOutputStream(out.buffered()).use { zip ->
            for (activity in activities) {
                val icon = runCatching { activity.getIcon(density) }.getOrNull() ?: continue
                val component = activity.componentName
                val name = drawableName(activity.label.toString(), component.packageName, taken)
                zip.putNextEntry(ZipEntry("icons/$name.png"))
                render(icon).compress(Bitmap.CompressFormat.PNG, 100, zip)
                zip.closeEntry()
                filter.append("    <item component=\"ComponentInfo{")
                    .append(component.packageName).append('/').append(component.className)
                    .append("}\" drawable=\"").append(name).append("\" />\n")
                written++
            }
            filter.append("</resources>\n")
            zip.putNextEntry(ZipEntry("appfilter.xml"))
            zip.write(filter.toString().toByteArray())
            zip.closeEntry()
        }
        return written
    }

    // An icon pack's drawable names are resource names: lowercase letters,
    // digits and underscores, starting with a letter, never a Java keyword
    // (the resource compiler rejects "switch" or "new"), and each one
    // unique. A label with no Latin letters to keep falls back to the
    // package's last segment.
    fun drawableName(label: String, packageName: String, taken: MutableSet<String>): String {
        fun clean(raw: String) = raw.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        val base = clean(label).ifEmpty { clean(packageName.substringAfterLast('.')) }
            .let { if (it.firstOrNull()?.isLetter() == true && it !in RESERVED) it else "app_$it".trimEnd('_') }
        var name = base
        var suffix = 2
        while (!taken.add(name)) name = "${base}_${suffix++}"
        return name
    }

    // Java's keywords and literals, which R.java can't name a field after.
    private val RESERVED = setOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
        "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
        "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
        "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
        "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
        "volatile", "while", "true", "false", "null"
    )

    private fun render(icon: Drawable): Bitmap =
        Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888).also { bitmap ->
            icon.setBounds(0, 0, ICON_PX, ICON_PX)
            icon.draw(Canvas(bitmap))
        }
}
