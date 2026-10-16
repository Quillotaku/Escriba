package dev.escriba.shared.recording

import dev.escriba.shared.Segment

/**
 * Diario de segmentos de un fragmento abierto: una línea por evento, añadida al momento.
 *
 * Si el proceso muere, el Ogg queda válido hasta su última página completa, pero la tabla de
 * segmentos solo vivía en memoria. Con el diario y la duración real del Ogg se reconstruye.
 *
 * Formato: `S <offsetAudioMs> <inicioRealMs>` al empezar un segmento y `E <duracionMs>` al acabar.
 */
object FragmentJournal {

    fun start(audioOffsetMs: Long, wallStartMs: Long) = "S $audioOffsetMs $wallStartMs"

    fun end(durationMs: Long) = "E $durationMs"

    /**
     * Reconstruye los segmentos a partir del diario. [audioDurationMs] es lo que se ha podido
     * salvar del Ogg: los segmentos que sigan abiertos o que lo sobrepasen se recortan.
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
