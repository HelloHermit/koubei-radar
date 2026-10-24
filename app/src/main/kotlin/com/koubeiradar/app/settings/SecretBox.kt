package com.koubeiradar.app.settings

import java.security.GeneralSecurityException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Raised when a secret cannot be encrypted; the plain text is never stored as a fallback. */
class SecretStorageException(cause: Throwable) : Exception("Secure storage is unavailable", cause)

/**
 * AES-256/GCM envelope for credentials stored in DataStore. The stored form is `Base64(IV || ciphertext+tag)`.
 *
 * [keyProvider] supplies the key: production uses a non-extractable AndroidKeyStore key
 * (see [androidKeyStoreKeyProvider]); tests can pass an in-memory key.
 */
class SecretBox(private val keyProvider: () -> SecretKey) {

    @Throws(SecretStorageException::class)
    fun encrypt(plain: String): String = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.getEncoder().encodeToString(cipher.iv + sealed)
    } catch (e: GeneralSecurityException) {
        throw SecretStorageException(e)
    } catch (e: RuntimeException) {
        // AndroidKeyStore reports provider failures as ProviderException / IllegalStateException.
        throw SecretStorageException(e)
    }

    /** Returns the plain text, or `null` when [encoded] is malformed, tampered with or was sealed with another key. */
    fun decrypt(encoded: String): String? = try {
        val bytes = Base64.getDecoder().decode(encoded)
        if (bytes.size <= IV_LENGTH) {
            null
        } else {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_LENGTH))
            String(cipher.doFinal(bytes, IV_LENGTH, bytes.size - IV_LENGTH), Charsets.UTF_8)
        }
    } catch (e: GeneralSecurityException) {
        null
    } catch (e: RuntimeException) {
        // Malformed Base64 (IllegalArgumentException) and AndroidKeyStore provider failures.
        null
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
    }
}
