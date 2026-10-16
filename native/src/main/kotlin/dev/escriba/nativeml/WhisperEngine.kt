package dev.escriba.nativeml

import java.io.Closeable

/** Llamadas JNI a whisper.cpp (whisper_jni.cpp). */
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

/** Segmento transcrito, con tiempos en ms relativos al inicio del audio transcrito. */
data class WhisperSegment(val startMs: Long, val endMs: Long, val noSpeechProb: Float, val text: String)

/** Desglose de tiempos de whisper.cpp (ms) de la última transcripción. */
data class WhisperTimings(val encodeMs: Float, val decodeMs: Float, val batchMs: Float, val promptMs: Float, val sampleMs: Float)

/**
 * Un modelo Whisper cargado. Ocupa cientos de MB: se carga para una tanda de trabajo y se
 * libera al terminar, antes de cargar otro modelo (p. ej. el LLM en la fase 7).
 *
 * No es seguro usarlo desde varios hilos a la vez.
 */
class WhisperEngine private constructor(private var handle: Long, val modelName: String) : Closeable {

    /**
     * Transcribe PCM float 16 kHz mono. [threads] por defecto: los núcleos rápidos, dejando
     * margen al sistema (ggml espera al hilo más lento en cada operación).
     */
    fun transcribe(
        pcm: FloatArray,
        language: String,
        prompt: String? = null,
        threads: Int = defaultThreads(),
        beamSize: Int = 1,
        /** Un solo candidato y sin reintentos con temperatura: mucho más rápido, algo menos robusto. */
        fast: Boolean = false,
    ): List<WhisperSegment> {
        check(handle != 0L) { "Modelo liberado" }
        val lines = WhisperNative.nativeTranscribe(handle, pcm, language, prompt, threads, beamSize, fast)
            ?: throw IllegalStateException("whisper.cpp no pudo transcribir (¿cancelado?)")
        return lines.mapNotNull { line ->
            val parts = line.split('\t', limit = 4)
            if (parts.size < 4) return@mapNotNull null
            WhisperSegment(parts[0].toLong(), parts[1].toLong(), parts[2].toFloatOrNull() ?: 0f, parts[3].trim())
        }
    }

    /** Tiempos de la última llamada a [transcribe]. */
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
         * Carga un modelo ggml desde un descriptor abierto (un archivo elegido con SAF). Se lee
         * por el descriptor, no por su ruta: la ruta exigiría permiso de almacenamiento general.
         */
        fun load(fd: Int, modelName: String, flashAttn: Boolean = true): WhisperEngine {
            val handle = WhisperNative.nativeInitFd(fd, flashAttn)
            check(handle != 0L) { "No se pudo cargar el modelo $modelName" }
            return WhisperEngine(handle, modelName)
        }

        fun cancel() = WhisperNative.nativeCancel()

        fun systemInfo(): String = WhisperNative.nativeSystemInfo()

        fun nativeLog(): String = WhisperNative.nativeLog()

        fun defaultThreads(): Int = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)
    }
}
