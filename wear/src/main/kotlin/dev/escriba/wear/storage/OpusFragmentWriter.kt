package dev.escriba.wear.storage

import dev.escriba.shared.audio.OggOpusWriter
import dev.escriba.shared.recording.FragmentWriter
import dev.escriba.wear.recorder.OpusEncoder
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import kotlin.random.Random

/**
 * Escribe un fragmento abierto: audio a `<seq>.ogg.part` (Opus en nuestro Ogg) y eventos
 * de segmento a `<seq>.journal`. Ambos se vuelcan a disco sobre la marcha.
 */
class OpusFragmentWriter(
    private val partFile: File,
    private val journalFile: File,
    sampleRate: Int,
    bitrate: Int,
) : FragmentWriter {

    private val out = BufferedOutputStream(FileOutputStream(partFile), 16 * 1024)
    private val journalOut = FileWriter(journalFile, true)
    private var ogg: OggOpusWriter? = null
    /** Paquetes llegados antes del formato (no debería ocurrir, pero no se pierden). */
    private val early = ArrayList<ByteArray>()

    private val encoder = OpusEncoder(
        sampleRate = sampleRate,
        bitrate = bitrate,
        onFormat = { preSkip ->
            if (ogg == null) {
                ogg = OggOpusWriter(out, serial = Random.nextInt(), preSkip = preSkip, inputSampleRate = sampleRate)
                early.forEach { ogg!!.writePacket(it) }
                early.clear()
            }
        },
        onPacket = { packet -> ogg?.writePacket(packet) ?: early.add(packet) },
    )

    val codecName: String get() = encoder.codecName

    override fun write(samples: ShortArray) = encoder.encode(samples)

    override fun journal(line: String) {
        journalOut.write(line)
        journalOut.write("\n")
        journalOut.flush()
    }

    override fun close() {
        try {
            encoder.close()
            ogg?.close()
        } finally {
            out.close()
            journalOut.close()
        }
    }

    override fun discard() {
        runCatching { encoder.close() }
        runCatching { out.close() }
        runCatching { journalOut.close() }
        partFile.delete()
        journalFile.delete()
    }
}
