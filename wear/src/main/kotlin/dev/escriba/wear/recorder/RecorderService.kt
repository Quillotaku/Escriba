package dev.escriba.wear.recorder

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.escriba.datalayer.AppLog
import dev.escriba.shared.audio.SpeechGate
import dev.escriba.shared.recording.Fragmenter
import dev.escriba.wear.EscribaWearApp
import dev.escriba.wear.storage.FragmentStore
import dev.escriba.wear.storage.SessionMeta
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Servicio en primer plano de tipo micrófono: graba, filtra la voz con el VAD y guarda
 * fragmentos Opus/Ogg independientes en el reloj.
 *
 * Estados: IDLE → CAPTURING ⇄ PAUSED (fuera del horario, en modo continuo) → IDLE.
 * En pausa el servicio sigue vivo (y con su notificación) pero el micrófono está cerrado:
 * así, al empezar la franja, puede volver a grabar sin que el usuario toque nada.
 */
class RecorderService : Service() {

    private enum class StopReason { USER, SCHEDULE, ERROR, STORAGE }

    private lateinit var settings: RecorderSettings
    private lateinit var recordingIntent: RecordingIntent
    private lateinit var store: FragmentStore
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var stopReason: StopReason? = null
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastChangeElapsed = 0L
    private var origin = ""
    private var mode = RecordingMode.BAJO_DEMANDA

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = RecorderSettings(this)
        recordingIntent = RecordingIntent(this)
        store = (application as EscribaWearApp).store
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val origin = intent?.getStringExtra(EXTRA_ORIGIN) ?: "desconocido"
        val phase = RecordingState.phase.value
        when (intent?.action) {
            ACTION_START -> if (phase == RecordingState.Phase.IDLE) start(origin)
            ACTION_STOP -> stop(origin)
            ACTION_RESUME -> resume()
            ACTION_TOGGLE -> {
                val sinceLastChange = SystemClock.elapsedRealtime() - lastChangeElapsed
                when {
                    sinceLastChange < TOGGLE_DEBOUNCE_MS ->
                        AppLog.i(TAG, "Alternancia ignorada: llegó $sinceLastChange ms después de la anterior (origen=$origin)")
                    phase == RecordingState.Phase.IDLE -> start(origin)
                    else -> stop(origin)
                }
            }
            else -> AppLog.i(TAG, "Arranque sin acción desde $origin; no se graba")
        }
        if (RecordingState.phase.value == RecordingState.Phase.IDLE && worker == null) stopSelf()
        return START_NOT_STICKY
    }

    private fun start(origin: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            AppLog.e(TAG, "Sin permiso de micrófono; ábrelo desde la app del reloj")
            return
        }
        try {
            ServiceCompat.startForeground(
                this,
                RecorderNotifications.ONGOING_ID,
                RecorderNotifications.ongoing(this, "Escriba graba", settings.mode.label, SystemClock.elapsedRealtime()),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } catch (e: Exception) {
            // Android 14+ lanza SecurityException si el origen no cumple las condiciones de uso
            // del micrófono en primer plano.
            AppLog.e(TAG, "startForeground DENEGADO (origen=$origin)", e)
            return
        }
        RecorderNotifications.cancelInterrupted(this)
        this.origin = origin
        mode = settings.mode
        recordingIntent.markActive(origin)
        lastChangeElapsed = SystemClock.elapsedRealtime()
        AppLog.i(TAG, "Inicio (origen=$origin, modo=${mode.label}, fuente=${settings.source.label}, VAD=${settings.sensitivity.name.lowercase()})")

        if (mode == RecordingMode.CONTINUO && !settings.window.contains(LocalTime.now())) {
            enterPause()
        } else {
            beginSession()
        }
    }

    private fun stop(origin: String) {
        lastChangeElapsed = SystemClock.elapsedRealtime()
        when (RecordingState.phase.value) {
            RecordingState.Phase.CAPTURING -> {
                AppLog.i(TAG, "Parada solicitada (origen=$origin)")
                stopReason = StopReason.USER
            }
            RecordingState.Phase.PAUSED -> {
                AppLog.i(TAG, "Parada en pausa (origen=$origin)")
                cancelWindowAlarm()
                finishService()
            }
            RecordingState.Phase.IDLE -> Unit
        }
    }

    private fun resume() {
        if (RecordingState.phase.value != RecordingState.Phase.PAUSED) return
        cancelWindowAlarm()
        AppLog.i(TAG, "Reanudación por horario")
        beginSession()
    }

    private fun beginSession() {
        val meta = SessionMeta(
            sessionId = UUID.randomUUID().toString(),
            profileId = "pruebas",
            profileHash = "fase2",
            startMs = System.currentTimeMillis(),
            origin = origin,
            mode = mode.name,
        )
        store.createSession(meta)
        val startedElapsed = SystemClock.elapsedRealtime()
        RecordingState.update {
            RecordingState.Live(
                sessionId = meta.sessionId,
                origin = origin,
                mode = mode,
                startedAtMs = meta.startMs,
                startedElapsedMs = startedElapsed,
                batteryAtStart = BatteryLog.read(this)?.level,
            )
        }
        RecordingState.setPhase(this, RecordingState.Phase.CAPTURING)
        RecorderNotifications.update(this, RecorderNotifications.ongoing(this, "Escriba graba", mode.label, startedElapsed))

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "escriba:recorder")
            .apply { acquire(WAKELOCK_TIMEOUT_MS) }
        stopReason = null
        BatteryLog.append(this, "inicio", 0)
        AppLog.i(TAG, "Sesión ${meta.sessionId.take(8)} iniciada")
        worker = Thread({ capture(meta) }, "escriba-recorder").also { it.start() }
    }

    @SuppressLint("MissingPermission") // comprobado en start()
    private fun capture(meta: SessionMeta) {
        var reason = StopReason.ERROR
        var recorder: AudioRecord? = null
        var fragmenter: Fragmenter? = null
        val gate = SpeechGate(sampleRate = SAMPLE_RATE, sensitivity = settings.sensitivity)
        try {
            val captureStartWall = System.currentTimeMillis()
            fragmenter = Fragmenter(
                sampleRate = SAMPLE_RATE,
                wallClockAt = { sample -> captureStartWall + sample * 1000 / SAMPLE_RATE },
                openWriter = { seq -> store.openWriter(meta.sessionId, seq) },
                onClosed = { closed ->
                    val header = store.commit(meta, closed)
                    val check = OggInspector.inspect(store.audioFile(header))
                    val speechMs = header.segments.sumOf { it.durationMs }
                    AppLog.i(TAG, "Fragmento ${header.seq}: ${speechMs / 1000} s de voz en ${header.segments.size} tramos, " +
                        "${header.sizeBytes / 1024} KB (${kbps(header.sizeBytes, speechMs)} kbps) → ${if (check.ok) "Ogg válido" else "Ogg INVÁLIDO: $check"}")
                    RecordingState.update { it.copy(fragments = it.fragments + 1) }
                    if (store.stats().bytes > MAX_STORAGE_BYTES) {
                        AppLog.e(TAG, "Almacenamiento lleno: se para la grabación")
                        stopReason = StopReason.STORAGE
                    }
                },
            )
            recorder = openRecorder(settings.source)
            recorder.startRecording()
            AppLog.i(TAG, "Micrófono abierto: fuente=${settings.source.label}")

            val frame = ShortArray(gate.frameSamples)
            var position = 0L
            var frames = 0L
            while (stopReason == null) {
                val read = recorder.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING)
                if (read != frame.size) {
                    AppLog.e(TAG, "AudioRecord.read devolvió $read")
                    break
                }
                gate.process(frame).forEach(fragmenter::onEvent)
                position += read
                fragmenter.onTick(position)

                if (++frames % FRAMES_PER_SECOND == 0L) {
                    val speechPct = (gate.speechFrames * 100 / gate.totalFrames).toInt()
                    RecordingState.update {
                        it.copy(speechPct = speechPct, levelDb = gate.lastDb.toInt(), floorDb = gate.floorDb.toInt(), inSpeech = gate.isInSpeech)
                    }
                    if (mode == RecordingMode.CONTINUO && !settings.window.contains(LocalTime.now())) {
                        AppLog.i(TAG, "Fin de la franja ${settings.window}")
                        stopReason = StopReason.SCHEDULE
                    }
                    if (frames % (FRAMES_PER_SECOND * BATTERY_LOG_EVERY_S) == 0L) {
                        BatteryLog.append(this, "grabando", speechPct)
                    }
                }
            }
            reason = stopReason ?: StopReason.ERROR
        } catch (e: Exception) {
            AppLog.e(TAG, "Fallo durante la grabación", e)
        } finally {
            runCatching { recorder?.stop() }
            recorder?.release()
            runCatching {
                gate.flush().forEach { fragmenter?.onEvent(it) }
                fragmenter?.finish()
            }.onFailure { AppLog.e(TAG, "Fallo al cerrar el último fragmento", it) }
            store.endSession(meta.sessionId, System.currentTimeMillis(), fragmenter?.fragmentCount ?: 0)
            val speechPct = if (gate.totalFrames > 0) gate.speechFrames * 100 / gate.totalFrames else 0
            AppLog.i(TAG, "Sesión ${meta.sessionId.take(8)} cerrada: ${fragmenter?.fragmentCount ?: 0} fragmentos, " +
                "${gate.totalFrames / FRAMES_PER_SECOND} s escuchados, voz $speechPct %")
        }
        val finalReason = reason
        main.post { onCaptureEnded(finalReason) }
    }

    private fun onCaptureEnded(reason: StopReason) {
        worker = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        BatteryLog.append(this, "fin-${reason.name.lowercase()}", RecordingState.live.value.speechPct)
        when (reason) {
            StopReason.USER -> finishService()
            StopReason.SCHEDULE -> enterPause()
            StopReason.ERROR -> {
                RecorderNotifications.showInterrupted(this, "Falló el micrófono")
                finishService()
            }
            StopReason.STORAGE -> {
                RecorderNotifications.showInterrupted(this, "El reloj no tiene espacio")
                finishService()
            }
        }
    }

    private fun enterPause() {
        val next = settings.window.nextChange(LocalDateTime.now())
        val label = when {
            next == null -> "—"
            next.toLocalDate() == LocalDateTime.now().toLocalDate() -> "a las " + next.format(DateTimeFormatter.ofPattern("HH:mm"))
            else -> "mañana a las " + next.format(DateTimeFormatter.ofPattern("HH:mm"))
        }
        if (next != null) scheduleWindowAlarm(next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
        RecordingState.update { RecordingState.Live(origin = origin, mode = mode, pausedUntil = label) }
        RecordingState.setPhase(this, RecordingState.Phase.PAUSED)
        RecorderNotifications.update(this, RecorderNotifications.ongoing(this, "Escriba en pausa", "Fuera del horario; vuelve $label", null))
        AppLog.i(TAG, "En pausa hasta $label (horario ${settings.window})")
    }

    private fun finishService() {
        recordingIntent.clear()
        RecordingState.reset()
        RecordingState.setPhase(this, RecordingState.Phase.IDLE)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun windowAlarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        this,
        0,
        Intent(this, RecorderReceiver::class.java).setAction(RecorderReceiver.ACTION_WINDOW_START),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Alarma inexacta: en reposo profundo puede retrasarse unos minutos, que basta para un horario. */
    private fun scheduleWindowAlarm(atMs: Long) {
        getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, windowAlarmIntent())
    }

    private fun cancelWindowAlarm() {
        getSystemService(AlarmManager::class.java).cancel(windowAlarmIntent())
    }

    @SuppressLint("MissingPermission")
    private fun openRecorder(preferred: MicSource): AudioRecord {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufferBytes = maxOf(minBuffer, SAMPLE_RATE * 2 / 2) // al menos 0,5 s de margen
        val sources = listOf(preferred) + MicSource.entries.filter { it != preferred }
        for (source in sources) {
            val recorder = AudioRecord(source.audioSource, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes)
            if (recorder.state == AudioRecord.STATE_INITIALIZED) return recorder
            recorder.release()
            AppLog.i(TAG, "Fuente ${source.label} no disponible, probando otra")
        }
        error("No se pudo inicializar AudioRecord")
    }

    override fun onDestroy() {
        // Si el sistema destruye el servicio, el hilo cierra el fragmento abierto. La intención
        // de grabar se conserva para avisar al usuario cuando el proceso vuelva.
        if (worker != null) stopReason = StopReason.ERROR
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun kbps(bytes: Long, ms: Long) = if (ms > 0) bytes * 8 / ms else 0

    companion object {
        private const val TAG = "rec"
        const val ACTION_START = "dev.escriba.action.START"
        const val ACTION_STOP = "dev.escriba.action.STOP"
        const val ACTION_TOGGLE = "dev.escriba.action.TOGGLE"
        const val ACTION_RESUME = "dev.escriba.action.RESUME"
        const val EXTRA_ORIGIN = "origen"

        const val SAMPLE_RATE = 16_000
        const val BITRATE = 24_000
        private const val FRAMES_PER_SECOND = 50L
        private const val BATTERY_LOG_EVERY_S = 300L
        private const val WAKELOCK_TIMEOUT_MS = 20 * 60 * 60_000L
        private const val MAX_STORAGE_BYTES = 1_500L * 1024 * 1024
        /** Dos alternancias más juntas que esto se consideran una sola pulsación duplicada. */
        private const val TOGGLE_DEBOUNCE_MS = 1_500L

        fun intent(context: Context, action: String, origin: String): Intent =
            Intent(context, RecorderService::class.java).setAction(action).putExtra(EXTRA_ORIGIN, origin)
    }
}
