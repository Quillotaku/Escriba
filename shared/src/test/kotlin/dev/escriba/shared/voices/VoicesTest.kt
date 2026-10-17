package dev.escriba.shared.voices

import dev.escriba.shared.transcription.TranscriptLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoicesTest {

    private fun line(s: Long, e: Long, t: String) = TranscriptLine(s * 1000, e * 1000, t)

    @Test
    fun `merge une los hablantes con poca voz al principal mas parecido`() {
        val turns = listOf(
            SpeakerTurn(0, 30_000, 0),       // Yo
            SpeakerTurn(30_000, 60_000, 1),  // Ana
            SpeakerTurn(60_000, 65_000, 2),  // Ana con poca voz, justo después de mí
            SpeakerTurn(65_000, 90_000, 0),
        )
        val r = VoiceMath.merge(
            turns,
            mapOf(0 to floatArrayOf(1f, 0f), 1 to floatArrayOf(0f, 1f), 2 to floatArrayOf(0.5f, 0.86f)),
            mapOf(0 to 55_000L, 1 to 30_000L, 2 to 5_000L),
        )
        assertEquals(listOf(0, 1, 1, 0), r.turns.map { it.cluster })
    }

    @Test
    fun `merge une huellas parecidas y reparte a los hablantes con poca voz`() {
        val turns = listOf(
            SpeakerTurn(0, 10_000, 5),       // Ana
            SpeakerTurn(10_000, 20_000, 7),  // Luis
            SpeakerTurn(20_000, 21_000, 9),  // un segundo suelto: no es un hablante
            SpeakerTurn(21_000, 30_000, 3),  // Ana otra vez, con otro número
        )
        val ana = floatArrayOf(1f, 0f, 0f)
        val anaBis = floatArrayOf(0.95f, 0.1f, 0f)
        val luis = floatArrayOf(0f, 1f, 0f)
        val r = VoiceMath.merge(
            turns,
            mapOf(5 to ana, 7 to luis, 3 to anaBis),
            mapOf(5 to 10_000L, 7 to 10_000L, 9 to 1_000L, 3 to 9_000L),
        )
        assertEquals(listOf(0, 1, 1, 0), r.turns.map { it.cluster })
        assertEquals(setOf(0, 1), r.embeddings.keys)
        assertEquals(19_000L, r.speechMs[0])
        assertEquals(11_000L, r.speechMs[1])
    }

    @Test
    fun `cada frase toma el hablante con mas solape`() {
        val turns = listOf(SpeakerTurn(0, 10_000, 0), SpeakerTurn(10_000, 20_000, 1), SpeakerTurn(20_000, 25_000, 0))
        val lines = listOf(line(1, 9, "hola"), line(9, 14, "qué tal"), line(21, 24, "bien"), line(30, 31, "fuera"))
        val out = VoiceMath.assign(lines, turns) { "S$it" }
        assertEquals(listOf("S0", "S1", "S0", "S0"), out.map { it.speaker })
    }

    @Test
    fun `similitud coseno`() {
        assertEquals(1f, VoiceMath.cosine(floatArrayOf(1f, 2f, 3f), floatArrayOf(2f, 4f, 6f)), 1e-5f)
        assertEquals(0f, VoiceMath.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 1e-5f)
    }

    @Test
    fun `reconoce voces registradas sin repetirlas y numera el resto`() {
        val yo = floatArrayOf(1f, 0f, 0f)
        val ana = floatArrayOf(0f, 1f, 0f)
        val clusters = mapOf(
            0 to floatArrayOf(0.1f, 0.95f, 0f), // Ana
            1 to floatArrayOf(0.9f, 0.1f, 0.1f), // yo
            2 to floatArrayOf(0f, 0.1f, 1f), // desconocido
            3 to floatArrayOf(0.2f, 0.9f, 0.1f), // parecido a Ana, pero Ana ya está asignada
        )
        val names = VoiceMath.identify(clusters, listOf(EnrolledVoice("Yo", yo), EnrolledVoice("Ana López", ana)))
        assertEquals("Ana López", names[0])
        assertEquals("Yo", names[1])
        assertEquals("Hablante 1", names[2])
        assertEquals("Hablante 2", names[3])
    }

    @Test
    fun `media normalizada de huellas`() {
        val avg = VoiceMath.average(listOf(floatArrayOf(2f, 0f), floatArrayOf(0f, 4f)))
        assertEquals(0.5f, avg[0], 1e-5f)
        assertEquals(0.5f, avg[1], 1e-5f)
        assertTrue(VoiceMath.cosine(avg, floatArrayOf(1f, 1f)) > 0.99f)
    }
}
