package com.openandroidintelligence.capability

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LocalSmsAutoSendAuthorizerTest {
    @Test
    fun `local auto send ignores Agent request consent but binds both local revisions`() {
        val authorizer = LocalSmsAutoSendAuthorizer()
        val localState = LocalSmsAutoSendState(
            grant = CapabilityGrant(
                capability = MobileDataCapability.SMS,
                filter = CapabilityFilter.Sms,
                onDemandEnabled = false,
                autoSendEnabled = true,
                agentMayRequest = false,
                policyRevision = 7u,
            ),
            authorizationRevision = 11u,
        )

        val allowed = authorizer.authorize(
            LocalSmsAutoSendRequest(policyRevision = 7u, authorizationRevision = 11u),
            localState,
            CapabilityAvailability.READY,
        )
        val staleAuthorization = authorizer.authorize(
            LocalSmsAutoSendRequest(policyRevision = 7u, authorizationRevision = 10u),
            localState,
            CapabilityAvailability.READY,
        )
        val stalePolicy = authorizer.authorize(
            LocalSmsAutoSendRequest(policyRevision = 6u, authorizationRevision = 11u),
            localState,
            CapabilityAvailability.READY,
        )

        assertNotNull(allowed)
        assertNull(staleAuthorization)
        assertNull(stalePolicy)
    }
}
