package dev.escriba.shared.audio

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** Sensibilidad del VAD: margen en dB sobre el ruido de fondo para considerar que hay voz. */
enum class VadSensitivity(val marginDb: Double) {
    BAJA(12.0),
    MEDIA(9.0),
    ALTA(6.0),
}

/**
 * Detector de voz por energía, con suelo de ruido adaptativo.
 *
 * Decide trama a trama (20 ms) si hay habla:
 *  - el suelo de ruido sigue al nivel en silencio (baja deprisa y sube despacio);
 *  - hay voz cuando la energía supera el suelo + margen y un mínimo absoluto;
 *  - hacen falta [onsetFrames] tramas seguidas para empezar, para ignorar golpes sueltos;
 *  - se conserva [preRollMs] de audio anterior para no cortar la primera palabra;
 *  - se mantiene [hangoverMs] tras la última trama con voz para no cortar entre frases.
 *
 * Es deliberadamente sencillo y barato (una raíz cuadrada por trama). La interfaz permite
 * sustituirlo por WebRTC VAD o Silero en la fase 9 si la medición lo justifica.
 */
class SpeechGate(
    private val sampleRate: Int = 16_000,
    frameMs: Int = 20,
    var sensitivity: VadSensitivity = VadSensitivity.MEDIA,
    preRollMs: Int = 1_000,
    hangoverMs: Int = 1_500,
    private val onsetFrames: Int = 3,
    private val absoluteMinDb: Double = -62.0,
    /** Si es NaN, el suelo se calibra con la primera trama (lo normal: al pulsar grabar aún no se habla). */
    initialFloorDb: Double = Double.NaN,
) {
    sealed interface Event {
        /** Empieza un tramo de voz; [startSample] incluye el pre-roll. */
        data class SpeechStarted(val startSample: Long) : Event

        /** Trama a guardar. [samples] es una copia propia. */
        class Audio(val samples: ShortArray, val startSample: Long) : Event

        /** Termina el tramo; [endSample] es la primera muestra que ya no forma parte de él. */
        data class SpeechEnded(val endSample: Long) : Event
    }

    val frameSamples = sampleRate * frameMs / 1000
    private val preRollFrames = preRollMs / frameMs
    private val hangoverFrames = hangoverMs / frameMs

    private val preRoll = ArrayDeque<Pair<ShortArray, Long>>()
    private var position = 0L
    private var inSpeech = false
    private var candidateRun = 0
    private var framesSinceVoice = 0

    var floorDb = initialFloorDb
        private set
    var lastDb = -120.0
        private set
    var speechFrames = 0L
        private set
    var totalFrames = 0L
        private set

    val isInSpeech: Boolean get() = inSpeech

    /** Procesa una trama de exactamente [frameSamples] muestras. */
    fun process(frame: ShortArray, length: Int = frame.size): List<Event> {
        require(length == frameSamples) { "Trama de $length muestras; se esperaban $frameSamples" }
        val start = position
        position += length
        totalFrames++

        val db = levelDb(frame, length)
        lastDb = db
        if (floorDb.isNaN()) floorDb = db.coerceIn(-90.0, -25.0)
        val voiced = db > floorDb + sensitivity.marginDb && db > absoluteMinDb
        // Se adapta en todas las tramas: un fondo alto y constante (un ventilador, el coche) acaba
        // siendo "suelo" en unos segundos, mientras que los huecos entre palabras de una voz real
        // lo vuelven a bajar enseguida.
        adaptFloor(db)

        val copy = frame.copyOf(length)
        val events = ArrayList<Event>(2)

        if (!inSpeech) {
            preRoll.addLast(copy to start)
            while (preRoll.size > preRollFrames + onsetFrames) preRoll.removeFirst()
            candidateRun = if (voiced) candidateRun + 1 else 0
            if (candidateRun >= onsetFrames) {
                inSpeech = true
                framesSinceVoice = 0
                events += Event.SpeechStarted(preRoll.first().second)
                preRoll.forEach { (samples, at) -> events += Event.Audio(samples, at) }
                speechFrames += preRoll.size
                preRoll.clear()
            }
        } else {
            events += Event.Audio(copy, start)
            speechFrames++
            framesSinceVoice = if (voiced) 0 else framesSinceVoice + 1
            if (framesSinceVoice >= hangoverFrames) {
                inSpeech = false
                candidateRun = 0
                events += Event.SpeechEnded(position)
            }
        }
        return events
    }

    /** Cierra el tramo abierto, si lo hay (al parar o pausar). */
    fun flush(): List<Event> {
        preRoll.clear()
        candidateRun = 0
        if (!inSpeech) return emptyList()
        inSpeech = false
        return listOf(Event.SpeechEnded(position))
    }

    fun samplesToMs(samples: Long): Long = samples * 1000 / sampleRate

    private fun adaptFloor(db: Double) {
        floorDb = if (db < floorDb) {
            // Baja rápido: el ruido de fondo puede reducirse de golpe (p. ej., al apagar algo).
            floorDb + (db - floorDb) * 0.2
        } else {
            // Sube despacio (~1 dB/s), para que una voz larga no se convierta en "ruido".
            floorDb + 0.02
        }
        floorDb = floorDb.coerceIn(-90.0, -25.0)
    }

    companion object {
        fun levelDb(frame: ShortArray, length: Int = frame.size): Double {
            if (length == 0) return -120.0
            var sum = 0.0
            for (i in 0 until length) {
                val s = frame[i].toDouble()
                sum += s * s
            }
            val rms = sqrt(sum / length)
            return 20 * log10(max(rms, 1e-3) / 32768.0)
        }
    }
}
