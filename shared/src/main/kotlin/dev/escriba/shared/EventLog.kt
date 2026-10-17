package dev.escriba.shared

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * In-memory log of what happens in the app, shown on screen.
 * An app installed over ADB can't read logcat, so diagnostics have to live inside the app.
 */
class EventLog(private val capacity: Int = 300, private val clock: () -> Long = System::currentTimeMillis) {

    data class Entry(val atMs: Long, val tag: String, val message: String) {
        override fun toString(): String = "${TIME.format(Instant.ofEpochMilli(atMs))} [$tag] $message"
    }

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    /** Called with every new entry; the apps use it to persist the log to disk. */
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
