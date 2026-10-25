package com.jassam.smsgate.ui.home

import com.jassam.smsgate.api.Sim
import com.jassam.smsgate.ui.onboarding.Oem
import com.jassam.smsgate.ui.onboarding.OnboardingSnapshot
import com.jassam.smsgate.ui.onboarding.PermissionState
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeLogicTest {
    private val granted = PermissionState(granted = true, asked = true, showRationale = false)
    private val notAsked = PermissionState(granted = false, asked = false, showRationale = false)
    private val denied = PermissionState(granted = false, asked = true, showRationale = true)
    private val blocked = PermissionState(granted = false, asked = true, showRationale = false)

    private fun snapshot(
        sdk: Int = 34,
        sms: PermissionState = granted,
        phone: PermissionState = granted,
        notifications: PermissionState = granted,
        exempt: Boolean = true,
        oem: Oem? = null,
        oemDone: Boolean = false,
    ) = OnboardingSnapshot(sdk, sms, phone, notifications, exempt, oem, oemDone)

    @Test
    fun allGoodMeansNoWarnings() {
        assertEquals(emptyList<PermissionWarning>(), HomeLogic.warnings(snapshot(), notificationsEnabled = true))
    }

    @Test
    fun eachMissingItemWarnsInOrder() {
        val w = HomeLogic.warnings(
            snapshot(sms = notAsked, phone = denied, notifications = blocked, exempt = false, oem = Oem.Xiaomi),
            notificationsEnabled = false,
        )
        assertEquals(
            listOf(
                PermissionWarning(WarningKind.Sms, blocked = false),
                PermissionWarning(WarningKind.PhoneState, blocked = false),
                PermissionWarning(WarningKind.Notifications, blocked = true),
                PermissionWarning(WarningKind.Battery),
                PermissionWarning(WarningKind.Oem),
            ),
            w,
        )
    }

    @Test
    fun aBlockedPermissionOpensAppSettings() {
        assertEquals(listOf(PermissionWarning(WarningKind.Sms, blocked = true)), HomeLogic.warnings(snapshot(sms = blocked), true))
    }

    @Test
    fun notificationsBelow33FollowTheAppSwitch() {
        // Below API 33 there is no runtime permission (the snapshot reports it granted).
        assertEquals(emptyList<PermissionWarning>(), HomeLogic.warnings(snapshot(sdk = 30), notificationsEnabled = true))
        assertEquals(
            listOf(PermissionWarning(WarningKind.Notifications, blocked = true)),
            HomeLogic.warnings(snapshot(sdk = 30), notificationsEnabled = false),
        )
    }

    @Test
    fun anAcknowledgedOemStepNoLongerWarns() {
        assertEquals(emptyList<PermissionWarning>(), HomeLogic.warnings(snapshot(oem = Oem.Samsung, oemDone = true), true))
    }

    @Test
    fun simsMarkTheDefaultSubscription() {
        val info = PhoneInfo(listOf(Sim(0, 11, "Orange", "+212600000001"), Sim(1, 22, "inwi")), defaultSubscriptionId = 22)
        assertEquals(
            SimsInfo.Known(listOf(SimCard(0, "Orange", "+212600000001", isDefault = false), SimCard(1, "inwi", null, isDefault = true))),
            HomeLogic.sims(info),
        )
    }

    @Test
    fun unknownSimsMeanNoPermission() {
        assertEquals(SimsInfo.NoPermission, HomeLogic.sims(PhoneInfo(null, 11)))
        assertEquals(SimsInfo.Known(emptyList()), HomeLogic.sims(PhoneInfo(emptyList(), null)))
    }

    @Test
    fun theSimCardFixUsesTheWarningsBlockedFlag() {
        val blocked = PermissionWarning(WarningKind.PhoneState, blocked = true)
        assertEquals(blocked, HomeLogic.simFix(listOf(PermissionWarning(WarningKind.Sms), blocked)))
        assertEquals(PermissionWarning(WarningKind.PhoneState, blocked = false), HomeLogic.simFix(emptyList()))
    }
}
