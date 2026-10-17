package dev.escriba.mobile.transcription

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import dev.escriba.mobile.data.FragmentEntity
import dev.escriba.nativeml.WhisperEngine
import dev.escriba.shared.EscribaJson
import dev.escriba.shared.Segment
import dev.escriba.shared.transcription.Pcm
import dev.escriba.shared.transcription.Timeline
import java.io.Closeable
import java.io.File

/** Frase reconocida en un bloque de audio, con tiempos relativos al bloque. */
data class AsrSegment(val startMs: Long, val endMs: Long, val noSpeechProb: Float, val text: String)

/** Motor de reconocimiento: Whisper (whisper.cpp) o Parakeet (sherpa-onnx). */
interface AsrEngine : Closeable {
    val name: String

    /** Transcribe un bloque de PCM 16 kHz mono de hasta ~30 s. */
    fun transcribe(pcm: FloatArray, language: String, prompt: String?, threads: Int, fast: Boolean): List<AsrSegment>

    /** Tiempos de codificador y decodificador del último bloque, si el motor los da (ms). */
    fun timings(): Pair<Float, Float>? = null

    /** Admite el vocabulario del perfil como contexto inicial. */
    val supportsPrompt: Boolean
}

class WhisperAsr(private val engine: WhisperEngine, private val file: OpenModel) : AsrEngine {
    override val name: String get() = engine.modelName
    override val supportsPrompt = true

    override fun transcribe(pcm: FloatArray, language: String, prompt: String?, threads: Int, fast: Boolean): List<AsrSegment> =
        engine.transcribe(pcm, language, prompt, threads, fast = fast).map { AsrSegment(it.startMs, it.endMs, it.noSpeechProb, it.text) }

    override fun timings(): Pair<Float, Float>? = engine.timings()?.let { it.encodeMs to (it.decodeMs + it.batchMs + it.promptMs + it.sampleMs) }

    override fun close() {
        engine.close()
        file.close()
    }
}

/**
 * NVIDIA Parakeet TDT 0.6B v3 con sherpa-onnx: 25 idiomas europeos, con puntuación. No admite
 * prompt inicial; devuelve tokens con su instante, que aquí se agrupan en frases.
 */
class ParakeetAsr(encoder: String, decoder: String, joiner: String, tokens: String, threads: Int) : AsrEngine {
    override val name = MODEL_NAME
    override val supportsPrompt = false

    private val recognizer = OfflineRecognizer(
        config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(encoder = encoder, decoder = decoder, joiner = joiner),
                tokens = tokens,
                numThreads = threads,
                provider = "cpu",
                modelType = "nemo_transducer",
            ),
            decodingMethod = "greedy_search",
        ),
    )

    override fun transcribe(pcm: FloatArray, language: String, prompt: String?, threads: Int, fast: Boolean): List<AsrSegment> {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(pcm, 16_000)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            return sentences(result.tokens, result.timestamps, pcm.size * 1000L / 16_000)
        } finally {
            stream.release()
        }
    }

    /**
     * Agrupa los tokens en frases: acaban en «.», «?» o «!», en una pausa de más de
     * [PAUSE_MS] o, si se alargan mucho, en el siguiente cambio de palabra. Frases cortas
     * permiten luego asignar bien a cada hablante.
     */
    private fun sentences(tokens: Array<String>, times: FloatArray, totalMs: Long): List<AsrSegment> {
        val out = ArrayList<AsrSegment>()
        val text = StringBuilder()
        var startMs = -1L
        var lastMs = 0L
        for (i in tokens.indices) {
            val t = (times.getOrNull(i)?.times(1000))?.toLong() ?: 0L
            val newWord = tokens[i].startsWith(" ")
            if (startMs >= 0 && newWord && (t - lastMs > PAUSE_MS || text.length > MAX_CHARS)) {
                out += AsrSegment(startMs, t, 0f, text.toString().trim())
                text.clear()
                startMs = -1
            }
            if (startMs < 0) startMs = t
            lastMs = t
            text.append(tokens[i])
            val piece = tokens[i].trim()
            if (piece.endsWith(".") || piece.endsWith("?") || piece.endsWith("!")) {
                val endMs = times.getOrNull(i + 1)?.times(1000)?.toLong() ?: totalMs
                out += AsrSegment(startMs, endMs, 0f, text.toString().trim())
                text.clear()
                startMs = -1
            }
        }
        if (text.isNotBlank()) out += AsrSegment(startMs.coerceAtLeast(0), totalMs, 0f, text.toString().trim())
        return out
    }

    override fun close() = recognizer.release()

    companion object {
        const val MODEL_NAME = "parakeet-tdt-0.6b-v3"
        private const val PAUSE_MS = 900L
        private const val MAX_CHARS = 220
        const val ENCODER = "$MODEL_NAME-encoder.int8.onnx"
        const val DECODER = "$MODEL_NAME-decoder.int8.onnx"
        const val JOINER = "$MODEL_NAME-joiner.int8.onnx"
        const val TOKENS = "$MODEL_NAME-tokens.txt"
    }
}

/** Audio de voz de una lista de fragmentos, unido, normalizado y con su mapa de hora real. */
class SpeechAudio(val pcm: FloatArray, val segments: List<Segment>) {
    val timeline = Timeline(segments)
    val durationMs: Long get() = pcm.size * 1000L / 16_000

    companion object {
        fun decode(fragments: List<FragmentEntity>): SpeechAudio {
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
            val out = FloatArray(pieces.sumOf { it.size })
            var pos = 0
            for (p in pieces) {
                p.copyInto(out, pos)
                pos += p.size
            }
            return SpeechAudio(Pcm.normalize(out), segments)
        }
    }
}
