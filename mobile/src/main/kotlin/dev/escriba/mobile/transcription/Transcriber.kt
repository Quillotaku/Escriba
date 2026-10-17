package dev.escriba.mobile.transcription

import dev.escriba.datalayer.AppLog
import dev.escriba.mobile.data.FragmentEntity
import dev.escriba.nativeml.WhisperEngine
import dev.escriba.shared.transcription.Chunker
import dev.escriba.shared.transcription.HallucinationFilter
import dev.escriba.shared.transcription.TranscriptLine
import java.io.Closeable

/**
 * Transcribes conversations with Whisper or Parakeet.
 *
 * It joins the speech audio of all the conversation's fragments, splits it into ≤30 s chunks cut
 * at the silences and maps each line to its real time with the segment table.
 */
class Transcriber(private val models: ModelStore, private val slot: ModelSlot) {

    enum class Engine { WHISPER, PARAKEET }

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
        /** Sum over the chunks: encoder and decoder (only Whisper reports them). */
        val encodeMs: Long,
        val decodeTextMs: Long,
    ) {
        /** Compute time per second of audio: < 1 is faster than real time. */
        val realTimeFactor: Double get() = if (audioMs > 0) transcribeMs.toDouble() / audioMs else 0.0
    }

    data class Result(val lines: List<TranscriptLine>, val stats: Stats)

    /** Model loaded for a batch of work; released with [Session.close]. */
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

            // The profile's vocabulary steers Whisper towards proper names and jargon.
            val prompt = if (engine.supportsPrompt) vocabulary.takeIf { it.isNotEmpty() }?.joinToString(", ", postfix = ".") else null

            val transcribeStart = System.currentTimeMillis()
            val raw = ArrayList<HallucinationFilter.Input>()
            var encodeMs = 0f
            var decodeTextMs = 0f
            // Chunks of up to 30 s cut at the silences: a single encoder pass per chunk.
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
            AppLog.i(TAG, "Transcribed with ${engine.name}: ${stats.audioMs / 1000} s of audio in ${transcribeMs / 1000} s " +
                "(×%.2f of real time, %d threads), %d/%d lines".format(stats.realTimeFactor, threads, kept.size, raw.size))
            return Result(lines, stats)
        }

        override fun close() {
            engine.close()
            slot.mutex.unlock()
        }
    }

    /**
     * Loads the engine. For Whisper, [name] is the model (`large-v3-turbo-q8_0`); Parakeet has a
     * single model. Only one large model is loaded at a time: the others wait.
     */
    suspend fun open(name: String, flashAttn: Boolean = true, engine: Engine = Engine.WHISPER): Session {
        slot.mutex.lock()
        try {
            val start = System.currentTimeMillis()
            val asr: AsrEngine = when (engine) {
                Engine.WHISPER -> {
                    val entry = models.find(name) ?: error("There's no model \"$name\" in the models folder")
                    val file = models.open(entry)
                    val whisper = runCatching { WhisperEngine.load(file.fd, entry.name, flashAttn) }.getOrElse {
                        file.close()
                        throw it
                    }
                    WhisperAsr(whisper, file)
                }
                Engine.PARAKEET -> {
                    val progress: (Long, Long) -> Unit = { c, t -> AppLog.i(TAG, "Copying Parakeet into the app: $c/$t MB (first time only)") }
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
            AppLog.i(TAG, "Engine ${asr.name} loaded in $loadMs ms")
            return Session(asr, loadMs, flashAttn)
        } catch (e: Throwable) {
            slot.mutex.unlock()
            throw e
        }
    }

    private companion object {
        const val TAG = "transcription"
        /** Less than 0.3 s has no words to transcribe (and Whisper hallucinates on it). */
        const val MIN_CHUNK_SAMPLES = 16_000 * 3 / 10
    }
}
