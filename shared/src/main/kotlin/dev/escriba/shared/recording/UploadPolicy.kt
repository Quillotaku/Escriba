package dev.escriba.shared.recording

/**
 * When the watch sends its fragments to the phone.
 *
 * Waking the radio for every fragment costs battery, so fragments go in batches. But audio
 * shouldn't pile up on the watch either: if the watch is lost or breaks, whatever wasn't sent is lost.
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
        // A session that just ended goes right away: that's when the user expects to see the note.
        endedSessions > 0 -> true
        pendingFragments >= BATCH_FRAGMENTS -> true
        else -> oldestPendingAgeMs >= MAX_WAIT_MS
    }
}
