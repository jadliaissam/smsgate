package com.jassam.smsgate.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OemGuidanceTest {
    @Test
    fun detectsAggressiveBatteryManagersByManufacturerOrBrand() {
        assertEquals(Oem.Xiaomi, OemGuidance.detect("Xiaomi", "Redmi"))
        assertEquals(Oem.Xiaomi, OemGuidance.detect("XIAOMI", "POCO"))
        assertEquals(Oem.Xiaomi, OemGuidance.detect(null, "redmi"))
        assertEquals(Oem.Samsung, OemGuidance.detect("samsung", "samsung"))
        assertEquals(Oem.Huawei, OemGuidance.detect("HUAWEI", "HUAWEI"))
        assertEquals(Oem.Huawei, OemGuidance.detect("HONOR", "HONOR"))
        assertEquals(Oem.Oppo, OemGuidance.detect("OPPO", "OPPO"))
        assertEquals(Oem.Oppo, OemGuidance.detect("realme", "realme"))
        assertEquals(Oem.OnePlus, OemGuidance.detect("OnePlus", "OnePlus"))
        assertEquals(Oem.Vivo, OemGuidance.detect("vivo", "vivo"))
        assertEquals(Oem.Vivo, OemGuidance.detect("vivo", "iQOO"))
    }

    @Test
    fun manufacturerWinsOverBrand() {
        // OnePlus phones made by OPPO report manufacturer OnePlus; keep their own screens.
        assertEquals(Oem.OnePlus, OemGuidance.detect("OnePlus", "OPPO"))
    }

    @Test
    fun coversOlderSamsungAndPostSplitHonorScreens() {
        assertTrue(Oem.Samsung.screens.any { it.className == "com.samsung.android.sm.ui.battery.BatteryActivity" })
        assertTrue(Oem.Huawei.screens.any { it.packageName == "com.hihonor.systemmanager" })
    }

    @Test
    fun stockAndUnknownPhonesGetNoOemStep() {
        assertNull(OemGuidance.detect("Google", "google"))
        assertNull(OemGuidance.detect("motorola", "motorola"))
        assertNull(OemGuidance.detect("", ""))
        assertNull(OemGuidance.detect(null, null))
    }

    @Test
    fun everyOemHasStepsAndScreensToTry() {
        for (oem in Oem.entries) {
            assertTrue("$oem has no settings screen", oem.screens.isNotEmpty())
            assertTrue("$oem screens must be fully qualified", oem.screens.all { '.' in it.packageName && !it.className.startsWith(".") && '.' in it.className })
        }
    }
}
