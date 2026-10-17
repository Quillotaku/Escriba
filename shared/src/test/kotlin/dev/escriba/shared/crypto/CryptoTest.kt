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
    fun `HKDF passes RFC 5869 test vector 1`() {
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
    fun `a full pairing gives the same key and code on both sides`() {
        val watch = Pairing.Initiator("watch")
        val phone = Pairing.Responder("phone")

        val reply = phone.onInit(watch.start())
        val (reveal, watchResult) = watch.onReply(reply)
        val phoneResult = phone.onReveal(reveal)

        assertContentEquals(watchResult.key, phoneResult.key)
        assertEquals(watchResult.code, phoneResult.code)
        assertEquals(6, watchResult.code.length)
        assertTrue(watchResult.code.all(Char::isDigit))

        // Each side proves to the other that it has the same key.
        assertTrue(phoneResult.verify(Pairing.ROLE_WATCH, watchResult.confirm(Pairing.ROLE_WATCH)))
        assertTrue(watchResult.verify(Pairing.ROLE_PHONE, phoneResult.confirm(Pairing.ROLE_PHONE)))
        // A confirmation isn't valid for the other role.
        assertFalse(watchResult.verify(Pairing.ROLE_PHONE, watchResult.confirm(Pairing.ROLE_WATCH)))
    }

    @Test
    fun `a man in the middle swapping the watch key is caught by the commitment`() {
        val watch = Pairing.Initiator("watch")
        val attacker = Pairing.Initiator("intruder")
        val phone = Pairing.Responder("phone")

        val reply = phone.onInit(watch.start())
        val (attackerReveal, _) = attacker.onReply(reply)
        assertFailsWith<GeneralSecurityException> { phone.onReveal(attackerReveal) }
    }

    @Test
    fun `two pairings give different keys`() {
        fun pair(): Pairing.Result {
            val w = Pairing.Initiator("w")
            val p = Pairing.Responder("p")
            val (reveal, result) = w.onReply(p.onInit(w.start()))
            p.onReveal(reveal)
            return result
        }
        assertNotEquals(pair().keyId, pair().keyId)
    }

    @Test
    fun `revealing without a commitment fails`() {
        assertFailsWith<GeneralSecurityException> { Pairing.Responder("p").onReveal(PairReveal("AA==", "AA==")) }
    }

    @Test
    fun `secure channel round trip, context and key`() {
        val key = Crypto.randomBytes(32)
        val channel = SecureChannel(key)
        val message = "hello watch".encodeToByteArray()

        val sealed = channel.seal("/escriba/v1/ack", message)
        assertContentEquals(message, channel.open("/escriba/v1/ack", sealed))
        // Two encryptions of the same text look nothing alike (random nonce).
        assertFalse(sealed.contentEquals(channel.seal("/escriba/v1/ack", message)))
        // The context is authenticated.
        assertFailsWith<GeneralSecurityException> { channel.open("/escriba/v1/other", sealed) }
        // Any flipped bit is detected.
        val tampered = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertFailsWith<GeneralSecurityException> { channel.open("/escriba/v1/ack", tampered) }
        // Another key is recognized as such, so the app can ask to pair again.
        assertFailsWith<SecureChannel.KeyMismatchException> { SecureChannel(Crypto.randomBytes(32)).open("/escriba/v1/ack", sealed) }
    }
}

class UploadPolicyTest {
    @Test
    fun rules() {
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
