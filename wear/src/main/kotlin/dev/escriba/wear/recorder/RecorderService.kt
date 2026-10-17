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
import dev.escriba.shared.audio.VadSensitivity
import dev.escriba.shared.recording.DailyWindow
import dev.escriba.shared.vault.WatchProfile
import dev.escriba.wear.profiles.WatchProfiles
import dev.escriba.shared.recording.Fragmenter
import dev.escriba.wear.EscribaWearApp
import dev.escriba.wear.R
import dev.escriba.wear.storage.FragmentStore
import dev.escriba.wear.storage.SessionMeta
import dev.escriba.wear.transfer.UploadWorker
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Microphone-type foreground service: records, filters speech with the VAD and stores
 * independent Opus/Ogg fragments on the watch.
 *
 * States: IDLE → CAPTURING ⇄ PAUSED (outside the schedule, in continuous mode) → IDLE.
 * While paused the service stays alive (with its notification) but the microphone is closed:
 * that way, when the window starts, it can record again without the user touching anything.
 */
class RecorderService : Service() {

    private enum class StopReason { USER, SCHEDULE, ERROR, STORAGE, SWITCH }

    private lateinit var settings: RecorderSettings
    private lateinit var recordingIntent: RecordingIntent
    private lateinit var store: FragmentStore
    private lateinit var profiles: WatchProfiles
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var stopReason: StopReason? = null
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastChangeElapsed = 0L
    private var origin = ""
    private var mode = RecordingMode.ON_DEMAND
    private var sensitivity = VadSensitivity.MEDIUM
    private var window = DailyWindow.DEFAULT
    private var profile: WatchProfile? = null
    /** Profile to switch to once the current session has finished closing. */
    private var pendingSwitch: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = RecorderSettings(this)
        recordingIntent = RecordingIntent(this)
        store = (application as EscribaWearApp).store
        profiles = (application as EscribaWearApp).profiles
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val origin = intent?.getStringExtra(EXTRA_ORIGIN) ?: "unknown"
        val phase = RecordingState.phase.value
        when (intent?.action) {
            ACTION_START -> if (phase == RecordingState.Phase.IDLE) start(origin)
            ACTION_STOP -> stop(origin)
            ACTION_RESUME -> resume()
            ACTION_SWITCH -> switchTo(intent.getStringExtra(EXTRA_PROFILE) ?: PROFILE_NEXT, origin)
            ACTION_TOGGLE -> {
                val sinceLastChange = SystemClock.elapsedRealtime() - lastChangeElapsed
                when {
                    // Only with the service running: when stopped, the command arrives through
                    // startForegroundService and must be honoured (or Android kills the app).
                    phase != RecordingState.Phase.IDLE && sinceLastChange < TOGGLE_DEBOUNCE_MS ->
                        AppLog.i(TAG, "Toggle ignored: it came $sinceLastChange ms after the previous one (origin=$origin)")
                    phase == RecordingState.Phase.IDLE -> start(origin)
                    else -> stop(origin)
                }
            }
            else -> AppLog.i(TAG, "Started without an action from $origin; not recording")
        }
        if (RecordingState.phase.value == RecordingState.Phase.IDLE && worker == null) stopSelf()
        return START_NOT_STICKY
    }

    private fun start(origin: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            AppLog.e(TAG, "No microphone permission; open the watch app to grant it")
            return
        }
        try {
            ServiceCompat.startForeground(
                this,
                RecorderNotifications.ONGOING_ID,
                RecorderNotifications.ongoing(this, getString(R.string.notif_recording), profiles.selected.value?.name ?: getString(settings.mode.label), SystemClock.elapsedRealtime()),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } catch (e: Exception) {
            // Android 14+ throws SecurityException if the origin doesn't meet the conditions for
            // using the microphone in the foreground.
            AppLog.e(TAG, "startForeground DENIED (origin=$origin)", e)
            return
        }
        RecorderNotifications.cancelInterrupted(this)
        this.origin = origin
        applyProfile()
        recordingIntent.markActive(origin)
        lastChangeElapsed = SystemClock.elapsedRealtime()
        AppLog.i(TAG, "Start (origin=$origin, profile=${profile?.name ?: "no profiles"}, mode=${mode.name.lowercase()}, source=${settings.source.name.lowercase()}, VAD=${sensitivity.name.lowercase()})")
        captureOrPause()
    }

    /** The chosen profile rules; local settings are only used while there are no profiles yet. */
    private fun applyProfile() {
        profile = profiles.selected.value
        mode = profiles.modeOf(profile, settings.mode)
        sensitivity = profiles.vadOf(profile, settings.sensitivity)
        window = settings.windowOverride ?: profiles.scheduleOr(settings.window)
    }

    private fun captureOrPause() {
        if (mode == RecordingMode.CONTINUOUS && !window.contains(LocalTime.now())) enterPause() else beginSession()
    }

    /**
     * Switching profile closes the current session and opens another with the new profile: each
     * session has a single profile, which decides folder, template and processing on the phone.
     */
    private fun switchTo(requested: String, origin: String) {
        val target = if (requested == PROFILE_NEXT) profiles.nextId() else requested
        if (target == null) {
            AppLog.i(TAG, "No profiles to switch to")
            return
        }
        when (RecordingState.phase.value) {
            RecordingState.Phase.IDLE -> {
                profiles.select(target)
                start(origin)
            }
            RecordingState.Phase.CAPTURING -> {
                if (target == profile?.id) return
                AppLog.i(TAG, "Switching profile to \"$target\" (origin=$origin): closing the current session")
                pendingSwitch = target
                stopReason = StopReason.SWITCH
            }
            RecordingState.Phase.PAUSED -> {
                cancelWindowAlarm()
                profiles.select(target)
                applyProfile()
                AppLog.i(TAG, "Profile switched while paused to \"${profile?.name}\"")
                captureOrPause()
            }
        }
    }

    private fun stop(origin: String) {
        lastChangeElapsed = SystemClock.elapsedRealtime()
        when (RecordingState.phase.value) {
            RecordingState.Phase.CAPTURING -> {
                AppLog.i(TAG, "Stop requested (origin=$origin)")
                stopReason = StopReason.USER
            }
            RecordingState.Phase.PAUSED -> {
                AppLog.i(TAG, "Stopped while paused (origin=$origin)")
                cancelWindowAlarm()
                finishService()
            }
            RecordingState.Phase.IDLE -> Unit
        }
    }

    private fun resume() {
        if (RecordingState.phase.value != RecordingState.Phase.PAUSED) return
        cancelWindowAlarm()
        AppLog.i(TAG, "Resumed by the schedule")
        beginSession()
    }

    private fun beginSession() {
        val meta = SessionMeta(
            sessionId = UUID.randomUUID().toString(),
            profileId = profile?.id ?: "no-profile",
            profileHash = profile?.hash ?: "",
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
                profileName = profile?.name,
                profileColor = profile?.color,
                batteryAtStart = BatteryLog.read(this)?.level,
            )
        }
        RecordingState.setPhase(this, RecordingState.Phase.CAPTURING)
        RecorderNotifications.update(this, RecorderNotifications.ongoing(this, getString(R.string.notif_recording), profile?.name ?: getString(mode.label), startedElapsed))

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "escriba:recorder")
            .apply { acquire(WAKELOCK_TIMEOUT_MS) }
        stopReason = null
        BatteryLog.append(this, "start", 0)
        AppLog.i(TAG, "Session ${meta.sessionId.take(8)} started")
        worker = Thread({ capture(meta) }, "escriba-recorder").also { it.start() }
    }

    @SuppressLint("MissingPermission") // checked in start()
    private fun capture(meta: SessionMeta) {
        var reason = StopReason.ERROR
        var recorder: AudioRecord? = null
        var fragmenter: Fragmenter? = null
        val gate = SpeechGate(sampleRate = SAMPLE_RATE, sensitivity = sensitivity)
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
                    AppLog.i(TAG, "Fragment ${header.seq}: ${speechMs / 1000} s of speech in ${header.segments.size} segments, " +
                        "${header.sizeBytes / 1024} KB (${kbps(header.sizeBytes, speechMs)} kbps) → ${if (check.ok) "valid Ogg" else "INVALID Ogg: $check"}")
                    RecordingState.update { it.copy(fragments = it.fragments + 1) }
                    UploadWorker.kick(this)
                    if (store.stats().bytes > MAX_STORAGE_BYTES) {
                        AppLog.e(TAG, "Storage full: stopping the recording")
                        stopReason = StopReason.STORAGE
                    }
                },
            )
            recorder = openRecorder(settings.source)
            recorder.startRecording()
            AppLog.i(TAG, "Microphone open: source=${settings.source.name.lowercase()}")

            val frame = ShortArray(gate.frameSamples)
            var position = 0L
            var frames = 0L
            while (stopReason == null) {
                val read = recorder.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING)
                if (read != frame.size) {
                    AppLog.e(TAG, "AudioRecord.read returned $read")
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
                    if (mode == RecordingMode.CONTINUOUS && !window.contains(LocalTime.now())) {
                        AppLog.i(TAG, "End of the window $window")
                        stopReason = StopReason.SCHEDULE
                    }
                    if (frames % (FRAMES_PER_SECOND * BATTERY_LOG_EVERY_S) == 0L) {
                        BatteryLog.append(this, "recording", speechPct)
                    }
                }
            }
            reason = stopReason ?: StopReason.ERROR
        } catch (e: Exception) {
            AppLog.e(TAG, "Failure while recording", e)
        } finally {
            runCatching { recorder?.stop() }
            recorder?.release()
            runCatching {
                gate.flush().forEach { fragmenter?.onEvent(it) }
                fragmenter?.finish()
            }.onFailure { AppLog.e(TAG, "Failed to close the last fragment", it) }
            store.endSession(meta.sessionId, System.currentTimeMillis(), fragmenter?.fragmentCount ?: 0)
            UploadWorker.kick(this)
            val speechPct = if (gate.totalFrames > 0) gate.speechFrames * 100 / gate.totalFrames else 0
            AppLog.i(TAG, "Session ${meta.sessionId.take(8)} closed: ${fragmenter?.fragmentCount ?: 0} fragments, " +
                "${gate.totalFrames / FRAMES_PER_SECOND} s listened, speech $speechPct %")
        }
        val finalReason = reason
        main.post { onCaptureEnded(finalReason) }
    }

    private fun onCaptureEnded(reason: StopReason) {
        worker = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        BatteryLog.append(this, "end-${reason.name.lowercase()}", RecordingState.live.value.speechPct)
        when (reason) {
            StopReason.USER -> finishService()
            StopReason.SCHEDULE -> enterPause()
            StopReason.SWITCH -> {
                pendingSwitch?.let(profiles::select)
                pendingSwitch = null
                applyProfile()
                AppLog.i(TAG, "New session with the profile \"${profile?.name}\"")
                captureOrPause()
            }
            StopReason.ERROR -> {
                RecorderNotifications.showInterrupted(this, R.string.interrupted_mic)
                finishService()
            }
            StopReason.STORAGE -> {
                RecorderNotifications.showInterrupted(this, R.string.interrupted_storage)
                finishService()
            }
        }
    }

    private fun enterPause() {
        val next = window.nextChange(LocalDateTime.now())
        val label = when {
            next == null -> "—"
            next.toLocalDate() == LocalDateTime.now().toLocalDate() -> getString(R.string.pause_at, next.format(DateTimeFormatter.ofPattern("HH:mm")))
            else -> getString(R.string.pause_tomorrow_at, next.format(DateTimeFormatter.ofPattern("HH:mm")))
        }
        if (next != null) scheduleWindowAlarm(next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
        RecordingState.update { RecordingState.Live(origin = origin, mode = mode, pausedUntil = label, profileName = profile?.name, profileColor = profile?.color) }
        RecordingState.setPhase(this, RecordingState.Phase.PAUSED)
        RecorderNotifications.update(this, RecorderNotifications.ongoing(this, getString(R.string.notif_paused), getString(R.string.notif_paused_text, label), null))
        AppLog.i(TAG, "Paused until $label (schedule $window)")
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

    /** Inexact alarm: in deep idle it may be a few minutes late, which is enough for a schedule. */
    private fun scheduleWindowAlarm(atMs: Long) {
        getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, windowAlarmIntent())
    }

    private fun cancelWindowAlarm() {
        getSystemService(AlarmManager::class.java).cancel(windowAlarmIntent())
    }

    @SuppressLint("MissingPermission")
    private fun openRecorder(preferred: MicSource): AudioRecord {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufferBytes = maxOf(minBuffer, SAMPLE_RATE * 2 / 2) // at least 0.5 s of headroom
        val sources = listOf(preferred) + MicSource.entries.filter { it != preferred }
        for (source in sources) {
            val recorder = AudioRecord(source.audioSource, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes)
            if (recorder.state == AudioRecord.STATE_INITIALIZED) return recorder
            recorder.release()
            AppLog.i(TAG, "Source ${source.name.lowercase()} not available, trying another")
        }
        error("Couldn't initialize AudioRecord")
    }

    override fun onDestroy() {
        // If the system destroys the service, the thread closes the open fragment. The intent to
        // record is kept so the user is told when the process comes back.
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
        const val ACTION_SWITCH = "dev.escriba.action.SWITCH"
        const val EXTRA_ORIGIN = "origin"
        const val EXTRA_PROFILE = "profile"
        const val PROFILE_NEXT = "_next"

        const val SAMPLE_RATE = 16_000
        const val BITRATE = 24_000
        private const val FRAMES_PER_SECOND = 50L
        private const val BATTERY_LOG_EVERY_S = 300L
        private const val WAKELOCK_TIMEOUT_MS = 20 * 60 * 60_000L
        private const val MAX_STORAGE_BYTES = 1_500L * 1024 * 1024
        /** Two toggles closer than this count as a single duplicated press. */
        private const val TOGGLE_DEBOUNCE_MS = 1_500L

        fun intent(context: Context, action: String, origin: String): Intent =
            Intent(context, RecorderService::class.java).setAction(action).putExtra(EXTRA_ORIGIN, origin)

        fun switchIntent(context: Context, profileId: String, origin: String): Intent =
            intent(context, ACTION_SWITCH, origin).putExtra(EXTRA_PROFILE, profileId)

        /**
         * Sends a command to the service with the right call:
         *  - stopped: `startForegroundService`, because the command starts it and the service will
         *    call `startForeground` (only a command that starts recording makes sense);
         *  - recording or paused: `startService`. The service is already in the foreground, and if
         *    it's sent `startForegroundService` to stop it, Android demands a `startForeground`
         *    that never comes and kills the app (seen when stopping from the Tile).
         */
        fun send(context: Context, intent: Intent) {
            val running = RecordingState.phase.value != RecordingState.Phase.IDLE
            when {
                running -> context.startService(intent)
                intent.action == ACTION_STOP -> AppLog.i(TAG, "Stop command with no recording in progress; ignored")
                else -> context.startForegroundService(intent)
            }
        }
    }
}
