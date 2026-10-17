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

/** A remote node as the Data Layer sees it. */
data class Peer(
    val id: String,
    val name: String,
    val nearby: Boolean,
    val hasEscriba: Boolean,
)

/**
 * Link with the other device over the Wearable Data Layer.
 *
 * Privacy rules:
 *  - messages only go to nodes with `isNearby == true`, i.e. directly connected (Bluetooth). If
 *    the only route is Google's cloud, nothing is sent;
 *  - everything except the ping and pairing is encrypted with the link key
 *    ([sendSealed] / [openSealed]).
 */
class DataLayerLink(
    context: Context,
    /** "wear" or "mobile"; it travels in the messages for diagnostics. */
    private val origin: String,
    /** Capability announced by the app on the other end. */
    private val peerCapability: String,
) {
    private val appContext = context.applicationContext
    private val nodes = Wearable.getNodeClient(appContext)
    private val capabilities = Wearable.getCapabilityClient(appContext)
    private val messages = Wearable.getMessageClient(appContext)
    val channels: ChannelClient = Wearable.getChannelClient(appContext)
    val keys = LinkKeyStore(appContext)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** The same message may arrive both ways; it's processed only once. */
    private val seen = LinkedHashSet<String>()
    private val handlers = ConcurrentHashMap<String, suspend (MessageEvent) -> Unit>()

    /** Registers who handles a path. Used by pairing and by the transfer. */
    fun handle(path: String, handler: suspend (MessageEvent) -> Unit) {
        handlers[path] = handler
    }

    /** Every connected node, marking which ones have Escriba installed. */
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

    /** The paired device, only if it's directly connected. */
    suspend fun nearbyPairedPeer(): Peer? {
        val paired = keys.peer.value ?: return null
        return peers().firstOrNull { it.id == paired.nodeId && it.nearby }
    }

    /** The first nearby node with Escriba (for pairing). */
    suspend fun nearbyPeerWithApp(): Peer? = peers().firstOrNull { it.nearby && it.hasEscriba }

    suspend fun isNearby(nodeId: String): Boolean =
        nodes.connectedNodes.await().any { it.id == nodeId && it.isNearby }

    /** Plain sending: only for the ping and pairing. */
    suspend fun sendPlain(nodeId: String, path: String, data: ByteArray) {
        check(isNearby(nodeId)) { "The other device isn't directly connected" }
        messages.sendMessage(nodeId, path, data).await()
    }

    /** Sends [payload] encrypted to the paired device. Returns false if it isn't near or there's no link. */
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

    /** Decrypts a received message. Rejects anything that doesn't come from the paired device. */
    inline fun <reified T> openSealed(event: MessageEvent): T {
        val peer = keys.peer.value ?: error("Sealed message without a link")
        check(event.sourceNodeId == peer.nodeId) { "Message from an unpaired node" }
        val channel = keys.channel() ?: error("Link key not available")
        return EscribaJson.decodeFromString(channel.open(event.path, event.data).decodeToString())
    }

    /** Pings every nearby node with Escriba. Returns how many were pinged. */
    suspend fun pingNearby(): Int {
        val all = peers()
        AppLog.i(TAG, "Nodes: " + all.joinToString { "${it.name}(nearby=${it.nearby}, app=${it.hasEscriba})" }.ifEmpty { "none" })
        val targets = all.filter { it.nearby && it.hasEscriba }
        all.filter { it.hasEscriba && !it.nearby }.forEach {
            AppLog.i(TAG, "Not sending to ${it.name}: not directly connected")
        }
        for (peer in targets) {
            val ping = Ping(id = UUID.randomUUID().toString().take(8), sentAtMs = System.currentTimeMillis(), origin = origin)
            messages.sendMessage(peer.id, Protocol.PATH_PING, ping.toWire()).await()
            AppLog.i(TAG, "Ping ${ping.id} → ${peer.name}")
        }
        return targets.size
    }

    /**
     * Direct listener while the app is on screen. It's a fallback and a diagnostic: if a message
     * arrives here but never through the service, the manufacturer's system is stopping GMS from
     * waking the app in the background.
     */
    private val foregroundListener = MessageClient.OnMessageReceivedListener { event ->
        scope.launch {
            runCatching { onMessage(event, via = "open app") }
                .onFailure { AppLog.e(TAG, "Failed to process ${event.path}", it) }
        }
    }

    fun startForegroundListening() {
        messages.addListener(foregroundListener)
    }

    fun stopForegroundListening() {
        messages.removeListener(foregroundListener)
    }

    /** Common entry point: the WearableListenerService and the foreground listener. */
    suspend fun onMessage(event: MessageEvent, via: String) {
        val key = "${event.sourceNodeId}:${event.requestId}"
        synchronized(seen) {
            if (!seen.add(key)) return
            if (seen.size > 500) seen.remove(seen.first())
        }
        when (event.path) {
            Protocol.PATH_PING -> {
                val ping: Ping = event.data.fromWire()
                AppLog.i(TAG, "Ping ${ping.id} received from ${ping.origin} via $via")
                if (!isNearby(event.sourceNodeId)) {
                    AppLog.i(TAG, "Not replying: the sender isn't directly connected")
                    return
                }
                val pong = Pong(ping.id, ping.sentAtMs, System.currentTimeMillis(), origin)
                messages.sendMessage(event.sourceNodeId, Protocol.PATH_PONG, pong.toWire()).await()
            }

            Protocol.PATH_PONG -> {
                val pong: Pong = event.data.fromWire()
                val rtt = System.currentTimeMillis() - pong.pingSentAtMs
                AppLog.i(TAG, "Pong ${pong.id} from ${pong.origin}: round trip $rtt ms")
            }

            else -> {
                // A sealed message from someone not paired with us: drop it and tell them, so the
                // other side knows it has to pair again.
                if (event.path !in PLAIN_PATHS) {
                    val paired = keys.peer.value
                    if (paired == null || paired.nodeId != event.sourceNodeId) {
                        AppLog.i(TAG, "Message ${event.path} from a node without a link: dropped")
                        reportUnknown(event.sourceNodeId)
                        return
                    }
                }
                val handler = handlers[event.path]
                if (handler == null) AppLog.i(TAG, "Unhandled message: ${event.path}") else handler(event)
            }
        }
    }

    private val lastUnknownReport = ConcurrentHashMap<String, Long>()

    /**
     * Tells [nodeId] that we have no link with it. Only to nearby nodes and at most once a minute.
     * The notice carries nothing secret and deletes no keys: the other side only shows it.
     */
    suspend fun reportUnknown(nodeId: String) {
        val now = System.currentTimeMillis()
        val last = lastUnknownReport[nodeId] ?: 0L
        if (now - last < 60_000) return
        lastUnknownReport[nodeId] = now
        runCatching { sendPlain(nodeId, Protocol.PATH_PAIR_UNKNOWN, ByteArray(0)) }
            .onFailure { AppLog.e(TAG, "Couldn't send the no-link notice", it) }
    }

    private companion object {
        const val TAG = "link"

        /** Paths that travel in plain text (everything else requires a link). */
        val PLAIN_PATHS = setOf(
            Protocol.PATH_PAIR_INIT, Protocol.PATH_PAIR_REPLY, Protocol.PATH_PAIR_REVEAL,
            Protocol.PATH_PAIR_CONFIRM, Protocol.PATH_PAIR_UNKNOWN,
        )
    }
}
