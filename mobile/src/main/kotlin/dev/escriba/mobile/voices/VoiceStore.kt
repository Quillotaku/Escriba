package dev.escriba.mobile.voices

import dev.escriba.datalayer.AppLog
import dev.escriba.mobile.data.EscribaDb
import dev.escriba.mobile.data.VoiceEntity
import dev.escriba.shared.crypto.Crypto
import dev.escriba.shared.voices.EnrolledVoice
import dev.escriba.shared.voices.VoiceMath
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Enrolled voices. A voiceprint is biometric data: it lives only in the phone's private database,
 * never in the vault or on the watch, and it can be deleted from the app.
 */
class VoiceStore(private val db: EscribaDb) {

    suspend fun all(): List<EnrolledVoice> = db.sessions().voices().map { EnrolledVoice(it.name, decode(it.embedding)) }

    suspend fun names(): List<String> = db.sessions().voices().map { it.name }

    /**
     * Enrolls [embedding] under the name [name]. If the voice already existed, it's averaged with
     * what was there: the more sessions, the more reliable the recognition.
     */
    suspend fun enroll(name: String, embedding: FloatArray) {
        val dao = db.sessions()
        val existing = dao.voice(name)
        val merged = if (existing == null) embedding else {
            val old = decode(existing.embedding)
            // Average weighted by the number of samples it already had.
            val n = existing.samples.toFloat()
            val a = VoiceMath.average(listOf(old))
            val b = VoiceMath.average(listOf(embedding))
            FloatArray(a.size) { (a[it] * n + b[it]) / (n + 1) }
        }
        dao.upsertVoice(VoiceEntity(name, encode(merged), (existing?.samples ?: 0) + 1, System.currentTimeMillis()))
        AppLog.i(TAG, "Voice \"$name\" ${if (existing == null) "enrolled" else "updated (${existing.samples + 1} samples)"}")
    }

    suspend fun delete(name: String) {
        db.sessions().deleteVoice(name)
        AppLog.i(TAG, "Voice \"$name\" deleted")
    }

    companion object {
        private const val TAG = "voices"

        fun encode(e: FloatArray): String {
            val buffer = ByteBuffer.allocate(e.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            e.forEach(buffer::putFloat)
            return Crypto.b64(buffer.array())
        }

        fun decode(s: String): FloatArray {
            val buffer = ByteBuffer.wrap(Crypto.unb64(s)).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(buffer.remaining() / 4) { buffer.float }
        }
    }
}
