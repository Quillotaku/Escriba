package dev.escriba.shared

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Wire format of a fragment on a channel: `[u32 big-endian: length][JSON header][payload]`.
 * The header comes first so the receiver knows what it's getting before reading the audio.
 */
object Framing {
    /** Real headers are a few hundred bytes; this guards against garbage on the channel. */
    const val MAX_HEADER_BYTES = 64 * 1024

    fun writeHeader(out: OutputStream, header: ByteArray) {
        require(header.size in 1..MAX_HEADER_BYTES) { "Header of ${header.size} bytes is out of range" }
        val n = header.size
        out.write(byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()))
        out.write(header)
    }

    /** Reads the header and leaves [input] positioned at the start of the payload. */
    fun readHeader(input: InputStream): ByteArray {
        val data = DataInputStream(input)
        val n = try {
            data.readInt()
        } catch (e: EOFException) {
            throw IOException("Channel closed before the header", e)
        }
        if (n !in 1..MAX_HEADER_BYTES) throw IOException("Invalid header length: $n")
        val header = ByteArray(n)
        data.readFully(header)
        return header
    }
}
