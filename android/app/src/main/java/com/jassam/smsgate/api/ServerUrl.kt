package com.jassam.smsgate.api

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Server base URL as typed by the user or carried by the pairing QR.
 *
 * [normalize] trims, adds `https://` when no scheme is given, drops query,
 * fragment and trailing slashes, and requires HTTPS. Plain HTTP is accepted only
 * when [allowLocalHttp] (debug builds) and the host is local: localhost,
 * loopback, the emulator's 10.0.2.2, private IPv4 ranges or `*.local`.
 */
object ServerUrl {
    fun normalize(raw: String, allowLocalHttp: Boolean): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
        val url = withScheme.toHttpUrlOrNull() ?: return null
        when (url.scheme) {
            "https" -> Unit
            "http" -> if (!(allowLocalHttp && isLocalHost(url.host))) return null
            else -> return null
        }
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        val clean = url.newBuilder().query(null).fragment(null).build()
        return clean.toString().trimEnd('/')
    }

    internal fun isLocalHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".local") || host == "::1") return true
        val parts = host.split('.')
        if (parts.size != 4) return false
        val o = parts.map { it.toIntOrNull() ?: return false }
        if (o.any { it !in 0..255 }) return false
        return o[0] == 127 || o[0] == 10 ||
            (o[0] == 192 && o[1] == 168) ||
            (o[0] == 172 && o[1] in 16..31)
    }

    /** `<base>/<path>`, keeping any path prefix of the base. */
    internal fun resolve(base: HttpUrl, path: String): HttpUrl =
        base.newBuilder().addPathSegments(path.trimStart('/')).build()
}
