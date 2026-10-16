package dev.escriba.shared

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Codificación JSON común a ambos extremos. */
val EscribaJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

inline fun <reified T> T.toWire(): ByteArray = EscribaJson.encodeToString(this).encodeToByteArray()

inline fun <reified T> ByteArray.fromWire(): T = EscribaJson.decodeFromString(decodeToString())

@Serializable
data class Ping(
    val id: String,
    val sentAtMs: Long,
    val origin: String,
)

@Serializable
data class Pong(
    val id: String,
    val pingSentAtMs: Long,
    val repliedAtMs: Long,
    val origin: String,
)

/**
 * Tramo de habla dentro de un fragmento. El VAD descarta los silencios, así
 * que el audio queda comprimido en el tiempo: esta tabla permite traducir una
 * posición del audio a la hora real en que se dijo.
 */
@Serializable
data class Segment(
    val audioOffsetMs: Long,
    val wallStartMs: Long,
    val durationMs: Long,
)

/**
 * Cabecera que precede a los bytes Ogg/Opus de cada fragmento en el canal.
 * La identidad de un fragmento es [sessionId] + [seq], nunca la hora.
 */
@Serializable
data class FragmentHeader(
    val protocol: Int = Protocol.VERSION,
    val sessionId: String,
    val seq: Int,
    val profileId: String,
    val profileHash: String,
    val sessionStartMs: Long,
    val startMs: Long,
    val endMs: Long,
    val segments: List<Segment>,
    val codec: String = CODEC_OPUS_OGG,
    val sampleRate: Int,
    val bitrate: Int,
    val sha256: String,
    val sizeBytes: Long,
    val last: Boolean,
) {
    val key: String get() = "$sessionId#$seq"

    companion object {
        const val CODEC_OPUS_OGG = "opus/ogg"
    }
}

@Serializable
data class Ack(
    val sessionId: String,
    val seq: Int,
    val sha256: String,
)

@Serializable
data class SessionEnd(
    val sessionId: String,
    val totalFragments: Int,
    val endMs: Long,
)
