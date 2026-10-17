package dev.escriba.shared.crypto

import dev.escriba.shared.PairConfirm
import dev.escriba.shared.PairInit
import dev.escriba.shared.PairReply
import dev.escriba.shared.PairReveal
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

/**
 * Watch ↔ phone pairing verified with a code (in the style of Bluetooth numeric comparison).
 * It protects against a man in the middle, including GMS's own transport.
 *
 * ```
 * watch (initiates)                               phone (responds)
 *   PairInit   { commit = SHA256(pubW ‖ nonceW) }  →
 *              ←  PairReply  { pubP, nonceP }
 *   PairReveal { pubW, nonceW }                    →   checks the commitment
 *   both: ECDH P-256 → HKDF → link key + 6-digit code
 *   the user checks both codes match and confirms on each screen
 *   PairConfirm { HMAC(key, role) }                ⇄
 * ```
 *
 * The initial commitment forces the watch to fix its key before seeing the phone's, so a man in
 * the middle can't keep trying keys until the codes match: they get one chance in a million.
 */
object Pairing {

    private const val CURVE = "secp256r1"
    private val INFO_KEY = "escriba v1 link".encodeToByteArray()
    private val INFO_SAS = "escriba v1 code".encodeToByteArray()
    const val ROLE_WATCH = "watch"
    const val ROLE_PHONE = "phone"

    /** Result of a pairing: the link key and the code shown to the user. */
    class Result(val key: ByteArray, val code: String) {
        val keyId: String get() = Crypto.hex(Crypto.sha256(key).copyOf(8))

        fun confirm(role: String) = PairConfirm(Crypto.b64(Crypto.hmacSha256(key, "confirm:$role".encodeToByteArray())))

        fun verify(peerRole: String, confirm: PairConfirm): Boolean =
            Crypto.constantTimeEquals(Crypto.unb64(confirm.mac), Crypto.unb64(confirm(peerRole).mac))
    }

    /** Watch side. */
    class Initiator(private val name: String) {
        private val keyPair = generateKeyPair()
        private val nonce = Crypto.randomBytes(16)
        private val publicKey = keyPair.public.encoded

        fun start() = PairInit(commit = Crypto.b64(Crypto.sha256(publicKey, nonce)), name = name)

        fun onReply(reply: PairReply): Pair<PairReveal, Result> {
            val peerPublic = Crypto.unb64(reply.publicKey)
            val peerNonce = Crypto.unb64(reply.nonce)
            val result = derive(keyPair, peerPublic, watchPublic = publicKey, phonePublic = peerPublic, watchNonce = nonce, phoneNonce = peerNonce)
            return PairReveal(publicKey = Crypto.b64(publicKey), nonce = Crypto.b64(nonce)) to result
        }
    }

    /** Phone side. */
    class Responder(private val name: String) {
        private val keyPair = generateKeyPair()
        private val nonce = Crypto.randomBytes(16)
        private val publicKey = keyPair.public.encoded
        private var commit: ByteArray? = null

        fun onInit(init: PairInit): PairReply {
            commit = Crypto.unb64(init.commit)
            return PairReply(publicKey = Crypto.b64(publicKey), nonce = Crypto.b64(nonce), name = name)
        }

        fun onReveal(reveal: PairReveal): Result {
            val expected = commit ?: throw GeneralSecurityException("Reveal without a prior commitment")
            val peerPublic = Crypto.unb64(reveal.publicKey)
            val peerNonce = Crypto.unb64(reveal.nonce)
            if (!Crypto.constantTimeEquals(expected, Crypto.sha256(peerPublic, peerNonce))) {
                throw GeneralSecurityException("The watch's key doesn't match its commitment")
            }
            return derive(keyPair, peerPublic, watchPublic = peerPublic, phonePublic = publicKey, watchNonce = peerNonce, phoneNonce = nonce)
        }
    }

    private fun generateKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(CURVE)) }.generateKeyPair()

    private fun derive(
        own: KeyPair,
        peerPublicEncoded: ByteArray,
        watchPublic: ByteArray,
        phonePublic: ByteArray,
        watchNonce: ByteArray,
        phoneNonce: ByteArray,
    ): Result {
        val peerPublic: PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(peerPublicEncoded))
        val secret = KeyAgreement.getInstance("ECDH").run {
            init(own.private)
            doPhase(peerPublic, true)
            generateSecret()
        }
        // The whole exchange is bound to the key: both public keys and both nonces.
        val salt = watchNonce + phoneNonce
        val transcript = Crypto.sha256(watchPublic, phonePublic)
        val key = Crypto.hkdf(secret, salt, INFO_KEY + transcript, 32)
        val sasBytes = Crypto.hkdf(secret, salt, INFO_SAS + transcript, 4)
        val sas = ((sasBytes[0].toLong() and 0xFF shl 24) or (sasBytes[1].toLong() and 0xFF shl 16) or
            (sasBytes[2].toLong() and 0xFF shl 8) or (sasBytes[3].toLong() and 0xFF)) % 1_000_000
        return Result(key, "%06d".format(sas))
    }
}
