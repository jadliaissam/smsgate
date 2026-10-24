package com.jassam.smsgate.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingLogicTest {
    private val granted = PermissionState(granted = true, asked = true, showRationale = false)
    private val notAsked = PermissionState(granted = false, asked = false, showRationale = false)
    private val denied = PermissionState(granted = false, asked = true, showRationale = true)
    private val blocked = PermissionState(granted = false, asked = true, showRationale = false)

    private fun snapshot(
        sdk: Int = 34,
        sms: PermissionState = notAsked,
        phone: PermissionState = notAsked,
        notifications: PermissionState = notAsked,
        battery: Boolean = false,
        oem: Oem? = null,
        oemDone: Boolean = false,
    ) = OnboardingSnapshot(sdk, sms, phone, notifications, battery, oem, oemDone)

    @Test
    fun permissionStatusTruthTable() {
        assertEquals(ItemStatus.Done, OnboardingLogic.status(granted))
        // Granted wins even if a stale "asked"/rationale flag says otherwise.
        assertEquals(ItemStatus.Done, OnboardingLogic.status(PermissionState(true, false, true)))
        assertEquals(ItemStatus.NotAsked, OnboardingLogic.status(notAsked))
        assertEquals(ItemStatus.Denied, OnboardingLogic.status(denied))
        assertEquals(ItemStatus.Blocked, OnboardingLogic.status(blocked))
    }

    @Test
    fun notificationsOnlyOnApi33AndUp() {
        assertFalse(OnboardingLogic.checklist(snapshot(sdk = 32)).any { it.item == ChecklistItem.Notifications })
        assertTrue(OnboardingLogic.checklist(snapshot(sdk = 33)).any { it.item == ChecklistItem.Notifications })
    }

    @Test
    fun oemStepOnlyForKnownManufacturers() {
        assertFalse(OnboardingLogic.checklist(snapshot(oem = null)).any { it.item == ChecklistItem.OemBattery })
        val oem = OnboardingLogic.checklist(snapshot(oem = Oem.Xiaomi)).single { it.item == ChecklistItem.OemBattery }
        assertEquals(ItemStatus.NotAsked, oem.status)
        assertEquals(ItemAction.OpenOemSettings, oem.action)
        val done = OnboardingLogic.checklist(snapshot(oem = Oem.Xiaomi, oemDone = true)).single { it.item == ChecklistItem.OemBattery }
        assertEquals(ItemStatus.Done, done.status)
        assertEquals(ItemAction.None, done.action)
    }

    @Test
    fun orderIsStable() {
        val items = OnboardingLogic.checklist(snapshot(sdk = 34, oem = Oem.Samsung)).map { it.item }
        assertEquals(
            listOf(ChecklistItem.Sms, ChecklistItem.PhoneState, ChecklistItem.Notifications, ChecklistItem.Battery, ChecklistItem.OemBattery),
            items,
        )
    }

    @Test
    fun actionsFollowStatus() {
        val list = OnboardingLogic.checklist(snapshot(sms = notAsked, phone = denied, notifications = blocked))
        val sms = list.single { it.item == ChecklistItem.Sms }
        assertEquals(ItemAction.Request, sms.action)
        assertFalse(sms.settingsLink)

        // Denied but askable again: ask, and offer app settings as a link.
        val phone = list.single { it.item == ChecklistItem.PhoneState }
        assertEquals(ItemStatus.Denied, phone.status)
        assertEquals(ItemAction.Request, phone.action)
        assertTrue(phone.settingsLink)

        // Probably blocked, but a dismissed dialog looks the same on Android 11+:
        // keep "Allow" and add the app settings link.
        val notif = list.single { it.item == ChecklistItem.Notifications }
        assertEquals(ItemStatus.Blocked, notif.status)
        assertEquals(ItemAction.Request, notif.action)
        assertTrue(notif.settingsLink)

        val done = OnboardingLogic.checklist(snapshot(sms = granted)).single { it.item == ChecklistItem.Sms }
        assertEquals(ItemAction.None, done.action)
        assertFalse(done.settingsLink)
    }

    @Test
    fun batteryExemption() {
        val todo = OnboardingLogic.checklist(snapshot(battery = false)).single { it.item == ChecklistItem.Battery }
        assertEquals(ItemStatus.NotAsked, todo.status)
        assertEquals(ItemAction.Request, todo.action)
        val done = OnboardingLogic.checklist(snapshot(battery = true)).single { it.item == ChecklistItem.Battery }
        assertEquals(ItemStatus.Done, done.status)
    }

    @Test
    fun onlySmsIsRequired() {
        val list = OnboardingLogic.checklist(snapshot(oem = Oem.Huawei))
        assertEquals(listOf(ChecklistItem.Sms), list.filter { it.required }.map { it.item })
        assertFalse(OnboardingLogic.ready(list))
        assertTrue(OnboardingLogic.ready(OnboardingLogic.checklist(snapshot(sms = granted, phone = blocked))))
    }

    @Test
    fun progressCountsDoneItems() {
        val list = OnboardingLogic.checklist(snapshot(sdk = 34, sms = granted, phone = granted, battery = true))
        assertEquals(3, OnboardingLogic.doneCount(list))
        assertEquals(4, list.size)
    }
}
