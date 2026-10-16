package dev.escriba.shared.audio

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpeechGateTest {

    private val random = Random(42)

    /** Ruido de fondo de baja amplitud (≈ -66 dBFS). */
    private fun noise(n: Int = 320) = ShortArray(n) { (random.nextInt(-30, 30)).toShort() }

    /** Tono de 300 Hz como "voz" (≈ -33 dBFS, en el rango que da el micrófono del reloj). */
    private fun voice(n: Int = 320, offset: Long = 0) =
        ShortArray(n) { (sin(2 * PI * 300 * (offset + it) / 16_000) * 800).toInt().toShort() }

    private fun run(gate: SpeechGate, frames: List<ShortArray>) = frames.flatMap { gate.process(it) }

    @Test
    fun `el silencio no produce eventos`() {
        val gate = SpeechGate()
        assertTrue(run(gate, List(500) { noise() }).isEmpty())
        assertEquals(0, gate.speechFrames)
    }

    @Test
    fun `una frase genera inicio con pre-roll, audio y final tras la cola`() {
        val gate = SpeechGate(preRollMs = 1_000, hangoverMs = 1_500)
        val frames = List(200) { noise() } + List(100) { voice() } + List(200) { noise() }
        val events = run(gate, frames)

        val started = events.filterIsInstance<SpeechGate.Event.SpeechStarted>()
        val ended = events.filterIsInstance<SpeechGate.Event.SpeechEnded>()
        assertEquals(1, started.size)
        assertEquals(1, ended.size)

        // La voz empieza en la trama 200 (muestra 64.000); el pre-roll retrocede 1 s (50 tramas).
        assertEquals((200 - 50) * 320L, started.single().startSample)
        // Termina 1,5 s (75 tramas) después de la última trama con voz (la 299).
        assertEquals((300 + 75) * 320L, ended.single().endSample)

        val audio = events.filterIsInstance<SpeechGate.Event.Audio>()
        assertEquals(375 - 150, audio.size)
        // Las tramas son contiguas: no se pierde ni se duplica audio.
        audio.zipWithNext().forEach { (a, b) -> assertEquals(a.startSample + 320, b.startSample) }
    }

    @Test
    fun `un golpe aislado no dispara el VAD`() {
        val gate = SpeechGate(onsetFrames = 3)
        val frames = List(100) { noise() } + listOf(voice(), voice()) + List(100) { noise() }
        assertTrue(run(gate, frames).none { it is SpeechGate.Event.SpeechStarted })
    }

    @Test
    fun `pausas cortas dentro de la cola no parten la frase`() {
        val gate = SpeechGate(hangoverMs = 1_500)
        val frames = List(100) { noise() } + List(50) { voice() } + List(40) { noise() } + List(50) { voice() } + List(200) { noise() }
        val events = run(gate, frames)
        assertEquals(1, events.count { it is SpeechGate.Event.SpeechStarted })
        assertEquals(1, events.count { it is SpeechGate.Event.SpeechEnded })
    }

    @Test
    fun `el suelo se calibra con las primeras tramas`() {
        val gate = SpeechGate()
        val background = { ShortArray(320) { random.nextInt(-300, 300).toShort() } } // ≈ -45 dBFS
        val louder = { ShortArray(320) { random.nextInt(-3000, 3000).toShort() } } // ≈ -25 dBFS
        // Con un fondo alto desde el principio no debe detectarse voz...
        assertTrue(run(gate, List(100) { background() }).none { it is SpeechGate.Event.SpeechStarted })
        // ...pero sí cuando alguien habla por encima de ese fondo.
        assertTrue(run(gate, List(20) { louder() }).any { it is SpeechGate.Event.SpeechStarted })
    }

    @Test
    fun `flush cierra un tramo abierto`() {
        val gate = SpeechGate()
        run(gate, List(100) { noise() } + List(20) { voice() })
        assertTrue(gate.isInSpeech)
        val events = gate.flush()
        assertEquals(listOf<SpeechGate.Event>(SpeechGate.Event.SpeechEnded(120 * 320L)), events)
    }

    @Test
    fun `el suelo de ruido se adapta cuando el fondo sube a mitad de grabacion`() {
        val gate = SpeechGate()
        // Primero silencio; luego se enciende un ventilador (≈ -45 dBFS) y se queda encendido.
        // Al principio parece voz, pero el suelo debe subir y acabar cerrando el tramo.
        val loudNoise = { ShortArray(320) { random.nextInt(-300, 300).toShort() } }
        val events = run(gate, List(100) { noise() } + List(3_000) { loudNoise() })
        val lastEnd = events.indexOfLast { it is SpeechGate.Event.SpeechEnded }
        val lastStart = events.indexOfLast { it is SpeechGate.Event.SpeechStarted }
        assertTrue(lastEnd > lastStart, "El VAD debería acabar cerrado con un fondo estable")
        assertTrue(gate.floorDb > -50, "Suelo adaptado: ${gate.floorDb}")
    }
}
