package dev.escriba.mobile.ui.sessions

import dev.escriba.shared.transcription.TranscriptLine
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

/** Párrafos de la transcripción en el detalle de sesión: los mismos cortes que la nota. */
class ParagraphsTest {

    init {
        // Las horas se formatean en la zona del sistema: se fija para que el test no dependa del PC.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    private fun line(startS: Long, endS: Long, text: String, speaker: String? = null) =
        TranscriptLine(startS * 1000, endS * 1000, text, speaker)

    @Test
    fun `se corta al cambiar de hablante y tras una pausa`() {
        val p = paragraphs(
            listOf(
                line(0, 2, "Hola.", "Yo"),
                line(2, 4, "¿Qué tal?", "Yo"),
                line(4, 6, "Bien.", "Ana López"),
                line(12, 14, "Seguimos.", "Ana López"), // 6 s de pausa
            ),
            known = setOf("Ana López"),
        )
        assertEquals(listOf("Hola. ¿Qué tal?", "Bien.", "Seguimos."), p.map { it.text })
        assertEquals(listOf("Yo", "Ana López", "Ana López"), p.map { it.speaker })
        assertEquals("00:00:04", p[1].time)
    }

    @Test
    fun `yo y las voces registradas cuentan como conocidas`() {
        val p = paragraphs(listOf(line(0, 1, "a", "Yo"), line(1, 2, "b", "Hablante 2"), line(2, 3, "c", "Ana")), known = setOf("Ana"))
        assertEquals(listOf(true, false, true), p.map { it.known })
    }

    @Test
    fun `un parrafo no pasa de 400 caracteres`() {
        val long = "x".repeat(300)
        val p = paragraphs(listOf(line(0, 1, long), line(1, 2, long)), emptySet())
        assertEquals(2, p.size)
    }
}
