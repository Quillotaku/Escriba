package dev.escriba.wear.link

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import dev.escriba.datalayer.AppLog
import dev.escriba.wear.EscribaWearApp
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** GMS wakes this service when a message from the phone arrives, even with the app closed. */
class WatchListenerService : WearableListenerService() {

    override fun onCreate() {
        super.onCreate()
        AppLog.i("link", "Listener service woken up by GMS")
    }

    // GMS calls this on its own thread, so blocking is safe and keeps the service alive until it
    // has finished replying.
    override fun onMessageReceived(event: MessageEvent) {
        runCatching {
            runBlocking { withTimeout(15_000) { (application as EscribaWearApp).link.onMessage(event, via = "service") } }
        }.onFailure { AppLog.e("link", "Failed to process ${event.path}", it) }
    }
}
