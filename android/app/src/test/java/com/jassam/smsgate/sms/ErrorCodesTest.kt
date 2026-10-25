package com.jassam.smsgate.sms

import android.telephony.SmsManager
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorCodesTest {
    /** Send result constants of the compile SDK (37): RESULT_* minus RESULT_ERROR_NONE and inbound RESULT_RECEIVE_*. */
    private fun sdkSendResults(): Map<Int, String> =
        SmsManager::class.java.fields
            .filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
            .filter { it.name.startsWith("RESULT_") && !it.name.startsWith("RESULT_RECEIVE_") && it.name != "RESULT_ERROR_NONE" }
            .associate { it.getInt(null) to it.name.removePrefix("RESULT_ERROR_").removePrefix("RESULT_").lowercase() }

    @Test
    fun tableCoversEverySdkSendResultExactly() {
        val sdk = sdkSendResults()
        assertTrue("reflection found the constants", sdk.size >= 70)
        assertEquals(sdk.toSortedMap(), ErrorCodes.SEND_RESULTS.toSortedMap())
    }

    @Test
    fun knownPlanCodes() {
        assertEquals("generic_failure", ErrorCodes.forSendResult(1))
        assertEquals("radio_off", ErrorCodes.forSendResult(2))
        assertEquals("null_pdu", ErrorCodes.forSendResult(3))
        assertEquals("no_service", ErrorCodes.forSendResult(4))
        assertEquals("limit_exceeded", ErrorCodes.forSendResult(5))
        assertEquals("short_code_not_allowed", ErrorCodes.forSendResult(7))
        assertEquals("ril_sim_absent", ErrorCodes.forSendResult(120))
    }

    @Test
    fun unknownCodes() {
        assertEquals("sms_error_0", ErrorCodes.forSendResult(0))
        assertEquals("sms_error_99", ErrorCodes.forSendResult(99))
        assertEquals("sms_error_500", ErrorCodes.forSendResult(500))
        assertEquals("sms_error_neg5", ErrorCodes.forSendResult(-5))
        assertEquals("sms_error_neg2147483648", ErrorCodes.forSendResult(Int.MIN_VALUE))
        assertEquals("sms_error_2147483647", ErrorCodes.forSendResult(Int.MAX_VALUE))
    }

    @Test
    fun everyCodeMatchesTheBackendPattern() {
        val backend = Regex("^[a-z0-9_]{1,64}$")
        val fixed = listOf(
            ErrorCodes.SIM_UNAVAILABLE, ErrorCodes.PERMISSION_DENIED, ErrorCodes.INVALID_DESTINATION,
            ErrorCodes.SEND_EXCEPTION, ErrorCodes.NO_SMS_SERVICE, ErrorCodes.TOO_MANY_PARTS, ErrorCodes.BODY_MISSING,
            ErrorCodes.DELIVERY_FAILED, ErrorCodes.EXPIRED_ON_DEVICE, ErrorCodes.OUTCOME_UNKNOWN,
        )
        val unknown = listOf(0, 34, 99, 139, 500, -1, -5, Int.MIN_VALUE, Int.MAX_VALUE).map(ErrorCodes::forSendResult)
        for (code in fixed + ErrorCodes.SEND_RESULTS.values + unknown) {
            assertTrue(code, backend.matches(code))
            assertTrue(code, ErrorCodes.isValid(code))
        }
        assertEquals(ErrorCodes.SEND_RESULTS.size, ErrorCodes.SEND_RESULTS.values.toSet().size)
        assertFalse(ErrorCodes.isValid(""))
        assertFalse(ErrorCodes.isValid("Bad-Code"))
        assertFalse(ErrorCodes.isValid("a".repeat(65)))
    }

    @Test
    fun messagesAreClampedTo256CodePoints() {
        assertEquals("short", ErrorCodes.message("short"))
        assertEquals(256, ErrorCodes.message("x".repeat(300)).length)
        val emoji = "😀".repeat(300)
        val clamped = ErrorCodes.message(emoji)
        assertEquals(256, clamped.codePointCount(0, clamped.length))
    }
}
