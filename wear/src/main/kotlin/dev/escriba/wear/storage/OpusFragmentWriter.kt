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
 * Writes an open fragment: audio to `<seq>.ogg.part` (Opus in our own Ogg) and segment events
 * to `<seq>.journal`. Both are flushed to disk as they go.
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
    /** Packets that arrived before the format (shouldn't happen, but they aren't lost). */
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
