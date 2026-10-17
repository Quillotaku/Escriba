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

/** El panel «Hoy»: conversaciones, su estado, la semana por perfil y las tareas. */
class OverviewTest {

    private val zone = ZoneId.of("Europe/Madrid")
    private fun at(day: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 10, day, h, m).atZone(zone).toInstant().toEpochMilli()

    // Viernes 16 y sábado 17 de octubre de 2026; la semana empieza el lunes 12.
    private val now = at(17, 12)

    private val profiles = ProfileLoader.load(
        "---\nperfil_por_defecto: trabajo\n---\n",
        mapOf(
            "trabajo" to "---\nnombre: Trabajo\ncolor: \"#2E7D32\"\nseparar_tras_silencio: 15m\nredondeo: 5m\n---\n## Prompt\nResume.\n",
            "casual" to "---\nnombre: Casual\ncolor: \"#2C4DA8\"\n---\n## Prompt\nResume.\n",
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

    private fun note(session: String, conversation: Int) = NoteEntity(session, conversation, "n.md", "h", null, "trabajo", 0, 0)

    private fun insights(session: String, conversation: Int, title: String, tasks: List<Task> = emptyList(), createdAt: Long = now) =
        InsightsEntity(session, conversation, "gemma", "h", EscribaJson.encodeToString(Insights(title = title, summary = "", tasks = tasks)), createdAt)

    @Test
    fun `una sesion larga se parte en conversaciones por silencios como las notas`() {
        val s = session("s1", "trabajo", at(17, 9), at(17, 11))
        // 9:00–9:10 y, tras 50 min de silencio (más que los 15 del perfil), 10:00–10:20.
        val frags = listOf(fragment("s1", 0, at(17, 9), at(17, 9, 10)), fragment("s1", 1, at(17, 10), at(17, 10, 20)))
        val list = conversationsOf(listOf(s), frags, listOf(note("s1", 1)), listOf(insights("s1", 1, "Reunión")), profiles.profiles, profiles.defaultProfile, null, false)

        assertEquals(2, list.size)
        val (second, first) = list // de la más reciente a la más antigua
        assertEquals("Reunión", first.title)
        assertEquals(ConversationState.WRITTEN, first.state)
        assertEquals(10, first.roundedMin)
        assertNull(second.title)
        assertEquals(ConversationState.QUEUED, second.state)
        assertEquals(20, second.roundedMin)
    }

    @Test
    fun `el estado sigue a la sesion grabando procesando o con error`() {
        val live = session("vivo", "trabajo", at(17, 11), null, processed = false)
        val busy = session("ocupada", "trabajo", at(17, 8), at(17, 8, 30), processed = false)
        val failed = session("fallo", "trabajo", at(17, 7), at(17, 7, 10), error = "sin transcripción")
        val frags = listOf(
            fragment("vivo", 0, at(17, 11), at(17, 11, 5)),
            fragment("ocupada", 0, at(17, 8), at(17, 8, 30)),
            fragment("fallo", 0, at(17, 7), at(17, 7, 10)),
        )
        val states = conversationsOf(listOf(live, busy, failed), frags, emptyList(), emptyList(), profiles.profiles, profiles.defaultProfile, "ocupada", true)
            .associate { it.sessionId to it.state }
        assertEquals(ConversationState.RECORDING, states["vivo"])
        assertEquals(ConversationState.PROCESSING, states["ocupada"])
        assertEquals(ConversationState.FAILED, states["fallo"])
    }

    @Test
    fun `la semana suma minutos redondeados por perfil y hoy solo lo de hoy`() {
        val sessions = listOf(
            session("lunes", "casual", at(12, 18), at(12, 18, 32)),
            session("ayer", "trabajo", at(16, 10), at(16, 10, 3)),
            session("hoy", "trabajo", at(17, 9), at(17, 9, 41)),
            session("semana-pasada", "trabajo", at(9, 9), at(9, 10)),
        )
        val frags = listOf(
            fragment("lunes", 0, at(12, 18), at(12, 18, 32)),
            fragment("ayer", 0, at(16, 10), at(16, 10, 3)),
            fragment("hoy", 0, at(17, 9), at(17, 9, 41)),
            fragment("semana-pasada", 0, at(9, 9), at(9, 10)),
        )
        val conv = conversationsOf(sessions, frags, emptyList(), emptyList(), profiles.profiles, profiles.defaultProfile, null, false)
        val o = buildOverview(now, zone, conv, emptyList())

        assertEquals(listOf("hoy"), o.today.map { it.sessionId })
        // Trabajo fija «redondeo: 5m»: 5 (3 min) + 40 (41 min). Casual, al minuto: 32. La semana pasada no cuenta.
        assertEquals(mapOf("trabajo" to 45, "casual" to 32), o.week.associate { it.profileId to it.minutes })
        assertEquals(77, o.weekMinutes)
        assertEquals("trabajo", o.week.first().profileId)
    }

    @Test
    fun `las tareas de la semana llevan el titulo de su conversacion`() {
        val s = session("s1", "trabajo", at(17, 9), at(17, 9, 30))
        val frags = listOf(fragment("s1", 0, at(17, 9), at(17, 9, 30)))
        val all = listOf(
            insights("s1", 1, "Oferta", listOf(Task("Enviar la oferta", "Ana", "2026-10-19")), createdAt = at(17, 10)),
            insights("viejo", 1, "Antigua", listOf(Task("De otra semana")), createdAt = at(5, 10)),
        )
        val conv = conversationsOf(listOf(s), frags, emptyList(), all, profiles.profiles, profiles.defaultProfile, null, false)
        val tasks = buildOverview(now, zone, conv, all).tasks
        assertEquals(listOf("Enviar la oferta"), tasks.map { it.task.text })
        assertEquals("Oferta", tasks.single().conversationTitle)
    }

    @Test
    fun `duraciones como las escribe la app`() {
        assertEquals("40 min", formatMinutes(40))
        assertEquals("1 h", formatMinutes(60))
        assertEquals("1 h 25 min", formatMinutes(85))
        assertEquals("35 s", formatSpeech(35_000))
        assertEquals("3 min 35 s", formatSpeech(215_000))
        assertEquals("2 min", formatSpeech(120_000))
        assertEquals("1 h 2 min", formatSpeech(3_725_000))
    }

    @Test
    fun `la semana empieza el lunes a medianoche`() {
        assertEquals(at(12, 0), weekStart(now, zone))
        assertEquals(at(12, 0), weekStart(at(12, 0), zone))
    }
}
