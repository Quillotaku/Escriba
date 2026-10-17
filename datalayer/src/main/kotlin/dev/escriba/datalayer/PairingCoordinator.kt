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

/** What a device is waiting for while pairing. Each app shows it with its own string resources. */
enum class PairingWait {
    /** Watch: the request went out; the user has to tap "Pair" on the phone. */
    PHONE,

    /** Phone: the window is open; the user has to tap "Pair" on the watch. */
    WATCH,

    /** Phone: keys exchanged, the code is being computed. */
    CODE,
}

/** Why pairing failed. Each app shows it with its own string resources. */
enum class PairingFailure {
    NO_NEARBY_PHONE,
    NOT_DIRECTLY_CONNECTED,
    /** The watch revealed a key that doesn't match its commitment: possible man in the middle. */
    COMMITMENT_MISMATCH,
    BAD_CONFIRMATION,
}

/** Pairing state, for each app's UI. */
sealed interface PairingState {
    data object Idle : PairingState
    data class Waiting(val wait: PairingWait) : PairingState
    data class Code(val code: String, val peerName: String, val localConfirmed: Boolean, val peerConfirmed: Boolean) : PairingState
    data class Done(val peer: PairedPeer) : PairingState
    data class Failed(val reason: PairingFailure) : PairingState
}

/**
 * Runs the [Pairing] protocol over the Data Layer. The watch initiates; the phone only answers if
 * the user opened the pairing window first (2 minutes), so nobody can pair without the user asking
 * for it on both screens.
 */
class PairingCoordinator(
    private val link: DataLayerLink,
    private val isWatch: Boolean,
    private val deviceName: String,
) {
    private val _state = MutableStateFlow<PairingState>(PairingState.Idle)
    val state: StateFlow<PairingState> = _state

    private val _peerLost = MutableStateFlow(false)

    /**
     * The paired device says it doesn't recognize us any more (its data was cleared, or it paired
     * with another device). Nothing is deleted: the UI asks to pair again.
     */
    val peerLost: StateFlow<Boolean> = _peerLost

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
        link.handle(Protocol.PATH_PAIR_UNKNOWN) { event ->
            if (event.sourceNodeId == link.keys.peer.value?.nodeId && !_peerLost.value) {
                AppLog.e(TAG, "The ${if (isWatch) "phone" else "watch"} no longer recognizes the pairing: pair again")
                _peerLost.value = true
            }
        }
    }

    /** Watch: starts pairing with the nearby phone. */
    suspend fun startAsWatch() {
        reset()
        val peer = link.nearbyPeerWithApp() ?: return fail(PairingFailure.NO_NEARBY_PHONE)
        val init = Pairing.Initiator(deviceName).also { initiator = it }
        peerNode = peer.id
        peerName = peer.name
        _state.value = PairingState.Waiting(PairingWait.PHONE)
        link.sendPlain(peer.id, Protocol.PATH_PAIR_INIT, init.start().toWire())
        AppLog.i(TAG, "Pairing request sent to ${peer.name}")
    }

    /** Phone: accepts requests for 2 minutes. */
    fun openWindowAsPhone() {
        reset()
        windowUntil = System.currentTimeMillis() + WINDOW_MS
        _state.value = PairingState.Waiting(PairingWait.WATCH)
    }

    /** The user confirms that both codes match. */
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
            AppLog.i(TAG, "Pairing request ignored: open Escriba on the phone and tap \"Pair\"")
            return
        }
        if (!link.isNearby(event.sourceNodeId)) return fail(PairingFailure.NOT_DIRECTLY_CONNECTED)
        val init: PairInit = event.data.fromWire()
        val r = Pairing.Responder(deviceName).also { responder = it }
        peerNode = event.sourceNodeId
        peerName = init.name
        link.sendPlain(event.sourceNodeId, Protocol.PATH_PAIR_REPLY, r.onInit(init).toWire())
        _state.value = PairingState.Waiting(PairingWait.CODE)
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
        result = runCatching { r.onReveal(reveal) }.getOrElse { return fail(PairingFailure.COMMITMENT_MISMATCH) }
        publishCode()
    }

    private fun onConfirm(event: MessageEvent) {
        val r = result ?: return
        if (event.sourceNodeId != peerNode) return
        val confirm: PairConfirm = event.data.fromWire()
        if (!r.verify(peerRole, confirm)) return fail(PairingFailure.BAD_CONFIRMATION)
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
        _peerLost.value = false
        AppLog.i(TAG, "Paired with $peerName (key ${r.keyId})")
        reset()
        _state.value = PairingState.Done(peer)
    }

    private fun fail(reason: PairingFailure) {
        AppLog.e(TAG, "Pairing failed: $reason")
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
        const val TAG = "link"
        const val WINDOW_MS = 2 * 60_000L
    }
}
