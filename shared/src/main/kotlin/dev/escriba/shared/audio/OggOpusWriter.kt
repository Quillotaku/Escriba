package dev.escriba.shared.audio

import java.io.Closeable
import java.io.OutputStream

/**
 * Empaqueta paquetes Opus ya codificados en un flujo Ogg.
 *
 * Agrupa muchos paquetes por página (por defecto 50 = 1 s a 20 ms). El empaquetador del
 * sistema escribe una página por paquete, y eso añade un 45 % de tamaño a 24 kbps.
 *
 * Cada página se vuelca al flujo nada más completarse. Si el proceso muere, el archivo
 * sigue siendo válido hasta la última página entera: como mucho se pierde 1 s de audio.
 */
class OggOpusWriter(
    private val out: OutputStream,
    private val serial: Int,
    preSkip: Int,
    inputSampleRate: Int,
    private val packetsPerPage: Int = 50,
    vendor: String = "Escriba",
) : Closeable {

    private var pageSequence = 0
    private var granule = 0L
    private val pending = ArrayList<ByteArray>()
    private var closed = false

    /** Muestras de audio a 48 kHz escritas hasta ahora (incluye el pre-skip). */
    val granulePosition: Long get() = granule

    var bytesWritten = 0L
        private set

    init {
        require(packetsPerPage in 1..Ogg.MAX_SEGMENTS)
        writePage(listOf(Ogg.opusHead(preSkip, inputSampleRate)), granule = 0, flags = Ogg.FLAG_BOS)
        writePage(listOf(Ogg.opusTags(vendor)), granule = 0, flags = 0)
    }

    fun writePacket(packet: ByteArray) {
        check(!closed) { "Escritor cerrado" }
        require(packet.size < Ogg.MAX_SEGMENTS * 255) { "Paquete Opus demasiado grande: ${packet.size}" }
        if (pending.isNotEmpty() && lacingCount(pending) + lacingValues(packet) > Ogg.MAX_SEGMENTS) {
            flushPending(flags = 0)
        }
        pending += packet
        granule += Ogg.opusPacketSamples(packet)
        // Se deja siempre un paquete pendiente: así la última página, con la marca EOS,
        // nunca queda vacía.
        if (pending.size > packetsPerPage) {
            val last = pending.removeAt(pending.lastIndex)
            flushPending(flags = 0, granuleOverride = granule - Ogg.opusPacketSamples(last))
            pending += last
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        flushPending(flags = Ogg.FLAG_EOS)
        out.flush()
    }

    private fun flushPending(flags: Int, granuleOverride: Long = granule) {
        writePage(pending.toList(), granuleOverride, flags)
        pending.clear()
    }

    private fun writePage(packets: List<ByteArray>, granule: Long, flags: Int) {
        val lacing = ArrayList<Int>()
        for (p in packets) {
            repeat(p.size / 255) { lacing += 255 }
            lacing += p.size % 255
        }
        val bodySize = packets.sumOf { it.size }
        val page = ByteArray(Ogg.HEADER_BYTES + lacing.size + bodySize)
        Ogg.CAPTURE_PATTERN.encodeToByteArray().copyInto(page)
        page[4] = 0 // versión
        page[5] = flags.toByte()
        Ogg.putLe(page, 6, granule, 8)
        Ogg.putLe(page, 14, serial.toLong(), 4)
        Ogg.putLe(page, 18, pageSequence++.toLong(), 4)
        // 22-25: CRC, se calcula con el campo a cero
        page[26] = lacing.size.toByte()
        lacing.forEachIndexed { i, v -> page[Ogg.HEADER_BYTES + i] = v.toByte() }
        var pos = Ogg.HEADER_BYTES + lacing.size
        for (p in packets) {
            p.copyInto(page, pos)
            pos += p.size
        }
        Ogg.putLe(page, 22, Ogg.crc(page).toLong() and 0xFFFFFFFFL, 4)
        out.write(page)
        out.flush()
        bytesWritten += page.size
    }

    private fun lacingValues(packet: ByteArray) = packet.size / 255 + 1
    private fun lacingCount(packets: List<ByteArray>) = packets.sumOf { lacingValues(it) }
}
