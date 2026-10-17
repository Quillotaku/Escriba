package dev.escriba.mobile.ui.sessions

import dev.escriba.shared.transcription.TranscriptLine
import dev.escriba.shared.voices.VoiceMath
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

/** Transcript paragraphs in the session detail: the same breaks as the note. */
class ParagraphsTest {

    init {
        // Times are formatted in the system zone: it's pinned so the test doesn't depend on the PC.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    private fun line(startS: Long, endS: Long, text: String, speaker: String? = null) =
        TranscriptLine(startS * 1000, endS * 1000, text, speaker)

    @Test
    fun `breaks on a speaker change and after a pause`() {
        val p = paragraphs(
            listOf(
                line(0, 2, "Hi.", VoiceMath.ME),
                line(2, 4, "How are you?", VoiceMath.ME),
                line(4, 6, "Fine.", "Ana López"),
                line(12, 14, "Let's go on.", "Ana López"), // 6 s pause
            ),
            known = setOf("Ana López"),
        )
        assertEquals(listOf("Hi. How are you?", "Fine.", "Let's go on."), p.map { it.text })
        assertEquals(listOf(VoiceMath.ME, "Ana López", "Ana López"), p.map { it.speaker })
        assertEquals("00:00:04", p[1].time)
    }

    @Test
    fun `me and the enrolled voices count as known`() {
        val p = paragraphs(listOf(line(0, 1, "a", VoiceMath.ME), line(1, 2, "b", "Speaker 2"), line(2, 3, "c", "Ana")), known = setOf("Ana"))
        assertEquals(listOf(true, false, true), p.map { it.known })
    }

    @Test
    fun `a paragraph doesn't go over 400 characters`() {
        val long = "x".repeat(300)
        val p = paragraphs(listOf(line(0, 1, long), line(1, 2, long)), emptySet())
        assertEquals(2, p.size)
    }
}
