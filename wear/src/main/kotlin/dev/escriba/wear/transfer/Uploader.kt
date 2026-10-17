package dev.escriba.wear.transfer

import android.content.Context
import dev.escriba.datalayer.AppLog
import dev.escriba.datalayer.DataLayerLink
import dev.escriba.datalayer.Peer
import dev.escriba.shared.Ack
import dev.escriba.shared.EscribaJson
import dev.escriba.shared.FragmentHeader
import dev.escriba.shared.Framing
import dev.escriba.shared.Protocol
import dev.escriba.shared.SessionEnd
import dev.escriba.shared.SessionEndAck
import dev.escriba.shared.WatchStatus
import dev.escriba.shared.crypto.SecureChannel
import dev.escriba.shared.recording.UploadPolicy
import dev.escriba.wear.recorder.BatteryLog
import dev.escriba.wear.recorder.RecordingState
import dev.escriba.wear.storage.FragmentStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

/**
 * Sends pending fragments and session ends to the phone, one at a time: opens a channel, sends
 * `[sealed header][sealed audio]`, waits for the ACK and only then deletes the fragment from the
 * watch. If anything fails, the fragment stays for the next attempt.
 */
class Uploader(
    private val context: Context,
    private val link: DataLayerLink,
    private val store: FragmentStore,
) {
    enum class Outcome { NOTHING_TO_DO, WAITING_FOR_BATCH, NOT_PAIRED, PHONE_NOT_NEARBY, DONE, PARTIAL }

    data class Stats(
        val lastAttemptMs: Long = 0,
        val lastSuccessMs: Long = 0,
        val lastOutcome: Outcome? = null,
        val sentFragments: Long = 0,
        val sentBytes: Long = 0,
        val failures: Long = 0,
        val lastKbps: Long = 0,
    )

    private val mutex = Mutex()
    private val waitingAcks = ConcurrentHashMap<String, CompletableDeferred<Ack>>()
    private val waitingEndAcks = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats

    init {
        link.handle(Protocol.PATH_ACK) { event ->
            val ack: Ack = link.openSealed(event)
            waitingAcks.remove("${ack.sessionId}#${ack.seq}")?.complete(ack)
        }
        link.handle(Protocol.PATH_SESSION_END_ACK) { event ->
            val ack: SessionEndAck = link.openSealed(event)
            waitingEndAcks.remove(ack.sessionId)?.complete(Unit)
        }
    }

    suspend fun run(manual: Boolean): Outcome = mutex.withLock {
        val outcome = runLocked(manual)
        _stats.value = _stats.value.copy(lastAttemptMs = System.currentTimeMillis(), lastOutcome = outcome)
        outcome
    }

    private suspend fun runLocked(manual: Boolean): Outcome {
        // Sessions without any fragment don't matter to the phone.
        store.endedSessions().filter { (it.totalFragments ?: 0) == 0 }.forEach { store.deleteEmptySession(it.sessionId) }

        val pending = store.pending()
        val ended = store.endedSessions().filter { (it.totalFragments ?: 0) > 0 }
        if (pending.isEmpty() && ended.isEmpty()) return Outcome.NOTHING_TO_DO

        val battery = BatteryLog.read(context)
        val oldestAge = pending.minOfOrNull { System.currentTimeMillis() - it.endMs } ?: 0
        val go = UploadPolicy.shouldUpload(pending.size, oldestAge, ended.size, battery?.charging == true, manual)
        if (!go) return Outcome.WAITING_FOR_BATCH

        val secure = link.keys.channel() ?: return Outcome.NOT_PAIRED
        val peer = link.nearbyPairedPeer() ?: return Outcome.PHONE_NOT_NEARBY

        var allOk = true
        for (header in pending) {
            val ok = runCatching { sendFragment(peer, secure, header) }
                .onFailure { AppLog.e(TAG, "Failed to send fragment ${header.key.short()}", it) }
                .getOrDefault(false)
            if (!ok) {
                allOk = false
                _stats.value = _stats.value.copy(failures = _stats.value.failures + 1)
                break // the connection is probably gone: it'll be retried later
            }
        }

        if (allOk) {
            val stillPending = store.pending().map { it.sessionId }.toSet()
            for (session in ended.filter { it.sessionId !in stillPending }) {
                val ok = runCatching { sendSessionEnd(session.sessionId, session.totalFragments ?: 0, session.endMs ?: 0) }
                    .onFailure { AppLog.e(TAG, "Failed to send the session end", it) }
                    .getOrDefault(false)
                if (!ok) {
                    allOk = false
                    break
                }
            }
        }

        sendStatus()
        if (allOk) _stats.value = _stats.value.copy(lastSuccessMs = System.currentTimeMillis())
        return if (allOk) Outcome.DONE else Outcome.PARTIAL
    }

    private suspend fun sendFragment(peer: Peer, secure: SecureChannel, header: FragmentHeader): Boolean {
        val audio = store.audioFile(header).readBytes()
        val ack = CompletableDeferred<Ack>().also { waitingAcks[header.key] = it }
        val started = System.currentTimeMillis()
        val channel = link.channels.openChannel(peer.id, Protocol.PATH_FRAGMENT).await()
        try {
            link.channels.getOutputStream(channel).await().use { out ->
                Framing.writeHeader(out, secure.seal(Protocol.SEAL_FRAGMENT_HEADER, EscribaJson.encodeToString(header).encodeToByteArray()))
                out.write(secure.seal(Protocol.sealFragmentAudio(header.key), audio))
                out.flush()
            }
            val received = withTimeout(ACK_TIMEOUT_MS) { ack.await() }
            if (received.sha256 != header.sha256) {
                AppLog.e(TAG, "ACK with a different hash for ${header.key.short()}; not deleted")
                return false
            }
        } finally {
            waitingAcks.remove(header.key)
            runCatching { link.channels.close(channel).await() }
        }
        val elapsed = (System.currentTimeMillis() - started).coerceAtLeast(1)
        val kbps = audio.size * 8 / elapsed
        store.delete(header)
        _stats.value = _stats.value.copy(
            sentFragments = _stats.value.sentFragments + 1,
            sentBytes = _stats.value.sentBytes + audio.size,
            lastKbps = kbps,
        )
        AppLog.i(TAG, "Fragment ${header.key.short()} delivered: ${audio.size / 1024} KB in $elapsed ms ($kbps kbps)")
        return true
    }

    private suspend fun sendSessionEnd(sessionId: String, total: Int, endMs: Long): Boolean {
        val ack = CompletableDeferred<Unit>().also { waitingEndAcks[sessionId] = it }
        try {
            if (!link.sendSealed(Protocol.PATH_SESSION_END, SessionEnd(sessionId, total, endMs))) return false
            withTimeout(ACK_TIMEOUT_MS) { ack.await() }
        } finally {
            waitingEndAcks.remove(sessionId)
        }
        store.markEndAcked(sessionId)
        AppLog.i(TAG, "End of session ${sessionId.take(8)} delivered ($total fragments)")
        return true
    }

    private suspend fun sendStatus() {
        val stats = store.stats()
        val battery = BatteryLog.read(context)
        runCatching {
            link.sendSealed(
                Protocol.PATH_STATUS,
                WatchStatus(
                    phase = RecordingState.phase.value.name,
                    pendingFragments = stats.fragments,
                    pendingBytes = stats.bytes,
                    batteryPct = battery?.level,
                    charging = battery?.charging == true,
                    atMs = System.currentTimeMillis(),
                ),
            )
        }
    }

    private fun String.short() = substringBefore('-') + "#" + substringAfter('#')

    companion object {
        private const val TAG = "upload"
        private const val ACK_TIMEOUT_MS = 30_000L
    }
}
