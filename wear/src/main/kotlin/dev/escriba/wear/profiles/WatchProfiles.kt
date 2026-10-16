package dev.escriba.wear.profiles

import android.content.Context
import dev.escriba.datalayer.AppLog
import dev.escriba.datalayer.DataLayerLink
import dev.escriba.shared.EscribaJson
import dev.escriba.shared.ProfilesRequest
import dev.escriba.shared.Protocol
import dev.escriba.shared.audio.VadSensitivity
import dev.escriba.shared.recording.DailyWindow
import dev.escriba.shared.vault.ProfileList
import dev.escriba.shared.vault.WatchProfile
import dev.escriba.wear.recorder.RecordingMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Perfiles en el reloj. Los define el móvil (a partir de la bóveda) y llegan cifrados; el reloj
 * guarda la última lista para poder grabar aunque el móvil no esté cerca.
 */
class WatchProfiles(context: Context, private val link: DataLayerLink) {

    private val prefs = context.applicationContext.getSharedPreferences("perfiles", Context.MODE_PRIVATE)

    private val _list = MutableStateFlow(prefs.getString(KEY_LIST, null)?.let { runCatching { EscribaJson.decodeFromString<ProfileList>(it) }.getOrNull() })
    val list: StateFlow<ProfileList?> = _list

    private val _selected = MutableStateFlow(resolveSelected(_list.value, prefs.getString(KEY_SELECTED, null)))
    val selected: StateFlow<WatchProfile?> = _selected

    init {
        link.handle(Protocol.PATH_PROFILES) { event ->
            val received = link.openSealed<ProfileList>(event)
            prefs.edit().putString(KEY_LIST, EscribaJson.encodeToString(received)).apply()
            _list.value = received
            _selected.value = resolveSelected(received, _selected.value?.id)
            AppLog.i(TAG, "Perfiles recibidos: ${received.profiles.joinToString { it.name }} · horario ${received.schedule}")
        }
    }

    fun select(id: String) {
        val profile = _list.value?.profiles?.firstOrNull { it.id == id } ?: return
        prefs.edit().putString(KEY_SELECTED, id).apply()
        _selected.value = profile
    }

    /** Pasa al siguiente perfil (favoritos primero, como los ordena el móvil). */
    fun selectNext() {
        val profiles = _list.value?.profiles.orEmpty()
        if (profiles.isEmpty()) return
        val index = profiles.indexOfFirst { it.id == _selected.value?.id }
        select(profiles[(index + 1) % profiles.size].id)
    }

    suspend fun requestFromPhone(): Boolean =
        runCatching { link.sendSealed(Protocol.PATH_PROFILES_REQUEST, ProfilesRequest(_list.value?.hash)) }.getOrDefault(false)

    /** Ajustes efectivos de una grabación: los del perfil o, si no hay perfiles, los locales. */
    fun modeOf(profile: WatchProfile?, fallback: RecordingMode): RecordingMode = when (profile?.mode) {
        "continuo" -> RecordingMode.CONTINUO
        "bajo_demanda" -> RecordingMode.BAJO_DEMANDA
        else -> fallback
    }

    fun vadOf(profile: WatchProfile?, fallback: VadSensitivity): VadSensitivity =
        profile?.vad?.let { runCatching { VadSensitivity.valueOf(it.uppercase()) }.getOrNull() } ?: fallback

    fun scheduleOr(fallback: DailyWindow): DailyWindow = _list.value?.schedule?.let(DailyWindow::parse) ?: fallback

    private fun resolveSelected(list: ProfileList?, id: String?): WatchProfile? {
        val profiles = list?.profiles ?: return null
        return profiles.firstOrNull { it.id == id } ?: profiles.firstOrNull { it.id == list.defaultId } ?: profiles.firstOrNull()
    }

    private companion object {
        const val TAG = "perfiles"
        const val KEY_LIST = "lista"
        const val KEY_SELECTED = "elegido"
    }
}
