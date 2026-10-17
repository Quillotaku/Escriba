package dev.escriba.mobile.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** El DAO sobre una base de datos en memoria. */
@RunWith(RobolectricTestRunner::class)
class SessionDaoTest {

    private lateinit var db: EscribaDb
    private val dao get() = db.sessions()

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), EscribaDb::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun close() = db.close()

    private fun session(id: String = "s1") = SessionEntity(
        id = id, profileId = "trabajo", profileHash = "h", startMs = 1_000, lastSpeechEndMs = 1_000, updatedAtMs = 1_000,
    )

    private fun fragment(seq: Int, startMs: Long, endMs: Long, speechMs: Long, size: Long) = FragmentEntity(
        sessionId = "s1", seq = seq, startMs = startMs, endMs = endMs, segmentsJson = "[]",
        speechMs = speechMs, sizeBytes = size, sha256 = "x$seq", path = "/f$seq.ogg", receivedAtMs = endMs,
    )

    @Test
    fun `cada fragmento suma a su sesion`() = runTest {
        dao.addFragment(fragment(0, 1_000, 31_000, 20_000, 100), session())
        dao.addFragment(fragment(1, 40_000, 70_000, 15_000, 50), session())
        val s = dao.session("s1")!!
        assertEquals(2, s.receivedFragments)
        assertEquals(35_000, s.speechMs)
        assertEquals(150, s.bytes)
        assertEquals(70_000, s.lastSpeechEndMs)
    }

    @Test
    fun `borrar una sesion borra todo lo suyo y nada mas`() = runTest {
        dao.addFragment(fragment(0, 1_000, 31_000, 20_000, 100), session())
        dao.upsertSession(session("s2"))
        dao.upsertNote(NoteEntity("s1", 1, "Trabajo/n.md", "h", null, "trabajo", 1_000, 2_000))
        dao.upsertTranscript(TranscriptEntity("s1", "q8", "es", "[]", 30_000, 9_000, 2_000))
        dao.upsertInsights(InsightsEntity("s1", 1, "gemma", "h", "{}", 2_000))
        dao.upsertDiarization(DiarizationEntity("s1", "[]", "{}", "{}", 2_000))
        dao.upsertVoice(VoiceEntity("Yo", "AAAA", 1, 2_000))

        dao.delete("s1")

        assertNull(dao.session("s1"))
        assertTrue(dao.fragments("s1").isEmpty())
        assertTrue(dao.notes("s1").isEmpty())
        assertNull(dao.transcript("s1"))
        assertTrue(dao.insights("s1").isEmpty())
        assertNull(dao.diarization("s1"))
        // Otra sesión y las voces registradas no se tocan.
        assertEquals("s2", dao.session("s2")?.id)
        assertEquals(listOf("Yo"), dao.voices().map { it.name })
    }

    @Test
    fun `una voz registrada se actualiza en su sitio`() = runTest {
        dao.upsertVoice(VoiceEntity("Ana", "AAAA", 1, 1))
        dao.upsertVoice(VoiceEntity("Ana", "BBBB", 2, 2))
        val voices = dao.observeVoices().first()
        assertEquals(1, voices.size)
        assertEquals(2, voices.single().samples)
    }
}
