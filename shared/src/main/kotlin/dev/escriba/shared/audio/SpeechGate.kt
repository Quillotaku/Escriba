package dev.escriba.shared.audio

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** VAD sensitivity: margin in dB over the background noise to consider it speech. */
enum class VadSensitivity(val marginDb: Double) {
    LOW(12.0),
    MEDIUM(9.0),
    HIGH(6.0),
}

/**
 * Energy-based voice detector with an adaptive noise floor.
 *
 * Decides frame by frame (20 ms) whether there is speech:
 *  - the noise floor follows the level during silence (drops fast, rises slowly);
 *  - it's speech when the energy is over the floor + margin and over an absolute minimum;
 *  - [onsetFrames] voiced frames in a row are needed to start, to ignore single knocks;
 *  - [preRollMs] of earlier audio is kept so the first word isn't cut;
 *  - [hangoverMs] is kept after the last voiced frame so pauses between sentences aren't cut.
 *
 * Deliberately simple and cheap (one square root per frame). The interface allows replacing it
 * with WebRTC VAD or Silero if measurements justify it.
 */
class SpeechGate(
    private val sampleRate: Int = 16_000,
    frameMs: Int = 20,
    var sensitivity: VadSensitivity = VadSensitivity.MEDIUM,
    preRollMs: Int = 1_000,
    hangoverMs: Int = 1_500,
    private val onsetFrames: Int = 3,
    private val absoluteMinDb: Double = -62.0,
    /** If NaN, the floor is calibrated on the first frame (normally nobody speaks yet when recording starts). */
    initialFloorDb: Double = Double.NaN,
) {
    sealed interface Event {
        /** A stretch of speech starts; [startSample] includes the pre-roll. */
        data class SpeechStarted(val startSample: Long) : Event

        /** Frame to store. [samples] is a private copy. */
        class Audio(val samples: ShortArray, val startSample: Long) : Event

        /** The stretch ends; [endSample] is the first sample no longer part of it. */
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

    /** Processes a frame of exactly [frameSamples] samples. */
    fun process(frame: ShortArray, length: Int = frame.size): List<Event> {
        require(length == frameSamples) { "Frame of $length samples; expected $frameSamples" }
        val start = position
        position += length
        totalFrames++

        val db = levelDb(frame, length)
        lastDb = db
        if (floorDb.isNaN()) floorDb = db.coerceIn(-90.0, -25.0)
        val voiced = db > floorDb + sensitivity.marginDb && db > absoluteMinDb
        // Adapts on every frame: a loud, steady background (a fan, the car) becomes the "floor"
        // within seconds, while the gaps between the words of a real voice bring it back down.
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

    /** Closes the open stretch, if any (on stop or pause). */
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
            // Drops fast: background noise can stop suddenly (e.g. when something is switched off).
            floorDb + (db - floorDb) * 0.2
        } else {
            // Rises slowly (~1 dB/s), so a long monologue doesn't turn into "noise".
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
