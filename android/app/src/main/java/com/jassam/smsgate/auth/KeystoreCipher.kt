package com.jassam.smsgate.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * [TokenCipher] backed by an AES-256-GCM key in the Android Keystore (D8): the key
 * never leaves secure hardware where available, needs no user authentication and
 * requires randomized encryption (the Keystore picks every IV).
 *
 * A key that is missing or unusable (wiped by a restore, OEM Keystore bugs) makes
 * [decrypt] return null, so the credentials read as absent and the app re-pairs.
 * [encrypt] recreates a broken key once before giving up.
 *
 * Instrumented-only: Robolectric has no AndroidKeyStore provider. The GCM/IV/encoding
 * logic is unit-tested through [AesGcmTokenCipher] with a software key.
 */
class KeystoreTokenCipher(private val alias: String = DEFAULT_ALIAS) : TokenCipher {
    private val delegate = AesGcmTokenCipher(::key)

    override fun encrypt(plaintext: String): String = try {
        delegate.encrypt(plaintext)
    } catch (e: GeneralSecurityException) {
        reset()
        delegate.encrypt(plaintext)
    } catch (e: RuntimeException) {
        reset()
        delegate.encrypt(plaintext)
    } catch (e: java.io.IOException) {
        reset()
        delegate.encrypt(plaintext)
    }

    override fun decrypt(blob: String): String? = delegate.decrypt(blob)

    @Synchronized
    override fun reset() {
        try {
            keyStore().deleteEntry(alias)
        } catch (_: GeneralSecurityException) {
        } catch (_: RuntimeException) {
        } catch (_: java.io.IOException) {
        }
    }

    @Synchronized
    private fun key(create: Boolean): SecretKey? {
        val existing = try {
            keyStore().getKey(alias, null) as? SecretKey
        } catch (e: GeneralSecurityException) {
            null
        }
        if (existing != null || !create) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val DEFAULT_ALIAS = "smsgate_device_token_v1"
    }
}
