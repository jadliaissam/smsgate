package com.jassam.smsgate.work

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.telephony.TelephonyManager
import com.jassam.smsgate.api.Heartbeat
import com.jassam.smsgate.sim.SimInventory

/** Reads what the heartbeat reports. */
fun interface HeartbeatSource {
    fun read(): Heartbeat
}

/**
 * Battery %, charging, signal level (0–4, API 28+), SIMs and app version, each
 * best effort: whatever can't be read (no permission, no telephony, an OEM quirk)
 * is null and left out of the request, never an exception. SIMs are null (the
 * server keeps its list) without READ_PHONE_STATE, rather than an empty list that
 * would erase it. Makes binder calls: call off the main thread.
 */
class DeviceHeartbeatSource(
    context: Context,
    private val sims: SimInventory,
    appVersion: String,
) : HeartbeatSource {
    private val app = context.applicationContext
    private val version = appVersion.take(MAX_APP_VERSION).takeIf { it.isNotEmpty() }

    override fun read(): Heartbeat {
        val battery = app.getSystemService(BatteryManager::class.java)
        return Heartbeat(
            battery = safely { battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 } },
            charging = safely { battery?.isCharging },
            signal = safely { signal() },
            sims = safely { sims.activeSimsOrNull() },
            appVersion = version,
        )
    }

    private fun signal(): Int? {
        if (Build.VERSION.SDK_INT < 28) return null
        val telephony = app.getSystemService(TelephonyManager::class.java) ?: return null
        return telephony.signalStrength?.level?.takeIf { it in 0..4 }
    }

    private inline fun <T> safely(read: () -> T?): T? = try {
        read()
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val MAX_APP_VERSION = 32
    }
}
