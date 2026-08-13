package com.agentlife.sms

import com.agentlife.capability.AgentDataRequest
import com.agentlife.capability.AgentRequestAuthorization
import com.agentlife.capability.AuthorizedAutoSendSubscription
import com.agentlife.capability.AuthorizedOnDemandRequest
import com.agentlife.capability.CapabilityAvailability
import com.agentlife.capability.CapabilityFilter
import com.agentlife.capability.CapabilityGrant
import com.agentlife.capability.CapabilityReadStatus
import com.agentlife.capability.DataSyncMode
import com.agentlife.capability.DefaultAgentRequestAuthorizer
import com.agentlife.capability.MobileDataCapability
import com.agentlife.capability.NormalizedContent
import com.agentlife.capability.SmsHistoryPolicy
import com.agentlife.capability.requireAutoSendScope
import com.agentlife.capability.requireReadScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSmsCapabilityProviderTest {
    @Test
    fun read_maps_complete_body_and_sms_metadata_in_descending_history_order() = runBlocking {
        val provider = providerWith(
            rows = listOf(
                row(providerId = 9L, messageAtEpochMs = 900L, body = "new", threadId = "thread-9", address = "+15550009", read = true, subscriptionId = 2),
                row(providerId = 8L, messageAtEpochMs = 800L, body = "old"),
            ),
            history = SmsHistoryPolicy(fromEpochMs = 700L, maxRecords = 2),
        )

        val result = provider.read(readScope())

        assertEquals(CapabilityReadStatus.COMPLETE, result.status)
        assertEquals(listOf("sms:9", "sms:8"), result.records.map { it.metadata.recordId })
        val newest = result.records.first()
        assertEquals("new", (newest.content as NormalizedContent.Released).value)
        assertEquals("+15550009", newest.metadata.senderAddress)
        assertEquals("thread-9", newest.metadata.threadId)
        assertTrue(newest.metadata.read)
        assertEquals(2, newest.metadata.subscriptionId)
        assertEquals(1_000L, newest.metadata.observedAtEpochMs)
    }

    @Test
    fun read_normalizes_a_null_provider_body_to_released_empty_content() = runBlocking {
        val provider = providerWith(rows = listOf(row(providerId = 7L, messageAtEpochMs = 700L, body = null)))

        val result = provider.read(readScope())

        assertEquals("", (result.records.single().content as NormalizedContent.Released).value)
    }

    @Test
    fun read_returns_failed_without_records_when_the_inbox_query_throws() = runBlocking {
        val provider = AndroidSmsCapabilityProvider(
            reader = SmsInboxReader { throw IllegalStateException("provider unavailable") },
            historyPolicySource = SmsHistoryPolicySource { SmsHistoryPolicy(null, 10) },
            cursorSource = SmsCursorSource { null },
            nowEpochMs = { 1_000L },
        )

        val result = provider.read(readScope())

        assertEquals(CapabilityReadStatus.FAILED, result.status)
        assertEquals("SMS_QUERY_FAILED", result.failureReason)
        assertTrue(result.records.isEmpty())
    }

    @Test
    fun observe_auto_send_orders_equal_dates_by_provider_id_and_uses_deterministic_event_ids() = runBlocking {
        var request: SmsInboxQuery? = null
        val provider = AndroidSmsCapabilityProvider(
            reader = SmsInboxReader {
                request = it
                listOf(
                    row(providerId = 42L, messageAtEpochMs = 2_000L, body = "received"),
                    row(providerId = 41L, messageAtEpochMs = 2_000L, body = "also received"),
                )
            },
            historyPolicySource = SmsHistoryPolicySource { SmsHistoryPolicy(null, 10) },
            cursorSource = SmsCursorSource { SmsCursor(providerId = 40L, messageAtEpochMs = 2_000L) },
            nowEpochMs = { 3_000L },
        )

        val events = provider.observeAutoSend(autoSendScope()).toList()

        assertEquals(SmsCursor(providerId = 40L, messageAtEpochMs = 2_000L), request?.cursor)
        assertEquals(listOf("sms:41", "sms:42"), events.map { it.eventId })
        assertEquals("sms:42", events.last().eventId)
        assertEquals("received", (events.last().record.content as NormalizedContent.Released).value)
    }

    @Test
    fun observe_auto_send_emits_only_the_inbox_rows_supplied_by_the_reader() = runBlocking {
        val provider = providerWith(rows = listOf(row(providerId = 42L, messageAtEpochMs = 2_000L)))

        val events = provider.observeAutoSend(autoSendScope()).toList()

        assertEquals(listOf("sms:42"), events.map { it.eventId })
    }

    private fun providerWith(
        rows: List<SmsInboxRow>,
        history: SmsHistoryPolicy = SmsHistoryPolicy(null, 10),
    ) = AndroidSmsCapabilityProvider(
        reader = SmsInboxReader { rows },
        historyPolicySource = SmsHistoryPolicySource { history },
        cursorSource = SmsCursorSource { null },
        nowEpochMs = { 1_000L },
    )

    private fun row(
        providerId: Long,
        messageAtEpochMs: Long,
        body: String? = "body",
        threadId: String? = null,
        address: String? = null,
        read: Boolean = false,
        subscriptionId: Int? = null,
    ) = SmsInboxRow(providerId, threadId, address, body, messageAtEpochMs, read, subscriptionId)

    private fun readScope() = authorizedScope(DataSyncMode.ON_DEMAND) { authorization ->
        (authorization.access as AuthorizedOnDemandRequest).requireReadScope(MobileDataCapability.SMS)
    }

    private fun autoSendScope() = authorizedScope(DataSyncMode.AUTO_SEND) { authorization ->
        (authorization.access as AuthorizedAutoSendSubscription).requireAutoSendScope(MobileDataCapability.SMS)
    }

    private fun <T> authorizedScope(
        mode: DataSyncMode,
        select: (AgentRequestAuthorization.Allowed) -> T,
    ): T {
        val request = AgentDataRequest("sms-$mode", MobileDataCapability.SMS, mode, CapabilityFilter.Sms, 7u)
        val grant = CapabilityGrant(MobileDataCapability.SMS, CapabilityFilter.Sms, true, true, true, 7u)
        val authorization = DefaultAgentRequestAuthorizer().authorize(request, grant, CapabilityAvailability.READY)
            as AgentRequestAuthorization.Allowed
        return select(authorization)
    }
}
