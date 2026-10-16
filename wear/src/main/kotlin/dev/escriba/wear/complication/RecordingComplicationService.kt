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
import dev.escriba.wear.QuickStartActivity
import dev.escriba.wear.R
import dev.escriba.wear.recorder.RecorderService
import dev.escriba.wear.recorder.RecordingState

/**
 * Fase 1 (spike): complicación que muestra si se está grabando. Tocarla alterna la
 * grabación a través de QuickStartActivity.
 */
class RecordingComplicationService : SuspendingComplicationDataSourceService() {

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData {
        AppLog.i("compl", "La esfera pide datos (tipo=${request.complicationType})")
        return build(RecordingState.isRecording)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        if (type == ComplicationType.SHORT_TEXT) build(recording = false) else null

    private fun build(recording: Boolean): ShortTextComplicationData {
        // Arrancar el servicio directamente desde la esfera no funciona en HyperOS (Android lo
        // bloquea en silencio). Abrir la actividad de inicio rápido sí, como en el Tile.
        val tap = PendingIntent.getActivity(
            this,
            0,
            Intent(this, QuickStartActivity::class.java)
                .putExtra(RecorderService.EXTRA_ORIGIN, "complicacion")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder(if (recording) "REC" else "Escriba").build(),
            contentDescription = PlainComplicationText.Builder(
                if (recording) "Escriba está grabando" else "Escriba parado",
            ).build(),
        )
            .setMonochromaticImage(MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_mic)).build())
            .setTapAction(tap)
            .build()
    }
}
