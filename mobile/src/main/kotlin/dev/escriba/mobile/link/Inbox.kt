package dev.escriba.mobile.link

import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import dev.escriba.datalayer.AppLog
import dev.escriba.datalayer.DataLayerLink
import dev.escriba.mobile.data.EscribaDb
import dev.escriba.mobile.data.FragmentEntity
import dev.escriba.mobile.data.SessionEntity
import dev.escriba.shared.Ack
import dev.escriba.shared.EscribaJson
import dev.escriba.shared.FragmentHeader
import dev.escriba.shared.Framing
import dev.escriba.shared.Protocol
import dev.escriba.shared.SessionEnd
import dev.escriba.shared.SessionEndAck
import dev.escriba.shared.WatchStatus
import dev.escriba.shared.crypto.Crypto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Recibe lo que manda el reloj: fragmentos por canal, finales de sesión y estado.
 *
 * Un fragmento solo se confirma (ACK) cuando su audio está escrito en disco y registrado en la
 * base de datos: hasta entonces el reloj lo conserva. Recibir dos veces el mismo fragmento no
 * duplica nada; simplemente se vuelve a confirmar.
 */
class Inbox(
    private val root: File,
    private val link: DataLayerLink,
    private val db: EscribaDb,
    /** Se llama cuando una sesión tiene todos sus fragmentos y su final: es el momento de escribir las notas. */
    private val onSessionComplete: (sessionId: String) -> Unit,
) {
    private val mutex = Mutex()

    private val _watchStatus = MutableStateFlow<WatchStatus?>(null)
    val watchStatus: StateFlow<WatchStatus?> = _watchStatus

    init {
        link.handle(Protocol.PATH_SESSION_END) { onSessionEnd(it) }
        link.handle(Protocol.PATH_STATUS) { event -> _watchStatus.value = link.openSealed<WatchStatus>(event) }
    }

    suspend fun onChannel(channel: ChannelClient.Channel) {
        if (channel.path != Protocol.PATH_FRAGMENT) return
        val paired = link.keys.peer.value
        if (paired == null || channel.nodeId != paired.nodeId) {
            AppLog.e(TAG, "Canal de un nodo no emparejado; se ignora")
            runCatching { link.channels.close(channel).await() }
            return
        }
        val secure = link.keys.channel() ?: return
        val started = System.currentTimeMillis()
        val bytes = link.channels.getInputStream(channel).await().use { it.readBytes() }
        runCatching { link.channels.close(channel).await() }

        val input = ByteArrayInputStream(bytes)
        val header: FragmentHeader = EscribaJson.decodeFromString(secure.open(Protocol.SEAL_FRAGMENT_HEADER, Framing.readHeader(input)).decodeToString())
        val audio = secure.open(Protocol.sealFragmentAudio(header.key), input.readBytes())
        val sha = Crypto.hex(Crypto.sha256(audio))
        if (sha != header.sha256 || audio.size.toLong() != header.sizeBytes) {
            AppLog.e(TAG, "Fragmento ${header.key} dañado (hash o tamaño); no se confirma")
            return
        }

        mutex.withLock { store(header, audio) }
        link.sendSealed(Protocol.PATH_ACK, Ack(header.sessionId, header.seq, header.sha256))
        AppLog.i(TAG, "Fragmento ${header.sessionId.take(8)}#${header.seq} recibido: ${audio.size / 1024} KB en ${System.currentTimeMillis() - started} ms")
    }

    private suspend fun store(header: FragmentHeader, audio: ByteArray) {
        val dao = db.sessions()
        val existing = dao.fragment(header.sessionId, header.seq)
        if (existing != null) {
            if (existing.sha256 != header.sha256) AppLog.e(TAG, "Fragmento ${header.key} repetido con otro contenido; se conserva el primero")
            return
        }
        val dir = File(root, header.sessionId).apply { mkdirs() }
        val file = File(dir, "${header.seq}.ogg")
        val tmp = File(dir, "${header.seq}.ogg.tmp")
        FileOutputStream(tmp).use {
            it.write(audio)
            it.fd.sync()
        }
        check(tmp.renameTo(file)) { "No se pudo guardar ${file.name}" }

        val now = System.currentTimeMillis()
        val speechMs = header.segments.sumOf { it.durationMs }
        dao.addFragment(
            FragmentEntity(
                sessionId = header.sessionId,
                seq = header.seq,
                startMs = header.startMs,
                endMs = header.endMs,
                segmentsJson = EscribaJson.encodeToString(header.segments),
                speechMs = speechMs,
                sizeBytes = header.sizeBytes,
                sha256 = header.sha256,
                path = file.absolutePath,
                receivedAtMs = now,
            ),
            SessionEntity(
                id = header.sessionId,
                profileId = header.profileId,
                profileHash = header.profileHash,
                startMs = header.sessionStartMs,
                lastSpeechEndMs = header.endMs,
                updatedAtMs = now,
            ),
        )
        reportIfComplete(header.sessionId)
    }

    private suspend fun onSessionEnd(event: MessageEvent) {
        val end: SessionEnd = link.openSealed(event)
        mutex.withLock {
            val dao = db.sessions()
            val now = System.currentTimeMillis()
            val session = dao.session(end.sessionId) ?: SessionEntity(
                id = end.sessionId, profileId = "", profileHash = "", startMs = end.endMs, lastSpeechEndMs = end.endMs, updatedAtMs = now,
            )
            dao.upsertSession(session.copy(endMs = end.endMs, expectedFragments = end.totalFragments, updatedAtMs = now))
            reportIfComplete(end.sessionId)
        }
        link.sendSealed(Protocol.PATH_SESSION_END_ACK, SessionEndAck(end.sessionId))
    }

    private suspend fun reportIfComplete(sessionId: String) {
        val s = db.sessions().session(sessionId) ?: return
        if (s.isComplete && s.processedAtMs == null) {
            AppLog.i(TAG, "Sesión ${sessionId.take(8)} completa: ${s.receivedFragments} fragmentos, ${s.speechMs / 1000} s de voz")
            onSessionComplete(sessionId)
        }
    }

    /** Borra una sesión del móvil: su audio y sus registros. */
    suspend fun delete(sessionId: String) = mutex.withLock {
        File(root, sessionId).deleteRecursively()
        db.sessions().delete(sessionId)
        AppLog.i(TAG, "Sesión ${sessionId.take(8)} borrada del móvil")
    }

    private companion object {
        const val TAG = "recepcion"
    }
}
