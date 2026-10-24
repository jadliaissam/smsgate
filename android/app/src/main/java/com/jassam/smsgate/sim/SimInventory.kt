package com.jassam.smsgate.sim

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import com.jassam.smsgate.api.Sim

/**
 * The phone's active SIMs as the backend wants them (`[{slot, subscription_id,
 * carrier, number?}]`), for register and heartbeat.
 *
 * Needs `READ_PHONE_STATE`; without it (or on a phone without telephony, or if
 * the platform throws) the list is empty, never an exception. The number is
 * best effort: API 33+ reads it with `READ_PHONE_NUMBERS`
 * ([SubscriptionManager.getPhoneNumber]); older releases take what
 * [SubscriptionInfo] carries. Most carriers don't provide it at all.
 *
 * Entries the backend would reject are dropped or trimmed here (slots 0–3,
 * unique; carrier ≤ 64 characters; number ≤ 32 or left out), so a phone with
 * an odd SIM still pairs. Makes a binder call: call it off the main thread.
 */
class SimInventory(context: Context) {
    private val app = context.applicationContext

    fun activeSims(): List<Sim> {
        if (!granted(Manifest.permission.READ_PHONE_STATE)) return emptyList()
        val manager = app.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
        val infos = try {
            activeSubscriptions(manager)
        } catch (_: SecurityException) {
            return emptyList()
        } ?: return emptyList()
        val sims = infos.map { info ->
            Sim(
                slot = info.simSlotIndex,
                subscriptionId = info.subscriptionId,
                carrier = carrierOf(info),
                number = numberOf(manager, info),
            )
        }
        return sanitize(sims)
    }

    // Guarded by the READ_PHONE_STATE check in activeSims (and the SecurityException catch).
    @SuppressLint("MissingPermission")
    private fun activeSubscriptions(manager: SubscriptionManager): List<SubscriptionInfo>? =
        manager.activeSubscriptionInfoList

    private fun carrierOf(info: SubscriptionInfo): String =
        info.carrierName?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: info.displayName?.toString()?.trim().orEmpty()

    // Guarded by explicit permission checks; any SecurityException just means "no number".
    @SuppressLint("MissingPermission", "HardwareIds")
    private fun numberOf(manager: SubscriptionManager, info: SubscriptionInfo): String? {
        val raw = try {
            if (Build.VERSION.SDK_INT >= 33) {
                if (!granted(Manifest.permission.READ_PHONE_NUMBERS)) return null
                manager.getPhoneNumber(info.subscriptionId)
            } else {
                @Suppress("DEPRECATION")
                info.number
            }
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null // the subscription disappeared between the two calls
        }
        return raw?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun granted(permission: String): Boolean =
        app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    internal companion object {
        const val MAX_SIMS = 4
        const val MAX_CARRIER = 64
        const val MAX_NUMBER = 32

        /** Backend rules (devices.validSIMs): slots 0–3 unique, carrier ≤ 64, number ≤ 32 code points. */
        fun sanitize(sims: List<Sim>): List<Sim> {
            val seen = BooleanArray(MAX_SIMS)
            val out = ArrayList<Sim>(sims.size)
            for (sim in sims) {
                if (sim.slot !in 0 until MAX_SIMS || seen[sim.slot]) continue
                seen[sim.slot] = true
                out += sim.copy(
                    carrier = sim.carrier.takeCodePoints(MAX_CARRIER),
                    number = sim.number?.takeIf { it.codePointCount(0, it.length) <= MAX_NUMBER },
                )
            }
            out.sortBy { it.slot }
            return out
        }

        private fun String.takeCodePoints(n: Int): String =
            if (codePointCount(0, length) <= n) this else substring(0, offsetByCodePoints(0, n))
    }
}
