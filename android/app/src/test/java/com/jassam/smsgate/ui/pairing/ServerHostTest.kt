package com.jassam.smsgate.ui.pairing

import com.jassam.smsgate.api.ServerUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class ServerHostTest {
    private fun shown(raw: String) = ServerHost.display(ServerUrl.normalize(raw, allowLocalHttp = true)!!)

    @Test
    fun defaultPortIsHiddenOtherPortsShown() {
        assertEquals("sms.example.com", shown("https://sms.example.com/gate"))
        assertEquals("sms.example.com", shown("https://sms.example.com:443"))
        assertEquals("sms.example.com:8443", shown("https://sms.example.com:8443"))
        assertEquals("10.0.2.2:8080", shown("http://10.0.2.2:8080"))
    }

    @Test
    fun hostIsCanonicalLowercaseAscii() {
        assertEquals("sms.example.com", shown("https://SMS.Example.COM"))
        // Cyrillic "а" in a look-alike of apple.com shows as punycode, not as glyphs.
        assertEquals("xn--pple-43d.com", shown("https://аpple.com"))
    }

    @Test
    fun ipv6KeepsBrackets() {
        assertEquals("[::1]:8080", shown("http://[::1]:8080"))
    }
}
