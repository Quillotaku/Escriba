package dev.escriba.shared.transcription

import dev.escriba.shared.Segment
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Traduce posiciones del audio transcrito a hora real.
 *
 * El audio que llega a Whisper son solo los tramos de voz, uno detrás de otro (el VAD del reloj
 * quitó los silencios). Cada tramo sabe a qué hora real empezó; este mapa permite poner la hora
 * correcta a cada frase de la transcripción.
 */
class Timeline(segments: List<Segment>) {

    private class Span(val audioStartMs: Long, val wallStartMs: Long, val durationMs: Long)

    private val spans: List<Span>

    init {
        var cursor = 0L
        spans = segments.map { s -> Span(cursor, s.wallStartMs, s.durationMs).also { cursor += s.durationMs } }
    }

    val totalMs: Long get() = spans.lastOrNull()?.let { it.audioStartMs + it.durationMs } ?: 0

    fun wallAt(audioMs: Long): Long {
        if (spans.isEmpty()) return 0
        val span = spans.lastOrNull { it.audioStartMs <= audioMs } ?: spans.first()
        val offset = (audioMs - span.audioStartMs).coerceIn(0, span.durationMs)
        return span.wallStartMs + offset
    }
}

/**
 * Reparte el audio de voz en bloques de hasta 30 s para Whisper, cortando en las uniones entre
 * tramos de voz (ahí había silencio, así que no se parte ninguna palabra).
 *
 * El codificador de Whisper procesa siempre ventanas de 30 s y es el 90 % del coste. Dejando
 * que Whisper avance solo, gasta más ventanas de las necesarias (6 en vez de 4 para 115 s en la
 * prueba de la fase 6a); con bloques de ≤30 s cada bloque es una sola pasada.
 */
object Chunker {
    const val MAX_CHUNK_MS = 29_500L

    /** Rangos [inicio, fin) en ms dentro del audio concatenado. */
    fun plan(segments: List<Segment>, maxMs: Long = MAX_CHUNK_MS): List<LongRange> {
        val ranges = ArrayList<LongRange>()
        var chunkStart = 0L
        var cursor = 0L
        for (s in segments) {
            var segStart = cursor
            val segEnd = cursor + s.durationMs
            // Un tramo de voz de más de 30 s no tiene silencio dentro: se corta donde toque.
            while (segEnd - chunkStart > maxMs) {
                if (segStart > chunkStart) {
                    // Cerrar el bloque en la unión anterior si ya tiene algo.
                    ranges += chunkStart until segStart
                    chunkStart = segStart
                } else {
                    ranges += chunkStart until chunkStart + maxMs
                    chunkStart += maxMs
                    segStart = chunkStart
                }
            }
            cursor = segEnd
        }
        if (cursor > chunkStart) ranges += chunkStart until cursor
        return ranges
    }
}

/** Frase de la transcripción con su hora real. */
@Serializable
data class TranscriptLine(
    val wallStartMs: Long,
    val wallEndMs: Long,
    val text: String,
    /** Hablante, cuando haya diarización (fase 8). */
    val speaker: String? = null,
)

/**
 * Filtro de alucinaciones típicas de Whisper. Con audio dudoso, Whisper "rellena" con frases
 * que aprendió de los subtítulos con que se entrenó (en español, sobre todo los créditos de Amara).
 */
object HallucinationFilter {

    private val PATTERNS = listOf(
        "amara\\.org", "subt[ií]tulos? (realizados|por|hechos)", "subtitulado por", "gracias por ver",
        "suscr[ií]bete", "dale (a )?like", "nos vemos en el pr[oó]ximo (v[ií]deo|cap[ií]tulo)",
        "^\\W*(m[uú]sica|aplausos|risas|silencio)\\W*$", "^\\W*\\[[^\\]]*\\]\\W*$", "^\\W*\\([^)]*\\)\\W*$",
        "thanks? (you )?for watching", "please subscribe",
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    data class Input(val startMs: Long, val endMs: Long, val noSpeechProb: Float, val text: String)

    fun clean(segments: List<Input>): List<Input> {
        val out = ArrayList<Input>()
        for (s in segments) {
            val text = s.text.trim()
            if (text.isEmpty() || text.all { !it.isLetterOrDigit() }) continue
            if (PATTERNS.any { it.containsMatchIn(text) }) continue
            // Muy probablemente no había voz y el texto es corto: inventado.
            if (s.noSpeechProb > 0.8f && text.length < 40) continue
            // Bucle: Whisper repite la misma frase una y otra vez.
            if (out.isNotEmpty() && out.last().text.equals(text, ignoreCase = true)) continue
            out += s.copy(text = text)
        }
        return out
    }
}

/** Utilidades de señal para preparar el audio que entra en Whisper. */
object Pcm {

    /** PCM 16 bits → float en [-1, 1]. */
    fun toFloat(samples: ShortArray, count: Int = samples.size): FloatArray = FloatArray(count) { samples[it] / 32768f }

    /**
     * Pasa de [fromRate] a 16 kHz. Para factores enteros (48 → 16 kHz, lo que da el decodificador
     * Opus de Android) usa un filtro paso bajo con ventana para no meter aliasing; para otros,
     * interpolación lineal.
     */
    fun to16k(input: FloatArray, fromRate: Int): FloatArray {
        if (fromRate == 16_000) return input
        if (fromRate % 16_000 == 0) return decimate(input, fromRate / 16_000)
        val ratio = fromRate / 16_000.0
        val n = (input.size / ratio).toInt()
        return FloatArray(n) { i ->
            val pos = i * ratio
            val a = pos.toInt()
            val b = minOf(a + 1, input.size - 1)
            val f = (pos - a).toFloat()
            input[a] * (1 - f) + input[b] * f
        }
    }

    fun decimate(input: FloatArray, factor: Int): FloatArray {
        require(factor >= 1)
        if (factor == 1) return input
        val taps = lowPass(factor)
        val half = taps.size / 2
        val out = FloatArray(input.size / factor)
        for (i in out.indices) {
            val center = i * factor
            var acc = 0f
            for (k in taps.indices) {
                val j = center + k - half
                if (j in input.indices) acc += input[j] * taps[k]
            }
            out[i] = acc
        }
        return out
    }

    /** Sinc con ventana de Hann, corte en la nueva frecuencia de Nyquist. */
    private fun lowPass(factor: Int): FloatArray {
        val n = 16 * factor + 1
        val cutoff = 1.0 / factor * 0.9
        val center = n / 2
        val taps = FloatArray(n) { i ->
            val x = (i - center).toDouble()
            val sinc = if (x == 0.0) cutoff else sin(PI * cutoff * x) / (PI * x)
            val window = 0.5 - 0.5 * cos(2 * PI * i / (n - 1))
            (sinc * window).toFloat()
        }
        val sum = taps.sum()
        for (i in taps.indices) taps[i] /= sum
        return taps
    }

    /**
     * Normaliza el volumen. El micrófono del reloj graba bajo (picos de ~0,05 en la fase 1);
     * se sube hasta un pico de [targetPeak], sin pasar de ×[maxGain] para no amplificar ruido.
     */
    fun normalize(input: FloatArray, targetPeak: Float = 0.7f, maxGain: Float = 12f): FloatArray {
        var peak = 0f
        for (v in input) peak = max(peak, abs(v))
        if (peak < 1e-5f) return input
        val gain = (targetPeak / peak).coerceAtMost(maxGain)
        if (gain <= 1f) return input
        return FloatArray(input.size) { input[it] * gain }
    }
}
