package dev.escriba.datalayer

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.util.Log
import dev.escriba.shared.EventLog
import java.io.File
import java.util.concurrent.Executors

/**
 * The app's diagnostic log: in memory for the UI and in a private file, so it can be read with
 * `adb shell run-as dev.escriba cat files/escriba-log.txt` on debug builds.
 *
 * Log messages are for diagnosing problems, not part of the UI, so they're plain English text
 * rather than string resources.
 */
object AppLog {
    val events = EventLog()

    private const val FILE_NAME = "escriba-log.txt"
    private const val MAX_BYTES = 512 * 1024L
    private val writer = Executors.newSingleThreadExecutor()

    @Volatile
    private var attached = false

    fun attach(context: Context) {
        if (attached) return
        attached = true
        val file = File(context.filesDir, FILE_NAME)
        events.sink = { entry ->
            Log.i("Escriba", entry.toString())
            writer.execute {
                runCatching {
                    if (file.length() > MAX_BYTES) {
                        file.renameTo(File(context.filesDir, "$FILE_NAME.1"))
                    }
                    file.appendText(entry.toString() + "\n")
                }
            }
        }
        logLastExit(context)
    }

    /** Why the previous process died: key to spotting HyperOS/MagicOS kills. */
    private fun logLastExit(context: Context) {
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val last = runCatching { am.getHistoricalProcessExitReasons(null, 0, 1).firstOrNull() }.getOrNull() ?: return
        val reason = when (last.reason) {
            ApplicationExitInfo.REASON_ANR -> "ANR"
            ApplicationExitInfo.REASON_CRASH -> "crash"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
            ApplicationExitInfo.REASON_EXIT_SELF -> "exited itself"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory"
            ApplicationExitInfo.REASON_SIGNALED -> "signal ${last.status}"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "closed by the user"
            ApplicationExitInfo.REASON_USER_STOPPED -> "stopped by the user"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive resource usage"
            ApplicationExitInfo.REASON_FREEZER -> "frozen"
            ApplicationExitInfo.REASON_OTHER -> "other (possibly the manufacturer's battery manager)"
            else -> "code ${last.reason}"
        }
        i("system", "Previous process ended: $reason — ${last.description ?: "no description"}")
    }

    fun i(tag: String, message: String) = events.add(tag, message)

    fun e(tag: String, message: String, error: Throwable? = null) =
        events.add(tag, if (error == null) "ERROR $message" else "ERROR $message: ${error.javaClass.simpleName}: ${error.message}")
}
