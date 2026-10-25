package com.jassam.smsgate.sms

import com.jassam.smsgate.api.Status
import com.jassam.smsgate.journal.PendingReport

/**
 * Where decided status reports go. A6 implements it with a unique, retried
 * WorkManager job per (id, status). Called at most once per (id, status): the
 * journal records the status in `reported` in the transaction that decides it.
 * [errorCode]/[errorMessage] only with [Status.FAILED]; messages are ≤ 256
 * characters and never contain a phone number or a body.
 */
fun interface StatusReportQueue {
    fun enqueue(id: String, status: Status, errorCode: String?, errorMessage: String?)
}

fun StatusReportQueue.enqueue(report: PendingReport) =
    enqueue(report.id, report.status, report.errorCode, report.errorMessage)
