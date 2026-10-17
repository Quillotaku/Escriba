package dev.escriba.shared.audio

import java.io.Closeable
import java.io.OutputStream

/**
 * Packs already-encoded Opus packets into an Ogg stream.
 *
 * Groups many packets per page (50 by default = 1 s at 20 ms). The system muxer writes one page
 * per packet, which adds 45 % to the size at 24 kbps.
 *
 * Each page is flushed as soon as it's complete. If the process dies, the file stays valid up to
 * the last full page: at most 1 s of audio is lost.
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

    /** Audio samples at 48 kHz written so far (including the pre-skip). */
    val granulePosition: Long get() = granule

    var bytesWritten = 0L
        private set

    init {
        require(packetsPerPage in 1..Ogg.MAX_SEGMENTS)
        writePage(listOf(Ogg.opusHead(preSkip, inputSampleRate)), granule = 0, flags = Ogg.FLAG_BOS)
        writePage(listOf(Ogg.opusTags(vendor)), granule = 0, flags = 0)
    }

    fun writePacket(packet: ByteArray) {
        check(!closed) { "Writer closed" }
        require(packet.size < Ogg.MAX_SEGMENTS * 255) { "Opus packet too large: ${packet.size}" }
        if (pending.isNotEmpty() && lacingCount(pending) + lacingValues(packet) > Ogg.MAX_SEGMENTS) {
            flushPending(flags = 0)
        }
        pending += packet
        granule += Ogg.opusPacketSamples(packet)
        // One packet is always kept pending, so the last page (the one with the EOS flag) is
        // never empty.
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
        page[4] = 0 // version
        page[5] = flags.toByte()
        Ogg.putLe(page, 6, granule, 8)
        Ogg.putLe(page, 14, serial.toLong(), 4)
        Ogg.putLe(page, 18, pageSequence++.toLong(), 4)
        // 22-25: CRC, computed with the field set to zero
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
