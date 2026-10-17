package dev.escriba.shared.audio

/**
 * Minimal Ogg reader to validate and recover fragments. It stops at the first incomplete page or
 * page with a bad CRC, which is what's left at the end of a file cut short by an unexpected stop.
 */
object OggReader {

    data class Page(
        val offset: Int,
        val length: Int,
        val flags: Int,
        val granule: Long,
        val serial: Int,
        val sequence: Int,
        val packets: List<ByteArray>,
    )

    data class Result(
        val pages: List<Page>,
        /** Valid bytes from the start; anything after is corrupt or incomplete. */
        val validLength: Int,
        val preSkip: Int?,
        val inputSampleRate: Int?,
    ) {
        val audioPackets: List<ByteArray> get() = pages.drop(2).flatMap { it.packets }
        val endsWithEos: Boolean get() = pages.lastOrNull()?.let { it.flags and Ogg.FLAG_EOS != 0 } == true

        /** Playable duration in ms: granule of the last page minus the pre-skip. */
        val durationMs: Long
            get() {
                val last = pages.lastOrNull()?.granule ?: return 0
                return ((last - (preSkip ?: 0)).coerceAtLeast(0) * 1000) / Ogg.OPUS_GRANULE_RATE
            }
    }

    fun read(data: ByteArray): Result {
        val pages = ArrayList<Page>()
        var pos = 0
        // A packet may continue on the next page; it's rebuilt here.
        var partial: ByteArray? = null
        while (pos + Ogg.HEADER_BYTES <= data.size) {
            if (String(data, pos, 4, Charsets.US_ASCII) != Ogg.CAPTURE_PATTERN) break
            val segments = data[pos + 26].toInt() and 0xFF
            val headerEnd = pos + Ogg.HEADER_BYTES + segments
            if (headerEnd > data.size) break
            val lacing = IntArray(segments) { data[pos + Ogg.HEADER_BYTES + it].toInt() and 0xFF }
            val pageLength = Ogg.HEADER_BYTES + segments + lacing.sum()
            if (pos + pageLength > data.size) break

            val page = data.copyOfRange(pos, pos + pageLength)
            val storedCrc = Ogg.getLe(page, 22, 4).toInt()
            for (i in 22..25) page[i] = 0
            if (Ogg.crc(page) != storedCrc) break

            val packets = ArrayList<ByteArray>()
            var bodyPos = Ogg.HEADER_BYTES + segments
            var current = partial ?: ByteArray(0)
            partial = null
            for (lace in lacing) {
                current += page.copyOfRange(bodyPos, bodyPos + lace)
                bodyPos += lace
                if (lace < 255) {
                    packets += current
                    current = ByteArray(0)
                }
            }
            if (current.isNotEmpty() || (lacing.isNotEmpty() && lacing.last() == 255)) partial = current

            pages += Page(
                offset = pos,
                length = pageLength,
                flags = data[pos + 5].toInt() and 0xFF,
                granule = Ogg.getLe(data, pos + 6, 8),
                serial = Ogg.getLe(data, pos + 14, 4).toInt(),
                sequence = Ogg.getLe(data, pos + 18, 4).toInt(),
                packets = packets,
            )
            pos += pageLength
        }

        val head = pages.firstOrNull()?.packets?.firstOrNull()
        val isOpus = head != null && head.size >= 19 && String(head, 0, 8, Charsets.US_ASCII) == "OpusHead"
        return Result(
            pages = pages,
            validLength = pos,
            preSkip = if (isOpus) Ogg.getLe(head, 10, 2).toInt() else null,
            inputSampleRate = if (isOpus) Ogg.getLe(head, 12, 4).toInt() else null,
        )
    }
}
