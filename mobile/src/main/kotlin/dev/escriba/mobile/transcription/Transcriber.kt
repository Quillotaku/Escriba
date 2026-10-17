package dev.escriba.mobile.transcription

import dev.escriba.datalayer.AppLog
import dev.escriba.mobile.data.FragmentEntity
import dev.escriba.nativeml.WhisperEngine
import dev.escriba.shared.transcription.Chunker
import dev.escriba.shared.transcription.HallucinationFilter
import dev.escriba.shared.transcription.TranscriptLine
import java.io.Closeable

/**
 * Transcribe conversaciones con Whisper o con Parakeet.
 *
 * Une el audio de voz de todos los fragmentos de la conversación, lo trocea en bloques de ≤30 s
 * cortados en los silencios y traduce cada frase a su hora real con la tabla de segmentos.
 */
class Transcriber(private val models: ModelStore, private val slot: ModelSlot) {

    enum class Motor { WHISPER, PARAKEET }

    data class Stats(
        val model: String,
        val audioMs: Long,
        val loadMs: Long,
        val decodeMs: Long,
        val transcribeMs: Long,
        val threads: Int,
        val segmentsRaw: Int,
        val segmentsKept: Int,
        val flashAttn: Boolean,
        val fast: Boolean,
        /** Suma de los bloques: codificador y decodificador (solo Whisper los da). */
        val encodeMs: Long,
        val decodeTextMs: Long,
    ) {
        /** Tiempo de cálculo por segundo de audio: < 1 es más rápido que el tiempo real. */
        val realTimeFactor: Double get() = if (audioMs > 0) transcribeMs.toDouble() / audioMs else 0.0
    }

    data class Result(val lines: List<TranscriptLine>, val stats: Stats)

    /** Modelo cargado para una tanda de trabajo; se libera con [Session.close]. */
    inner class Session(private val engine: AsrEngine, val loadMs: Long, private val flashAttn: Boolean) : Closeable {
        val modelName: String get() = engine.name

        fun transcribe(
            fragments: List<FragmentEntity>,
            language: String,
            vocabulary: List<String>,
            threads: Int = WhisperEngine.defaultThreads(),
            fast: Boolean = false,
        ): Result {
            val decodeStart = System.currentTimeMillis()
            val audio = SpeechAudio.decode(fragments)
            val decodeMs = System.currentTimeMillis() - decodeStart

            // El vocabulario del perfil orienta a Whisper con nombres propios y jerga.
            val prompt = if (engine.supportsPrompt) vocabulary.takeIf { it.isNotEmpty() }?.joinToString(", ", postfix = ".") else null

            val transcribeStart = System.currentTimeMillis()
            val raw = ArrayList<HallucinationFilter.Input>()
            var encodeMs = 0f
            var decodeTextMs = 0f
            // Bloques de hasta 30 s cortados en los silencios: una sola pasada del codificador por bloque.
            for (range in Chunker.plan(audio.segments)) {
                val from = (range.first * 16).toInt().coerceIn(0, audio.pcm.size)
                val to = ((range.last + 1) * 16).toInt().coerceIn(from, audio.pcm.size)
                if (to - from < MIN_CHUNK_SAMPLES) continue
                val chunk = audio.pcm.copyOfRange(from, to)
                val chunkStartMs = range.first
                engine.transcribe(chunk, language, prompt, threads, fast).forEach {
                    raw += HallucinationFilter.Input(chunkStartMs + it.startMs, chunkStartMs + it.endMs, it.noSpeechProb, it.text)
                }
                engine.timings()?.let { (enc, dec) ->
                    encodeMs += enc
                    decodeTextMs += dec
                }
            }
            val transcribeMs = System.currentTimeMillis() - transcribeStart
            val kept = HallucinationFilter.clean(raw)
            val lines = kept.map { TranscriptLine(audio.timeline.wallAt(it.startMs), audio.timeline.wallAt(it.endMs), it.text) }

            val stats = Stats(
                engine.name, audio.durationMs, loadMs, decodeMs, transcribeMs, threads, raw.size, kept.size,
                flashAttn, fast, encodeMs.toLong(), decodeTextMs.toLong(),
            )
            AppLog.i(TAG, "Transcrito con ${engine.name}: ${stats.audioMs / 1000} s de audio en ${transcribeMs / 1000} s " +
                "(×%.2f del tiempo real, %d hilos), %d/%d frases".format(stats.realTimeFactor, threads, kept.size, raw.size))
            return Result(lines, stats)
        }

        override fun close() {
            engine.close()
            slot.mutex.unlock()
        }
    }

    /**
     * Carga el motor. Para Whisper, [name] es el modelo (`large-v3-turbo-q8_0`); Parakeet tiene
     * un único modelo. Solo hay un modelo grande cargado a la vez: los demás esperan.
     */
    suspend fun open(name: String, flashAttn: Boolean = true, motor: Motor = Motor.WHISPER): Session {
        slot.mutex.lock()
        try {
            val start = System.currentTimeMillis()
            val engine: AsrEngine = when (motor) {
                Motor.WHISPER -> {
                    val entry = models.find(name) ?: error("No hay ningún modelo «$name» en la carpeta de modelos")
                    val file = models.open(entry)
                    val whisper = runCatching { WhisperEngine.load(file.fd, entry.name, flashAttn) }.getOrElse {
                        file.close()
                        throw it
                    }
                    WhisperAsr(whisper, file)
                }
                Motor.PARAKEET -> {
                    val progress: (Long, Long) -> Unit = { c, t -> AppLog.i(TAG, "Copiando Parakeet a la app: $c/$t MB (solo la primera vez)") }
                    ParakeetAsr(
                        encoder = models.localPathOf(ParakeetAsr.ENCODER, progress),
                        decoder = models.localPathOf(ParakeetAsr.DECODER),
                        joiner = models.localPathOf(ParakeetAsr.JOINER),
                        tokens = models.localPathOf(ParakeetAsr.TOKENS),
                        threads = WhisperEngine.defaultThreads(),
                    )
                }
            }
            val loadMs = System.currentTimeMillis() - start
            AppLog.i(TAG, "Motor ${engine.name} cargado en ${loadMs} ms")
            return Session(engine, loadMs, flashAttn)
        } catch (e: Throwable) {
            slot.mutex.unlock()
            throw e
        }
    }

    private companion object {
        const val TAG = "transcripcion"
        /** Menos de 0,3 s no tiene palabras que transcribir (y Whisper alucina con ellas). */
        const val MIN_CHUNK_SAMPLES = 16_000 * 3 / 10
    }
}
