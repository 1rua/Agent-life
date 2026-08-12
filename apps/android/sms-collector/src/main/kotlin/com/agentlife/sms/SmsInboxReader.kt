package com.agentlife.sms

import com.agentlife.capability.SmsHistoryPolicy

data class SmsInboxRow(
    val providerId: Long,
    val threadId: String?,
    val address: String?,
    val body: String?,
    val messageAtEpochMs: Long,
    val read: Boolean,
    val subscriptionId: Int?,
)

data class SmsInboxQuery(val history: SmsHistoryPolicy, val cursor: SmsCursor? = null)

fun interface SmsInboxReader {
    fun query(request: SmsInboxQuery): List<SmsInboxRow>
}
