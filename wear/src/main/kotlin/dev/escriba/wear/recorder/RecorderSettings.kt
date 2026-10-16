package dev.escriba.wear.recorder

import android.content.Context
import android.media.MediaRecorder
import dev.escriba.shared.audio.VadSensitivity
import dev.escriba.shared.recording.DailyWindow

enum class RecordingMode(val label: String) { CONTINUO("Continuo"), BAJO_DEMANDA("Bajo demanda") }

enum class MicSource(val label: String, val audioSource: Int) {
    VOZ("Voz", MediaRecorder.AudioSource.VOICE_RECOGNITION),
    MIC("Micro", MediaRecorder.AudioSource.MIC),
}

/**
 * Ajustes locales del reloj para la fase 2. En la fase 4 los sustituirán los perfiles que
 * llegan del móvil; de momento se cambian desde la app del reloj.
 */
class RecorderSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("ajustes", Context.MODE_PRIVATE)

    var mode: RecordingMode
        get() = enumOr(prefs.getString(KEY_MODE, null), RecordingMode.BAJO_DEMANDA)
        set(value) = prefs.edit().putString(KEY_MODE, value.name).apply()

    var source: MicSource
        get() = enumOr(prefs.getString(KEY_SOURCE, null), MicSource.VOZ)
        set(value) = prefs.edit().putString(KEY_SOURCE, value.name).apply()

    var sensitivity: VadSensitivity
        get() = enumOr(prefs.getString(KEY_VAD, null), VadSensitivity.MEDIA)
        set(value) = prefs.edit().putString(KEY_VAD, value.name).apply()

    var window: DailyWindow
        get() = prefs.getString(KEY_WINDOW, null)?.let(DailyWindow::parse) ?: DailyWindow.DEFAULT
        set(value) = prefs.edit().putString(KEY_WINDOW, value.toString().replace('–', '-')).apply()

    /** Franja de prueba (botón de diagnóstico): tiene prioridad sobre la de la configuración. */
    var windowOverride: DailyWindow?
        get() = prefs.getString(KEY_WINDOW_OVERRIDE, null)?.let(DailyWindow::parse)
        set(value) = prefs.edit().putString(KEY_WINDOW_OVERRIDE, value?.toString()?.replace('–', '-')).apply()

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default

    private companion object {
        const val KEY_MODE = "modo"
        const val KEY_SOURCE = "fuente"
        const val KEY_VAD = "vad"
        const val KEY_WINDOW = "horario"
        const val KEY_WINDOW_OVERRIDE = "horario_prueba"
    }
}

/**
 * Lo que el servicio quiere estar haciendo, persistido. Si el proceso muere, al volver a
 * arrancar sabemos que había una grabación en curso y podemos avisar para reanudarla.
 */
class RecordingIntent(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("grabacion", Context.MODE_PRIVATE)

    val active: Boolean get() = prefs.getBoolean(KEY_ACTIVE, false)
    val origin: String? get() = prefs.getString(KEY_ORIGIN, null)

    fun markActive(origin: String) = prefs.edit().putBoolean(KEY_ACTIVE, true).putString(KEY_ORIGIN, origin).commit()

    fun clear() = prefs.edit().clear().commit()

    private companion object {
        const val KEY_ACTIVE = "activa"
        const val KEY_ORIGIN = "origen"
    }
}
