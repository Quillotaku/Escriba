package dev.escriba.mobile.transcription

import dev.escriba.datalayer.AppLog
import dev.escriba.mobile.data.FragmentEntity
import dev.escriba.nativeml.WhisperEngine
import dev.escriba.shared.EscribaJson
import dev.escriba.shared.Segment
import dev.escriba.shared.transcription.Chunker
import dev.escriba.shared.transcription.HallucinationFilter
import dev.escriba.shared.transcription.Pcm
import dev.escriba.shared.transcription.Timeline
import dev.escriba.shared.transcription.TranscriptLine
import java.io.Closeable
import java.io.File

/**
 * Transcribe conversaciones con whisper.cpp.
 *
 * Une el audio de voz de todos los fragmentos de la conversación (Whisper procesa ventanas de
 * 30 s: transcribir fragmentos de pocos segundos por separado desperdiciaría casi todo el
 * cálculo) y traduce cada frase a su hora real con la tabla de segmentos.
 */
class Transcriber(private val models: ModelStore, private val slot: ModelSlot) {

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
        /** Suma de los bloques: codificador (ventanas de 30 s) y decodificador (texto). */
        val encodeMs: Long,
        val decodeTextMs: Long,
    ) {
        /** Tiempo de cálculo por segundo de audio: < 1 es más rápido que el tiempo real. */
        val realTimeFactor: Double get() = if (audioMs > 0) transcribeMs.toDouble() / audioMs else 0.0
    }

    data class Result(val lines: List<TranscriptLine>, val stats: Stats)

    /** Modelo cargado para una tanda de trabajo; se libera con [Session.close]. */
    inner class Session(
        val modelName: String,
        private val engine: WhisperEngine,
        private val file: OpenModel,
        val loadMs: Long,
        private val flashAttn: Boolean,
    ) : Closeable {

        fun transcribe(
            fragments: List<FragmentEntity>,
            language: String,
            vocabulary: List<String>,
            threads: Int = WhisperEngine.defaultThreads(),
            fast: Boolean = false,
        ): Result {
            val decodeStart = System.currentTimeMillis()
            val segments = ArrayList<Segment>()
            val pieces = ArrayList<FloatArray>()
            for (f in fragments.sortedBy { it.seq }) {
                val pcm = AudioDecoder.decode(File(f.path))
                pieces += pcm
                // La tabla del reloj y el audio decodificado pueden diferir unos ms por el retardo
                // del códec: se ajusta el último tramo para que la suma cuadre con el audio real.
                val fragSegments = EscribaJson.decodeFromString<List<Segment>>(f.segmentsJson)
                val declared = fragSegments.sumOf { it.durationMs }
                val actual = pcm.size * 1000L / 16_000
                segments += if (fragSegments.isNotEmpty() && declared != actual) {
                    fragSegments.dropLast(1) + fragSegments.last().let { it.copy(durationMs = (it.durationMs + actual - declared).coerceAtLeast(0)) }
                } else fragSegments
            }
            val pcm = Pcm.normalize(concat(pieces))
            val decodeMs = System.currentTimeMillis() - decodeStart
            val timeline = Timeline(segments)

            // El vocabulario del perfil orienta a Whisper con nombres propios y jerga.
            val prompt = vocabulary.takeIf { it.isNotEmpty() }?.joinToString(", ", postfix = ".")

            val transcribeStart = System.currentTimeMillis()
            val raw = ArrayList<HallucinationFilter.Input>()
            var encodeMs = 0f
            var decodeTextMs = 0f
            // Bloques de hasta 30 s cortados en los silencios: una sola pasada del codificador por bloque.
            for (range in Chunker.plan(segments)) {
                val from = (range.first * 16).toInt().coerceIn(0, pcm.size)
                val to = ((range.last + 1) * 16).toInt().coerceIn(from, pcm.size)
                if (to - from < MIN_CHUNK_SAMPLES) continue
                val chunk = pcm.copyOfRange(from, to)
                val chunkStartMs = range.first
                engine.transcribe(chunk, language, prompt, threads, fast = fast).forEach {
                    raw += HallucinationFilter.Input(chunkStartMs + it.startMs, chunkStartMs + it.endMs, it.noSpeechProb, it.text)
                }
                engine.timings()?.let {
                    encodeMs += it.encodeMs
                    decodeTextMs += it.decodeMs + it.batchMs + it.promptMs + it.sampleMs
                }
            }
            val transcribeMs = System.currentTimeMillis() - transcribeStart
            val kept = HallucinationFilter.clean(raw)
            val lines = kept.map { TranscriptLine(timeline.wallAt(it.startMs), timeline.wallAt(it.endMs), it.text) }

            val stats = Stats(
                modelName, pcm.size * 1000L / 16_000, loadMs, decodeMs, transcribeMs, threads, raw.size, kept.size,
                flashAttn, fast, encodeMs.toLong(), decodeTextMs.toLong(),
            )
            AppLog.i(TAG, "Transcrito con $modelName: ${stats.audioMs / 1000} s de audio en ${transcribeMs / 1000} s " +
                "(×%.2f del tiempo real, %d hilos, flash=%s, rápido=%s), %d/%d frases".format(
                    stats.realTimeFactor, threads, flashAttn, fast, kept.size, raw.size,
                ))
            return Result(lines, stats)
        }

        override fun close() {
            engine.close()
            file.close()
            slot.mutex.unlock()
        }
    }

    /** Carga el modelo [name]. Solo hay un modelo cargado a la vez: los demás esperan. */
    suspend fun open(name: String, flashAttn: Boolean = true): Session {
        val entry = models.find(name) ?: error("No hay ningún modelo «$name» importado. Impórtalo en la pestaña «Modelos».")
        slot.mutex.lock()
        try {
            val start = System.currentTimeMillis()
            val file = models.open(entry)
            val engine = runCatching { WhisperEngine.load(file.fd, entry.name, flashAttn) }.getOrElse {
                file.close()
                throw it
            }
            val loadMs = System.currentTimeMillis() - start
            AppLog.i(TAG, "Modelo ${entry.name} cargado en ${loadMs} ms")
            return Session(entry.name, engine, file, loadMs, flashAttn)
        } catch (e: Throwable) {
            slot.mutex.unlock()
            throw e
        }
    }

    private fun concat(pieces: List<FloatArray>): FloatArray {
        val out = FloatArray(pieces.sumOf { it.size })
        var pos = 0
        for (p in pieces) {
            p.copyInto(out, pos)
            pos += p.size
        }
        return out
    }

    private companion object {
        const val TAG = "transcripcion"
        /** Menos de 0,3 s no tiene palabras que transcribir (y Whisper alucina con ellas). */
        const val MIN_CHUNK_SAMPLES = 16_000 * 3 / 10
    }
}
