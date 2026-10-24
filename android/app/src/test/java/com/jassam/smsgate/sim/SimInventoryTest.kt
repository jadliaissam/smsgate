package com.jassam.smsgate.sim

import android.Manifest
import android.app.Application
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import org.robolectric.RuntimeEnvironment
import com.jassam.smsgate.api.Sim
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSubscriptionManager
import org.robolectric.shadows.ShadowSubscriptionManager.SubscriptionInfoBuilder

private fun sub(id: Int, slot: Int, carrier: String?, display: String? = null, number: String? = null): SubscriptionInfo =
    SubscriptionInfoBuilder.newBuilder()
        .setId(id)
        .setSimSlotIndex(slot)
        .apply { if (carrier != null) setCarrierName(carrier) }
        .apply { if (display != null) setDisplayName(display) }
        .apply { if (number != null) setNumber(number) }
        .buildSubscriptionInfo()

@RunWith(RobolectricTestRunner::class)
// SDK 35+ sandboxes need Java 21; the build JDK is 17. 34 covers the API 33+ number path.
@Config(sdk = [34])
class SimInventoryTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private lateinit var shadow: ShadowSubscriptionManager

    @Before
    fun setUp() {
        shadow = shadowOf(app.getSystemService(SubscriptionManager::class.java))
    }

    private fun grant(vararg permissions: String) = shadowOf(app).grantPermissions(*permissions)

    @Test
    fun emptyWithoutPhoneStatePermission() {
        shadow.setActiveSubscriptionInfos(sub(3, 0, "Orange"))
        assertEquals(emptyList<Sim>(), SimInventory(app).activeSims())
    }

    @Test
    fun mapsActiveSubscriptionsSortedBySlot() {
        grant(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_PHONE_NUMBERS)
        shadow.setActiveSubscriptionInfos(sub(7, 1, "inwi"), sub(3, 0, "", display = "Orange MA"))
        shadow.setPhoneNumber(7, "+212600000000")
        shadow.setPhoneNumber(3, "")
        assertEquals(
            listOf(Sim(0, 3, "Orange MA"), Sim(1, 7, "inwi", "+212600000000")),
            SimInventory(app).activeSims(),
        )
    }

    @Test
    fun numberLeftOutWithoutPhoneNumbersPermission() {
        grant(Manifest.permission.READ_PHONE_STATE)
        shadow.setReadPhoneNumbersPermission(false)
        shadow.setActiveSubscriptionInfos(sub(7, 0, "inwi"))
        shadow.setPhoneNumber(7, "+212600000000")
        assertEquals(listOf(Sim(0, 7, "inwi")), SimInventory(app).activeSims())
    }

    @Test
    fun keepsOnlyWhatTheBackendAccepts() {
        grant(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_PHONE_NUMBERS)
        shadow.setActiveSubscriptionInfos(
            sub(1, -1, "eSIM without slot"),
            sub(2, 4, "slot too high"),
            sub(3, 0, "c".repeat(80)),
            sub(4, 0, "duplicate slot"),
            sub(5, 2, "long number"),
        )
        shadow.setPhoneNumber(5, "+" + "1".repeat(40))
        assertEquals(
            listOf(Sim(0, 3, "c".repeat(64)), Sim(2, 5, "long number")),
            SimInventory(app).activeSims(),
        )
    }

    @Test
    fun securityExceptionMeansEmpty() {
        grant(Manifest.permission.READ_PHONE_STATE)
        shadow.setReadPhoneStatePermission(false)
        shadow.setActiveSubscriptionInfos(sub(3, 0, "Orange"))
        assertEquals(emptyList<Sim>(), SimInventory(app).activeSims())
    }

    @Test
    fun noSubscriptionsMeansEmpty() {
        grant(Manifest.permission.READ_PHONE_STATE)
        shadow.setActiveSubscriptionInfoList(null)
        assertEquals(emptyList<Sim>(), SimInventory(app).activeSims())
    }
}

/** Below API 33 the number comes from SubscriptionInfo itself. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class SimInventoryLegacyTest {
    private val app: Application = RuntimeEnvironment.getApplication()

    @Test
    fun numberFromSubscriptionInfo() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        shadowOf(app.getSystemService(SubscriptionManager::class.java))
            .setActiveSubscriptionInfos(sub(9, 0, "Orange", number = "+212611111111"))
        assertEquals(listOf(Sim(0, 9, "Orange", "+212611111111")), SimInventory(app).activeSims())
    }

    @Test
    fun blankNumberIsLeftOut() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        shadowOf(app.getSystemService(SubscriptionManager::class.java))
            .setActiveSubscriptionInfos(sub(9, 0, "Orange", number = "  "), sub(4, 1, "inwi"))
        assertEquals(listOf(Sim(0, 9, "Orange"), Sim(1, 4, "inwi")), SimInventory(app).activeSims())
    }

    @Test
    fun emptyWithoutPermission() {
        shadowOf(app.getSystemService(SubscriptionManager::class.java))
            .setActiveSubscriptionInfos(sub(9, 0, "Orange", number = "+212611111111"))
        assertEquals(emptyList<Sim>(), SimInventory(app).activeSims())
    }
}
