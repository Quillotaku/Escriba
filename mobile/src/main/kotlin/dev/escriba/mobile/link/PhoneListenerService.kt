package dev.escriba.mobile.link

import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import dev.escriba.datalayer.AppLog
import dev.escriba.mobile.EscribaApp
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** GMS wakes this service when a message from the watch arrives, even with the app closed. */
class PhoneListenerService : WearableListenerService() {

    override fun onCreate() {
        super.onCreate()
        AppLog.i("link", "Listener service woken up by GMS")
    }

    // GMS calls this on its own thread, so blocking is safe and keeps the service alive until it
    // has finished replying.
    override fun onMessageReceived(event: MessageEvent) {
        runCatching {
            runBlocking { withTimeout(15_000) { (application as EscribaApp).link.onMessage(event, via = "service") } }
        }.onFailure { AppLog.e("link", "Failed to process ${event.path}", it) }
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        runCatching {
            runBlocking { withTimeout(120_000) { (application as EscribaApp).inbox.onChannel(channel) } }
        }.onFailure { AppLog.e("inbox", "Failed to receive over the channel ${channel.path}", it) }
    }
}
