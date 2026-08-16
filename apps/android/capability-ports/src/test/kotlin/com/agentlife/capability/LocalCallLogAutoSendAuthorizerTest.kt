package com.agentlife.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LocalCallLogAutoSendAuthorizerTest {
    @Test
    fun `local auto send ignores Agent request consent but binds both local revisions`() {
        val authorizer = LocalCallLogAutoSendAuthorizer()
        val localState = LocalCallLogAutoSendState(
            grant = callGrant(),
            authorizationRevision = 11u,
        )

        val allowed = authorizer.authorize(
            LocalCallLogAutoSendRequest(policyRevision = 7u, authorizationRevision = 11u),
            localState,
            CapabilityAvailability.READY,
        )

        assertNotNull(allowed)
        assertEquals("local-call-log-auto-sync", allowed?.request?.requestId)
        assertEquals(DataSyncMode.AUTO_SEND, allowed?.request?.mode)
        assertNull(authorizer.authorize(LocalCallLogAutoSendRequest(7u, 10u), localState, CapabilityAvailability.READY))
        assertNull(authorizer.authorize(LocalCallLogAutoSendRequest(6u, 11u), localState, CapabilityAvailability.READY))
    }

    @Test
    fun local_auto_send_rejects_unready_or_non_exact_call_grants() {
        val authorizer = LocalCallLogAutoSendAuthorizer()
        val request = LocalCallLogAutoSendRequest(7u, 11u)

        assertNull(authorizer.authorize(request, LocalCallLogAutoSendState(callGrant(), 11u), CapabilityAvailability.DISABLED))
        assertNull(authorizer.authorize(request, LocalCallLogAutoSendState(
            CapabilityGrant(MobileDataCapability.SMS, CapabilityFilter.Sms, false, true, false, 7u), 11u,
        ), CapabilityAvailability.READY))
        assertNull(authorizer.authorize(request, LocalCallLogAutoSendState(
            CapabilityGrant(
                MobileDataCapability.CALLS,
                CapabilityFilter.Calls(setOf(CallDirection.MISSED), CallCounterpartyAccess.WITHHELD),
                false,
                true,
                false,
                7u,
            ),
            11u,
        ), CapabilityAvailability.READY))
    }

    private fun callGrant(): CapabilityGrant = CapabilityGrant(
        capability = MobileDataCapability.CALLS,
        filter = CapabilityFilter.Calls(
            directions = setOf(CallDirection.INCOMING, CallDirection.OUTGOING, CallDirection.MISSED, CallDirection.REJECTED),
            counterpartyAccess = CallCounterpartyAccess.WITHHELD,
        ),
        onDemandEnabled = false,
        autoSendEnabled = true,
        agentMayRequest = false,
        policyRevision = 7u,
    )
}
