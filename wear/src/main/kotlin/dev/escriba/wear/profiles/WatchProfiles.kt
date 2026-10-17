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
import dev.escriba.shared.vault.ProfileLoader
import dev.escriba.shared.vault.WatchProfile
import dev.escriba.wear.recorder.RecordingMode
import dev.escriba.wear.recorder.RecordingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Profiles on the watch. The phone defines them (from the vault) and they arrive encrypted; the
 * watch keeps the last list so it can record even when the phone isn't nearby.
 */
class WatchProfiles(context: Context, private val link: DataLayerLink) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("profiles", Context.MODE_PRIVATE)

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
            AppLog.i(TAG, "Profiles received: ${received.profiles.joinToString { it.name }} · schedule ${received.schedule}")
            RecordingState.refreshSurfaces(appContext)
        }
    }

    fun select(id: String) {
        val profile = _list.value?.profiles?.firstOrNull { it.id == id } ?: return
        prefs.edit().putString(KEY_SELECTED, id).apply()
        _selected.value = profile
        RecordingState.refreshSurfaces(appContext)
    }

    /** The profile after the chosen one (favourites first, as the phone orders them). */
    fun nextId(): String? {
        val profiles = _list.value?.profiles.orEmpty()
        if (profiles.isEmpty()) return null
        val index = profiles.indexOfFirst { it.id == _selected.value?.id }
        return profiles[(index + 1) % profiles.size].id
    }

    fun selectNext() {
        nextId()?.let(::select)
    }

    /** Profiles for the Tile: the favourites and, if there are slots left, the rest. */
    fun forTile(slots: Int): List<WatchProfile> {
        val profiles = _list.value?.profiles.orEmpty()
        return (profiles.filter { it.favorite } + profiles.filterNot { it.favorite }).take(slots)
    }

    suspend fun requestFromPhone(): Boolean =
        runCatching { link.sendSealed(Protocol.PATH_PROFILES_REQUEST, ProfilesRequest(_list.value?.hash)) }.getOrDefault(false)

    /** Effective settings of a recording: the profile's or, if there are no profiles, the local ones. */
    fun modeOf(profile: WatchProfile?, fallback: RecordingMode): RecordingMode = when (profile?.mode) {
        ProfileLoader.MODE_CONTINUOUS -> RecordingMode.CONTINUOUS
        ProfileLoader.MODE_ON_DEMAND -> RecordingMode.ON_DEMAND
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
        const val TAG = "profiles"
        const val KEY_LIST = "list"
        const val KEY_SELECTED = "selected"
    }
}
