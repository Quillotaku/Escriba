package dev.escriba.mobile.ui.today

import dev.escriba.mobile.data.FragmentEntity
import dev.escriba.mobile.data.InsightsEntity
import dev.escriba.mobile.data.NoteEntity
import dev.escriba.mobile.data.SessionEntity
import dev.escriba.shared.EscribaJson
import dev.escriba.shared.Segment
import dev.escriba.shared.llm.Insights
import dev.escriba.shared.llm.Task
import dev.escriba.shared.vault.ProfileLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** The Today screen: conversations, their state, the week per profile and the tasks. */
class OverviewTest {

    private val zone = ZoneId.of("Europe/Madrid")
    private fun at(day: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 10, day, h, m).atZone(zone).toInstant().toEpochMilli()

    // Friday 16 and Saturday 17 October 2026; the week starts on Monday the 12th.
    private val now = at(17, 12)

    private val profiles = ProfileLoader.load(
        "---\ndefault_profile: work\n---\n",
        mapOf(
            "work" to "---\nname: Work\ncolor: \"#2E7D32\"\nsplit_after_silence: 15m\nrounding: 5m\n---\n## Prompt\nSummarize.\n",
            "personal" to "---\nname: Personal\ncolor: \"#2C4DA8\"\n---\n## Prompt\nSummarize.\n",
        ),
    )

    private fun session(id: String, profile: String, start: Long, end: Long?, processed: Boolean = true, error: String? = null) = SessionEntity(
        id = id, profileId = profile, profileHash = "h", startMs = start, lastSpeechEndMs = end ?: start, endMs = end,
        expectedFragments = 1, receivedFragments = 1, updatedAtMs = start,
        processedAtMs = if (processed) end else null, processError = error,
    )

    private fun fragment(session: String, seq: Int, start: Long, end: Long) = FragmentEntity(
        sessionId = session, seq = seq, startMs = start, endMs = end,
        segmentsJson = EscribaJson.encodeToString(listOf(Segment(audioOffsetMs = 0, wallStartMs = start, durationMs = end - start))),
        speechMs = end - start, sizeBytes = 100, sha256 = "$session$seq", path = "/$session$seq.ogg", receivedAtMs = end,
    )

    private fun note(session: String, conversation: Int) = NoteEntity(session, conversation, "n.md", "h", null, "work", 0, 0)

    private fun insights(session: String, conversation: Int, title: String, tasks: List<Task> = emptyList(), createdAt: Long = now) =
        InsightsEntity(session, conversation, "gemma", "h", EscribaJson.encodeToString(Insights(title = title, summary = "", tasks = tasks)), createdAt)

    @Test
    fun `a long session is split into conversations by silences like the notes`() {
        val s = session("s1", "work", at(17, 9), at(17, 11))
        // 9:00–9:10 and, after 50 min of silence (more than the profile's 15), 10:00–10:20.
        val frags = listOf(fragment("s1", 0, at(17, 9), at(17, 9, 10)), fragment("s1", 1, at(17, 10), at(17, 10, 20)))
        val list = conversationsOf(listOf(s), frags, listOf(note("s1", 1)), listOf(insights("s1", 1, "Meeting")), profiles.profiles, profiles.defaultProfile, null, false)

        assertEquals(2, list.size)
        val (second, first) = list // from the most recent to the oldest
        assertEquals("Meeting", first.title)
        assertEquals(ConversationState.WRITTEN, first.state)
        assertEquals(10, first.roundedMin)
        assertNull(second.title)
        assertEquals(ConversationState.QUEUED, second.state)
        assertEquals(20, second.roundedMin)
    }

    @Test
    fun `the state follows the session recording processing or failed`() {
        val live = session("live", "work", at(17, 11), null, processed = false)
        val busy = session("busy", "work", at(17, 8), at(17, 8, 30), processed = false)
        val failed = session("failed", "work", at(17, 7), at(17, 7, 10), error = "without a transcript")
        val frags = listOf(
            fragment("live", 0, at(17, 11), at(17, 11, 5)),
            fragment("busy", 0, at(17, 8), at(17, 8, 30)),
            fragment("failed", 0, at(17, 7), at(17, 7, 10)),
        )
        val states = conversationsOf(listOf(live, busy, failed), frags, emptyList(), emptyList(), profiles.profiles, profiles.defaultProfile, "busy", true)
            .associate { it.sessionId to it.state }
        assertEquals(ConversationState.RECORDING, states["live"])
        assertEquals(ConversationState.PROCESSING, states["busy"])
        assertEquals(ConversationState.FAILED, states["failed"])
    }

    @Test
    fun `the week adds rounded minutes per profile and today only today`() {
        val sessions = listOf(
            session("monday", "personal", at(12, 18), at(12, 18, 32)),
            session("yesterday", "work", at(16, 10), at(16, 10, 3)),
            session("today", "work", at(17, 9), at(17, 9, 41)),
            session("last-week", "work", at(9, 9), at(9, 10)),
        )
        val frags = listOf(
            fragment("monday", 0, at(12, 18), at(12, 18, 32)),
            fragment("yesterday", 0, at(16, 10), at(16, 10, 3)),
            fragment("today", 0, at(17, 9), at(17, 9, 41)),
            fragment("last-week", 0, at(9, 9), at(9, 10)),
        )
        val conv = conversationsOf(sessions, frags, emptyList(), emptyList(), profiles.profiles, profiles.defaultProfile, null, false)
        val o = buildOverview(now, zone, conv, emptyList())

        assertEquals(listOf("today"), o.today.map { it.sessionId })
        // Work sets "rounding: 5m": 5 (3 min) + 40 (41 min). Personal, to the minute: 32. Last week doesn't count.
        assertEquals(mapOf("work" to 45, "personal" to 32), o.week.associate { it.profileId to it.minutes })
        assertEquals(77, o.weekMinutes)
        assertEquals("work", o.week.first().profileId)
    }

    @Test
    fun `the week's tasks carry their conversation's title`() {
        val s = session("s1", "work", at(17, 9), at(17, 9, 30))
        val frags = listOf(fragment("s1", 0, at(17, 9), at(17, 9, 30)))
        val all = listOf(
            insights("s1", 1, "Offer", listOf(Task("Send the offer", "Ana", "2026-10-19")), createdAt = at(17, 10)),
            insights("old", 1, "Old", listOf(Task("From another week")), createdAt = at(5, 10)),
        )
        val conv = conversationsOf(listOf(s), frags, emptyList(), all, profiles.profiles, profiles.defaultProfile, null, false)
        val tasks = buildOverview(now, zone, conv, all).tasks
        assertEquals(listOf("Send the offer"), tasks.map { it.task.text })
        assertEquals("Offer", tasks.single().conversationTitle)
    }

    @Test
    fun `durations as the app writes them`() {
        assertEquals("40 min", formatMinutes(40))
        assertEquals("1 h", formatMinutes(60))
        assertEquals("1 h 25 min", formatMinutes(85))
        assertEquals("35 s", formatSpeech(35_000))
        assertEquals("3 min 35 s", formatSpeech(215_000))
        assertEquals("2 min", formatSpeech(120_000))
        assertEquals("1 h 2 min", formatSpeech(3_725_000))
    }

    @Test
    fun `the week starts on Monday at midnight`() {
        assertEquals(at(12, 0), weekStart(now, zone))
        assertEquals(at(12, 0), weekStart(at(12, 0), zone))
    }
}
