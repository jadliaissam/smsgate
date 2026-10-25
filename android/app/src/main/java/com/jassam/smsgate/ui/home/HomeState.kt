package com.jassam.smsgate.ui.home

import androidx.compose.runtime.Immutable
import com.jassam.smsgate.api.Sim
import com.jassam.smsgate.journal.DayCounts
import com.jassam.smsgate.realtime.ConnectionState
import com.jassam.smsgate.ui.onboarding.ItemStatus
import com.jassam.smsgate.ui.onboarding.OnboardingLogic
import com.jassam.smsgate.ui.onboarding.OnboardingSnapshot

/** Which account and server-side name this phone is paired as (`GET /device/v1/me`). */
@Immutable
data class AccountInfo(val name: String, val email: String) {
    override fun toString(): String = "AccountInfo(name=$name, email=***)"
}

/** The last `me()` answer per device, so Home shows the account while offline. */
interface MeCache {
    suspend fun get(deviceId: String): AccountInfo?
    suspend fun put(deviceId: String, info: AccountInfo)

    /** Forgets the cached details if they belong to [deviceId] (unpair). */
    suspend fun clear(deviceId: String)
}

/** Where the account details stand. */
enum class AccountStatus {
    /** Asking the server. */
    Loading,

    /** Fresh from the server. */
    Fresh,

    /** No connection: showing the cached details, if any. */
    Offline,

    /** The server answered with an error (not a revocation). */
    Failed,
}

/** Push (FCM) readiness, as far as the phone can tell. */
enum class PushStatus {
    Checking,

    /** The server accepted this phone's current token. */
    Ok,

    /** Play services present, but the current token is not (yet) known to the server. */
    NotRegistered,

    /** No Google Play services (D11): no push; periodic sync or Always connected. */
    NoPlayServices,
}

/** SIMs and the default SMS subscription, read off the main thread. */
data class PhoneInfo(
    /** Null when unknown (no READ_PHONE_STATE, or the platform refused). */
    val sims: List<Sim>?,
    /** The default SMS subscription id, or null when none is set. */
    val defaultSubscriptionId: Int?,
)

/** One SIM card on Home and the default-SIM line in Settings. */
@Immutable
data class SimCard(val slot: Int, val carrier: String, val number: String?, val isDefault: Boolean) {
    override fun toString(): String = "SimCard(slot=$slot, carrier=$carrier, number=${number?.let { "***" }}, isDefault=$isDefault)"
}

@Immutable
sealed interface SimsInfo {
    data object Loading : SimsInfo

    /** READ_PHONE_STATE missing: the list is unknown. */
    data object NoPermission : SimsInfo

    data class Known(val sims: List<SimCard>) : SimsInfo
}

/** A permission or battery item that needs fixing, in display order. */
enum class WarningKind { Sms, PhoneState, Notifications, Battery, Oem }

/** [blocked]: Android won't ask again, so the fix opens the app's settings page. */
@Immutable
data class PermissionWarning(val kind: WarningKind, val blocked: Boolean = false)

/** Everything the home screen shows. */
@Immutable
data class HomeUiState(
    val serverHost: String,
    /** The local label from Settings, or null (then the server-side name is the title). */
    val deviceLabel: String? = null,
    val account: AccountInfo? = null,
    val accountStatus: AccountStatus = AccountStatus.Loading,
    val lastSyncMs: Long? = null,
    val push: PushStatus = PushStatus.Checking,
    val connection: ConnectionState = ConnectionState.OFF,
    val alwaysConnected: Boolean = false,
    val sims: SimsInfo = SimsInfo.Loading,
    val today: DayCounts = DayCounts.ZERO,
    val warnings: List<PermissionWarning> = emptyList(),
) {
    /** False only once the phone is known to lack Play services. */
    val playServices: Boolean get() = push != PushStatus.NoPlayServices
}

/** Pure rules behind the home screen (unit-tested). */
object HomeLogic {
    /**
     * Warnings from the checklist snapshot (Permissions.snapshot) plus whether
     * notifications are enabled at all (also off below API 33 when the user disabled
     * them, which no permission reflects).
     */
    fun warnings(s: OnboardingSnapshot, notificationsEnabled: Boolean): List<PermissionWarning> = buildList {
        if (!s.sms.granted) add(PermissionWarning(WarningKind.Sms, OnboardingLogic.status(s.sms) == ItemStatus.Blocked))
        if (!s.phoneState.granted) {
            add(PermissionWarning(WarningKind.PhoneState, OnboardingLogic.status(s.phoneState) == ItemStatus.Blocked))
        }
        if (s.sdkInt >= 33 && !s.notifications.granted) {
            add(PermissionWarning(WarningKind.Notifications, OnboardingLogic.status(s.notifications) == ItemStatus.Blocked))
        } else if (!notificationsEnabled) {
            add(PermissionWarning(WarningKind.Notifications, blocked = true))
        }
        if (!s.batteryExempt) add(PermissionWarning(WarningKind.Battery))
        if (s.oem != null && !s.oemAcknowledged) add(PermissionWarning(WarningKind.Oem))
    }

    /**
     * What the SIM card's "Allow" button fixes: the phone-state warning as the warnings
     * card has it (blocked → open the app's settings), or a plain request when the
     * warnings haven't been read yet.
     */
    fun simFix(warnings: List<PermissionWarning>): PermissionWarning =
        warnings.firstOrNull { it.kind == WarningKind.PhoneState } ?: PermissionWarning(WarningKind.PhoneState)

    /** SIM cards for display, marking the default SMS SIM; null list = no permission. */
    fun sims(info: PhoneInfo): SimsInfo {
        val sims = info.sims ?: return SimsInfo.NoPermission
        return SimsInfo.Known(
            sims.map { SimCard(it.slot, it.carrier, it.number, isDefault = it.subscriptionId == info.defaultSubscriptionId) },
        )
    }
}
