package dev.escriba.datalayer

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.escriba.shared.crypto.Crypto
import dev.escriba.shared.crypto.SecureChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The paired device. */
data class PairedPeer(val nodeId: String, val name: String, val keyId: String, val pairedAtMs: Long)

/**
 * Stores the link key. On disk it's encrypted with an Android Keystore key that never leaves the
 * hardware: copying the app's files isn't enough to read it.
 */
class LinkKeyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("link", Context.MODE_PRIVATE)

    private val _peer = MutableStateFlow(loadPeer())
    val peer: StateFlow<PairedPeer?> = _peer

    @Volatile
    private var cached: SecureChannel? = null

    fun channel(): SecureChannel? {
        cached?.let { return it }
        val wrapped = prefs.getString(KEY_WRAPPED, null) ?: return null
        val iv = prefs.getString(KEY_IV, null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, Crypto.unb64(iv)))
            }
            SecureChannel(cipher.doFinal(Crypto.unb64(wrapped)))
        }.onFailure { AppLog.e(TAG, "Couldn't open the link key", it) }
            .getOrNull()
            ?.also { cached = it }
    }

    fun save(peer: PairedPeer, key: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, wrappingKey()) }
        val wrapped = cipher.doFinal(key)
        prefs.edit()
            .putString(KEY_WRAPPED, Crypto.b64(wrapped))
            .putString(KEY_IV, Crypto.b64(cipher.iv))
            .putString(KEY_NODE, peer.nodeId)
            .putString(KEY_NAME, peer.name)
            .putString(KEY_ID, peer.keyId)
            .putLong(KEY_AT, peer.pairedAtMs)
            .commit()
        cached = SecureChannel(key)
        _peer.value = peer
    }

    fun forget() {
        prefs.edit().clear().commit()
        cached = null
        _peer.value = null
    }

    private fun loadPeer(): PairedPeer? {
        val node = prefs.getString(KEY_NODE, null) ?: return null
        return PairedPeer(node, prefs.getString(KEY_NAME, "") ?: "", prefs.getString(KEY_ID, "") ?: "", prefs.getLong(KEY_AT, 0))
    }

    private fun wrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val TAG = "link"
        const val ALIAS = "escriba_link"
        const val KEY_WRAPPED = "key"
        const val KEY_IV = "iv"
        const val KEY_NODE = "node"
        const val KEY_NAME = "name"
        const val KEY_ID = "key_id"
        const val KEY_AT = "paired_at"
    }
}
