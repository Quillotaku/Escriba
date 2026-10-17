package dev.escriba.wear.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import dev.escriba.wear.MainActivity
import dev.escriba.wear.QuickStartActivity
import dev.escriba.wear.R

/** The watch's notifications: the ongoing recording and the interrupted-recording alert. */
object RecorderNotifications {

    const val ONGOING_ID = 1
    private const val INTERRUPTED_ID = 2
    private const val CHANNEL_RECORDING = "recording"
    private const val CHANNEL_ALERTS = "alerts"

    private fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RECORDING, context.getString(R.string.channel_recording), NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, context.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    /**
     * The foreground service notification. With [startedElapsed] it shows a stopwatch; without
     * it, the pause text.
     */
    fun ongoing(context: Context, title: String, text: String, startedElapsed: Long?): Notification {
        ensureChannels(context)
        val openApp = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            context, 1, RecorderService.intent(context, RecorderService.ACTION_STOP, "notification"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val switchNext = PendingIntent.getService(
            context, 3, RecorderService.switchIntent(context, RecorderService.PROFILE_NEXT, "notification"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .addAction(R.drawable.ic_mic, context.getString(R.string.stop), stop)
            .addAction(R.drawable.ic_mic, context.getString(R.string.notif_switch_profile), switchNext)

        // Ongoing Activity: the microphone icon shows on the watch face while the service lives.
        val status = Status.Builder().apply {
            if (startedElapsed != null) {
                addTemplate("$title · #t#")
                addPart("t", Status.StopwatchPart(startedElapsed))
            } else {
                addTemplate(text)
            }
        }.build()
        OngoingActivity.Builder(context.applicationContext, ONGOING_ID, builder)
            .setStaticIcon(R.drawable.ic_mic)
            .setTouchIntent(openApp)
            .setStatus(status)
            .build()
            .apply(context.applicationContext)

        return builder.build()
    }

    fun update(context: Context, notification: Notification) {
        context.getSystemService(NotificationManager::class.java).notify(ONGOING_ID, notification)
    }

    /** Alert for a recording that ended without the user stopping it. A tap resumes it. */
    fun showInterrupted(context: Context, @StringRes reason: Int) {
        ensureChannels(context)
        val resume = PendingIntent.getActivity(
            context,
            2,
            Intent(context, QuickStartActivity::class.java)
                .putExtra(RecorderService.EXTRA_ORIGIN, "resume")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(context.getString(R.string.notif_interrupted))
            .setContentText(context.getString(R.string.notif_interrupted_text, context.getString(reason)))
            .setContentIntent(resume)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(INTERRUPTED_ID, notification)
    }

    fun cancelInterrupted(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(INTERRUPTED_ID)
    }
}
