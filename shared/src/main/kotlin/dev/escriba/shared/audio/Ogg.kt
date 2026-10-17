package dev.escriba.shared.audio

/**
 * Primitives of the Ogg container (RFC 3533) and of Opus in Ogg (RFC 7845).
 */
object Ogg {
    const val CAPTURE_PATTERN = "OggS"
    const val HEADER_BYTES = 27
    const val MAX_SEGMENTS = 255

    const val FLAG_CONTINUED = 0x01
    const val FLAG_BOS = 0x02
    const val FLAG_EOS = 0x04

    /** Opus always counts the granule position at 48 kHz. */
    const val OPUS_GRANULE_RATE = 48_000

    private val CRC_TABLE = IntArray(256) { index ->
        var r = index shl 24
        repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
        r
    }

    /** Ogg CRC-32: polynomial 0x04C11DB7, not reflected, initial value 0, no final XOR. */
    fun crc(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        var crc = 0
        for (i in offset until offset + length) {
            crc = (crc shl 8) xor CRC_TABLE[((crc ushr 24) xor (data[i].toInt() and 0xFF)) and 0xFF]
        }
        return crc
    }

    /** OpusHead identification header for a mono track. */
    fun opusHead(preSkip: Int, inputSampleRate: Int): ByteArray {
        val out = ByteArray(19)
        "OpusHead".encodeToByteArray().copyInto(out)
        out[8] = 1 // version
        out[9] = 1 // channels
        putLe(out, 10, preSkip.toLong(), 2)
        putLe(out, 12, inputSampleRate.toLong(), 4)
        // 16-17: output gain 0; 18: mapping family 0 (mono/stereo)
        return out
    }

    /** OpusTags comment header, with no comments. */
    fun opusTags(vendor: String): ByteArray {
        val vendorBytes = vendor.encodeToByteArray()
        val out = ByteArray(8 + 4 + vendorBytes.size + 4)
        "OpusTags".encodeToByteArray().copyInto(out)
        putLe(out, 8, vendorBytes.size.toLong(), 4)
        vendorBytes.copyInto(out, 12)
        // the last 4 bytes (comment count) stay 0
        return out
    }

    /**
     * Duration of an Opus packet in samples at 48 kHz, read from its TOC byte (RFC 6716 §3.1).
     * Returns 0 if the packet is empty or malformed.
     */
    fun opusPacketSamples(packet: ByteArray): Int {
        if (packet.isEmpty()) return 0
        val toc = packet[0].toInt() and 0xFF
        val config = toc ushr 3
        // Duration of one frame in units of 2.5 ms.
        val frameUnits = when {
            config < 12 -> intArrayOf(4, 8, 16, 24)[config and 3] // SILK: 10, 20, 40, 60 ms
            config < 16 -> intArrayOf(4, 8)[config and 1] // hybrid: 10, 20 ms
            else -> intArrayOf(1, 2, 4, 8)[config and 3] // CELT: 2.5, 5, 10, 20 ms
        }
        val frames = when (toc and 3) {
            0 -> 1
            1, 2 -> 2
            else -> if (packet.size < 2) return 0 else packet[1].toInt() and 0x3F
        }
        return frames * frameUnits * 120 // 2.5 ms = 120 samples at 48 kHz
    }

    internal fun putLe(buffer: ByteArray, offset: Int, value: Long, bytes: Int) {
        for (i in 0 until bytes) buffer[offset + i] = (value ushr (8 * i)).toByte()
    }

    internal fun getLe(buffer: ByteArray, offset: Int, bytes: Int): Long {
        var value = 0L
        for (i in 0 until bytes) value = value or ((buffer[offset + i].toLong() and 0xFF) shl (8 * i))
        return value
    }
}
