package dev.escriba.datalayer

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.util.Log
import dev.escriba.shared.EventLog
import java.io.File
import java.util.concurrent.Executors

/**
 * Registro de diagnóstico de la app: en memoria para la UI y en un archivo
 * privado para poder recuperarlo con `adb shell run-as dev.escriba cat files/escriba-log.txt`.
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

    /** Cuenta por qué murió el proceso anterior: clave para detectar los cierres de HyperOS/MagicOS. */
    private fun logLastExit(context: Context) {
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val last = runCatching { am.getHistoricalProcessExitReasons(null, 0, 1).firstOrNull() }.getOrNull() ?: return
        val reason = when (last.reason) {
            ApplicationExitInfo.REASON_ANR -> "ANR"
            ApplicationExitInfo.REASON_CRASH -> "crash"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash nativo"
            ApplicationExitInfo.REASON_EXIT_SELF -> "salida propia"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "poca memoria"
            ApplicationExitInfo.REASON_SIGNALED -> "señal ${last.status}"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "cerrada por el usuario"
            ApplicationExitInfo.REASON_USER_STOPPED -> "detenida por el usuario"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "uso excesivo de recursos"
            ApplicationExitInfo.REASON_FREEZER -> "congelada"
            ApplicationExitInfo.REASON_OTHER -> "otro (posible gestor de batería del fabricante)"
            else -> "código ${last.reason}"
        }
        i("sistema", "Proceso anterior terminó: $reason — ${last.description ?: "sin descripción"}")
    }

    fun i(tag: String, message: String) = events.add(tag, message)

    fun e(tag: String, message: String, error: Throwable? = null) =
        events.add(tag, if (error == null) "ERROR $message" else "ERROR $message: ${error.javaClass.simpleName}: ${error.message}")
}
