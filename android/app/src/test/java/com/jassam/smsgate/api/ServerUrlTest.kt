package com.jassam.smsgate.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerUrlTest {
    private fun n(s: String, debug: Boolean = false) = ServerUrl.normalize(s, debug)

    @Test
    fun httpsVariants() {
        assertEquals("https://sms.example.com", n("https://sms.example.com/"))
        assertEquals("https://sms.example.com", n("  sms.example.com  "))
        assertEquals("https://sms.example.com", n("HTTPS://SMS.example.com///"))
        assertEquals("https://sms.example.com/gate", n("https://sms.example.com/gate/?x=1#f"))
        assertEquals("https://sms.example.com:8443", n("https://sms.example.com:8443"))
    }

    @Test
    fun rejectsHttpAndJunk() {
        assertNull(n("http://sms.example.com"))
        assertNull(n("http://localhost:8080"))
        assertNull(n("http://sms.example.com", debug = true))
        assertNull(n("ftp://x.com"))
        assertNull(n(""))
        assertNull(n("https://"))
        assertNull(n("https://user:pw@x.com"))
    }

    @Test
    fun debugAllowsLocalHttp() {
        assertEquals("http://10.0.2.2:8080", n("http://10.0.2.2:8080/", debug = true))
        assertEquals("http://192.168.1.20:8080", n("http://192.168.1.20:8080", debug = true))
        assertEquals("http://localhost:8080", n("http://localhost:8080", debug = true))
        assertEquals("http://mac.local:8080", n("http://mac.local:8080", debug = true))
        assertEquals("http://172.20.0.1", n("http://172.20.0.1", debug = true))
        assertNull(n("http://172.32.0.1", debug = true))
        assertNull(n("http://8.8.8.8", debug = true))
    }
}
