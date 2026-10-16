package dev.escriba.shared.audio

/**
 * Primitivas del contenedor Ogg (RFC 3533) y de Opus en Ogg (RFC 7845).
 */
object Ogg {
    const val CAPTURE_PATTERN = "OggS"
    const val HEADER_BYTES = 27
    const val MAX_SEGMENTS = 255

    const val FLAG_CONTINUED = 0x01
    const val FLAG_BOS = 0x02
    const val FLAG_EOS = 0x04

    /** Opus siempre cuenta el tiempo de la granule position a 48 kHz. */
    const val OPUS_GRANULE_RATE = 48_000

    private val CRC_TABLE = IntArray(256) { index ->
        var r = index shl 24
        repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
        r
    }

    /** CRC-32 de Ogg: polinomio 0x04C11DB7, sin reflejar, valor inicial 0 y sin XOR final. */
    fun crc(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        var crc = 0
        for (i in offset until offset + length) {
            crc = (crc shl 8) xor CRC_TABLE[((crc ushr 24) xor (data[i].toInt() and 0xFF)) and 0xFF]
        }
        return crc
    }

    /** Cabecera de identificación OpusHead para una pista mono. */
    fun opusHead(preSkip: Int, inputSampleRate: Int): ByteArray {
        val out = ByteArray(19)
        "OpusHead".encodeToByteArray().copyInto(out)
        out[8] = 1 // versión
        out[9] = 1 // canales
        putLe(out, 10, preSkip.toLong(), 2)
        putLe(out, 12, inputSampleRate.toLong(), 4)
        // 16-17: ganancia de salida 0; 18: familia de mapeo 0 (mono/estéreo)
        return out
    }

    /** Cabecera de comentarios OpusTags, sin comentarios. */
    fun opusTags(vendor: String): ByteArray {
        val vendorBytes = vendor.encodeToByteArray()
        val out = ByteArray(8 + 4 + vendorBytes.size + 4)
        "OpusTags".encodeToByteArray().copyInto(out)
        putLe(out, 8, vendorBytes.size.toLong(), 4)
        vendorBytes.copyInto(out, 12)
        // los últimos 4 bytes (número de comentarios) quedan a 0
        return out
    }

    /**
     * Duración de un paquete Opus en muestras a 48 kHz, leída de su byte TOC (RFC 6716 §3.1).
     * Devuelve 0 si el paquete está vacío o mal formado.
     */
    fun opusPacketSamples(packet: ByteArray): Int {
        if (packet.isEmpty()) return 0
        val toc = packet[0].toInt() and 0xFF
        val config = toc ushr 3
        // Duración de una trama en unidades de 2,5 ms.
        val frameUnits = when {
            config < 12 -> intArrayOf(4, 8, 16, 24)[config and 3] // SILK: 10, 20, 40, 60 ms
            config < 16 -> intArrayOf(4, 8)[config and 1] // híbrido: 10, 20 ms
            else -> intArrayOf(1, 2, 4, 8)[config and 3] // CELT: 2,5, 5, 10, 20 ms
        }
        val frames = when (toc and 3) {
            0 -> 1
            1, 2 -> 2
            else -> if (packet.size < 2) return 0 else packet[1].toInt() and 0x3F
        }
        return frames * frameUnits * 120 // 2,5 ms = 120 muestras a 48 kHz
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
