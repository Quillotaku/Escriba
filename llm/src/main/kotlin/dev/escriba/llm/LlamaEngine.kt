package dev.escriba.llm

import java.io.Closeable

/** Llamadas JNI a llama.cpp (llama_jni.cpp). */
internal object LlamaNative {
    init {
        System.loadLibrary("escriba_llm")
    }

    external fun nativeLoadFd(fd: Int, nCtx: Int, nThreads: Int, flashAttn: Boolean): Long
    external fun nativeFree(handle: Long)
    external fun nativeCancel()
    external fun nativeLog(): String
    external fun nativeSystemInfo(): String
    external fun nativeContextSize(handle: Long): Int
    external fun nativeCountTokens(handle: Long, text: String): Int
    external fun nativeLastStats(handle: Long): String?
    external fun nativeGenerate(handle: Long, system: String, user: String, grammar: String?, maxTokens: Int, temperature: Float): String?
}

/** Estadísticas de una generación: velocidades de lectura del prompt y de escritura. */
data class LlamaStats(val promptTokens: Int, val generatedTokens: Int, val promptMs: Double, val generateMs: Double, val truncated: Boolean) {
    val promptTokensPerSecond: Double get() = if (promptMs > 0) promptTokens * 1000 / promptMs else 0.0
    val generateTokensPerSecond: Double get() = if (generateMs > 0) generatedTokens * 1000 / generateMs else 0.0
}

/**
 * Un LLM cargado. Pesa varios GB: se carga para una tanda de trabajo y se libera al terminar, y
 * nunca está cargado a la vez que Whisper. No es seguro usarlo desde varios hilos a la vez.
 */
class LlamaEngine private constructor(private var handle: Long, val modelName: String) : Closeable {

    val contextSize: Int get() = LlamaNative.nativeContextSize(handle)

    fun countTokens(text: String): Int = LlamaNative.nativeCountTokens(handle, text)

    /**
     * Genera la respuesta a [user] con las instrucciones de [system]. Con [grammar] (GBNF), la
     * salida queda restringida a ella. Temperatura 0 = voraz, lo más estable para extraer datos.
     */
    fun generate(system: String, user: String, grammar: String? = null, maxTokens: Int = 1024, temperature: Float = 0f): String {
        check(handle != 0L) { "Modelo liberado" }
        return LlamaNative.nativeGenerate(handle, system, user, grammar, maxTokens, temperature)
            ?: throw IllegalStateException("llama.cpp no pudo generar: " + nativeLog().lines().lastOrNull { it.isNotBlank() }.orEmpty())
    }

    fun lastStats(): LlamaStats? {
        val p = LlamaNative.nativeLastStats(handle)?.split(',') ?: return null
        if (p.size < 5) return null
        return LlamaStats(p[0].toInt(), p[1].toInt(), p[2].toDouble(), p[3].toDouble(), p[4] == "1")
    }

    override fun close() {
        if (handle != 0L) {
            LlamaNative.nativeFree(handle)
            handle = 0
        }
    }

    companion object {
        /** Carga un GGUF desde un descriptor abierto con SAF (sin ruta: no hace falta permiso de almacenamiento). */
        fun load(fd: Int, modelName: String, contextSize: Int = 8192, threads: Int = defaultThreads(), flashAttn: Boolean = true): LlamaEngine {
            val handle = LlamaNative.nativeLoadFd(fd, contextSize, threads, flashAttn)
            check(handle != 0L) { "No se pudo cargar el modelo $modelName: " + nativeLog().lines().lastOrNull { it.isNotBlank() }.orEmpty() }
            return LlamaEngine(handle, modelName)
        }

        fun cancel() = LlamaNative.nativeCancel()

        fun nativeLog(): String = LlamaNative.nativeLog()

        fun systemInfo(): String = LlamaNative.nativeSystemInfo()

        fun defaultThreads(): Int = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)
    }
}
