package com.jassam.smsgate.ui.pairing

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** How a server is shown when the user must decide whether to trust it. */
object ServerHost {
    /**
     * The host of a normalised server URL, in full, with the port when it isn't
     * the scheme's default. OkHttp canonicalises hosts to lowercase ASCII, so an
     * internationalised (possibly look-alike) name shows as its `xn--` punycode
     * form rather than as lookalike glyphs. IPv6 literals get their brackets.
     */
    fun display(normalizedUrl: String): String {
        val url = normalizedUrl.toHttpUrlOrNull() ?: return normalizedUrl
        val host = if (':' in url.host) "[${url.host}]" else url.host
        return if (url.port == HttpUrl.defaultPort(url.scheme)) host else "$host:${url.port}"
    }
}
