package dev.escriba.wear

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import dev.escriba.datalayer.AppLog
import dev.escriba.wear.recorder.RecorderService

/**
 * Alterna la grabación y se cierra. La lanzan el Tile ("vía actividad") y el botón
 * físico. Arranca el servicio en onResume, con la actividad ya visible, porque es la
 * condición que Android exige para conceder el micrófono a un servicio nuevo.
 */
class QuickStartActivity : Activity() {

    private var origin = ORIGIN_BUTTON
    /** Una pulsación = una alternancia, aunque el sistema reanude la actividad varias veces. */
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handled = savedInstanceState?.getBoolean(KEY_HANDLED) ?: false
        // La actividad está exportada (icono del lanzador y botón físico), así que cualquier app
        // podría lanzarla con extras inventados: el origen solo se acepta si es uno de los nuestros.
        origin = intent.getStringExtra(RecorderService.EXTRA_ORIGIN)?.takeIf { it in KNOWN_ORIGINS } ?: ORIGIN_BUTTON
        val extras = intent.extras?.keySet()?.joinToString().orEmpty().ifEmpty { "ninguno" }
        AppLog.i(TAG, "Abierta: origen=$origin extras=[$extras] restaurada=${savedInstanceState != null}")
    }

    override fun onResume() {
        super.onResume()
        if (handled) {
            AppLog.i(TAG, "Reanudada otra vez; se ignora (origen=$origin)")
            finish()
            return
        }
        handled = true
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            AppLog.i(TAG, "Falta el permiso de micrófono; abriendo la app")
            startActivity(Intent(this, MainActivity::class.java))
        } else {
            runCatching {
                RecorderService.send(this, RecorderService.intent(this, RecorderService.ACTION_TOGGLE, origin))
            }.onFailure { AppLog.e(TAG, "No se pudo arrancar el servicio (origen=$origin)", it) }
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
        const val ORIGIN_BUTTON = "boton"
        val KNOWN_ORIGINS = setOf("boton", "complicacion", "reanudar", "tile-actividad")
    }
}
