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
 * Emparejamiento reloj ↔ móvil con verificación por código (al estilo de la comparación
 * numérica de Bluetooth). Protege de un intermediario, incluido el propio transporte de GMS.
 *
 * ```
 * reloj (inicia)                                  móvil (responde)
 *   PairInit   { commit = SHA256(pubR ‖ nonceR) }  →
 *              ←  PairReply  { pubM, nonceM }
 *   PairReveal { pubR, nonceR }                    →   comprueba el commit
 *   ambos: ECDH P-256 → HKDF → clave del enlace + código de 6 dígitos
 *   el usuario comprueba que los dos códigos coinciden y confirma en cada pantalla
 *   PairConfirm { HMAC(clave, rol) }               ⇄
 * ```
 *
 * El compromiso inicial obliga al reloj a fijar su clave antes de ver la del móvil, así que
 * un intermediario no puede ir probando claves hasta que los códigos coincidan: tiene una
 * sola oportunidad entre un millón.
 */
object Pairing {

    private const val CURVE = "secp256r1"
    private val INFO_KEY = "escriba v1 enlace".encodeToByteArray()
    private val INFO_SAS = "escriba v1 codigo".encodeToByteArray()
    const val ROLE_WATCH = "reloj"
    const val ROLE_PHONE = "movil"

    /** Resultado de un emparejamiento: la clave del enlace y el código que se muestra al usuario. */
    class Result(val key: ByteArray, val code: String) {
        val keyId: String get() = Crypto.hex(Crypto.sha256(key).copyOf(8))

        fun confirm(role: String) = PairConfirm(Crypto.b64(Crypto.hmacSha256(key, "confirmar:$role".encodeToByteArray())))

        fun verify(peerRole: String, confirm: PairConfirm): Boolean =
            Crypto.constantTimeEquals(Crypto.unb64(confirm.mac), Crypto.unb64(confirm(peerRole).mac))
    }

    /** Lado del reloj. */
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

    /** Lado del móvil. */
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
            val expected = commit ?: throw GeneralSecurityException("Revelación sin compromiso previo")
            val peerPublic = Crypto.unb64(reveal.publicKey)
            val peerNonce = Crypto.unb64(reveal.nonce)
            if (!Crypto.constantTimeEquals(expected, Crypto.sha256(peerPublic, peerNonce))) {
                throw GeneralSecurityException("La clave del reloj no coincide con su compromiso")
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
        // Todo el intercambio queda ligado a la clave: ambas claves públicas y ambos nonces.
        val salt = watchNonce + phoneNonce
        val transcript = Crypto.sha256(watchPublic, phonePublic)
        val key = Crypto.hkdf(secret, salt, INFO_KEY + transcript, 32)
        val sasBytes = Crypto.hkdf(secret, salt, INFO_SAS + transcript, 4)
        val sas = ((sasBytes[0].toLong() and 0xFF shl 24) or (sasBytes[1].toLong() and 0xFF shl 16) or
            (sasBytes[2].toLong() and 0xFF shl 8) or (sasBytes[3].toLong() and 0xFF)) % 1_000_000
        return Result(key, "%06d".format(sas))
    }
}
