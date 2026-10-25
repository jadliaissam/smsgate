package com.jassam.smsgate.ui.pairing

import java.net.URLDecoder

/** Pairing codes: 8 Crockford base32 characters, shown as `XXXX-XXXX` (backend secret.PairingCode). */
object PairingCode {
    const val LENGTH = 8
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /**
     * Canonical form of typed or scanned input, like backend `normalizeCode`:
     * uppercase, look-alikes folded (O→0, I/L→1), anything else outside the
     * alphabet (dashes, spaces, U, punctuation) dropped, capped at [LENGTH].
     */
    fun normalize(input: String): String {
        val out = StringBuilder(LENGTH)
        for (raw in input) {
            if (out.length == LENGTH) break
            val c = when (val u = raw.uppercaseChar()) {
                'O' -> '0'
                'I', 'L' -> '1'
                else -> u
            }
            if (c in ALPHABET) out.append(c)
        }
        return out.toString()
    }

    fun isComplete(code: String): Boolean = code.length == LENGTH && code.all { it in ALPHABET }

    /** `ABCD2345` → `ABCD-2345` (partial input keeps the dash once a fifth character exists). */
    fun format(code: String): String = if (code.length > 4) code.take(4) + "-" + code.drop(4) else code
}

/** What the dashboard's pairing QR carries. [server] is as encoded (normalise before use). */
data class PairingLink(val server: String, val code: String)

/**
 * Parser for `smsgate://pair?server=<url-encoded>&code=<code>` (backend
 * createPairingCode `qr_payload`). Pure Kotlin (no android.net.Uri) so it is
 * unit-testable; anything else returns null.
 */
object PairingQr {
    private const val PREFIX = "smsgate://pair"

    fun parse(raw: String): PairingLink? {
        val s = raw.trim()
        if (!s.startsWith(PREFIX, ignoreCase = true)) return null
        val rest = s.substring(PREFIX.length).removePrefix("/")
        if (!rest.startsWith("?")) return null
        val params = HashMap<String, String>()
        for (part in rest.substring(1).substringBefore('#').split('&')) {
            if (part.isEmpty()) continue
            val key = decode(part.substringBefore('=')) ?: return null
            val value = decode(part.substringAfter('=', "")) ?: return null
            params.putIfAbsent(key, value)
        }
        val server = params["server"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val code = PairingCode.normalize(params["code"] ?: return null)
        if (!PairingCode.isComplete(code)) return null
        return PairingLink(server, code)
    }

    // Go's url.QueryEscape encodes spaces as '+', which URLDecoder reverses.
    private fun decode(s: String): String? = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (_: IllegalArgumentException) {
        null
    }
}
