package com.agentlife.tailnet.core

import com.agentlife.core.model.BridgeIdentity
import com.agentlife.core.model.EnrollmentTicket
import com.agentlife.core.model.PolicyAttestation
import com.agentlife.core.model.VerifiedPairingTransportBinding

/**
 * Tailnet owns the construction seam, while the value type remains immutable
 * and constructor-private in core-model. This keeps transports from minting a
 * binding from user-entered connection details.
 */
object VerifiedPairingTransportBindingFactory {
    fun mint(
        ticket: EnrollmentTicket,
        bridge: BridgeIdentity,
        policy: PolicyAttestation,
        expectedPairingGeneration: ULong,
        nowEpochSeconds: Long,
    ): VerifiedPairingTransportBinding = VerifiedPairingTransportBinding.mint(
        ticket = ticket,
        bridge = bridge,
        policy = policy,
        expectedPairingGeneration = expectedPairingGeneration,
        nowEpochSeconds = nowEpochSeconds,
    )
}
