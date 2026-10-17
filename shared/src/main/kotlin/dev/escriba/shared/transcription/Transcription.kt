package dev.escriba.shared.transcription

import dev.escriba.shared.Segment
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Maps positions in the transcribed audio to real time.
 *
 * The audio that reaches the recognizer is only the stretches of speech, back to back (the
 * watch's VAD removed the silences). Each stretch knows when it really started; this map puts
 * the right time on every sentence of the transcript.
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
 * Splits the speech audio into chunks of up to 30 s for Whisper, cutting at the joins between
 * stretches of speech (there was silence there, so no word is split).
 *
 * Whisper's encoder always processes 30 s windows and is 90 % of the cost. Left to advance on its
 * own, Whisper spends more windows than needed (6 instead of 4 for 115 s in the phase 6a test);
 * with chunks of ≤30 s each chunk is a single pass.
 */
object Chunker {
    const val MAX_CHUNK_MS = 29_500L

    /** [start, end) ranges in ms within the concatenated audio. */
    fun plan(segments: List<Segment>, maxMs: Long = MAX_CHUNK_MS): List<LongRange> {
        val ranges = ArrayList<LongRange>()
        var chunkStart = 0L
        var cursor = 0L
        for (s in segments) {
            var segStart = cursor
            val segEnd = cursor + s.durationMs
            // A stretch of speech longer than 30 s has no silence inside: cut wherever needed.
            while (segEnd - chunkStart > maxMs) {
                if (segStart > chunkStart) {
                    // Close the chunk at the previous join if it already has something.
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

/** A transcript sentence with its real time. */
@Serializable
data class TranscriptLine(
    val wallStartMs: Long,
    val wallEndMs: Long,
    val text: String,
    /** Speaker, when diarization ran. */
    val speaker: String? = null,
)

/**
 * Filter for Whisper's typical hallucinations. On doubtful audio, Whisper "fills in" with phrases
 * learned from the subtitles it was trained on (mostly subtitle credits such as Amara's). The
 * patterns cover Spanish and English, the languages tested so far.
 */
object HallucinationFilter {

    private val PATTERNS = listOf(
        "amara\\.org", "subt[ií]tulos? (realizados|por|hechos)", "subtitulado por", "gracias por ver",
        "suscr[ií]bete", "dale (a )?like", "nos vemos en el pr[oó]ximo (v[ií]deo|cap[ií]tulo)",
        "^\\W*(m[uú]sica|aplausos|risas|silencio|music|applause|laughter|silence)\\W*$", "^\\W*\\[[^\\]]*\\]\\W*$", "^\\W*\\([^)]*\\)\\W*$",
        "thanks? (you )?for watching", "please subscribe",
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    data class Input(val startMs: Long, val endMs: Long, val noSpeechProb: Float, val text: String)

    fun clean(segments: List<Input>): List<Input> {
        val out = ArrayList<Input>()
        for (s in segments) {
            val text = s.text.trim()
            if (text.isEmpty() || text.all { !it.isLetterOrDigit() }) continue
            if (PATTERNS.any { it.containsMatchIn(text) }) continue
            // Most likely there was no speech and the text is short: made up.
            if (s.noSpeechProb > 0.8f && text.length < 40) continue
            // Loop: Whisper repeats the same sentence over and over.
            if (out.isNotEmpty() && out.last().text.equals(text, ignoreCase = true)) continue
            out += s.copy(text = text)
        }
        return out
    }
}

/** Signal helpers to prepare the audio that goes into the recognizer. */
object Pcm {

    /** 16-bit PCM → float in [-1, 1]. */
    fun toFloat(samples: ShortArray, count: Int = samples.size): FloatArray = FloatArray(count) { samples[it] / 32768f }

    /**
     * Converts from [fromRate] to 16 kHz. For integer factors (48 → 16 kHz, what Android's Opus
     * decoder outputs) it uses a windowed low-pass filter to avoid aliasing; otherwise, linear
     * interpolation.
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

    /** Hann-windowed sinc, cut off at the new Nyquist frequency. */
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
     * Normalizes the volume. The watch's microphone records quietly (peaks of ~0.05 in phase 1);
     * it's raised to a peak of [targetPeak], at most ×[maxGain] so noise isn't amplified.
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
