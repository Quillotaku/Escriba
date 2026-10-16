package dev.escriba.mobile.link

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import dev.escriba.datalayer.AppLog
import dev.escriba.mobile.EscribaApp
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** GMS despierta este servicio al llegar un mensaje del reloj, aunque la app esté cerrada. */
class PhoneListenerService : WearableListenerService() {

    override fun onCreate() {
        super.onCreate()
        AppLog.i("link", "Servicio de escucha despertado por GMS")
    }

    // GMS llama aquí en un hilo propio, así que bloquear es seguro y mantiene vivo el servicio
    // hasta terminar de responder.
    override fun onMessageReceived(event: MessageEvent) {
        runCatching {
            runBlocking { withTimeout(15_000) { (application as EscribaApp).link.onMessage(event, via = "servicio") } }
        }.onFailure { AppLog.e("link", "Fallo al procesar ${event.path}", it) }
    }
}
