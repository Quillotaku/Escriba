package dev.escriba.wear.recorder

import android.media.MediaCodec
import android.media.MediaFormat
import java.io.Closeable
import java.nio.ByteOrder

/**
 * Encodes 16-bit mono PCM into Opus packets with the system codec
 * (`c2.android.opus.encoder`, verified on the watch).
 *
 * It doesn't containerize anything: it hands each packet to [onPacket]. Before the first packet
 * it calls [onFormat] with the pre-skip (samples at 48 kHz the decoder must drop).
 */
class OpusEncoder(
    private val sampleRate: Int,
    bitrate: Int,
    private val onFormat: (preSkip: Int) -> Unit,
    private val onPacket: (ByteArray) -> Unit,
) : Closeable {

    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
    private val info = MediaCodec.BufferInfo()
    private var samplesQueued = 0L
    private var closed = false

    val codecName: String = codec.name

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (e: Exception) {
            codec.release()
            throw e
        }
    }

    fun encode(pcm: ShortArray, length: Int = pcm.size) {
        var offset = 0
        while (offset < length) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index < 0) {
                drain(endOfStream = false)
                continue
            }
            val buffer = codec.getInputBuffer(index)!!
            buffer.clear()
            val count = minOf(length - offset, buffer.remaining() / 2)
            buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm, offset, count)
            codec.queueInputBuffer(index, 0, count * 2, samplesQueued * 1_000_000L / sampleRate, 0)
            samplesQueued += count
            offset += count
            drain(endOfStream = false)
        }
    }

    /** Drains the codec (delivers the last packets) and releases it. */
    override fun close() {
        if (closed) return
        closed = true
        try {
            var index: Int
            var attempts = 0
            do {
                index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index < 0) drain(endOfStream = false)
            } while (index < 0 && ++attempts < MAX_ATTEMPTS)
            if (index >= 0) {
                codec.queueInputBuffer(index, 0, 0, samplesQueued * 1_000_000L / sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                drain(endOfStream = true)
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
    }

    private fun drain(endOfStream: Boolean) {
        var idle = 0
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (endOfStream) TIMEOUT_US else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream || ++idle > MAX_ATTEMPTS) return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onFormat(preSkip(codec.outputFormat))
                index >= 0 -> {
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && info.size > 0) {
                        val out = codec.getOutputBuffer(index)!!
                        val packet = ByteArray(info.size)
                        out.position(info.offset)
                        out.get(packet)
                        onPacket(packet)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val MAX_ATTEMPTS = 200
        /** Typical Opus encoder delay (6.5 ms) if the codec doesn't report it. */
        const val DEFAULT_PRE_SKIP = 312

        /** csd-1 holds the codec delay in nanoseconds (64-bit integer, little-endian). */
        fun preSkip(format: MediaFormat): Int {
            val csd = format.getByteBuffer("csd-1") ?: return DEFAULT_PRE_SKIP
            if (csd.remaining() < 8) return DEFAULT_PRE_SKIP
            val delayNs = csd.duplicate().order(ByteOrder.LITTLE_ENDIAN).long
            return (delayNs * 48_000 / 1_000_000_000L).toInt().takeIf { it in 0..10_000 } ?: DEFAULT_PRE_SKIP
        }
    }
}
