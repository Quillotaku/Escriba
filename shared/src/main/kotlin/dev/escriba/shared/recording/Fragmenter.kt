package dev.escriba.shared.recording

import dev.escriba.shared.Segment
import dev.escriba.shared.audio.SpeechGate

/** Where an open fragment goes: the watch implements it with Opus + Ogg on a file. */
interface FragmentWriter {
    fun write(samples: ShortArray)

    /** Writes a segment event to the journal, so the fragment can be recovered if the process dies. */
    fun journal(line: String)

    fun close()

    /** The fragment never got any speech: it's deleted without a trace. */
    fun discard()
}

/** Closed fragment, still without size or hash (whoever stores the file computes those). */
data class ClosedFragment(
    val seq: Int,
    val startMs: Long,
    val endMs: Long,
    val segments: List<Segment>,
    val last: Boolean,
)

/**
 * Turns VAD events into independent fragments.
 *
 * A fragment closes when:
 *  - it holds [maxSpeechMs] of audio (60 s by default), even mid-sentence: the segment
 *    continues in the next fragment;
 *  - it has been silent for [closeAfterSilenceMs] after its last stretch of speech, so the audio
 *    doesn't wait on the watch until someone speaks again;
 *  - the session ends ([finish]).
 *
 * Positions arrive in samples since capture started; [wallClockAt] maps them to real time.
 */
class Fragmenter(
    private val sampleRate: Int,
    private val wallClockAt: (sample: Long) -> Long,
    private val openWriter: (seq: Int) -> FragmentWriter,
    private val onClosed: (ClosedFragment) -> Unit,
    private val maxSpeechMs: Long = 60_000,
    private val closeAfterSilenceMs: Long = 30_000,
    firstSeq: Int = 0,
) {
    private var nextSeq = firstSeq
    private var current: Open? = null

    private class Open(val seq: Int, val writer: FragmentWriter) {
        val segments = ArrayList<Segment>()
        var audioSamples = 0L
        /** Start (in audio and in real time) of the open segment, if any. */
        var segmentAudioStart: Long? = null
        var segmentWallStart = 0L
        var silenceSince: Long? = null
    }

    /** Fragments created in the session (including the open one). */
    val fragmentCount: Int get() = nextSeq
    val hasOpenFragment: Boolean get() = current != null

    fun onEvent(event: SpeechGate.Event) {
        when (event) {
            is SpeechGate.Event.SpeechStarted -> {
                val fragment = current ?: open()
                fragment.silenceSince = null
                startSegment(fragment, event.startSample)
            }

            is SpeechGate.Event.Audio -> {
                var fragment = current ?: open().also { startSegment(it, event.startSample) }
                fragment.writer.write(event.samples)
                fragment.audioSamples += event.samples.size
                if (fragment.audioSamples * 1000 / sampleRate >= maxSpeechMs) {
                    val nextSample = event.startSample + event.samples.size
                    endSegment(fragment)
                    close(last = false)
                    // The sentence goes on: the next fragment starts right where this one ended.
                    fragment = open()
                    startSegment(fragment, nextSample)
                }
            }

            is SpeechGate.Event.SpeechEnded -> current?.let {
                endSegment(it)
                it.silenceSince = event.endSample
            }
        }
    }

    /** Called on every frame (silent ones too) to apply the close-after-silence rule. */
    fun onTick(sample: Long) {
        val fragment = current ?: return
        val since = fragment.silenceSince ?: return
        if ((sample - since) * 1000 / sampleRate >= closeAfterSilenceMs) close(last = false)
    }

    /** Ends the session, and with it any segment still open. */
    fun finish() {
        val fragment = current ?: return
        endSegment(fragment)
        close(last = true)
    }

    private fun open(): Open {
        val seq = nextSeq++
        return Open(seq, openWriter(seq)).also { current = it }
    }

    private fun startSegment(fragment: Open, sample: Long) {
        if (fragment.segmentAudioStart != null) return
        fragment.segmentAudioStart = fragment.audioSamples
        fragment.segmentWallStart = wallClockAt(sample)
        fragment.writer.journal(FragmentJournal.start(ms(fragment.audioSamples), fragment.segmentWallStart))
    }

    private fun endSegment(fragment: Open) {
        val audioStart = fragment.segmentAudioStart ?: return
        val segment = Segment(
            audioOffsetMs = ms(audioStart),
            wallStartMs = fragment.segmentWallStart,
            durationMs = ms(fragment.audioSamples - audioStart),
        )
        fragment.segments += segment
        fragment.segmentAudioStart = null
        fragment.writer.journal(FragmentJournal.end(segment.durationMs))
    }

    private fun close(last: Boolean) {
        val fragment = current ?: return
        current = null
        val segments = fragment.segments.filter { it.durationMs > 0 }
        if (segments.isEmpty()) {
            fragment.writer.discard()
            nextSeq = fragment.seq // no gaps in the numbering
            return
        }
        fragment.writer.close()
        onClosed(
            ClosedFragment(
                seq = fragment.seq,
                startMs = segments.first().wallStartMs,
                endMs = segments.last().let { it.wallStartMs + it.durationMs },
                segments = segments,
                last = last,
            ),
        )
    }

    private fun ms(samples: Long) = samples * 1000 / sampleRate
}
