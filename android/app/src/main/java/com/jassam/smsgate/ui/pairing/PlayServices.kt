package com.jassam.smsgate.ui.pairing

import android.app.Activity
import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.tasks.Task
import com.google.firebase.messaging.FirebaseMessaging
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Google Play services features used by pairing (D11): availability, the FCM
 * token and the code scanner (which needs no camera permission: Play services
 * owns the camera UI). Without Play services the app pairs by code or login
 * and registers without a token.
 */
object PlayServices {
    private const val FCM_TIMEOUT_MS = 10_000L

    fun available(context: Context): Boolean = try {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    } catch (_: RuntimeException) {
        false
    }

    /**
     * The FCM registration token, or null on failure or timeout (register goes
     * ahead without one; A6's `onNewToken` reports it later). Never logged.
     */
    // firebase-messaging 26 deprecates getToken() in favour of register() + onNewToken.
    // onNewToken (Task A6) reports later tokens; register needs the token synchronously,
    // so it keeps the still-supported getToken() until A6 settles the token flow.
    @Suppress("DEPRECATION")
    suspend fun fcmToken(): String? = try {
        withTimeoutOrNull(FCM_TIMEOUT_MS) { FirebaseMessaging.getInstance().token.await() }?.takeIf { it.isNotBlank() }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /**
     * Opens the Google code scanner for QR codes. [onResult] gets the raw text;
     * a user cancel calls nothing; any failure (scanner module not installed
     * yet, Play services error) calls [onUnavailable]. Listeners are scoped to
     * [activity], so nothing fires into a destroyed screen.
     */
    fun scan(activity: Activity, onResult: (String) -> Unit, onUnavailable: () -> Unit) {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
        try {
            GmsBarcodeScanning.getClient(activity, options).startScan()
                .addOnSuccessListener(activity) { barcode -> onResult(barcode.rawValue.orEmpty()) }
                .addOnFailureListener(activity) { onUnavailable() }
        } catch (_: RuntimeException) {
            onUnavailable()
        }
    }
}

/** Awaits a Play services [Task] (no kotlinx-coroutines-play-services dependency). */
internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { task ->
        when {
            task.isCanceled -> cont.cancel()
            task.isSuccessful -> cont.resume(task.result)
            else -> cont.resumeWithException(task.exception ?: IllegalStateException("task failed"))
        }
    }
}
