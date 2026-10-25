package com.jassam.smsgate.sms

import android.app.Activity

/** What a delivery report (SMS-STATUS-REPORT) says about one part. */
enum class DeliveryOutcome { DELIVERED, PENDING, FAILED }

/**
 * Classifies `SmsMessage.getStatus()` of a status report.
 *
 * - 3GPP (`format = "3gpp"`): TP-Status (3GPP TS 23.040 §9.2.3.15): 0x00–0x1F
 *   completed, 0x20–0x3F temporary error with the SC still trying, 0x40 and above a
 *   permanent error or a temporary error the SC gave up on.
 * - 3GPP2 (`format = "3gpp2"`): the platform shifts `errorClass << 8 | messageStatus`
 *   into bits 31–16, so the error class is bits 25–24: 0 none, 2 temporary,
 *   3 permanent.
 *
 * Without a parseable report the broadcast itself is the signal: `RESULT_OK` means
 * delivered (the platform sends status reports with RESULT_OK), anything else is
 * left pending.
 */
object DeliveryStatus {
    fun outcome(resultCode: Int, format: String?, status: Int?): DeliveryOutcome = when {
        status != null -> classify(format, status)
        resultCode == Activity.RESULT_OK -> DeliveryOutcome.DELIVERED
        else -> DeliveryOutcome.PENDING
    }

    fun classify(format: String?, status: Int): DeliveryOutcome =
        if (format == "3gpp2") {
            when ((status ushr 24) and 0x03) {
                0 -> DeliveryOutcome.DELIVERED
                3 -> DeliveryOutcome.FAILED
                else -> DeliveryOutcome.PENDING
            }
        } else {
            when (status and 0xFF) {
                in 0x00..0x1F -> DeliveryOutcome.DELIVERED
                in 0x20..0x3F -> DeliveryOutcome.PENDING
                else -> DeliveryOutcome.FAILED
            }
        }
}
