package com.agentlife.sms

import com.agentlife.capability.SmsHistoryPolicy

data class SmsCursor(val providerId: Long, val messageAtEpochMs: Long)

fun interface SmsHistoryPolicySource {
    fun current(): SmsHistoryPolicy
}

/** Read-only in this slice; Task 3 owns cursor persistence. */
fun interface SmsCursorSource {
    fun current(): SmsCursor?
}
