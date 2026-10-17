package dev.escriba.mobile.processing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Qué se está procesando ahora mismo, para el panel y la lista de sesiones. */
object ProcessingStatus {
    data class Current(val sessionId: String, val step: String)

    private val _current = MutableStateFlow<Current?>(null)
    val current: StateFlow<Current?> = _current

    internal fun set(sessionId: String, step: String) {
        _current.value = Current(sessionId, step)
    }

    internal fun clear(sessionId: String) {
        if (_current.value?.sessionId == sessionId) _current.value = null
    }
}
