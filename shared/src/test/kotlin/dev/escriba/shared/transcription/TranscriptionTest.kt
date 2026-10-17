package dev.escriba.shared.transcription

import dev.escriba.shared.Segment
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TimelineTest {
    @Test
    fun `audio positions map to real time skipping the silences`() {
        // Two stretches of speech: 10 s at 10:00:00 and 5 s at 10:05:00.
        val t0 = 1_000_000L
        val timeline = Timeline(listOf(Segment(0, t0, 10_000), Segment(10_000, t0 + 300_000, 5_000)))
        assertEquals(15_000, timeline.totalMs)
        assertEquals(t0, timeline.wallAt(0))
        assertEquals(t0 + 4_000, timeline.wallAt(4_000))
        assertEquals(t0 + 300_000, timeline.wallAt(10_000))
        assertEquals(t0 + 302_500, timeline.wallAt(12_500))
        assertEquals(t0 + 305_000, timeline.wallAt(20_000)) // past the end: the end
    }
}

class ChunkerTest {
    private fun segs(vararg seconds: Int) = seconds.map { Segment(0, 0, it * 1000L) }

    @Test
    fun `groups stretches up to 30 s cutting at the joins`() {
        // 10 + 12 + 6 = 28 s fit; the next one of 9 s opens another chunk.
        assertEquals(listOf(0L until 28_000, 28_000L until 37_000), Chunker.plan(segs(10, 12, 6, 9)))
    }

    @Test
    fun `a long stretch is split into 30 s chunks`() {
        assertEquals(listOf(0L until 29_500, 29_500L until 59_000, 59_000L until 70_000), Chunker.plan(segs(70)))
    }

    @Test
    fun `a long stretch after others closes the chunk at the join first`() {
        // 5 s then 40 s: the first chunk ends at 5 s; then 29.5 s and the rest.
        assertEquals(listOf(0L until 5_000, 5_000L until 34_500, 34_500L until 45_000), Chunker.plan(segs(5, 40)))
    }

    @Test
    fun `covers all the audio without gaps or overlaps`() {
        val s = segs(3, 25, 2, 31, 8, 8, 8, 8, 60, 1)
        val ranges = Chunker.plan(s)
        assertEquals(0L, ranges.first().first)
        assertEquals(s.sumOf { it.durationMs }, ranges.last().last + 1)
        ranges.zipWithNext().forEach { (a, b) -> assertEquals(a.last + 1, b.first) }
        assertTrue(ranges.all { it.last - it.first + 1 <= Chunker.MAX_CHUNK_MS })
    }
}

class HallucinationFilterTest {
    private fun s(text: String, nsp: Float = 0.1f) = HallucinationFilter.Input(0, 1000, nsp, text)

    @Test
    fun `removes typical hallucinations and keeps the real text`() {
        val input = listOf(
            s("Hola, ¿qué tal la reunión?"),
            s("Subtítulos realizados por la comunidad de Amara.org"),
            s("[Música]"),
            s("..."),
            s("Gracias por ver el vídeo."),
            s("Vale, vale.", nsp = 0.95f),
            s("Hay que enviar el presupuesto el viernes."),
            s("Hay que enviar el presupuesto el viernes."),
            s("Thanks for watching!"),
            s("(applause)"),
        )
        assertEquals(
            listOf("Hola, ¿qué tal la reunión?", "Hay que enviar el presupuesto el viernes."),
            HallucinationFilter.clean(input).map { it.text },
        )
    }
}

class PcmTest {
    private fun tone(freq: Double, rate: Int, seconds: Double) =
        FloatArray((rate * seconds).toInt()) { (0.5 * sin(2 * PI * freq * it / rate)).toFloat() }

    private fun rms(x: FloatArray, from: Int = 200) = sqrt(x.drop(from).dropLast(from).sumOf { it.toDouble() * it } / (x.size - 2 * from))

    @Test
    fun `48 to 16 kHz keeps the voice and removes what doesn't fit`() {
        val voice = Pcm.to16k(tone(1_000.0, 48_000, 1.0), 48_000)
        assertEquals(16_000, voice.size)
        assertTrue(abs(rms(voice) - 0.5 / sqrt(2.0)) < 0.02, "1 kHz must pass: ${rms(voice)}")
        // 12 kHz is above the new Nyquist (8 kHz): it must be strongly attenuated.
        val alias = Pcm.to16k(tone(12_000.0, 48_000, 1.0), 48_000)
        assertTrue(rms(alias) < 0.02, "12 kHz must be filtered: ${rms(alias)}")
    }

    @Test
    fun `normalizing raises the volume with a limit`() {
        val quiet = FloatArray(100) { if (it == 50) 0.05f else 0.01f }
        val n = Pcm.normalize(quiet)
        assertEquals(0.6f, n[50], 1e-4f) // maximum gain ×12
        val loud = FloatArray(10) { 0.9f }
        assertTrue(Pcm.normalize(loud) === loud)
    }
}
