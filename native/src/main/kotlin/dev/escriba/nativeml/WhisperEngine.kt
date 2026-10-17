package dev.escriba.nativeml

import java.io.Closeable

/** JNI calls into whisper.cpp (whisper_jni.cpp). */
internal object WhisperNative {
    init {
        System.loadLibrary("escriba_native")
    }

    external fun nativeInitFd(fd: Int, flashAttn: Boolean): Long
    external fun nativeTimings(handle: Long): String?
    external fun nativeFree(handle: Long)
    external fun nativeCancel()
    external fun nativeSystemInfo(): String
    external fun nativeLog(): String
    external fun nativeTranscribe(handle: Long, pcm: FloatArray, language: String, prompt: String?, threads: Int, beamSize: Int, fast: Boolean): Array<String>?
}

/** A transcribed segment, with times in ms relative to the start of the transcribed audio. */
data class WhisperSegment(val startMs: Long, val endMs: Long, val noSpeechProb: Float, val text: String)

/** whisper.cpp's timing breakdown (ms) for the last transcription. */
data class WhisperTimings(val encodeMs: Float, val decodeMs: Float, val batchMs: Float, val promptMs: Float, val sampleMs: Float)

/**
 * A loaded Whisper model. It takes hundreds of MB: it's loaded for a batch of work and released
 * when done, before another model (e.g. the LLM) is loaded.
 *
 * Not safe to use from several threads at once.
 */
class WhisperEngine private constructor(private var handle: Long, val modelName: String) : Closeable {

    /**
     * Transcribes 16 kHz mono float PCM. Default [threads]: the fast cores, leaving room for the
     * system (ggml waits for the slowest thread on every operation).
     */
    fun transcribe(
        pcm: FloatArray,
        language: String,
        prompt: String? = null,
        threads: Int = defaultThreads(),
        beamSize: Int = 1,
        /** A single candidate and no temperature fallback: much faster, slightly less robust. */
        fast: Boolean = false,
    ): List<WhisperSegment> {
        check(handle != 0L) { "Model released" }
        val lines = WhisperNative.nativeTranscribe(handle, pcm, language, prompt, threads, beamSize, fast)
            ?: throw IllegalStateException("whisper.cpp couldn't transcribe (cancelled?)")
        return lines.mapNotNull { line ->
            val parts = line.split('\t', limit = 4)
            if (parts.size < 4) return@mapNotNull null
            WhisperSegment(parts[0].toLong(), parts[1].toLong(), parts[2].toFloatOrNull() ?: 0f, parts[3].trim())
        }
    }

    /** Timings of the last call to [transcribe]. */
    fun timings(): WhisperTimings? {
        val parts = WhisperNative.nativeTimings(handle)?.split(',')?.mapNotNull { it.toFloatOrNull() } ?: return null
        if (parts.size < 5) return null
        return WhisperTimings(parts[0], parts[1], parts[2], parts[3], parts[4])
    }

    override fun close() {
        if (handle != 0L) {
            WhisperNative.nativeFree(handle)
            handle = 0
        }
    }

    companion object {
        /**
         * Loads a ggml model from an open descriptor (a file picked with SAF). It's read through the
         * descriptor, not its path: the path would require broad storage permission.
         */
        fun load(fd: Int, modelName: String, flashAttn: Boolean = true): WhisperEngine {
            val handle = WhisperNative.nativeInitFd(fd, flashAttn)
            check(handle != 0L) { "Couldn't load the model $modelName" }
            return WhisperEngine(handle, modelName)
        }

        fun cancel() = WhisperNative.nativeCancel()

        fun systemInfo(): String = WhisperNative.nativeSystemInfo()

        fun nativeLog(): String = WhisperNative.nativeLog()

        fun defaultThreads(): Int = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)
    }
}
