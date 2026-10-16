package dev.escriba.shared.recording

import dev.escriba.shared.Segment
import dev.escriba.shared.audio.SpeechGate

/** Destino de un fragmento abierto: lo implementa el reloj con Opus + Ogg sobre un archivo. */
interface FragmentWriter {
    fun write(samples: ShortArray)

    /** Apunta en el diario un evento de segmento, para poder recuperar el fragmento si se muere el proceso. */
    fun journal(line: String)

    fun close()

    /** El fragmento no llegó a tener voz: se borra sin dejar rastro. */
    fun discard()
}

/** Fragmento cerrado, todavía sin tamaño ni hash (eso lo calcula quien guarda el archivo). */
data class ClosedFragment(
    val seq: Int,
    val startMs: Long,
    val endMs: Long,
    val segments: List<Segment>,
    val last: Boolean,
)

/**
 * Convierte los eventos del VAD en fragmentos independientes.
 *
 * Un fragmento se cierra cuando:
 *  - acumula [maxSpeechMs] de audio (60 s por defecto), aunque se esté en mitad de una frase:
 *    el segmento continúa en el siguiente fragmento;
 *  - lleva [closeAfterSilenceMs] en silencio después de su último tramo de voz, para que el
 *    audio no espere en el reloj a que vuelva a hablar alguien;
 *  - termina la sesión ([finish]).
 *
 * Las posiciones llegan en muestras desde el inicio de la captura; [wallClockAt] las traduce
 * a hora real.
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
        /** Inicio (en audio y en hora real) del segmento abierto, si lo hay. */
        var segmentAudioStart: Long? = null
        var segmentWallStart = 0L
        var silenceSince: Long? = null
    }

    /** Fragmentos creados en la sesión (el abierto incluido). */
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
                    // La frase sigue: el siguiente fragmento empieza justo donde acabó este.
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

    /** Se llama en cada trama (también en silencio) para aplicar el cierre por inactividad. */
    fun onTick(sample: Long) {
        val fragment = current ?: return
        val since = fragment.silenceSince ?: return
        if ((sample - since) * 1000 / sampleRate >= closeAfterSilenceMs) close(last = false)
    }

    /** Cierra la sesión, y con ella el segmento que siga abierto. */
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
            nextSeq = fragment.seq // sin huecos en la numeración
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
