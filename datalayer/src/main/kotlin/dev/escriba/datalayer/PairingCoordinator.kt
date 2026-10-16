package dev.escriba.datalayer

import com.google.android.gms.wearable.MessageEvent
import dev.escriba.shared.PairConfirm
import dev.escriba.shared.PairInit
import dev.escriba.shared.PairReply
import dev.escriba.shared.PairReveal
import dev.escriba.shared.Protocol
import dev.escriba.shared.crypto.Pairing
import dev.escriba.shared.fromWire
import dev.escriba.shared.toWire
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Estado del emparejamiento, para la UI de cada app. */
sealed interface PairingState {
    data object Idle : PairingState
    data class Waiting(val message: String) : PairingState
    data class Code(val code: String, val peerName: String, val localConfirmed: Boolean, val peerConfirmed: Boolean) : PairingState
    data class Done(val peer: PairedPeer) : PairingState
    data class Failed(val reason: String) : PairingState
}

/**
 * Lleva el protocolo de [Pairing] sobre la Data Layer. El reloj inicia; el móvil responde
 * solo si el usuario ha abierto antes la ventana de emparejamiento (2 minutos), para que
 * nadie pueda emparejarse sin que el usuario lo pida en las dos pantallas.
 */
class PairingCoordinator(
    private val link: DataLayerLink,
    private val isWatch: Boolean,
    private val deviceName: String,
) {
    private val _state = MutableStateFlow<PairingState>(PairingState.Idle)
    val state: StateFlow<PairingState> = _state

    private var initiator: Pairing.Initiator? = null
    private var responder: Pairing.Responder? = null
    private var result: Pairing.Result? = null
    private var peerNode: String? = null
    private var peerName = ""
    private var windowUntil = 0L
    private var localConfirmed = false
    private var peerConfirmed = false

    private val myRole = if (isWatch) Pairing.ROLE_WATCH else Pairing.ROLE_PHONE
    private val peerRole = if (isWatch) Pairing.ROLE_PHONE else Pairing.ROLE_WATCH

    init {
        if (isWatch) {
            link.handle(Protocol.PATH_PAIR_REPLY, ::onReply)
        } else {
            link.handle(Protocol.PATH_PAIR_INIT, ::onInit)
            link.handle(Protocol.PATH_PAIR_REVEAL, ::onReveal)
        }
        link.handle(Protocol.PATH_PAIR_CONFIRM, ::onConfirm)
    }

    /** Reloj: empieza el emparejamiento con el móvil cercano. */
    suspend fun startAsWatch() {
        reset()
        val peer = link.nearbyPeerWithApp() ?: return fail("No hay ningún móvil cercano con Escriba")
        val init = Pairing.Initiator(deviceName).also { initiator = it }
        peerNode = peer.id
        peerName = peer.name
        _state.value = PairingState.Waiting("Esperando al móvil… Abre Escriba en el móvil y pulsa «Emparejar».")
        link.sendPlain(peer.id, Protocol.PATH_PAIR_INIT, init.start().toWire())
        AppLog.i(TAG, "Solicitud de emparejamiento enviada a ${peer.name}")
    }

    /** Móvil: acepta solicitudes durante 2 minutos. */
    fun openWindowAsPhone() {
        reset()
        windowUntil = System.currentTimeMillis() + WINDOW_MS
        _state.value = PairingState.Waiting("Pulsa «Emparejar» en el reloj.")
    }

    /** El usuario confirma que los dos códigos coinciden. */
    suspend fun confirm() {
        val r = result ?: return
        val node = peerNode ?: return
        localConfirmed = true
        link.sendPlain(node, Protocol.PATH_PAIR_CONFIRM, r.confirm(myRole).toWire())
        publishCode()
        maybeFinish()
    }

    fun cancel() {
        reset()
        _state.value = PairingState.Idle
    }

    private suspend fun onInit(event: MessageEvent) {
        if (System.currentTimeMillis() > windowUntil) {
            AppLog.i(TAG, "Solicitud de emparejamiento ignorada: abre Escriba en el móvil y pulsa «Emparejar»")
            return
        }
        if (!link.isNearby(event.sourceNodeId)) return fail("El reloj no está conectado directamente")
        val init: PairInit = event.data.fromWire()
        val r = Pairing.Responder(deviceName).also { responder = it }
        peerNode = event.sourceNodeId
        peerName = init.name
        link.sendPlain(event.sourceNodeId, Protocol.PATH_PAIR_REPLY, r.onInit(init).toWire())
        _state.value = PairingState.Waiting("Calculando el código…")
    }

    private suspend fun onReply(event: MessageEvent) {
        val init = initiator ?: return
        if (event.sourceNodeId != peerNode) return
        val reply: PairReply = event.data.fromWire()
        val (reveal, r) = init.onReply(reply)
        result = r
        peerName = reply.name
        link.sendPlain(event.sourceNodeId, Protocol.PATH_PAIR_REVEAL, reveal.toWire())
        publishCode()
    }

    private fun onReveal(event: MessageEvent) {
        val r = responder ?: return
        if (event.sourceNodeId != peerNode) return
        val reveal: PairReveal = event.data.fromWire()
        result = runCatching { r.onReveal(reveal) }.getOrElse {
            return fail("El reloj no cumplió su compromiso: posible intermediario. Vuelve a intentarlo.")
        }
        publishCode()
    }

    private fun onConfirm(event: MessageEvent) {
        val r = result ?: return
        if (event.sourceNodeId != peerNode) return
        val confirm: PairConfirm = event.data.fromWire()
        if (!r.verify(peerRole, confirm)) return fail("La confirmación del otro dispositivo no es válida")
        peerConfirmed = true
        publishCode()
        maybeFinish()
    }

    private fun publishCode() {
        val r = result ?: return
        _state.value = PairingState.Code(r.code, peerName, localConfirmed, peerConfirmed)
    }

    private fun maybeFinish() {
        val r = result ?: return
        val node = peerNode ?: return
        if (!localConfirmed || !peerConfirmed) return
        val peer = PairedPeer(node, peerName, r.keyId, System.currentTimeMillis())
        link.keys.save(peer, r.key)
        AppLog.i(TAG, "Emparejado con $peerName (clave ${r.keyId})")
        reset()
        _state.value = PairingState.Done(peer)
    }

    private fun fail(reason: String) {
        AppLog.e(TAG, "Emparejamiento: $reason")
        reset()
        _state.value = PairingState.Failed(reason)
    }

    private fun reset() {
        initiator = null
        responder = null
        result = null
        peerNode = null
        peerName = ""
        windowUntil = 0
        localConfirmed = false
        peerConfirmed = false
    }

    private companion object {
        const val TAG = "enlace"
        const val WINDOW_MS = 2 * 60_000L
    }
}
