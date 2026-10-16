package dev.escriba.shared.crypto

import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cifrado de extremo a extremo de todo lo que viaja entre reloj y móvil (salvo el ping y el
 * propio emparejamiento), con AES-256-GCM.
 *
 * Formato: `[versión 1][id de clave 8][nonce 12][texto cifrado + etiqueta 16]`.
 *
 * El [context] (la ruta del mensaje, p. ej.) va como dato autenticado: un mensaje cifrado
 * para una ruta no se puede reutilizar en otra.
 */
class SecureChannel(private val key: ByteArray) {

    class KeyMismatchException : GeneralSecurityException("El mensaje se cifró con otra clave: hay que volver a emparejar")

    init {
        require(key.size == 32) { "La clave del enlace debe tener 32 bytes" }
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
            throw GeneralSecurityException("Mensaje cifrado con formato desconocido")
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
