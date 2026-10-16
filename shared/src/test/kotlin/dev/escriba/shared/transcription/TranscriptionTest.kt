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
    fun `las posiciones del audio se traducen a hora real saltando los silencios`() {
        // Dos tramos de voz: 10 s a las 10:00:00 y 5 s a las 10:05:00.
        val t0 = 1_000_000L
        val timeline = Timeline(listOf(Segment(0, t0, 10_000), Segment(10_000, t0 + 300_000, 5_000)))
        assertEquals(15_000, timeline.totalMs)
        assertEquals(t0, timeline.wallAt(0))
        assertEquals(t0 + 4_000, timeline.wallAt(4_000))
        assertEquals(t0 + 300_000, timeline.wallAt(10_000))
        assertEquals(t0 + 302_500, timeline.wallAt(12_500))
        assertEquals(t0 + 305_000, timeline.wallAt(20_000)) // más allá del final: el final
    }
}

class ChunkerTest {
    private fun segs(vararg seconds: Int) = seconds.map { Segment(0, 0, it * 1000L) }

    @Test
    fun `agrupa tramos hasta 30 s cortando en las uniones`() {
        // 10 + 12 + 6 = 28 s caben; el siguiente de 9 s abre otro bloque.
        assertEquals(listOf(0L until 28_000, 28_000L until 37_000), Chunker.plan(segs(10, 12, 6, 9)))
    }

    @Test
    fun `un tramo largo se parte en bloques de 30 s`() {
        assertEquals(listOf(0L until 29_500, 29_500L until 59_000, 59_000L until 70_000), Chunker.plan(segs(70)))
    }

    @Test
    fun `un tramo largo tras otros cierra antes el bloque en la union`() {
        // 5 s y luego 40 s: el primer bloque acaba a los 5 s; luego 29,5 s y el resto.
        assertEquals(listOf(0L until 5_000, 5_000L until 34_500, 34_500L until 45_000), Chunker.plan(segs(5, 40)))
    }

    @Test
    fun `cubre todo el audio sin huecos ni solapes`() {
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
    fun `quita las alucinaciones tipicas y conserva el texto real`() {
        val input = listOf(
            s("Hola, ¿qué tal la reunión?"),
            s("Subtítulos realizados por la comunidad de Amara.org"),
            s("[Música]"),
            s("..."),
            s("Gracias por ver el vídeo."),
            s("Vale, vale.", nsp = 0.95f),
            s("Hay que enviar el presupuesto el viernes."),
            s("Hay que enviar el presupuesto el viernes."),
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
    fun `48 a 16 kHz conserva la voz y elimina lo que no cabe`() {
        val voice = Pcm.to16k(tone(1_000.0, 48_000, 1.0), 48_000)
        assertEquals(16_000, voice.size)
        assertTrue(abs(rms(voice) - 0.5 / sqrt(2.0)) < 0.02, "1 kHz debe pasar: ${rms(voice)}")
        // 12 kHz está por encima de la nueva Nyquist (8 kHz): debe quedar muy atenuado.
        val alias = Pcm.to16k(tone(12_000.0, 48_000, 1.0), 48_000)
        assertTrue(rms(alias) < 0.02, "12 kHz debe filtrarse: ${rms(alias)}")
    }

    @Test
    fun `normalizar sube el volumen con limite`() {
        val quiet = FloatArray(100) { if (it == 50) 0.05f else 0.01f }
        val n = Pcm.normalize(quiet)
        assertEquals(0.6f, n[50], 1e-4f) // ganancia máxima ×12
        val loud = FloatArray(10) { 0.9f }
        assertTrue(Pcm.normalize(loud) === loud)
    }
}
