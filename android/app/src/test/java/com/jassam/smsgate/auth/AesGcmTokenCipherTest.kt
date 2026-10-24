package com.jassam.smsgate.auth

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.io.encoding.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The GCM/IV/encoding logic shared with [KeystoreTokenCipher], exercised with a
 * software AES-256 key. The AndroidKeyStore round-trip itself is instrumented-only
 * (Robolectric has no AndroidKeyStore provider).
 */
class AesGcmTokenCipherTest {
    private var key: SecretKey? = newKey()
    private val cipher = AesGcmTokenCipher { create -> if (key == null && create) newKey().also { key = it } else key }

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun roundTrip() {
        val blob = cipher.encrypt("dt_0123456789abcdef")
        assertEquals("dt_0123456789abcdef", cipher.decrypt(blob))
    }

    @Test
    fun blobLayoutVersionIvCiphertextTag() {
        val bytes = Base64.decode(cipher.encrypt("dt_x"))
        assertEquals(AesGcmTokenCipher.VERSION, bytes[0])
        assertEquals(1 + 12 + "dt_x".length + 16, bytes.size)
    }

    @Test
    fun freshIvEveryTime() {
        val a = Base64.decode(cipher.encrypt("same"))
        val b = Base64.decode(cipher.encrypt("same"))
        assertNotEquals(a.copyOfRange(1, 13).toList(), b.copyOfRange(1, 13).toList())
    }

    @Test
    fun tamperedOrForeignDataIsNull() {
        val bytes = Base64.decode(cipher.encrypt("dt_x"))
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        assertNull(cipher.decrypt(Base64.encode(bytes)))
        assertNull(cipher.decrypt("not base64 !!"))
        assertNull(cipher.decrypt(Base64.encode(byteArrayOf(1, 2, 3))))
        val wrongVersion = Base64.decode(cipher.encrypt("dt_x")).also { it[0] = 9 }
        assertNull(cipher.decrypt(Base64.encode(wrongVersion)))
    }

    @Test
    fun lostOrReplacedKeyIsNull() {
        val blob = cipher.encrypt("dt_x")
        key = null
        assertNull(cipher.decrypt(blob)) // decrypt never creates a key
        key = newKey()
        assertNull(cipher.decrypt(blob))
    }
}
