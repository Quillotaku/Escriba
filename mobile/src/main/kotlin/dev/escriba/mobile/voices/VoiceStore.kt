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
 * Voces registradas. Una huella de voz es un dato biométrico: vive solo en la base de datos
 * privada del móvil, nunca en la bóveda ni en el reloj, y se puede borrar desde la app.
 */
class VoiceStore(private val db: EscribaDb) {

    suspend fun all(): List<EnrolledVoice> = db.sessions().voices().map { EnrolledVoice(it.name, decode(it.embedding)) }

    suspend fun names(): List<String> = db.sessions().voices().map { it.name }

    /**
     * Registra [embedding] con el nombre [name]. Si la voz ya existía, se promedia con lo que ya
     * se tenía: cuantas más sesiones, más fiable el reconocimiento.
     */
    suspend fun enroll(name: String, embedding: FloatArray) {
        val dao = db.sessions()
        val existing = dao.voice(name)
        val merged = if (existing == null) embedding else {
            val old = decode(existing.embedding)
            // Media ponderada por el número de muestras que ya tenía.
            val n = existing.samples.toFloat()
            val a = VoiceMath.average(listOf(old))
            val b = VoiceMath.average(listOf(embedding))
            FloatArray(a.size) { (a[it] * n + b[it]) / (n + 1) }
        }
        dao.upsertVoice(VoiceEntity(name, encode(merged), (existing?.samples ?: 0) + 1, System.currentTimeMillis()))
        AppLog.i(TAG, "Voz «$name» ${if (existing == null) "registrada" else "actualizada (${existing.samples + 1} muestras)"}")
    }

    suspend fun delete(name: String) {
        db.sessions().deleteVoice(name)
        AppLog.i(TAG, "Voz «$name» borrada")
    }

    companion object {
        private const val TAG = "voces"

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
