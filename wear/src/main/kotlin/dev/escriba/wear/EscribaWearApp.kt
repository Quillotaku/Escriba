package dev.escriba.wear

import android.app.Application
import dev.escriba.datalayer.AppLog
import dev.escriba.datalayer.DataLayerLink
import dev.escriba.shared.Protocol
import dev.escriba.wear.recorder.RecorderNotifications
import dev.escriba.wear.recorder.RecorderService
import dev.escriba.wear.recorder.RecordingIntent
import dev.escriba.wear.storage.FragmentStore
import java.io.File

class EscribaWearApp : Application() {

    lateinit var link: DataLayerLink
        private set

    lateinit var store: FragmentStore
        private set

    override fun onCreate() {
        super.onCreate()
        AppLog.attach(this)
        link = DataLayerLink(this, origin = "wear", peerCapability = Protocol.CAPABILITY_PHONE)
        store = FragmentStore(File(filesDir, "fragments"), RecorderService.SAMPLE_RATE, RecorderService.BITRATE)
        recoverAfterCrash()
    }

    /**
     * Un proceso nuevo nunca tiene una grabación en curso, así que todo lo que haya a medias
     * es de un proceso anterior: se cierra y, si el usuario estaba grabando, se le avisa.
     */
    private fun recoverAfterCrash() {
        val recovered = runCatching { store.recover() }
            .onFailure { AppLog.e("store", "Fallo en la recuperación", it) }
            .getOrDefault(0)
        val intent = RecordingIntent(this)
        if (intent.active) {
            AppLog.i("rec", "La grabación anterior (origen=${intent.origin}) se cortó; $recovered fragmentos recuperados")
            RecorderNotifications.showInterrupted(this, "La grabación se cortó")
            intent.clear()
        }
    }
}
