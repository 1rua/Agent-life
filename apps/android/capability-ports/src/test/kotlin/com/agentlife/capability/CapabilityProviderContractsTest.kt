package com.agentlife.capability

import org.junit.Assert.assertEquals
import org.junit.Test

class CapabilityProviderContractsTest {
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
