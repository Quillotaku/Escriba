package dev.escriba.shared.recording

import dev.escriba.shared.Segment
import dev.escriba.shared.audio.SpeechGate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FragmenterTest {

    private val sr = 16_000
    private val frame = 320 // 20 ms
    private val t0 = 1_760_000_000_000L

    private class FakeWriter(val seq: Int) : FragmentWriter {
        var samples = 0L
        val journal = ArrayList<String>()
        var closed = false
        var discarded = false
        override fun write(samples: ShortArray) { this.samples += samples.size }
        override fun journal(line: String) { journal += line }
        override fun close() { closed = true }
        override fun discard() { discarded = true }
    }

    private val writers = ArrayList<FakeWriter>()
    private val closed = ArrayList<ClosedFragment>()

    private fun fragmenter(maxSpeechMs: Long = 60_000, closeAfterSilenceMs: Long = 30_000) = Fragmenter(
        sampleRate = sr,
        wallClockAt = { sample -> t0 + sample * 1000 / sr },
        openWriter = { seq -> FakeWriter(seq).also { writers += it } },
        onClosed = { closed += it },
        maxSpeechMs = maxSpeechMs,
        closeAfterSilenceMs = closeAfterSilenceMs,
    )

    /** Simulates the VAD's output: a stretch of speech of [frames] frames from frame [at]. */
    private fun Fragmenter.speech(at: Long, frames: Int) {
        onEvent(SpeechGate.Event.SpeechStarted(at * frame))
        for (i in 0 until frames) {
            onEvent(SpeechGate.Event.Audio(ShortArray(frame), (at + i) * frame))
            onTick((at + i + 1) * frame)
        }
        onEvent(SpeechGate.Event.SpeechEnded((at + frames) * frame))
    }

    private fun Fragmenter.silence(from: Long, frames: Int) {
        for (i in 0 until frames) onTick((from + i + 1) * frame)
    }

    @Test
    fun `two nearby sentences go to the same fragment with their real time`() {
        val f = fragmenter()
        f.speech(at = 100, frames = 150) // 3 s at 2 s
        f.silence(from = 250, frames = 250) // 5 s of silence
        f.speech(at = 500, frames = 100) // 2 s at 10 s
        f.finish()

        assertEquals(1, closed.size)
        val c = closed.single()
        assertEquals(
            listOf(Segment(0, t0 + 2_000, 3_000), Segment(3_000, t0 + 10_000, 2_000)),
            c.segments,
        )
        assertEquals(t0 + 2_000, c.startMs)
        assertEquals(t0 + 12_000, c.endMs)
        assertTrue(c.last)
        assertEquals(listOf("S 0 ${t0 + 2_000}", "E 3000", "S 3000 ${t0 + 10_000}", "E 2000"), writers.single().journal)
    }

    @Test
    fun `a long silence closes the fragment and the next sentence opens another`() {
        val f = fragmenter(closeAfterSilenceMs = 30_000)
        f.speech(at = 0, frames = 100)
        f.silence(from = 100, frames = 1_600) // 32 s
        assertEquals(1, closed.size)
        assertFalse(closed.single().last)
        f.speech(at = 1_700, frames = 50)
        f.finish()
        assertEquals(listOf(0, 1), closed.map { it.seq })
        assertTrue(closed.last().last)
    }

    @Test
    fun `a long sentence is split into 60 s fragments without losing audio`() {
        val f = fragmenter(maxSpeechMs = 60_000)
        f.speech(at = 0, frames = 7_500) // 150 s in a row
        f.finish()

        assertEquals(listOf(0, 1, 2), closed.map { it.seq })
        assertEquals(listOf(60_000L, 60_000L, 30_000L), closed.map { it.segments.single().durationMs })
        // The fragments chain in real time without gaps.
        assertEquals(t0, closed[0].startMs)
        assertEquals(closed[0].endMs, closed[1].startMs)
        assertEquals(closed[1].endMs, closed[2].startMs)
        assertEquals(t0 + 150_000, closed[2].endMs)
        assertEquals(7_500L * frame, writers.sumOf { it.samples })
    }

    @Test
    fun `finish without speech leaves no fragments or numbering gaps`() {
        val f = fragmenter()
        f.silence(from = 0, frames = 100)
        f.finish()
        assertTrue(closed.isEmpty())
        assertEquals(0, f.fragmentCount)
    }

    @Test
    fun `finish mid-sentence closes the segment`() {
        val f = fragmenter()
        f.onEvent(SpeechGate.Event.SpeechStarted(0))
        repeat(100) { f.onEvent(SpeechGate.Event.Audio(ShortArray(frame), it.toLong() * frame)) }
        f.finish()
        assertEquals(2_000, closed.single().segments.single().durationMs)
    }
}

