package dev.escriba.wear.recorder

import android.content.Context
import android.media.MediaRecorder
import androidx.annotation.StringRes
import dev.escriba.shared.audio.VadSensitivity
import dev.escriba.shared.recording.DailyWindow
import dev.escriba.wear.R

enum class RecordingMode(@StringRes val label: Int) { CONTINUOUS(R.string.mode_continuous), ON_DEMAND(R.string.mode_on_demand) }

enum class MicSource(@StringRes val label: Int, val audioSource: Int) {
    VOICE(R.string.mic_voice, MediaRecorder.AudioSource.VOICE_RECOGNITION),
    MIC(R.string.mic_raw, MediaRecorder.AudioSource.MIC),
}

@get:StringRes
val VadSensitivity.label: Int
    get() = when (this) {
        VadSensitivity.LOW -> R.string.vad_low
        VadSensitivity.MEDIUM -> R.string.vad_medium
        VadSensitivity.HIGH -> R.string.vad_high
    }

/**
 * The watch's local settings. Profiles from the phone take precedence; mode and sensitivity are
 * only used from here while the watch has no profiles yet.
 */
class RecorderSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var mode: RecordingMode
        get() = enumOr(prefs.getString(KEY_MODE, null), RecordingMode.ON_DEMAND)
        set(value) = prefs.edit().putString(KEY_MODE, value.name).apply()

    var source: MicSource
        get() = enumOr(prefs.getString(KEY_SOURCE, null), MicSource.VOICE)
        set(value) = prefs.edit().putString(KEY_SOURCE, value.name).apply()

    var sensitivity: VadSensitivity
        get() = enumOr(prefs.getString(KEY_VAD, null), VadSensitivity.MEDIUM)
        set(value) = prefs.edit().putString(KEY_VAD, value.name).apply()

    var window: DailyWindow
        get() = prefs.getString(KEY_WINDOW, null)?.let(DailyWindow::parse) ?: DailyWindow.DEFAULT
        set(value) = prefs.edit().putString(KEY_WINDOW, value.toString().replace('–', '-')).apply()

    /** Test window (diagnostics button): takes precedence over the configured one. */
    var windowOverride: DailyWindow?
        get() = prefs.getString(KEY_WINDOW_OVERRIDE, null)?.let(DailyWindow::parse)
        set(value) = prefs.edit().putString(KEY_WINDOW_OVERRIDE, value?.toString()?.replace('–', '-')).apply()

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default

    private companion object {
        const val KEY_MODE = "mode"
        const val KEY_SOURCE = "source"
        const val KEY_VAD = "vad"
        const val KEY_WINDOW = "schedule"
        const val KEY_WINDOW_OVERRIDE = "schedule_test"
    }
}

/**
 * What the service wants to be doing, persisted. If the process dies, on the next start we know
 * a recording was in progress and can tell the user so they can resume it.
 */
class RecordingIntent(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("recording", Context.MODE_PRIVATE)

    val active: Boolean get() = prefs.getBoolean(KEY_ACTIVE, false)
    val origin: String? get() = prefs.getString(KEY_ORIGIN, null)

    fun markActive(origin: String) = prefs.edit().putBoolean(KEY_ACTIVE, true).putString(KEY_ORIGIN, origin).commit()

    fun clear() = prefs.edit().clear().commit()

    private companion object {
        const val KEY_ACTIVE = "active"
        const val KEY_ORIGIN = "origin"
    }
}
