package com.agentlife.sms

import com.agentlife.capability.AgentDataRequest
import com.agentlife.capability.AgentRequestAuthorization
import com.agentlife.capability.AuthorizedOnDemandRequest
import com.agentlife.capability.CapabilityAvailability
import com.agentlife.capability.CapabilityFilter
import com.agentlife.capability.CapabilityGrant
import com.agentlife.capability.DataSyncMode
import com.agentlife.capability.DefaultAgentRequestAuthorizer
import com.agentlife.capability.MobileDataCapability
import com.agentlife.capability.NormalizedContent
import com.agentlife.capability.normalizeContent
import com.agentlife.capability.requireReadScope

internal fun releasedSmsContent(body: String): NormalizedContent<String> {
    val request = AgentDataRequest(
        requestId = "sms-test-read",
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
    val allowed = DefaultAgentRequestAuthorizer().authorize(request, grant, CapabilityAvailability.READY)
        as AgentRequestAuthorization.Allowed
    val scope = (allowed.access as AuthorizedOnDemandRequest).requireReadScope(MobileDataCapability.SMS)
    return normalizeContent(body, scope)
}
