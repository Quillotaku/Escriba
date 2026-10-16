package dev.escriba.wear.complication

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import dev.escriba.datalayer.AppLog
import dev.escriba.wear.EscribaWearApp
import dev.escriba.wear.QuickStartActivity
import dev.escriba.wear.R
import dev.escriba.wear.recorder.RecorderService
import dev.escriba.wear.recorder.RecordingState

/**
 * Complicación de Escriba: el perfil activo y si se está grabando. Un toque empieza o para la
 * grabación con ese perfil.
 *
 * El toque pasa por QuickStartActivity: en la fase 1 se vio que arrancar el servicio de
 * micrófono directamente desde la esfera no funciona en HyperOS (se bloquea sin avisar).
 */
class RecordingComplicationService : SuspendingComplicationDataSourceService() {

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData {
        val profile = (application as EscribaWearApp).profiles.selected.value
        AppLog.i("compl", "La esfera pide datos: ${RecordingState.phase.value.name.lowercase()}, perfil ${profile?.name}")
        return build(RecordingState.phase.value, profile?.name)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        if (type == ComplicationType.SHORT_TEXT) build(RecordingState.Phase.IDLE, "Casual") else null

    private fun build(phase: RecordingState.Phase, profileName: String?): ShortTextComplicationData {
        val tap = PendingIntent.getActivity(
            this,
            0,
            Intent(this, QuickStartActivity::class.java)
                .putExtra(RecorderService.EXTRA_ORIGIN, "complicacion")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val name = (profileName ?: "Escriba").take(7)
        // Muchas esferas (las de Xiaomi incluidas) no muestran el título: el estado va en el texto
        // principal y en el icono, y el nombre del perfil queda como título por si la esfera lo enseña.
        data class Look(val text: String, val title: String?, val icon: Int, val description: String)
        val look = when (phase) {
            RecordingState.Phase.CAPTURING -> Look("REC", name, R.drawable.ic_rec, "Escriba graba con el perfil ${profileName ?: ""}. Toca para parar.")
            RecordingState.Phase.PAUSED -> Look("pausa", name, R.drawable.ic_mic, "Escriba en pausa por el horario")
            RecordingState.Phase.IDLE -> Look(name, null, R.drawable.ic_mic, "Escriba parado. Perfil ${profileName ?: "sin elegir"}. Toca para grabar.")
        }
        return ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder(look.text).build(),
            contentDescription = PlainComplicationText.Builder(look.description).build(),
        )
            .apply { look.title?.let { setTitle(PlainComplicationText.Builder(it).build()) } }
            .setMonochromaticImage(MonochromaticImage.Builder(Icon.createWithResource(this, look.icon)).build())
            .setTapAction(tap)
            .build()
    }
}
