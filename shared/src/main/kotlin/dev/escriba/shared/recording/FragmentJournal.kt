package dev.escriba.shared.recording

import dev.escriba.shared.Segment

/**
 * Segment journal of an open fragment: one line per event, appended as it happens.
 *
 * If the process dies, the Ogg file stays valid up to its last complete page, but the segment
 * table only lived in memory. The journal plus the Ogg's real duration rebuild it.
 *
 * Format: `S <audioOffsetMs> <wallStartMs>` when a segment starts and `E <durationMs>` when it ends.
 */
object FragmentJournal {

    fun start(audioOffsetMs: Long, wallStartMs: Long) = "S $audioOffsetMs $wallStartMs"

    fun end(durationMs: Long) = "E $durationMs"

    /**
     * Rebuilds the segments from the journal. [audioDurationMs] is what could be saved from the
     * Ogg: segments still open, or longer than that, are trimmed.
     */
    fun recover(lines: List<String>, audioDurationMs: Long): List<Segment> {
        val segments = ArrayList<Segment>()
        var open: Pair<Long, Long>? = null
        for (raw in lines) {
            val parts = raw.trim().split(' ')
            when (parts.firstOrNull()) {
                "S" -> {
                    val offset = parts.getOrNull(1)?.toLongOrNull() ?: continue
                    val wall = parts.getOrNull(2)?.toLongOrNull() ?: continue
                    open = offset to wall
                }

                "E" -> {
                    val duration = parts.getOrNull(1)?.toLongOrNull() ?: continue
                    val (offset, wall) = open ?: continue
                    segments += Segment(offset, wall, duration)
                    open = null
                }
            }
        }
        open?.let { (offset, wall) -> segments += Segment(offset, wall, audioDurationMs - offset) }

        return segments.mapNotNull { s ->
            val available = audioDurationMs - s.audioOffsetMs
            when {
                available <= 0 -> null
                s.durationMs > available -> s.copy(durationMs = available)
                else -> s
            }
        }.filter { it.durationMs > 0 }
    }
}
