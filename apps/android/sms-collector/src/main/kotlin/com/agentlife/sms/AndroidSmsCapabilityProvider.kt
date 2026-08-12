package com.agentlife.sms

import com.agentlife.capability.AuthorizedAutoSendScope
import com.agentlife.capability.AuthorizedReadScope
import com.agentlife.capability.CapabilityEvent
import com.agentlife.capability.CapabilityReadResult
import com.agentlife.capability.CapabilityReadStatus
import com.agentlife.capability.MobileDataCapability
import com.agentlife.capability.SmsCapabilityProvider
import com.agentlife.capability.SmsMetadata
import com.agentlife.capability.SmsPayload
import com.agentlife.capability.normalizeContent
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
