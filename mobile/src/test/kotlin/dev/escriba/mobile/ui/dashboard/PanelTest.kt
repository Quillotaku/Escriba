package dev.escriba.mobile.ui.dashboard

import dev.escriba.mobile.data.UsageEntity
import dev.escriba.mobile.ui.today.ConversationItem
import dev.escriba.mobile.ui.today.ConversationState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** The dashboard: periods, time per profile and model usage. */
class PanelTest {

    private val zone = ZoneId.of("Europe/Madrid")
    private fun at(month: Int, day: Int, h: Int = 12) = LocalDateTime.of(2026, month, day, h, 0).atZone(zone).toInstant().toEpochMilli()
    private val now = at(10, 17)

    private fun conv(session: String, start: Long, minutes: Int, profile: String) = ConversationItem(
        sessionId = session, index = 1, startMs = start, endMs = start + minutes * 60_000L, speechMs = 0, roundedMin = minutes,
        profileId = profile, profileName = profile.replaceFirstChar { it.uppercase() }, profileColor = null, title = null,
        state = ConversationState.WRITTEN,
    )

    private fun usage(kind: String, model: String, at: Long, audioMin: Int = 0, computeMin: Int = 0, prompt: Int = 0, generated: Int = 0) =
        UsageEntity(kind = kind, model = model, sessionId = "s", audioMs = audioMin * 60_000L, computeMs = computeMin * 60_000L,
            promptTokens = prompt, generatedTokens = generated, atMs = at)

    @Test
    fun `week month and all start where they should`() {
        assertEquals(at(10, 12, 0), periodStart(now, zone, Period.WEEK)) // Monday the 12th
        assertEquals(at(10, 1, 0), periodStart(now, zone, Period.MONTH))
        assertEquals(0L, periodStart(now, zone, Period.ALL))
    }

    @Test
    fun `usage is grouped by model and only counts the period`() {
        val usage = listOf(
            usage(UsageEntity.ASR, "ggml-large-v3-turbo-q8_0.bin", at(10, 16), audioMin = 30, computeMin = 8),
            usage(UsageEntity.ASR, "ggml-large-v3-turbo-q8_0.bin", at(10, 17), audioMin = 10, computeMin = 2),
            usage(UsageEntity.ASR, "parakeet-tdt-0.6b-v3", at(10, 17), audioMin = 20, computeMin = 1),
            usage(UsageEntity.ASR, "ggml-large-v3-turbo-q8_0.bin", at(9, 20), audioMin = 99), // another month
            usage(UsageEntity.LLM, "gemma-3-4b-it-Q4_K_M.gguf", at(10, 17), prompt = 1500, generated = 170),
            usage(UsageEntity.LLM, "gemma-3-4b-it-Q4_K_M.gguf", at(10, 13), prompt = 600, generated = 140),
        )
        val p = buildPanel(periodStart(now, zone, Period.WEEK), emptyList(), emptyList(), usage)

        assertEquals(listOf("large-v3-turbo-q8_0", "parakeet-tdt-0.6b-v3"), p.transcription.map { it.model })
        assertEquals(40 * 60_000L, p.transcription.first().audioMs)
        assertEquals(60 * 60_000L, p.audioMs)
        assertEquals(11.0 / 60, p.realTimeFactor, 1e-9)

        assertEquals(listOf("gemma-3-4b-it-Q4_K_M"), p.summaries.map { it.model })
        assertEquals(2, p.summaries.single().runs)
        assertEquals(2100L, p.promptTokens)
        assertEquals(310L, p.generatedTokens)
    }

    @Test
    fun `time per profile adds up the period`() {
        val convs = listOf(
            conv("a", at(10, 15), 41, "work"),
            conv("b", at(10, 16), 3, "work"),
            conv("c", at(10, 14), 32, "personal"),
            conv("d", at(10, 2), 60, "work"), // outside the week
        )
        val p = buildPanel(periodStart(now, zone, Period.WEEK), convs, emptyList(), emptyList())
        assertEquals(mapOf("work" to 44, "personal" to 32), p.byProfile.associate { it.profileId to it.minutes })
        assertEquals(76, p.totalMinutes)
    }

    @Test
    fun `short model names and figures with a thousands separator`() {
        assertEquals("large-v3-turbo-q8_0", shortModelName("ggml-large-v3-turbo-q8_0.bin"))
        assertEquals("gemma-3-4b-it-Q4_K_M", shortModelName("/models/gemma-3-4b-it-Q4_K_M.gguf"))
        assertEquals("12,345", formatCount(12_345))
        assertEquals("170", formatCount(170))
    }
}
