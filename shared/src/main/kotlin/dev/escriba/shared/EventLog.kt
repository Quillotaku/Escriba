package dev.escriba.shared

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Registro en memoria de lo que ocurre en la app, visible en pantalla.
 * Una app instalada por ADB no puede leer logcat, así que los diagnósticos
 * tienen que vivir dentro de la propia app.
 */
class EventLog(private val capacity: Int = 300, private val clock: () -> Long = System::currentTimeMillis) {

    data class Entry(val atMs: Long, val tag: String, val message: String) {
        override fun toString(): String = "${TIME.format(Instant.ofEpochMilli(atMs))} [$tag] $message"
    }

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    /** Se invoca con cada entrada nueva; las apps lo usan para persistirla en disco. */
    var sink: ((Entry) -> Unit)? = null

    fun add(tag: String, message: String) {
        val entry = Entry(clock(), tag, message)
        _entries.update { (it + entry).takeLast(capacity) }
        runCatching { sink?.invoke(entry) }
    }

    fun clear() = _entries.update { emptyList() }

    private companion object {
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
    }
}
