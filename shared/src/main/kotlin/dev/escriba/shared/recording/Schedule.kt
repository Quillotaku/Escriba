package dev.escriba.shared.recording

import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Daily window in which continuous mode records. Windows may cross midnight (e.g. 22:00–06:00).
 * If start and end are equal, it records all day.
 */
data class DailyWindow(val start: LocalTime, val end: LocalTime) {

    val allDay: Boolean get() = start == end

    fun contains(time: LocalTime): Boolean = when {
        allDay -> true
        start < end -> time >= start && time < end
        else -> time >= start || time < end
    }

    /** Next instant, strictly after [now], at which the window opens or closes. */
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

        /** Accepts "07:30-23:30". */
        fun parse(text: String): DailyWindow? {
            val parts = text.split('-', '–').map { it.trim() }
            if (parts.size != 2) return null
            return runCatching { DailyWindow(LocalTime.parse(parts[0]), LocalTime.parse(parts[1])) }.getOrNull()
        }
    }
}
