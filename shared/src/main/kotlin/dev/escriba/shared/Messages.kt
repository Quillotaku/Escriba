package dev.escriba.shared

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** JSON encoding shared by both ends. */
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
 * A stretch of speech inside a fragment. The VAD drops silences, so the audio is compressed in
 * time: this table maps a position in the audio to the real time it was said.
 */
@Serializable
data class Segment(
    val audioOffsetMs: Long,
    val wallStartMs: Long,
    val durationMs: Long,
)

/**
 * Header that precedes each fragment's Ogg/Opus bytes on the channel.
 * A fragment's identity is [sessionId] + [seq], never the time.
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

@Serializable
data class SessionEndAck(
    val sessionId: String,
)

/** Watch status sent to the phone with every batch, for the dashboard. */
@Serializable
data class WatchStatus(
    val phase: String,
    val pendingFragments: Int,
    val pendingBytes: Long,
    val batteryPct: Float?,
    val charging: Boolean,
    val atMs: Long,
)

// --- Pairing (in plain text: it only carries public keys, nonces and commitments) ---

@Serializable
data class PairInit(val commit: String, val name: String)

@Serializable
data class PairReply(val publicKey: String, val nonce: String, val name: String)

@Serializable
data class PairReveal(val publicKey: String, val nonce: String)

@Serializable
data class PairConfirm(val mac: String)

/** The watch asks for the profile list (on start, or when it has none). */
@Serializable
data class ProfilesRequest(val currentHash: String? = null)
