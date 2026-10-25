package com.jassam.smsgate.work

import android.Manifest
import android.app.Application
import android.os.BatteryManager
import com.jassam.smsgate.sim.SimInventory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeviceHeartbeatSourceTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val battery = shadowOf(app.getSystemService(BatteryManager::class.java))

    private fun source(version: String = "1.2.3") = DeviceHeartbeatSource(app, SimInventory(app), version)

    @Test
    fun readsBatteryAndChargingWithoutPhonePermissions() {
        battery.setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, 64)
        battery.setIsCharging(true)
        val hb = source().read()
        assertEquals(64, hb.battery)
        assertEquals(true, hb.charging)
        assertNull("SIMs unknown without READ_PHONE_STATE: leave the server's list unchanged", hb.sims)
        assertEquals("1.2.3", hb.appVersion)
        assertTrue(hb.signal == null || hb.signal in 0..4)
    }

    @Test
    fun withPhoneStateTheSimListIsSentEvenWhenEmpty() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        assertEquals(emptyList<Any>(), source().read().sims)
    }

    @Test
    fun unsupportedBatteryPropertyIsNull() {
        battery.setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, Int.MIN_VALUE)
        battery.setIsCharging(false)
        val hb = source().read()
        assertNull(hb.battery)
        assertEquals(false, hb.charging)
    }

    @Test
    fun appVersionIsCappedForTheBackend() {
        assertEquals(32, source("1".repeat(40)).read().appVersion!!.length)
        assertNull(source("").read().appVersion)
    }
}
