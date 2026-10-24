package com.jassam.smsgate.auth

import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.io.encoding.Base64

/**
 * Encrypts the device token at rest. The production implementation is
 * [KeystoreTokenCipher]; tests use a fake (Robolectric has no AndroidKeyStore).
 */
interface TokenCipher {
    /** Encrypts [plaintext] into a self-contained base64 blob. Throws [GeneralSecurityException] when the key is unusable. */
    fun encrypt(plaintext: String): String

    /** Decrypts a blob from [encrypt]; null when it cannot be decrypted (key lost or invalidated, corrupt data). */
    fun decrypt(blob: String): String?

    /** Destroys the key; later [encrypt] calls create a new one. */
    fun reset()
}

/**
 * AES-256-GCM with a 12-byte random IV chosen by the cipher (required by Keystore
 * keys with randomized encryption) and a 128-bit tag.
 *
 * Blob = base64(version(1) ‖ iv(12) ‖ ciphertext+tag). The key comes from [key]
 * (`create = true` may generate one; `create = false` returns null when there is
 * none), so the encoding and IV handling are testable with a software key.
 */
open class AesGcmTokenCipher(private val key: (create: Boolean) -> SecretKey?) : TokenCipher {
    override fun encrypt(plaintext: String): String {
        val k = key(true) ?: throw GeneralSecurityException("no encryption key")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, k)
        val iv = cipher.iv
        if (iv == null || iv.size != IV_BYTES) throw GeneralSecurityException("unexpected IV length ${iv?.size}")
        val ct = cipher.doFinal(plaintext.encodeToByteArray())
        val out = ByteArray(1 + IV_BYTES + ct.size)
        out[0] = VERSION
        iv.copyInto(out, 1)
        ct.copyInto(out, 1 + IV_BYTES)
        return Base64.encode(out)
    }

    override fun decrypt(blob: String): String? = try {
        val bytes = Base64.decode(blob)
        if (bytes.size < 1 + IV_BYTES + TAG_BITS / 8 || bytes[0] != VERSION) {
            null
        } else {
            val k = key(false)
            if (k == null) {
                null
            } else {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(TAG_BITS, bytes, 1, IV_BYTES))
                cipher.doFinal(bytes, 1 + IV_BYTES, bytes.size - 1 - IV_BYTES).decodeToString(throwOnInvalidSequence = true)
            }
        }
    } catch (e: GeneralSecurityException) {
        null // AEADBadTag, invalidated/unrecoverable key, …
    } catch (e: java.io.IOException) {
        null // KeyStore.load failure
    } catch (e: IllegalArgumentException) {
        null // bad base64 / bad UTF-8
    } catch (e: RuntimeException) {
        null // ProviderException and other Keystore/OEM failures
    }

    override fun reset() = Unit

    companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val VERSION: Byte = 1
    }
}
