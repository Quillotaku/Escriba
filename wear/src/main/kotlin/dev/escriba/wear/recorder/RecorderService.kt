package dev.escriba.wear.recorder

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import dev.escriba.datalayer.AppLog
import dev.escriba.wear.MainActivity
import dev.escriba.wear.R
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Fase 1 (spike): servicio en primer plano de tipo micrófono que graba a Opus/Ogg.
 *
 * Su objetivo es responder a dos preguntas en el reloj real:
 *  1. ¿Desde qué orígenes (app, Tile, complicación, botón, notificación) permite
 *     HyperOS arrancar el servicio y, sobre todo, recibir audio real? Si Android
 *     niega el micrófono en segundo plano, AudioRecord entrega solo ceros.
 *  2. ¿El códec Opus del sistema + MediaMuxer generan un Ogg válido?
 */
class RecorderService : Service() {

    @Volatile
    private var stopRequested = false
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastChangeElapsed = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val origin = intent?.getStringExtra(EXTRA_ORIGIN) ?: "desconocido"
        when (intent?.action) {
            ACTION_START -> start(origin)
            ACTION_STOP -> stop(origin)
            ACTION_TOGGLE -> {
                val sinceLastChange = SystemClock.elapsedRealtime() - lastChangeElapsed
                if (sinceLastChange < TOGGLE_DEBOUNCE_MS) {
                    AppLog.i(TAG, "Alternancia ignorada: llegó $sinceLastChange ms después de la anterior (origen=$origin)")
                } else if (worker != null) stop(origin) else start(origin)
            }
            else -> AppLog.i(TAG, "Arranque sin acción (¿reinicio del sistema?) desde $origin; no se graba")
        }
        if (worker == null) stopSelf()
        return START_NOT_STICKY
    }

    private fun start(origin: String) {
        if (worker != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            AppLog.e(TAG, "Sin permiso de micrófono; ábrelo desde la app del reloj")
            return
        }

        val startedElapsed = SystemClock.elapsedRealtime()
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(startedElapsed),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } catch (e: Exception) {
            // Android 14+ lanza SecurityException si el origen no cumple las condiciones de uso
            // del micrófono en primer plano.
            AppLog.e(TAG, "startForeground DENEGADO (origen=$origin)", e)
            return
        }
        AppLog.i(TAG, "startForeground OK (origen=$origin)")

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "escriba:recorder")
            .apply { acquire(MAX_DURATION_MS + 60_000) }

        stopRequested = false
        lastChangeElapsed = SystemClock.elapsedRealtime()
        RecordingState.set(this, RecordingState.Active(origin, System.currentTimeMillis(), startedElapsed))
        worker = Thread({ record(origin) }, "escriba-recorder").also { it.start() }
    }

    private fun stop(origin: String) {
        if (worker == null) return
        AppLog.i(TAG, "Parada solicitada (origen=$origin)")
        lastChangeElapsed = SystemClock.elapsedRealtime()
        stopRequested = true
    }

    @SuppressLint("MissingPermission") // comprobado en start()
    private fun record(origin: String) {
        val dir = File(filesDir, "spike").apply { mkdirs() }
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val file = File(dir, "spike-$stamp-$origin.ogg")

        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val frame = ShortArray(SAMPLE_RATE / 10) // 100 ms
        var recorder: AudioRecord? = null
        var writer: OpusOggWriter? = null
        var samples = 0L
        var nonZero = 0L
        var peak = 0
        var sumSquares = 0.0

        try {
            recorder = openRecorder(maxOf(minBuffer, frame.size * 2 * 4))
            writer = OpusOggWriter(file, SAMPLE_RATE, BITRATE)
            AppLog.i(TAG, "Grabando: fuente=${recorder.audioSource} códec=${writer.codecName} ${SAMPLE_RATE}Hz ${BITRATE / 1000}kbps")

            recorder.startRecording()
            val deadline = SystemClock.elapsedRealtime() + MAX_DURATION_MS
            while (!stopRequested && SystemClock.elapsedRealtime() < deadline) {
                val read = recorder.read(frame, 0, frame.size)
                if (read <= 0) {
                    AppLog.e(TAG, "AudioRecord.read devolvió $read")
                    break
                }
                for (i in 0 until read) {
                    val s = frame[i].toInt()
                    if (s != 0) nonZero++
                    val a = abs(s)
                    if (a > peak) peak = a
                    sumSquares += s.toDouble() * s
                }
                samples += read
                writer.write(frame, read)
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "Fallo durante la grabación", e)
        } finally {
            runCatching { recorder?.stop() }
            recorder?.release()
            runCatching { writer?.close() }.onFailure { AppLog.e(TAG, "Fallo al cerrar el Ogg", it) }
        }

        val seconds = samples / SAMPLE_RATE.toDouble()
        val rms = if (samples > 0) sqrt(sumSquares / samples) else 0.0
        val verdict = when {
            samples == 0L -> "SIN AUDIO"
            nonZero == 0L -> "SOLO CEROS: el sistema bloqueó el micrófono"
            else -> "AUDIO REAL"
        }
        AppLog.i(TAG, "Fin (origen=$origin): %.1f s, pico=%d, rms=%.0f → %s".format(seconds, peak, rms, verdict))
        if (file.exists()) {
            AppLog.i(TAG, "${file.name}: ${OggInspector.inspect(file)}")
        }

        finish()
    }

    @SuppressLint("MissingPermission")
    private fun openRecorder(bufferBytes: Int): AudioRecord {
        // VOICE_RECOGNITION entrega audio sin el procesado agresivo de llamadas, mejor para Whisper.
        for (source in intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
            val recorder = AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes)
            if (recorder.state == AudioRecord.STATE_INITIALIZED) return recorder
            recorder.release()
            AppLog.i(TAG, "Fuente de audio $source no disponible, probando otra")
        }
        error("No se pudo inicializar AudioRecord")
    }

    private fun finish() {
        worker = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        RecordingState.set(this, null)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopRequested = true
        super.onDestroy()
    }

    private fun buildNotification(startedElapsed: Long): android.app.Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_recording), NotificationManager.IMPORTANCE_LOW),
        )

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 1, intent(this, ACTION_STOP, "notificacion"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Escriba está grabando")
            .setContentText("Toca para abrir")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .addAction(R.drawable.ic_mic, "Parar", stopIntent)

        // Ongoing Activity: el icono del micrófono aparece en la esfera mientras se graba.
        OngoingActivity.Builder(applicationContext, NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.ic_mic)
            .setTouchIntent(openApp)
            .setStatus(
                Status.Builder()
                    .addTemplate("Grabando · #t#")
                    .addPart("t", Status.StopwatchPart(startedElapsed))
                    .build(),
            )
            .build()
            .apply(applicationContext)

        return builder.build()
    }

    companion object {
        private const val TAG = "rec"
        const val ACTION_START = "dev.escriba.action.START"
        const val ACTION_STOP = "dev.escriba.action.STOP"
        const val ACTION_TOGGLE = "dev.escriba.action.TOGGLE"
        const val EXTRA_ORIGIN = "origen"

        private const val CHANNEL_ID = "grabacion"
        private const val NOTIFICATION_ID = 1
        private const val SAMPLE_RATE = 16_000
        private const val BITRATE = 24_000
        /** Tope de seguridad del spike. */
        private const val MAX_DURATION_MS = 10 * 60_000L
        /** Dos alternancias más juntas que esto se consideran una sola pulsación duplicada. */
        private const val TOGGLE_DEBOUNCE_MS = 1_500L

        fun intent(context: Context, action: String, origin: String): Intent =
            Intent(context, RecorderService::class.java).setAction(action).putExtra(EXTRA_ORIGIN, origin)
    }
}
