package dev.escriba.shared.audio

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpeechGateTest {

    private val random = Random(42)

    /** Low-amplitude background noise (≈ -66 dBFS). */
    private fun noise(n: Int = 320) = ShortArray(n) { (random.nextInt(-30, 30)).toShort() }

    /** 300 Hz tone as "voice" (≈ -33 dBFS, in the range the watch microphone gives). */
    private fun voice(n: Int = 320, offset: Long = 0) =
        ShortArray(n) { (sin(2 * PI * 300 * (offset + it) / 16_000) * 800).toInt().toShort() }

    private fun run(gate: SpeechGate, frames: List<ShortArray>) = frames.flatMap { gate.process(it) }

    @Test
    fun `silence produces no events`() {
        val gate = SpeechGate()
        assertTrue(run(gate, List(500) { noise() }).isEmpty())
        assertEquals(0, gate.speechFrames)
    }

    @Test
    fun `a sentence produces a start with pre-roll, audio and an end after the hangover`() {
        val gate = SpeechGate(preRollMs = 1_000, hangoverMs = 1_500)
        val frames = List(200) { noise() } + List(100) { voice() } + List(200) { noise() }
        val events = run(gate, frames)

        val started = events.filterIsInstance<SpeechGate.Event.SpeechStarted>()
        val ended = events.filterIsInstance<SpeechGate.Event.SpeechEnded>()
        assertEquals(1, started.size)
        assertEquals(1, ended.size)

        // Speech starts at frame 200 (sample 64,000); the pre-roll goes back 1 s (50 frames).
        assertEquals((200 - 50) * 320L, started.single().startSample)
        // It ends 1.5 s (75 frames) after the last voiced frame (299).
        assertEquals((300 + 75) * 320L, ended.single().endSample)

        val audio = events.filterIsInstance<SpeechGate.Event.Audio>()
        assertEquals(375 - 150, audio.size)
        // Frames are contiguous: no audio is lost or duplicated.
        audio.zipWithNext().forEach { (a, b) -> assertEquals(a.startSample + 320, b.startSample) }
    }

    @Test
    fun `a single knock doesn't trigger the VAD`() {
        val gate = SpeechGate(onsetFrames = 3)
        val frames = List(100) { noise() } + listOf(voice(), voice()) + List(100) { noise() }
        assertTrue(run(gate, frames).none { it is SpeechGate.Event.SpeechStarted })
    }

    @Test
    fun `short pauses within the hangover don't split the sentence`() {
        val gate = SpeechGate(hangoverMs = 1_500)
        val frames = List(100) { noise() } + List(50) { voice() } + List(40) { noise() } + List(50) { voice() } + List(200) { noise() }
        val events = run(gate, frames)
        assertEquals(1, events.count { it is SpeechGate.Event.SpeechStarted })
        assertEquals(1, events.count { it is SpeechGate.Event.SpeechEnded })
    }

    @Test
    fun `the floor is calibrated on the first frames`() {
        val gate = SpeechGate()
        val background = { ShortArray(320) { random.nextInt(-300, 300).toShort() } } // ≈ -45 dBFS
        val louder = { ShortArray(320) { random.nextInt(-3000, 3000).toShort() } } // ≈ -25 dBFS
        // With a loud background from the start no speech should be detected...
        assertTrue(run(gate, List(100) { background() }).none { it is SpeechGate.Event.SpeechStarted })
        // ...but it should when someone speaks over that background.
        assertTrue(run(gate, List(20) { louder() }).any { it is SpeechGate.Event.SpeechStarted })
    }

    @Test
    fun `flush closes an open stretch`() {
        val gate = SpeechGate()
        run(gate, List(100) { noise() } + List(20) { voice() })
        assertTrue(gate.isInSpeech)
        val events = gate.flush()
        assertEquals(listOf<SpeechGate.Event>(SpeechGate.Event.SpeechEnded(120 * 320L)), events)
    }

    @Test
    fun `the noise floor adapts when the background rises mid-recording`() {
        val gate = SpeechGate()
        // Silence first; then a fan (≈ -45 dBFS) is switched on and stays on.
        // At first it looks like speech, but the floor must rise and end up closing the stretch.
        val loudNoise = { ShortArray(320) { random.nextInt(-300, 300).toShort() } }
        val events = run(gate, List(100) { noise() } + List(3_000) { loudNoise() })
        val lastEnd = events.indexOfLast { it is SpeechGate.Event.SpeechEnded }
        val lastStart = events.indexOfLast { it is SpeechGate.Event.SpeechStarted }
        assertTrue(lastEnd > lastStart, "The VAD should end up closed with a steady background")
        assertTrue(gate.floorDb > -50, "Adapted floor: ${gate.floorDb}")
    }
}
