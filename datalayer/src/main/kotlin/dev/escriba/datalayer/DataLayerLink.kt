package dev.escriba.datalayer

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import dev.escriba.shared.EscribaJson
import dev.escriba.shared.Ping
import dev.escriba.shared.Pong
import dev.escriba.shared.Protocol
import dev.escriba.shared.fromWire
import dev.escriba.shared.toWire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Nodo remoto tal y como lo ve la Data Layer. */
data class Peer(
    val id: String,
    val name: String,
    val nearby: Boolean,
    val hasEscriba: Boolean,
)

/**
 * Enlace con el otro dispositivo por la Wearable Data Layer.
 *
 * Reglas de privacidad:
 *  - solo se envía a nodos con `isNearby == true`, es decir, conectados directamente
 *    (Bluetooth). Si el único camino es la nube de Google, no se envía nada;
 *  - todo lo que no sea el ping o el emparejamiento va cifrado con la clave del enlace
 *    ([sendSealed] / [openSealed]).
 */
class DataLayerLink(
    context: Context,
    /** "wear" o "mobile"; viaja en los mensajes para el diagnóstico. */
    private val origin: String,
    /** Capacidad que anuncia la app del otro extremo. */
    private val peerCapability: String,
) {
    private val appContext = context.applicationContext
    private val nodes = Wearable.getNodeClient(appContext)
    private val capabilities = Wearable.getCapabilityClient(appContext)
    private val messages = Wearable.getMessageClient(appContext)
    val channels: ChannelClient = Wearable.getChannelClient(appContext)
    val keys = LinkKeyStore(appContext)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** El mismo mensaje puede llegar por las dos vías; se procesa una sola vez. */
    private val seen = LinkedHashSet<String>()
    private val handlers = ConcurrentHashMap<String, suspend (MessageEvent) -> Unit>()

    /** Registra quién atiende una ruta. Lo usan el emparejamiento y la transferencia. */
    fun handle(path: String, handler: suspend (MessageEvent) -> Unit) {
        handlers[path] = handler
    }

    /** Todos los nodos conectados, marcando cuáles tienen Escriba instalada. */
    suspend fun peers(): List<Peer> {
        val connected = nodes.connectedNodes.await()
        val withApp = capabilities
            .getCapability(peerCapability, CapabilityClient.FILTER_REACHABLE)
            .await()
            .nodes
            .map(Node::getId)
            .toSet()
        return connected.map { Peer(it.id, it.displayName, it.isNearby, it.id in withApp) }
    }

    /** El dispositivo emparejado, solo si está conectado directamente. */
    suspend fun nearbyPairedPeer(): Peer? {
        val paired = keys.peer.value ?: return null
        return peers().firstOrNull { it.id == paired.nodeId && it.nearby }
    }

    /** El primer nodo cercano con Escriba (para emparejar). */
    suspend fun nearbyPeerWithApp(): Peer? = peers().firstOrNull { it.nearby && it.hasEscriba }

    suspend fun isNearby(nodeId: String): Boolean =
        nodes.connectedNodes.await().any { it.id == nodeId && it.isNearby }

    /** Envío en claro: solo para el ping y el emparejamiento. */
    suspend fun sendPlain(nodeId: String, path: String, data: ByteArray) {
        check(isNearby(nodeId)) { "El otro dispositivo no está conectado directamente" }
        messages.sendMessage(nodeId, path, data).await()
    }

    /** Envía [payload] cifrado al dispositivo emparejado. Devuelve false si no está cerca o no hay enlace. */
    suspend inline fun <reified T> sendSealed(path: String, payload: T): Boolean {
        val channel = keys.channel() ?: return false
        val peer = nearbyPairedPeer() ?: return false
        val sealed = channel.seal(path, EscribaJson.encodeToString(payload).encodeToByteArray())
        sendRaw(peer.id, path, sealed)
        return true
    }

    @PublishedApi
    internal suspend fun sendRaw(nodeId: String, path: String, data: ByteArray) {
        messages.sendMessage(nodeId, path, data).await()
    }

    /** Descifra un mensaje recibido. Rechaza lo que no venga del dispositivo emparejado. */
    inline fun <reified T> openSealed(event: MessageEvent): T {
        val peer = keys.peer.value ?: error("Mensaje cifrado sin enlace")
        check(event.sourceNodeId == peer.nodeId) { "Mensaje de un nodo no emparejado" }
        val channel = keys.channel() ?: error("Clave del enlace no disponible")
        return EscribaJson.decodeFromString(channel.open(event.path, event.data).decodeToString())
    }

    /** Envía un ping a cada nodo cercano con Escriba. Devuelve a cuántos se envió. */
    suspend fun pingNearby(): Int {
        val all = peers()
        AppLog.i(TAG, "Nodos: " + all.joinToString { "${it.name}(cercano=${it.nearby}, app=${it.hasEscriba})" }.ifEmpty { "ninguno" })
        val targets = all.filter { it.nearby && it.hasEscriba }
        all.filter { it.hasEscriba && !it.nearby }.forEach {
            AppLog.i(TAG, "No se envía a ${it.name}: no está conectado directamente")
        }
        for (peer in targets) {
            val ping = Ping(id = UUID.randomUUID().toString().take(8), sentAtMs = System.currentTimeMillis(), origin = origin)
            messages.sendMessage(peer.id, Protocol.PATH_PING, ping.toWire()).await()
            AppLog.i(TAG, "Ping ${ping.id} → ${peer.name}")
        }
        return targets.size
    }

    /**
     * Escucha directa mientras la app está en pantalla. Sirve de respaldo y de diagnóstico:
     * si un mensaje llega por aquí pero nunca por el servicio, el sistema del fabricante está
     * impidiendo que GMS despierte la app en segundo plano.
     */
    private val foregroundListener = MessageClient.OnMessageReceivedListener { event ->
        scope.launch {
            runCatching { onMessage(event, via = "app abierta") }
                .onFailure { AppLog.e(TAG, "Fallo al procesar ${event.path}", it) }
        }
    }

    fun startForegroundListening() {
        messages.addListener(foregroundListener)
    }

    fun stopForegroundListening() {
        messages.removeListener(foregroundListener)
    }

    /** Punto de entrada común: el WearableListenerService y la escucha en primer plano. */
    suspend fun onMessage(event: MessageEvent, via: String) {
        val key = "${event.sourceNodeId}:${event.requestId}"
        synchronized(seen) {
            if (!seen.add(key)) return
            if (seen.size > 500) seen.remove(seen.first())
        }
        when (event.path) {
            Protocol.PATH_PING -> {
                val ping: Ping = event.data.fromWire()
                AppLog.i(TAG, "Ping ${ping.id} recibido de ${ping.origin} vía $via")
                if (!isNearby(event.sourceNodeId)) {
                    AppLog.i(TAG, "No se responde: el emisor no está conectado directamente")
                    return
                }
                val pong = Pong(ping.id, ping.sentAtMs, System.currentTimeMillis(), origin)
                messages.sendMessage(event.sourceNodeId, Protocol.PATH_PONG, pong.toWire()).await()
            }

            Protocol.PATH_PONG -> {
                val pong: Pong = event.data.fromWire()
                val rtt = System.currentTimeMillis() - pong.pingSentAtMs
                AppLog.i(TAG, "Pong ${pong.id} de ${pong.origin}: ida y vuelta $rtt ms")
            }

            else -> {
                // Un mensaje cifrado de quien no está emparejado con nosotros: se descarta y se le
                // avisa, para que el otro lado sepa que hay que volver a emparejar.
                if (event.path !in PLAIN_PATHS) {
                    val paired = keys.peer.value
                    if (paired == null || paired.nodeId != event.sourceNodeId) {
                        AppLog.i(TAG, "Mensaje ${event.path} de un nodo sin enlace: se descarta")
                        reportUnknown(event.sourceNodeId)
                        return
                    }
                }
                val handler = handlers[event.path]
                if (handler == null) AppLog.i(TAG, "Mensaje no atendido: ${event.path}") else handler(event)
            }
        }
    }

    private val lastUnknownReport = ConcurrentHashMap<String, Long>()

    /**
     * Avisa a [nodeId] de que no tenemos enlace con él. Solo a nodos cercanos y como mucho una
     * vez por minuto. El aviso no lleva nada secreto y no borra claves: el otro lado solo lo muestra.
     */
    suspend fun reportUnknown(nodeId: String) {
        val now = System.currentTimeMillis()
        val last = lastUnknownReport[nodeId] ?: 0L
        if (now - last < 60_000) return
        lastUnknownReport[nodeId] = now
        runCatching { sendPlain(nodeId, Protocol.PATH_PAIR_UNKNOWN, ByteArray(0)) }
            .onFailure { AppLog.e(TAG, "No se pudo avisar de que no hay enlace", it) }
    }

    private companion object {
        const val TAG = "link"

        /** Rutas que viajan en claro (todo lo demás exige enlace). */
        val PLAIN_PATHS = setOf(
            Protocol.PATH_PAIR_INIT, Protocol.PATH_PAIR_REPLY, Protocol.PATH_PAIR_REVEAL,
            Protocol.PATH_PAIR_CONFIRM, Protocol.PATH_PAIR_UNKNOWN,
        )
    }
}
