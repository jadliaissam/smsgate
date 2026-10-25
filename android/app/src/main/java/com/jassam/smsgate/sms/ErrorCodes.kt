package com.jassam.smsgate.sms

/**
 * Error codes reported with `failed` (backend rule: `[a-z0-9_]{1,64}`) and the
 * mapping from SmsManager send results. Error messages are short, at most 256
 * characters, and never contain a phone number or a message body.
 */
object ErrorCodes {
    const val SIM_UNAVAILABLE = "sim_unavailable"
    const val NO_DEFAULT_SIM = "no_default_sim"
    const val PERMISSION_DENIED = "permission_denied"
    const val INVALID_DESTINATION = "invalid_destination"
    const val SEND_EXCEPTION = "send_exception"
    const val NO_SMS_SERVICE = "no_sms_service"
    const val TOO_MANY_PARTS = "too_many_parts"
    const val BODY_MISSING = "body_missing"
    const val DELIVERY_FAILED = "delivery_failed"

    /** The validity window passed before sending (SmsSender, before markSending). */
    const val EXPIRED_ON_DEVICE = "expired_on_device"

    /** For A6 (D7): the app died after handing the SMS to Android, before its result. */
    const val OUTCOME_UNKNOWN = "outcome_unknown"

    const val MAX_MESSAGE = 256

    private val PATTERN = Regex("[a-z0-9_]{1,64}")

    /**
     * Every send result code of `SmsManager` (`RESULT_ERROR_*`, `RESULT_*`,
     * `RESULT_RIL_*`) up to API 37, as literal ints because most constants are newer
     * than minSdk 24. The name is the constant without `RESULT_ERROR_`/`RESULT_`, lower
     * case. Excluded: 0 (`RESULT_ERROR_NONE`, which on a sent broadcast is also
     * `Activity.RESULT_CANCELED`, not a success) and the inbound `RESULT_RECEIVE_*`.
     * Success is `Activity.RESULT_OK` (-1) and never reaches this table.
     */
    val SEND_RESULTS: Map<Int, String> = mapOf(
        1 to "generic_failure",
        2 to "radio_off",
        3 to "null_pdu",
        4 to "no_service",
        5 to "limit_exceeded",
        6 to "fdn_check_failure",
        7 to "short_code_not_allowed",
        8 to "short_code_never_allowed",
        9 to "radio_not_available",
        10 to "network_reject",
        11 to "invalid_arguments",
        12 to "invalid_state",
        13 to "no_memory",
        14 to "invalid_sms_format",
        15 to "system_error",
        16 to "modem_error",
        17 to "network_error",
        18 to "encoding_error",
        19 to "invalid_smsc_address",
        20 to "operation_not_allowed",
        21 to "internal_error",
        22 to "no_resources",
        23 to "cancelled",
        24 to "request_not_supported",
        25 to "no_bluetooth_service",
        26 to "invalid_bluetooth_address",
        27 to "bluetooth_disconnected",
        28 to "unexpected_event_stop_sending",
        29 to "sms_blocked_during_emergency",
        30 to "sms_send_retry_failed",
        31 to "remote_exception",
        32 to "no_default_sms_app",
        33 to "user_not_allowed",
        100 to "ril_radio_not_available",
        101 to "ril_sms_send_fail_retry",
        102 to "ril_network_reject",
        103 to "ril_invalid_state",
        104 to "ril_invalid_arguments",
        105 to "ril_no_memory",
        106 to "ril_request_rate_limited",
        107 to "ril_invalid_sms_format",
        108 to "ril_system_err",
        109 to "ril_encoding_err",
        110 to "ril_invalid_smsc_address",
        111 to "ril_modem_err",
        112 to "ril_network_err",
        113 to "ril_internal_err",
        114 to "ril_request_not_supported",
        115 to "ril_invalid_modem_state",
        116 to "ril_network_not_ready",
        117 to "ril_operation_not_allowed",
        118 to "ril_no_resources",
        119 to "ril_cancelled",
        120 to "ril_sim_absent",
        121 to "ril_simultaneous_sms_and_call_not_allowed",
        122 to "ril_access_barred",
        123 to "ril_blocked_due_to_call",
        124 to "ril_generic_error",
        125 to "ril_invalid_response",
        126 to "ril_sim_pin2",
        127 to "ril_sim_puk2",
        128 to "ril_subscription_not_available",
        129 to "ril_sim_error",
        130 to "ril_invalid_sim_state",
        131 to "ril_no_sms_to_ack",
        132 to "ril_sim_busy",
        133 to "ril_sim_full",
        134 to "ril_no_subscription",
        135 to "ril_no_network_found",
        136 to "ril_device_in_use",
        137 to "ril_aborted",
        138 to "sms_send_failed_after_max_retry",
    )

    /** The error code for a failed sent broadcast's result code; unknown → `sms_error_<n>`. */
    fun forSendResult(resultCode: Int): String =
        SEND_RESULTS[resultCode]
            ?: if (resultCode < 0) "sms_error_neg${-resultCode.toLong()}" else "sms_error_$resultCode"

    fun isValid(code: String): Boolean = PATTERN.matches(code)

    /** Clamps an error message to [MAX_MESSAGE] code points. Never pass numbers or bodies here. */
    fun message(text: String): String =
        if (text.codePointCount(0, text.length) <= MAX_MESSAGE) text
        else text.substring(0, text.offsetByCodePoints(0, MAX_MESSAGE))
}
