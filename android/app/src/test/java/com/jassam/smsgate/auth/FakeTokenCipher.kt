package com.jassam.smsgate.auth

/** Reversible stand-in for the Keystore cipher; [keyLost] simulates a wiped/invalidated key. */
class FakeTokenCipher : TokenCipher {
    var keyLost = false
    var resets = 0
    var encrypts = 0
    var decrypts = 0

    /** Runs inside [encrypt] (on the caller's thread), e.g. to block it and force an interleaving. */
    @Volatile
    var onEncrypt: (() -> Unit)? = null

    override fun encrypt(plaintext: String): String {
        encrypts++
        onEncrypt?.invoke()
        keyLost = false
        return "enc:" + plaintext.reversed()
    }

    override fun decrypt(blob: String): String? {
        decrypts++
        if (keyLost || !blob.startsWith("enc:")) return null
        return blob.removePrefix("enc:").reversed()
    }

    override fun reset() {
        resets++
        keyLost = true
    }
}
