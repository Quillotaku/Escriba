package dev.escriba.wear

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import dev.escriba.datalayer.AppLog
import dev.escriba.wear.recorder.RecorderService

/**
 * Toggles the recording and closes. The complication, the interrupted-recording alert and the
 * hardware button launch it. It starts the service in onResume, with the activity already
 * visible, because that's what Android requires to grant the microphone to a new service.
 */
class QuickStartActivity : Activity() {

    private var origin = ORIGIN_BUTTON
    /** One press = one toggle, even if the system resumes the activity several times. */
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handled = savedInstanceState?.getBoolean(KEY_HANDLED) ?: false
        // The activity is exported (launcher icon and hardware button), so any app could launch
        // it with made-up extras: the origin is only accepted if it's one of ours.
        origin = intent.getStringExtra(RecorderService.EXTRA_ORIGIN)?.takeIf { it in KNOWN_ORIGINS } ?: ORIGIN_BUTTON
        val extras = intent.extras?.keySet()?.joinToString().orEmpty().ifEmpty { "none" }
        AppLog.i(TAG, "Opened: origin=$origin extras=[$extras] restored=${savedInstanceState != null}")
    }

    override fun onResume() {
        super.onResume()
        if (handled) {
            AppLog.i(TAG, "Resumed again; ignored (origin=$origin)")
            finish()
            return
        }
        handled = true
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            AppLog.i(TAG, "Microphone permission missing; opening the app")
            startActivity(Intent(this, MainActivity::class.java))
        } else {
            runCatching {
                RecorderService.send(this, RecorderService.intent(this, RecorderService.ACTION_TOGGLE, origin))
            }.onFailure { AppLog.e(TAG, "Couldn't start the service (origin=$origin)", it) }
        }
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_HANDLED, handled)
    }

    private companion object {
        const val TAG = "quick"
        const val KEY_HANDLED = "handled"
        const val ORIGIN_BUTTON = "button"
        val KNOWN_ORIGINS = setOf("button", "complication", "resume")
    }
}
