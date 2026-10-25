package com.jassam.smsgate.realtime

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionPolicyTest {
    @Test
    fun connectsOnlyWhenPairedAndChargingOrAlwaysConnected() {
        // paired, charging, alwaysConnected → connect
        val table = listOf(
            Triple(Triple(false, false, false), false, "unpaired"),
            Triple(Triple(false, true, false), false, "unpaired while charging"),
            Triple(Triple(false, false, true), false, "unpaired with the setting on"),
            Triple(Triple(false, true, true), false, "unpaired, charging, setting on"),
            Triple(Triple(true, false, false), false, "paired on battery, setting off: FCM only"),
            Triple(Triple(true, true, false), true, "paired and charging"),
            Triple(Triple(true, false, true), true, "paired with the setting on"),
            Triple(Triple(true, true, true), true, "paired, charging, setting on"),
        )
        for ((input, expected, name) in table) {
            val (paired, charging, always) = input
            assertEquals(name, expected, ConnectionPolicy.shouldConnect(paired, charging, always))
        }
    }
}
