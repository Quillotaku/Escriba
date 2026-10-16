package dev.escriba.shared.crypto

import dev.escriba.shared.PairReveal
import dev.escriba.shared.recording.UploadPolicy
import java.security.GeneralSecurityException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CryptoTest {

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun `HKDF cumple el vector de prueba 1 del RFC 5869`() {
        val okm = Crypto.hkdf(
            ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"),
            salt = hex("000102030405060708090a0b0c"),
            info = hex("f0f1f2f3f4f5f6f7f8f9"),
            length = 42,
        )
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            Crypto.hex(okm),
        )
    }

    @Test
    fun `emparejamiento completo da la misma clave y el mismo codigo en ambos lados`() {
        val watch = Pairing.Initiator("reloj")
        val phone = Pairing.Responder("móvil")

        val reply = phone.onInit(watch.start())
        val (reveal, watchResult) = watch.onReply(reply)
        val phoneResult = phone.onReveal(reveal)

        assertContentEquals(watchResult.key, phoneResult.key)
        assertEquals(watchResult.code, phoneResult.code)
        assertEquals(6, watchResult.code.length)
        assertTrue(watchResult.code.all(Char::isDigit))

        // Cada lado prueba al otro que tiene la misma clave.
        assertTrue(phoneResult.verify(Pairing.ROLE_WATCH, watchResult.confirm(Pairing.ROLE_WATCH)))
        assertTrue(watchResult.verify(Pairing.ROLE_PHONE, phoneResult.confirm(Pairing.ROLE_PHONE)))
        // Una confirmación no sirve para el otro rol.
        assertFalse(watchResult.verify(Pairing.ROLE_PHONE, watchResult.confirm(Pairing.ROLE_WATCH)))
    }

    @Test
    fun `un intermediario que cambia la clave del reloj es detectado por el compromiso`() {
        val watch = Pairing.Initiator("reloj")
        val attacker = Pairing.Initiator("intruso")
        val phone = Pairing.Responder("móvil")

        val reply = phone.onInit(watch.start())
        val (attackerReveal, _) = attacker.onReply(reply)
        assertFailsWith<GeneralSecurityException> { phone.onReveal(attackerReveal) }
    }

    @Test
    fun `dos emparejamientos distintos dan claves distintas`() {
        fun pair(): Pairing.Result {
            val w = Pairing.Initiator("r")
            val p = Pairing.Responder("m")
            val (reveal, result) = w.onReply(p.onInit(w.start()))
            p.onReveal(reveal)
            return result
        }
        assertNotEquals(pair().keyId, pair().keyId)
    }

    @Test
    fun `revelar sin compromiso falla`() {
        assertFailsWith<GeneralSecurityException> { Pairing.Responder("m").onReveal(PairReveal("AA==", "AA==")) }
    }

    @Test
    fun `canal seguro ida y vuelta, contexto y clave`() {
        val key = Crypto.randomBytes(32)
        val channel = SecureChannel(key)
        val message = "hola reloj".encodeToByteArray()

        val sealed = channel.seal("/escriba/v1/ack", message)
        assertContentEquals(message, channel.open("/escriba/v1/ack", sealed))
        // Dos cifrados del mismo texto no se parecen (nonce aleatorio).
        assertFalse(sealed.contentEquals(channel.seal("/escriba/v1/ack", message)))
        // El contexto está autenticado.
        assertFailsWith<GeneralSecurityException> { channel.open("/escriba/v1/otra", sealed) }
        // Cualquier bit cambiado se detecta.
        val tampered = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertFailsWith<GeneralSecurityException> { channel.open("/escriba/v1/ack", tampered) }
        // Otra clave se reconoce como tal, para poder pedir que se vuelva a emparejar.
        assertFailsWith<SecureChannel.KeyMismatchException> { SecureChannel(Crypto.randomBytes(32)).open("/escriba/v1/ack", sealed) }
    }
}

class UploadPolicyTest {
    @Test
    fun reglas() {
        assertFalse(UploadPolicy.shouldUpload(0, 0, 0, charging = true, manual = true))
        assertTrue(UploadPolicy.shouldUpload(1, 0, 0, charging = false, manual = true))
        assertTrue(UploadPolicy.shouldUpload(1, 0, 0, charging = true, manual = false))
        assertTrue(UploadPolicy.shouldUpload(1, 0, 1, charging = false, manual = false))
        assertTrue(UploadPolicy.shouldUpload(5, 0, 0, charging = false, manual = false))
        assertFalse(UploadPolicy.shouldUpload(4, 60_000, 0, charging = false, manual = false))
        assertTrue(UploadPolicy.shouldUpload(1, 15 * 60_000L, 0, charging = false, manual = false))
        assertTrue(UploadPolicy.shouldUpload(0, 0, 1, charging = false, manual = false))
    }
}
