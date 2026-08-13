package com.agentlife.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CapabilityProviderContractsTest {
    @Test
    fun sms_metadata_rejects_non_sms_non_positive_and_out_of_range_record_ids() {
        listOf("42", "mms:42", "sms:0", "sms:01", "sms:18446744073709551616").forEach { recordId ->
            assertThrows(IllegalArgumentException::class.java) {
                SmsMetadata(
                    recordId = recordId,
                    senderAddress = null,
                    threadId = null,
                    messageAtEpochMs = 1,
                    observedAtEpochMs = 1,
                    read = false,
                    subscriptionId = null,
                )
            }
        }
    }

    @Test
    fun sms_metadata_rejects_negative_subscription_ids() {
        assertThrows(IllegalArgumentException::class.java) {
            SmsMetadata(
                recordId = "sms:1",
                senderAddress = null,
                threadId = null,
                messageAtEpochMs = 1,
                observedAtEpochMs = 1,
                read = false,
                subscriptionId = -1,
            )
        }
    }

    @Test
    fun sms_scope_releases_complete_body_including_empty_body() {
        val request = AgentDataRequest(
            requestId = "sms-read",
            capability = MobileDataCapability.SMS,
            mode = DataSyncMode.ON_DEMAND,
            filter = CapabilityFilter.Sms,
            policyRevision = 7u,
        )
        val grant = CapabilityGrant(
            capability = MobileDataCapability.SMS,
            filter = CapabilityFilter.Sms,
            onDemandEnabled = true,
            autoSendEnabled = false,
            agentMayRequest = true,
            policyRevision = 7u,
        )
        val authorization = DefaultAgentRequestAuthorizer().authorize(
            request = request,
            grant = grant,
            availability = CapabilityAvailability.READY,
        ) as AgentRequestAuthorization.Allowed
        val scope = (authorization.access as AuthorizedOnDemandRequest)
            .requireReadScope(MobileDataCapability.SMS)

        assertEquals(NormalizedContent.Released("full body"), normalizeContent("full body", scope))
        assertEquals(NormalizedContent.Released(""), normalizeContent("", scope))
        assertEquals(NormalizedContent.Released(""), normalizeContent(null, scope))
    }

    @Test
    fun non_sms_future_scope_still_withholds_content() {
        val request = AgentDataRequest(
            requestId = "calls-read",
            capability = MobileDataCapability.CALLS,
            mode = DataSyncMode.ON_DEMAND,
            filter = CapabilityFilter.Calls,
            policyRevision = 7u,
        )
        val grant = CapabilityGrant(
            capability = MobileDataCapability.CALLS,
            filter = CapabilityFilter.Calls,
            onDemandEnabled = true,
            autoSendEnabled = false,
            agentMayRequest = true,
            policyRevision = 7u,
        )
        val authorization = DefaultAgentRequestAuthorizer().authorize(
            request = request,
            grant = grant,
            availability = CapabilityAvailability.READY,
        ) as AgentRequestAuthorization.Allowed
        val scope = (authorization.access as AuthorizedOnDemandRequest)
            .requireReadScope(MobileDataCapability.CALLS)

        assertEquals(NormalizedContent.Withheld, normalizeContent("full body", scope))
    }
}
