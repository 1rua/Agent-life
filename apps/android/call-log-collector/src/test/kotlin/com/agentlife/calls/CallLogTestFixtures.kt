package com.agentlife.calls

import com.agentlife.capability.AgentDataRequest
import com.agentlife.capability.AgentRequestAuthorization
import com.agentlife.capability.AuthorizedAutoSendSubscription
import com.agentlife.capability.AuthorizedOnDemandRequest
import com.agentlife.capability.AuthorizedReadScope
import com.agentlife.capability.CallCounterpartyAccess
import com.agentlife.capability.CallDirection
import com.agentlife.capability.CallHistoryPolicy
import com.agentlife.capability.CallLogSyncInterval
import com.agentlife.capability.CallNumberPresentation
import com.agentlife.capability.CapabilityAvailability
import com.agentlife.capability.CapabilityFilter
import com.agentlife.capability.CapabilityGrant
import com.agentlife.capability.DataSyncMode
import com.agentlife.capability.DefaultAgentRequestAuthorizer
import com.agentlife.capability.MobileDataCapability
import com.agentlife.capability.requireAutoSendScope
import com.agentlife.capability.requireReadScope

internal object CallLogTestFixtures {
    fun policy(
        revision: ULong = 7u,
        directions: Set<CallDirection> = setOf(CallDirection.INCOMING, CallDirection.OUTGOING),
        counterpartyAccess: CallCounterpartyAccess = CallCounterpartyAccess.WITHHELD,
    ) = CallLogLocalPolicy(
        historyPolicy = CallHistoryPolicy(fromEpochMs = 100L, maxRecords = 10),
        directions = directions,
        counterpartyAccess = counterpartyAccess,
        syncInterval = CallLogSyncInterval.MINUTES_30,
        onDemandEnabled = true,
        autoSendEnabled = true,
        agentMayRequest = true,
        policyRevision = revision,
    )

    fun authority(policy: CallLogLocalPolicy = policy()): PersistentCallLogSettingsAuthority =
        PersistentCallLogSettingsAuthority(InMemoryCallLogSettingsPersistence()).also { authority ->
            authority.beginRevocation(1u, policy.policyRevision, policy, authorizationRevision = 1u)
            authority.commitRevocationTarget()
        }

    fun readScope(policy: CallLogLocalPolicy = policy()): AuthorizedReadScope =
        authorized(policy, DataSyncMode.ON_DEMAND) { access ->
            (access as AuthorizedOnDemandRequest).requireReadScope(MobileDataCapability.CALLS)
        }

    fun autoScope(policy: CallLogLocalPolicy = policy()) =
        authorized(policy, DataSyncMode.AUTO_SEND) { access ->
            (access as AuthorizedAutoSendSubscription).requireAutoSendScope(MobileDataCapability.CALLS)
        }

    fun row(
        id: Long,
        startedAt: Long,
        direction: CallDirection = CallDirection.INCOMING,
        number: String? = "+8613800000000",
        presentation: CallNumberPresentation = CallNumberPresentation.ALLOWED,
    ) = CallLogRow(id, direction, startedAt, 2L, number, presentation)

    fun reader(query: (CallLogQuery) -> List<CallLogRow>): CallLogReader = object : CallLogReader {
        override fun query(request: CallLogQuery): List<CallLogRow> = query(request)
        override fun probe() = Unit
    }

    private fun <T> authorized(
        policy: CallLogLocalPolicy,
        mode: DataSyncMode,
        select: (Any) -> T,
    ): T {
        val filter = policy.filter()
        val request = AgentDataRequest("calls-$mode", MobileDataCapability.CALLS, mode, filter, policy.policyRevision)
        val grant = CapabilityGrant(MobileDataCapability.CALLS, filter, true, true, true, policy.policyRevision)
        val authorization = DefaultAgentRequestAuthorizer().authorize(request, grant, CapabilityAvailability.READY)
            as AgentRequestAuthorization.Allowed
        return select(authorization.access)
    }
}
