package com.jassam.smsgate.realtime

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import com.jassam.smsgate.ui.MainActivity
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConnectivityManifestTest {
    private val app: Application = RuntimeEnvironment.getApplication()

    @Test
    fun serviceIsPrivateAndSpecialUse() {
        val info = app.packageManager.getServiceInfo(ComponentName(app, ConnectivityService::class.java), 0)
        assertFalse(info.exported)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, info.foregroundServiceType)
    }

    @Test
    fun permissionsForTheForegroundServiceAndNetworkCallback() {
        val requested = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions!!.toSet()
        for (p in listOf(
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE,
            Manifest.permission.ACCESS_NETWORK_STATE,
        )) {
            assertTrue("$p requested", p in requested)
        }
    }

    @Test
    fun specialUseSubtypeIsDeclaredOnTheService() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val service = Regex("""<service\s+android:name="\.realtime\.ConnectivityService".*?</service>""", RegexOption.DOT_MATCHES_ALL)
            .find(manifest)?.value ?: error("ConnectivityService not in the manifest")
        assertTrue(service.contains("""android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE""""))
        assertTrue(service.contains("""android:exported="false""""))
        assertTrue(service.contains("""android:foregroundServiceType="specialUse""""))
    }

    @Test
    fun notificationIsQuietOngoingOnItsOwnChannelAndOpensTheApp() {
        val n = ConnectivityNotification.build(app)
        val channel = app.getSystemService(NotificationManager::class.java).getNotificationChannel(ConnectivityNotification.CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertEquals("Always connected", channel.name)
        assertEquals(ConnectivityNotification.CHANNEL_ID, n.channelId)
        assertEquals("SMSGate is connected", n.extras.getCharSequence(NotificationCompat.EXTRA_TITLE).toString())
        assertTrue(n.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(NotificationCompat.CATEGORY_SERVICE, n.category)
        assertEquals(ComponentName(app, MainActivity::class.java), shadowOf(n.contentIntent).savedIntent.component)
        assertTrue(shadowOf(n.contentIntent).isImmutable)
        assertTrue(ConnectivityNotification.NOTIFICATION_ID != 0x5347) // not the sending notification's id
    }
}
