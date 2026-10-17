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
 * Escriba's complication: the active profile and whether it's recording. A tap starts or stops
 * recording with that profile.
 *
 * The tap goes through QuickStartActivity: starting the microphone service directly from the
 * watch face doesn't work on HyperOS (it's silently blocked).
 */
class RecordingComplicationService : SuspendingComplicationDataSourceService() {

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData {
        val profile = (application as EscribaWearApp).profiles.selected.value
        AppLog.i("compl", "The watch face asks for data: ${RecordingState.phase.value.name.lowercase()}, profile ${profile?.name}")
        return build(RecordingState.phase.value, profile?.name)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        if (type == ComplicationType.SHORT_TEXT) build(RecordingState.Phase.IDLE, getString(R.string.compl_preview_profile)) else null

    private fun build(phase: RecordingState.Phase, profileName: String?): ShortTextComplicationData {
        val tap = PendingIntent.getActivity(
            this,
            0,
            Intent(this, QuickStartActivity::class.java)
                .putExtra(RecorderService.EXTRA_ORIGIN, "complication")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val name = (profileName ?: getString(R.string.app_name)).take(7)
        // Many watch faces (Xiaomi's included) don't show the title: the state goes in the main
        // text and the icon, and the profile name stays as the title in case the face shows it.
        data class Look(val text: String, val title: String?, val icon: Int, val description: String)
        val look = when (phase) {
            RecordingState.Phase.CAPTURING -> Look("REC", name, R.drawable.ic_rec, getString(R.string.compl_recording_desc, profileName ?: ""))
            RecordingState.Phase.PAUSED -> Look(getString(R.string.compl_paused), name, R.drawable.ic_mic, getString(R.string.compl_paused_desc))
            RecordingState.Phase.IDLE -> Look(name, null, R.drawable.ic_mic, getString(R.string.compl_idle_desc, profileName ?: getString(R.string.compl_no_profile)))
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
