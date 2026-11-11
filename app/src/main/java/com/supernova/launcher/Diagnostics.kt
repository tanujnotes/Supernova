package com.supernova.launcher

import android.content.Context
import android.util.Log
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.coroutines.cancellation.CancellationException

// Failures the app carries on from. A swallowed exception used to leave no
// trace at all, so "tapping it does nothing" had nothing behind it. These go
// to logcat, and the first of each kind in a run goes into the crash log as
// well: the one file a tester can send (Settings > About > Crash log).
object Diagnostics {
    private const val TAG = "Supernova"

    // Not SupernovaApp.CRASH_ENTRY: these travel in the shared log without
    // being counted as crashes.
    const val CAUGHT_ENTRY = "--- "

    // A failure on a hot path (a scroll frame, a bind) repeats: logcat hears
    // the first few of a kind, the file only the first.
    private const val LOGCAT_REPEATS = 3
    private const val MAX_LOG_BYTES = 256 * 1024L
    private const val FRAMES = 8

    private val seen = ConcurrentHashMap<String, Int>()

    @Volatile
    private var log: File? = null

    private val writer by lazy {
        Executors.newSingleThreadExecutor { task -> Thread(task, "diagnostics").apply { isDaemon = true } }
    }

    fun install(context: Context) {
        log = SupernovaApp.crashLog(context)
    }

    fun caught(what: String, failure: Throwable) {
        val times = seen.merge("$what/${failure.javaClass.name}", 1, Int::plus) ?: 1
        // Log is a stub under unit tests, and throws there.
        if (times <= LOGCAT_REPEATS) runCatching { Log.w(TAG, what, failure) }
        val file = log ?: return
        if (times != 1) return
        val entry = entry(what, failure, Instant.now())
        writer.execute {
            runCatching { if (file.length() < MAX_LOG_BYTES) file.appendText(entry) }
        }
    }

    // What failed, the exception, and where: the top of the stack plus the
    // first frame of this app's own code, which a framework exception buries.
    internal fun entry(what: String, failure: Throwable, at: Instant): String {
        val frames = failure.stackTrace
        val top = frames.take(FRAMES)
        val own = frames.drop(FRAMES).firstOrNull { it.className.startsWith("com.supernova.") }
            ?.takeIf { top.none { frame -> frame.className.startsWith("com.supernova.") } }
        return buildString {
            append("\n").append(CAUGHT_ENTRY).append(at).append(" caught: ").append(what).append("\n")
            append(failure).append("\n")
            top.forEach { append("    at ").append(it).append("\n") }
            if (own != null) append("    ...\n    at ").append(own).append("\n")
            failure.cause?.let { append("Caused by: ").append(it).append("\n") }
        }
    }
}

// runCatching for a failure worth knowing about: `what` names the action in a
// few words ("open app info"), and the failure is noted before it is handed
// back. A cancelled coroutine stays cancelled, which runCatching forgets:
// around suspend code it turned a cancellation into an ordinary failure.
@Suppress("TooGenericExceptionCaught")
inline fun <T> runLogged(what: String, block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        Diagnostics.caught(what, failure)
        Result.failure(failure)
    }
