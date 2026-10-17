package dev.escriba.llm

import java.io.Closeable

/** JNI calls into llama.cpp (llama_jni.cpp). */
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

/** Statistics of one generation: prompt reading and writing speeds. */
data class LlamaStats(val promptTokens: Int, val generatedTokens: Int, val promptMs: Double, val generateMs: Double, val truncated: Boolean) {
    val promptTokensPerSecond: Double get() = if (promptMs > 0) promptTokens * 1000 / promptMs else 0.0
    val generateTokensPerSecond: Double get() = if (generateMs > 0) generatedTokens * 1000 / generateMs else 0.0
}

/**
 * A loaded LLM. It weighs several GB: it's loaded for a batch of work and released when done, and
 * never loaded at the same time as Whisper. Not safe to use from several threads at once.
 */
class LlamaEngine private constructor(private var handle: Long, val modelName: String) : Closeable {

    val contextSize: Int get() = LlamaNative.nativeContextSize(handle)

    fun countTokens(text: String): Int = LlamaNative.nativeCountTokens(handle, text)

    /**
     * Generates the answer to [user] with the instructions in [system]. With [grammar] (GBNF), the
     * output is restricted to it. Temperature 0 = greedy, the most stable for extracting data.
     */
    fun generate(system: String, user: String, grammar: String? = null, maxTokens: Int = 1024, temperature: Float = 0f): String {
        check(handle != 0L) { "Model released" }
        return LlamaNative.nativeGenerate(handle, system, user, grammar, maxTokens, temperature)
            ?: throw IllegalStateException("llama.cpp couldn't generate: " + nativeLog().lines().lastOrNull { it.isNotBlank() }.orEmpty())
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
        /** Loads a GGUF from a descriptor opened with SAF (no path: no storage permission needed). */
        fun load(fd: Int, modelName: String, contextSize: Int = 8192, threads: Int = defaultThreads(), flashAttn: Boolean = true): LlamaEngine {
            val handle = LlamaNative.nativeLoadFd(fd, contextSize, threads, flashAttn)
            check(handle != 0L) { "Couldn't load the model $modelName: " + nativeLog().lines().lastOrNull { it.isNotBlank() }.orEmpty() }
            return LlamaEngine(handle, modelName)
        }

        fun cancel() = LlamaNative.nativeCancel()

        fun nativeLog(): String = LlamaNative.nativeLog()

        fun systemInfo(): String = LlamaNative.nativeSystemInfo()

        fun defaultThreads(): Int = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)
    }
}
