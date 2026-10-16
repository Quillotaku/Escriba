package dev.escriba.mobile.transcription

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import dev.escriba.shared.transcription.Pcm
import java.io.File
import java.nio.ByteOrder

/** Decodifica un fragmento Ogg/Opus a PCM float 16 kHz mono, lo que espera Whisper. */
object AudioDecoder {

    fun decode(file: File): FloatArray {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            require(extractor.trackCount > 0) { "${file.name} no tiene pistas de audio" }
            val format = extractor.getTrackFormat(0)
            extractor.selectTrack(0)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            val pcm = ShortBuilder()
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                while (true) {
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (i >= 0) {
                            val size = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(i, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                    when {
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            rate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        o >= 0 -> {
                            if (info.size > 0) {
                                val buffer = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                pcm.append(buffer.asShortBuffer())
                            }
                            codec.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        }
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
            val mono = pcm.toMonoFloat(channels)
            return Pcm.to16k(mono, rate)
        } finally {
            extractor.release()
        }
    }

    private class ShortBuilder {
        private var data = ShortArray(16_000 * 60)
        private var size = 0

        fun append(src: java.nio.ShortBuffer) {
            val n = src.remaining()
            if (size + n > data.size) data = data.copyOf(maxOf(data.size * 2, size + n))
            src.get(data, size, n)
            size += n
        }

        fun toMonoFloat(channels: Int): FloatArray {
            if (channels <= 1) return Pcm.toFloat(data, size)
            val frames = size / channels
            return FloatArray(frames) { f ->
                var acc = 0
                for (c in 0 until channels) acc += data[f * channels + c]
                acc / (32768f * channels)
            }
        }
    }

    private const val TIMEOUT_US = 10_000L
}
