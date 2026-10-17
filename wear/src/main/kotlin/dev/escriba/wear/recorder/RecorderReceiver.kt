package dev.escriba.wear.recorder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.escriba.datalayer.AppLog
import dev.escriba.wear.R

/**
 * Schedule alarm and watch boot.
 *
 * - When the window starts: if the service is still alive and paused, it's asked to resume; if
 *   the system killed it overnight, a notification tells the user (Android doesn't allow opening
 *   the microphone again from the background without the user touching something).
 * - After the watch restarts: same situation; if a recording was in progress, the user is told.
 *   EscribaWearApp already recovers the fragments when the process starts.
 */
class RecorderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_WINDOW_START -> {
                if (RecordingState.phase.value == RecordingState.Phase.PAUSED) {
                    AppLog.i(TAG, "The window starts: resuming")
                    context.startService(RecorderService.intent(context, RecorderService.ACTION_RESUME, "schedule"))
                } else if (RecordingIntent(context).active) {
                    AppLog.i(TAG, "The window starts but the service isn't alive: telling the user")
                    RecorderNotifications.showInterrupted(context, R.string.interrupted_schedule)
                }
            }

            Intent.ACTION_BOOT_COMPLETED -> {
                if (RecordingIntent(context).active) {
                    AppLog.i(TAG, "The watch restarted during a recording")
                    RecorderNotifications.showInterrupted(context, R.string.interrupted_reboot)
                }
            }
        }
    }

    companion object {
        private const val TAG = "rec"
        const val ACTION_WINDOW_START = "dev.escriba.action.WINDOW_START"
    }
}
