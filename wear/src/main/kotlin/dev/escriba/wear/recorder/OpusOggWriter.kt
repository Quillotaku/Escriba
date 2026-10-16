package dev.escriba.wear.recorder

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.Closeable
import java.io.File
import java.nio.ByteOrder

/**
 * Codifica PCM 16 bits mono a Opus con el códec del sistema y lo empaqueta en Ogg.
 *
 * Reglas que evitan archivos corruptos:
 *  - la pista del muxer se añade solo al recibir INFO_OUTPUT_FORMAT_CHANGED, porque ese
 *    formato trae la cabecera OpusHead (csd-0) que el muxer necesita;
 *  - los búferes marcados como CODEC_CONFIG no se escriben como muestras de audio.
 */
class OpusOggWriter(
    file: File,
    private val sampleRate: Int,
    bitrate: Int,
) : Closeable {

    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
    private val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var muxerStarted = false
    private var samplesQueued = 0L
    private var closed = false

    /** Nombre del códec real (p. ej. `c2.android.opus.encoder`), para el diagnóstico. */
    val codecName: String = codec.name

    var packetsWritten = 0L
        private set

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    fun write(pcm: ShortArray, length: Int) {
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
            codec.queueInputBuffer(index, 0, count * 2, presentationTimeUs(), 0)
            samplesQueued += count
            offset += count
            drain(endOfStream = false)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            var index: Int
            do {
                index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index < 0) drain(endOfStream = false)
            } while (index < 0)
            codec.queueInputBuffer(index, 0, 0, presentationTimeUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(endOfStream = true)
        } finally {
            runCatching { codec.stop() }
            codec.release()
            if (muxerStarted) runCatching { muxer.stop() }
            muxer.release()
        }
    }

    private fun presentationTimeUs(): Long = samplesQueued * 1_000_000L / sampleRate

    private fun drain(endOfStream: Boolean) {
        var idleRounds = 0
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (endOfStream) TIMEOUT_US else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream || ++idleRounds > MAX_IDLE_ROUNDS_AT_EOS) return
                }

                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxerStarted) { "El formato de salida cambió dos veces" }
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }

                index >= 0 -> {
                    val out = codec.getOutputBuffer(index)!!
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && info.size > 0 && muxerStarted) {
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        muxer.writeSampleData(track, out, info)
                        packetsWritten++
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val MAX_IDLE_ROUNDS_AT_EOS = 200
    }
}
