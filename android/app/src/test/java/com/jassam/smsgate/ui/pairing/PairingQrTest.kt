package com.jassam.smsgate.ui.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingQrTest {
    @Test
    fun parsesTheDashboardPayload() {
        // Exactly what backend createPairingCode renders (url.QueryEscape of PUBLIC_URL).
        val link = PairingQr.parse("smsgate://pair?server=https%3A%2F%2Fsms.example.com&code=ABCD2345")
        assertEquals(PairingLink("https://sms.example.com", "ABCD2345"), link)
    }

    @Test
    fun decodesUrlEncodedServerWithPortAndPath() {
        val link = PairingQr.parse("smsgate://pair?server=https%3A%2F%2Fsms.example.com%3A8443%2Fgate%2Fv2&code=ABCD2345")
        assertEquals("https://sms.example.com:8443/gate/v2", link?.server)
    }

    @Test
    fun acceptsAnyParameterOrderCaseAndSurroundingWhitespace() {
        val link = PairingQr.parse("  SMSGate://PAIR?code=abcd-2345&extra=1&server=https%3A%2F%2Fsms.example.com \n")
        assertEquals(PairingLink("https://sms.example.com", "ABCD2345"), link)
    }

    @Test
    fun firstOccurrenceOfARepeatedParameterWins() {
        val link = PairingQr.parse("smsgate://pair?server=https%3A%2F%2Fa.example&server=https%3A%2F%2Fb.example&code=ABCD2345")
        assertEquals("https://a.example", link?.server)
    }

    @Test
    fun rejectsWrongScheme() {
        assertNull(PairingQr.parse("https://pair?server=https%3A%2F%2Fsms.example.com&code=ABCD2345"))
        assertNull(PairingQr.parse("smsgateway://pair?server=https%3A%2F%2Fsms.example.com&code=ABCD2345"))
        assertNull(PairingQr.parse("https://sms.example.com/pair?code=ABCD2345"))
    }

    @Test
    fun rejectsWrongHost() {
        assertNull(PairingQr.parse("smsgate://pairing?server=https%3A%2F%2Fsms.example.com&code=ABCD2345"))
        assertNull(PairingQr.parse("smsgate://login?server=https%3A%2F%2Fsms.example.com&code=ABCD2345"))
    }

    @Test
    fun rejectsMissingOrMalformedCode() {
        assertNull(PairingQr.parse("smsgate://pair?server=https%3A%2F%2Fsms.example.com"))
        assertNull(PairingQr.parse("smsgate://pair?server=https%3A%2F%2Fsms.example.com&code="))
        assertNull(PairingQr.parse("smsgate://pair?server=https%3A%2F%2Fsms.example.com&code=ABC"))
    }

    @Test
    fun rejectsMissingServerOrBrokenEncoding() {
        assertNull(PairingQr.parse("smsgate://pair?code=ABCD2345"))
        assertNull(PairingQr.parse("smsgate://pair?server=&code=ABCD2345"))
        assertNull(PairingQr.parse("smsgate://pair?server=%zz&code=ABCD2345"))
        assertNull(PairingQr.parse("smsgate://pair"))
        assertNull(PairingQr.parse(""))
    }
}
