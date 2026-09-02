package com.openandroidintelligence.sms

import com.openandroidintelligence.capability.AuthorizedAutoSendScope
import com.openandroidintelligence.capability.AuthorizedReadScope
import com.openandroidintelligence.capability.CapabilityEvent
import com.openandroidintelligence.capability.CapabilityReadResult
import com.openandroidintelligence.capability.CapabilityReadStatus
import com.openandroidintelligence.capability.MobileDataCapability
import com.openandroidintelligence.capability.SmsCapabilityProvider
import com.openandroidintelligence.capability.SmsMetadata
import com.openandroidintelligence.capability.SmsPayload
import com.openandroidintelligence.capability.normalizeContent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class AndroidSmsCapabilityProvider(
    private val reader: SmsInboxReader,
    private val historyPolicySource: SmsHistoryPolicySource,
    private val cursorSource: SmsCursorSource,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
) : SmsCapabilityProvider {
    override suspend fun read(scope: AuthorizedReadScope): CapabilityReadResult<SmsPayload> {
        val history = historyPolicySource.current()
        return try {
            CapabilityReadResult(
                records = reader.query(SmsInboxQuery(history)).take(history.maxRecords).map { it.toPayload(scope) },
                status = CapabilityReadStatus.COMPLETE,
                policyRevision = scope.policyRevision,
            )
        } catch (_: Exception) {
            CapabilityReadResult(
                records = emptyList(),
                status = CapabilityReadStatus.FAILED,
                policyRevision = scope.policyRevision,
                failureReason = SMS_QUERY_FAILED,
            )
        }
    }

    override fun observeAutoSend(scope: AuthorizedAutoSendScope): Flow<CapabilityEvent<SmsPayload>> = flow {
        val history = historyPolicySource.current()
        reader.query(SmsInboxQuery(history, cursorSource.current()))
            .take(history.maxRecords)
            .sortedWith(compareBy<SmsInboxRow> { it.messageAtEpochMs }.thenBy { it.providerId })
            .forEach { row ->
                emit(
                    CapabilityEvent(
                        capability = MobileDataCapability.SMS,
                        eventId = "sms:${row.providerId}",
                        record = row.toPayload(scope),
                        policyRevision = scope.policyRevision,
                    ),
                )
            }
    }

    private fun SmsInboxRow.toPayload(scope: AuthorizedReadScope): SmsPayload = SmsPayload(
        metadata = metadata(),
        content = normalizeContent(body ?: "", scope),
    )

    private fun SmsInboxRow.toPayload(scope: AuthorizedAutoSendScope): SmsPayload = SmsPayload(
        metadata = metadata(),
        content = normalizeContent(body ?: "", scope),
    )

    private fun SmsInboxRow.metadata(): SmsMetadata = SmsMetadata(
        recordId = "sms:$providerId",
        senderAddress = address,
        threadId = threadId,
        messageAtEpochMs = messageAtEpochMs,
        observedAtEpochMs = nowEpochMs(),
        read = read,
        subscriptionId = subscriptionId,
    )

    private companion object {
        const val SMS_QUERY_FAILED = "SMS_QUERY_FAILED"
    }
}