class FragmentJournalTest {

    @Test
    fun `rebuilds closed segments and the open one`() {
        val lines = listOf("S 0 1000", "E 3000", "S 3000 9000")
        assertEquals(
            listOf(Segment(0, 1000, 3000), Segment(3000, 9000, 4500)),
            FragmentJournal.recover(lines, audioDurationMs = 7_500),
        )
    }

    @Test
    fun `trims what was lost at the end of the Ogg`() {
        val lines = listOf("S 0 1000", "E 3000", "S 3000 9000", "E 2000")
        // Only 3.5 s were saved: the second segment is left with 0.5 s.
        assertEquals(
            listOf(Segment(0, 1000, 3000), Segment(3000, 9000, 500)),
            FragmentJournal.recover(lines, audioDurationMs = 3_500),
        )
    }

    @Test
    fun `ignores corrupt lines`() {
        val lines = listOf("S 0 1000", "garbage", "E", "E 10", "S x y")
        assertEquals(listOf(Segment(0, 1000, 10)), FragmentJournal.recover(lines, audioDurationMs = 5_000))
    }
}

class ScheduleTest {

    private val day = DailyWindow(LocalTime.of(7, 30), LocalTime.of(23, 30))
    private val night = DailyWindow(LocalTime.of(22, 0), LocalTime.of(6, 0))

    @Test
    fun `daytime window`() {
        assertTrue(day.contains(LocalTime.of(7, 30)))
        assertTrue(day.contains(LocalTime.of(23, 29)))
        assertFalse(day.contains(LocalTime.of(23, 30)))
        assertFalse(day.contains(LocalTime.of(3, 0)))
    }

    @Test
    fun `window crossing midnight`() {
        assertTrue(night.contains(LocalTime.of(23, 0)))
        assertTrue(night.contains(LocalTime.of(5, 59)))
        assertFalse(night.contains(LocalTime.of(12, 0)))
    }

    @Test
    fun `next change`() {
        val d = LocalDateTime.of(2026, 10, 16, 0, 0)
        assertEquals(d.withHour(23).withMinute(30), day.nextChange(d.withHour(12)))
        assertEquals(d.plusDays(1).withHour(7).withMinute(30), day.nextChange(d.withHour(23).withMinute(45)))
        assertEquals(d.withHour(7).withMinute(30), day.nextChange(d.withHour(1)))
        // At the exact boundary instant, the next change is the other end.
        assertEquals(d.withHour(23).withMinute(30), day.nextChange(d.withHour(7).withMinute(30)))
        assertEquals(d.plusDays(1).withHour(6), night.nextChange(d.withHour(22)))
    }

    @Test
    fun `all day and parsing text`() {
        val all = DailyWindow(LocalTime.MIDNIGHT, LocalTime.MIDNIGHT)
        assertTrue(all.contains(LocalTime.of(3, 0)))
        assertNull(all.nextChange(LocalDateTime.now()))
        assertEquals(day, DailyWindow.parse("07:30-23:30"))
        assertEquals(night, DailyWindow.parse("22:00 – 06:00"))
        assertNull(DailyWindow.parse("25:00-26:00"))
    }
}
