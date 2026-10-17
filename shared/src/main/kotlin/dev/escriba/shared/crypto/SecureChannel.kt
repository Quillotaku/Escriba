package dev.escriba.shared.crypto

import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end encryption of everything that travels between watch and phone (except the ping and
 * pairing itself), with AES-256-GCM.
 *
 * Format: `[version 1][key id 8][nonce 12][ciphertext + tag 16]`.
 *
 * The [context] (e.g. the message path) is authenticated data: a message sealed for one path
 * can't be replayed on another.
 */
class SecureChannel(private val key: ByteArray) {

    class KeyMismatchException : GeneralSecurityException("The message was sealed with another key: pair again")

    init {
        require(key.size == 32) { "The link key must be 32 bytes" }
    }

    private val keyIdBytes = Crypto.sha256(key).copyOf(KEY_ID_BYTES)
    val keyId: String = Crypto.hex(keyIdBytes)

    fun seal(context: String, plaintext: ByteArray): ByteArray {
        val nonce = Crypto.randomBytes(NONCE_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(aad(context))
        }
        return byteArrayOf(VERSION) + keyIdBytes + nonce + cipher.doFinal(plaintext)
    }

    fun open(context: String, sealed: ByteArray): ByteArray {
        if (sealed.size < HEADER_BYTES + TAG_BITS / 8 || sealed[0] != VERSION) {
            throw GeneralSecurityException("Sealed message in an unknown format")
        }
        if (!Crypto.constantTimeEquals(sealed.copyOfRange(1, 1 + KEY_ID_BYTES), keyIdBytes)) throw KeyMismatchException()
        val nonce = sealed.copyOfRange(1 + KEY_ID_BYTES, HEADER_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(aad(context))
        }
        return cipher.doFinal(sealed, HEADER_BYTES, sealed.size - HEADER_BYTES)
    }

    private fun aad(context: String) = "escriba/v1:$context".encodeToByteArray()

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val VERSION: Byte = 1
        const val KEY_ID_BYTES = 8
        const val NONCE_BYTES = 12
        const val HEADER_BYTES = 1 + KEY_ID_BYTES + NONCE_BYTES
        const val TAG_BITS = 128
    }
}
