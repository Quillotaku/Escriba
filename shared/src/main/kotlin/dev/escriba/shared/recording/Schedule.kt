package dev.escriba.shared.recording

import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Franja diaria en la que se graba en modo continuo. Admite franjas que cruzan la medianoche
 * (p. ej., 22:00–06:00). Si inicio y fin coinciden, se graba todo el día.
 */
data class DailyWindow(val start: LocalTime, val end: LocalTime) {

    val allDay: Boolean get() = start == end

    fun contains(time: LocalTime): Boolean = when {
        allDay -> true
        start < end -> time >= start && time < end
        else -> time >= start || time < end
    }

    /** Próximo instante, estrictamente posterior a [now], en que cambia el estado de la franja. */
    fun nextChange(now: LocalDateTime): LocalDateTime? {
        if (allDay) return null
        val boundary = if (contains(now.toLocalTime())) end else start
        var candidate = now.toLocalDate().atTime(boundary)
        if (!candidate.isAfter(now)) candidate = candidate.plusDays(1)
        return candidate
    }

    override fun toString() = "%02d:%02d–%02d:%02d".format(start.hour, start.minute, end.hour, end.minute)

    companion object {
        val DEFAULT = DailyWindow(LocalTime.of(7, 30), LocalTime.of(23, 30))

        /** Acepta "07:30-23:30". */
        fun parse(text: String): DailyWindow? {
            val parts = text.split('-', '–').map { it.trim() }
            if (parts.size != 2) return null
            return runCatching { DailyWindow(LocalTime.parse(parts[0]), LocalTime.parse(parts[1])) }.getOrNull()
        }
    }
}
