package dev.escriba.wear.recorder

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer

/** Spike: comprueba que un .ogg generado es válido releyéndolo con el demuxer del sistema. */
object OggInspector {

    data class Report(
        val sizeBytes: Long,
        val hasOggMagic: Boolean,
        val hasOpusHead: Boolean,
        val mime: String?,
        val packets: Int,
        val lastPacketUs: Long,
        val error: String?,
    ) {
        val ok: Boolean get() = error == null && hasOggMagic && hasOpusHead && mime == MediaFormat.MIMETYPE_AUDIO_OPUS && packets > 0

        override fun toString(): String =
            "ogg=${if (ok) "VÁLIDO" else "INVÁLIDO"} tamaño=${sizeBytes}B OggS=$hasOggMagic OpusHead=$hasOpusHead " +
                "mime=$mime paquetes=$packets duración≈${lastPacketUs / 1000} ms" + (error?.let { " error=$it" } ?: "")
    }

    fun inspect(file: File): Report {
        val head = file.inputStream().use { it.readNBytes(512) }
        val hasMagic = head.size >= 4 && String(head, 0, 4, Charsets.US_ASCII) == "OggS"
        val hasOpusHead = String(head, Charsets.ISO_8859_1).contains("OpusHead")

        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.path)
            if (extractor.trackCount < 1) {
                return Report(file.length(), hasMagic, hasOpusHead, null, 0, 0, "sin pistas")
            }
            val mime = extractor.getTrackFormat(0).getString(MediaFormat.KEY_MIME)
            extractor.selectTrack(0)
            val buffer = ByteBuffer.allocate(64 * 1024)
            var packets = 0
            var lastUs = 0L
            while (extractor.readSampleData(buffer, 0) >= 0) {
                packets++
                lastUs = extractor.sampleTime
                extractor.advance()
            }
            Report(file.length(), hasMagic, hasOpusHead, mime, packets, lastUs, null)
        } catch (e: Exception) {
            Report(file.length(), hasMagic, hasOpusHead, null, 0, 0, "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            extractor.release()
        }
    }
}
