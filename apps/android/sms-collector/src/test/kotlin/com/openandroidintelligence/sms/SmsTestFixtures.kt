package com.openandroidintelligence.sms

import com.openandroidintelligence.capability.AgentDataRequest
import com.openandroidintelligence.capability.AgentRequestAuthorization
import com.openandroidintelligence.capability.AuthorizedOnDemandRequest
import com.openandroidintelligence.capability.CapabilityAvailability
import com.openandroidintelligence.capability.CapabilityFilter
import com.openandroidintelligence.capability.CapabilityGrant
import com.openandroidintelligence.capability.DataSyncMode
import com.openandroidintelligence.capability.DefaultAgentRequestAuthorizer
import com.openandroidintelligence.capability.MobileDataCapability
import com.openandroidintelligence.capability.NormalizedContent
import com.openandroidintelligence.capability.normalizeContent
import com.openandroidintelligence.capability.requireReadScope

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
