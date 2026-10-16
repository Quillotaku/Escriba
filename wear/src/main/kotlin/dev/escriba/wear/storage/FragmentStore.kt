package dev.escriba.wear.storage

import dev.escriba.datalayer.AppLog
import dev.escriba.shared.EscribaJson
import dev.escriba.shared.FragmentHeader
import dev.escriba.shared.audio.OggReader
import dev.escriba.shared.recording.ClosedFragment
import dev.escriba.shared.recording.FragmentJournal
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Metadatos de una sesión de grabación en el reloj. */
@Serializable
data class SessionMeta(
    val sessionId: String,
    val profileId: String,
    val profileHash: String,
    val startMs: Long,
    val origin: String,
    val mode: String,
    val endMs: Long? = null,
    val totalFragments: Int? = null,
    /** El móvil confirmó que sabe que la sesión terminó. */
    val endAcked: Boolean = false,
)

/**
 * Almacén de fragmentos en el reloj, basado en archivos (sin base de datos):
 *
 * ```
 * fragments/<sesion>/session.json
 *                   /<seq>.ogg.part + <seq>.journal   ← fragmento abierto
 *                   /<seq>.ogg + <seq>.json           ← fragmento cerrado, pendiente de envío
 * ```
 *
 * Cada cambio de estado es un renombrado o una escritura atómica (temporal + rename), así
 * que un corte en cualquier momento deja el almacén en un estado coherente y recuperable.
 * Los fragmentos se borran solo cuando el móvil confirma que los ha recibido (fase 3).
 */
class FragmentStore(private val root: File, private val sampleRate: Int, private val bitrate: Int) {

    data class Stats(val sessions: Int, val fragments: Int, val bytes: Long)

    fun createSession(meta: SessionMeta) {
        val dir = sessionDir(meta.sessionId).apply { mkdirs() }
        writeAtomic(File(dir, SESSION_FILE), EscribaJson.encodeToString(meta))
    }

    fun endSession(sessionId: String, endMs: Long, totalFragments: Int) {
        val meta = readSession(sessionId) ?: return
        writeAtomic(
            File(sessionDir(sessionId), SESSION_FILE),
            EscribaJson.encodeToString(meta.copy(endMs = endMs, totalFragments = totalFragments)),
        )
    }

    fun readSession(sessionId: String): SessionMeta? =
        runCatching { EscribaJson.decodeFromString<SessionMeta>(File(sessionDir(sessionId), SESSION_FILE).readText()) }.getOrNull()

    fun openWriter(sessionId: String, seq: Int): OpusFragmentWriter {
        val dir = sessionDir(sessionId)
        return OpusFragmentWriter(File(dir, "$seq$PART"), File(dir, "$seq$JOURNAL"), sampleRate, bitrate)
    }

    /** Pasa un fragmento abierto a cerrado y devuelve su cabecera definitiva. */
    fun commit(session: SessionMeta, closed: ClosedFragment): FragmentHeader {
        val dir = sessionDir(session.sessionId)
        val part = File(dir, "${closed.seq}$PART")
        val ogg = File(dir, "${closed.seq}$OGG")
        check(part.renameTo(ogg)) { "No se pudo renombrar ${part.name}" }
        val header = FragmentHeader(
            sessionId = session.sessionId,
            seq = closed.seq,
            profileId = session.profileId,
            profileHash = session.profileHash,
            sessionStartMs = session.startMs,
            startMs = closed.startMs,
            endMs = closed.endMs,
            segments = closed.segments,
            sampleRate = sampleRate,
            bitrate = bitrate,
            sha256 = sha256(ogg),
            sizeBytes = ogg.length(),
            last = closed.last,
        )
        writeAtomic(File(dir, "${closed.seq}$HEADER"), EscribaJson.encodeToString(header))
        File(dir, "${closed.seq}$JOURNAL").delete()
        return header
    }

    /**
     * Recupera lo que dejó a medias un proceso anterior: cierra los `.part` truncándolos a la
     * última página Ogg válida y reconstruyendo sus segmentos, y cierra las sesiones sin final.
     * Solo debe llamarse cuando no hay ninguna grabación en curso.
     */
    fun recover(): Int {
        var recovered = 0
        sessionDirs().forEach { dir ->
            val session = readSession(dir.name)
            if (session == null) {
                // Sesión que murió antes de escribir sus metadatos: sin ellos no hay cabeceras posibles.
                val audio = dir.listFiles { f -> f.name.endsWith(OGG) || f.name.endsWith(PART) }.orEmpty()
                if (audio.isEmpty()) dir.deleteRecursively() else AppLog.e(TAG, "Sesión ${dir.name.take(8)} sin session.json con ${audio.size} audios; se conserva")
                return@forEach
            }
            dir.listFiles { f -> f.name.endsWith(PART) }.orEmpty().sortedBy { seqOf(it) }.forEach { part ->
                if (recoverPart(session, part)) recovered++
            }
            if (session.endMs == null) {
                val headers = headersIn(dir)
                endSession(session.sessionId, headers.maxOfOrNull { it.endMs } ?: session.startMs, headers.size)
                AppLog.i(TAG, "Sesión ${session.sessionId.take(8)} cerrada en la recuperación (${headers.size} fragmentos)")
            }
            // Sin audio pendiente, la sesión solo sigue en el reloj si el móvil aún no sabe que terminó.
            val current = readSession(dir.name)
            if (headersIn(dir).isEmpty() && (current?.endAcked == true || (current?.totalFragments ?: 0) == 0)) dir.deleteRecursively()
        }
        return recovered
    }

