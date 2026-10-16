package dev.escriba.shared.recording

/**
 * Cuándo envía el reloj sus fragmentos al móvil.
 *
 * Encender la radio para cada fragmento gasta batería, así que se envía por lotes. Pero
 * tampoco conviene que el audio se acumule en el reloj: si se pierde o se rompe, se pierde
 * lo que no se haya enviado.
 */
object UploadPolicy {
    const val BATCH_FRAGMENTS = 5
    const val MAX_WAIT_MS = 15 * 60_000L

    fun shouldUpload(
        pendingFragments: Int,
        oldestPendingAgeMs: Long,
        endedSessions: Int,
        charging: Boolean,
        manual: Boolean,
    ): Boolean = when {
        pendingFragments == 0 && endedSessions == 0 -> false
        manual || charging -> true
        // Al terminar una sesión se envía enseguida: es cuando el usuario espera ver su nota.
        endedSessions > 0 -> true
        pendingFragments >= BATCH_FRAGMENTS -> true
        else -> oldestPendingAgeMs >= MAX_WAIT_MS
    }
}
