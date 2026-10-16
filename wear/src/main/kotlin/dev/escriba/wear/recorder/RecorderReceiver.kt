package dev.escriba.wear.recorder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.escriba.datalayer.AppLog

/**
 * Despertador del horario y arranque del reloj.
 *
 * - Al empezar la franja: si el servicio sigue vivo en pausa, se le pide que reanude; si el
 *   sistema lo mató por la noche, se avisa con una notificación (Android no permite volver a
 *   abrir el micrófono desde segundo plano sin que el usuario toque algo).
 * - Tras reiniciar el reloj: misma situación; si había una grabación en curso, se avisa.
 *   La recuperación de los fragmentos ya la hace EscribaWearApp al arrancar el proceso.
 */
class RecorderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_WINDOW_START -> {
                if (RecordingState.phase.value == RecordingState.Phase.PAUSED) {
                    AppLog.i(TAG, "Empieza la franja: reanudando")
                    context.startService(RecorderService.intent(context, RecorderService.ACTION_RESUME, "horario"))
                } else if (RecordingIntent(context).active) {
                    AppLog.i(TAG, "Empieza la franja pero el servicio no está vivo: aviso al usuario")
                    RecorderNotifications.showInterrupted(context, "Empieza tu horario de grabación")
                }
            }

            Intent.ACTION_BOOT_COMPLETED -> {
                if (RecordingIntent(context).active) {
                    AppLog.i(TAG, "El reloj se reinició durante una grabación")
                    RecorderNotifications.showInterrupted(context, "El reloj se reinició")
                }
            }
        }
    }

    companion object {
        private const val TAG = "rec"
        const val ACTION_WINDOW_START = "dev.escriba.action.WINDOW_START"
    }
}
