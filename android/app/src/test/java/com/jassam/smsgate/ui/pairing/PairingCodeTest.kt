package com.jassam.smsgate.ui.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingCodeTest {
    @Test
    fun normalizesLikeTheBackend() {
        assertEquals("ABCD2345", PairingCode.normalize("abcd-2345"))
        assertEquals("ABCD2345", PairingCode.normalize(" AB CD\t23 45 "))
        // Crockford look-alikes fold as in backend normalizeCode: O→0, I/L→1.
        assertEquals("01100110", PairingCode.normalize("OIL0oilo"))
        // Characters outside the alphabet are dropped; input is capped at 8.
        assertEquals("ABCD2345", PairingCode.normalize("ab!cd#2345XYZ"))
        assertEquals("", PairingCode.normalize("uuu"))
    }

    @Test
    fun completeOnlyWithEightAlphabetCharacters() {
        assertTrue(PairingCode.isComplete("ABCD2345"))
        assertFalse(PairingCode.isComplete("ABCD234"))
        assertFalse(PairingCode.isComplete("ABCD234U"))
        assertFalse(PairingCode.isComplete("abcd2345"))
    }

    @Test
    fun formatsAsTwoGroups() {
        assertEquals("ABCD-2345", PairingCode.format("ABCD2345"))
        assertEquals("ABCD-2", PairingCode.format("ABCD2"))
        assertEquals("ABCD", PairingCode.format("ABCD"))
    }
}
