package com.koubeiradar.app.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

private const val ANDROID_KEY_STORE = "AndroidKeyStore"
private const val SECRETS_KEY_ALIAS = "koubei_radar_secrets"
private const val KEY_SIZE_BITS = 256

/**
 * Key source for [SecretBox]: a non-extractable AES key that lives in the AndroidKeyStore and is created on first use.
 * A key that was wiped by the system simply makes old ciphertexts undecryptable, which [SecretBox.decrypt] reports as `null`.
 */
fun androidKeyStoreKeyProvider(alias: String = SECRETS_KEY_ALIAS): () -> SecretKey {
    val lock = Any()
    var cached: SecretKey? = null
    return {
        synchronized(lock) {
            cached ?: loadOrCreateKey(alias).also { cached = it }
        }
    }
}

private fun loadOrCreateKey(alias: String): SecretKey {
    val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
    (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
    generator.init(
        KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .build(),
    )
    return generator.generateKey()
}