    private fun recoverPart(session: SessionMeta, part: File): Boolean {
        val seq = seqOf(part) ?: return false
        val journal = File(part.parentFile, "$seq$JOURNAL")
        val result = OggReader.read(part.readBytes())
        val segments = FragmentJournal.recover(journal.takeIf { it.exists() }?.readLines().orEmpty(), result.durationMs)
        if (result.audioPackets.isEmpty() || segments.isEmpty()) {
            part.delete()
            journal.delete()
            AppLog.i(TAG, "Fragmento $seq vacío tras el cierre inesperado; descartado")
            return false
        }
        if (result.validLength < part.length()) {
            RandomAccessFile(part, "rw").use { it.setLength(result.validLength.toLong()) }
        }
        val header = commit(
            session,
            ClosedFragment(
                seq = seq,
                startMs = segments.first().wallStartMs,
                endMs = segments.last().let { it.wallStartMs + it.durationMs },
                segments = segments,
                last = false,
            ),
        )
        AppLog.i(TAG, "Fragmento $seq recuperado: ${result.durationMs} ms, ${header.sizeBytes} B, ${segments.size} segmentos")
        return true
    }

    /** Fragmentos cerrados pendientes de envío, en orden. */
    fun pending(): List<FragmentHeader> = sessionDirs().flatMap { headersIn(it) }.sortedWith(compareBy({ it.sessionStartMs }, { it.seq }))

    /** Sesiones terminadas cuyo final aún no ha confirmado el móvil. */
    fun endedSessions(): List<SessionMeta> =
        sessionDirs().mapNotNull { readSession(it.name) }.filter { it.endMs != null && !it.endAcked }

    /** Borra una sesión terminada sin ningún fragmento: el móvil nunca supo de ella. */
    fun deleteEmptySession(sessionId: String) {
        val dir = sessionDir(sessionId)
        if (headersIn(dir).isEmpty() && dir.listFiles { f -> f.name.endsWith(PART) }.orEmpty().isEmpty()) dir.deleteRecursively()
    }

    /** El móvil confirmó un fragmento: ya puede borrarse del reloj. */
    fun delete(header: FragmentHeader) {
        val dir = sessionDir(header.sessionId)
        File(dir, "${header.seq}$OGG").delete()
        File(dir, "${header.seq}$HEADER").delete()
        cleanup(header.sessionId)
    }

    fun markEndAcked(sessionId: String) {
        val meta = readSession(sessionId) ?: return
        writeAtomic(File(sessionDir(sessionId), SESSION_FILE), EscribaJson.encodeToString(meta.copy(endAcked = true)))
        cleanup(sessionId)
    }

    /** Una sesión desaparece del reloj cuando el móvil tiene su final y todos sus fragmentos. */
    private fun cleanup(sessionId: String) {
        val meta = readSession(sessionId) ?: return
        val dir = sessionDir(sessionId)
        val hasAudio = dir.listFiles { f -> f.name.endsWith(OGG) || f.name.endsWith(PART) }.orEmpty().isNotEmpty()
        if (meta.endAcked && !hasAudio) {
            dir.deleteRecursively()
            AppLog.i(TAG, "Sesión ${sessionId.take(8)} entregada por completo; borrada del reloj")
        }
    }

    fun audioFile(header: FragmentHeader): File = File(sessionDir(header.sessionId), "${header.seq}$OGG")

    fun stats(): Stats {
        val dirs = sessionDirs()
        val files = dirs.flatMap { it.listFiles().orEmpty().toList() }
        return Stats(
            sessions = dirs.size,
            fragments = files.count { it.name.endsWith(OGG) },
            bytes = files.sumOf { it.length() },
        )
    }

    fun deleteAll() {
        root.deleteRecursively()
    }

    private fun headersIn(dir: File): List<FragmentHeader> =
        dir.listFiles { f -> f.name.endsWith(HEADER) && f.name != SESSION_FILE }.orEmpty().mapNotNull {
            runCatching { EscribaJson.decodeFromString<FragmentHeader>(it.readText()) }.getOrNull()
        }.sortedBy { it.seq }

    private fun sessionDirs(): List<File> = root.listFiles { f -> f.isDirectory }.orEmpty().toList()

    private fun sessionDir(sessionId: String) = File(root, sessionId)

    private fun seqOf(file: File): Int? = file.name.substringBefore('.').toIntOrNull()

    private fun writeAtomic(target: File, content: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(content.encodeToByteArray())
            out.fd.sync()
        }
        check(tmp.renameTo(target)) { "No se pudo escribir ${target.name}" }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val TAG = "store"
        const val SESSION_FILE = "session.json"
        const val PART = ".ogg.part"
        const val OGG = ".ogg"
        const val JOURNAL = ".journal"
        const val HEADER = ".json"
    }
}
