package com.agentlife.core.model

/**
 * The only mobile transport boundary. A caller supplies a server-verified
 * binding, never an address, URL, route or generic network handle.
 */
interface PairedBridgeTransport {
    suspend fun open(binding: VerifiedPairingTransportBinding): BridgeSession
    suspend fun close(reason: TransportCloseReason)
}

interface BridgeSession {
    val connectionGeneration: ULong

    suspend fun sendControl(canonicalWire: ByteArray)
    suspend fun receiveControl(): ByteArray
}

/** A binding minted only after ticket, identity, policy and generation checks. */
class VerifiedPairingTransportBinding private constructor(
    val deviceId: String,
    val bridgeIdentity: String,
    val pairingGeneration: ULong,
    val policyAttestationRevision: ULong,
    val enrollmentTicketId: String,
) {
    init {
        require(deviceId.isNotBlank()) { "device id must not be blank" }
        require(bridgeIdentity.isNotBlank()) { "Bridge identity must not be blank" }
        require(enrollmentTicketId.isNotBlank()) { "enrollment ticket id must not be blank" }
    }

    companion object {
        /**
         * Mints a binding from verified, server-issued material. All values are
         * immutable; an endpoint cannot be supplied by the app or model.
         */
        fun mint(
            ticket: EnrollmentTicket,
            bridge: BridgeIdentity,
            policy: PolicyAttestation,
            expectedPairingGeneration: ULong,
            nowEpochSeconds: Long,
        ): VerifiedPairingTransportBinding {
            require(!ticket.used) { "enrollment ticket already used" }
            require(ticket.expiresAtEpochSeconds >= nowEpochSeconds) { "enrollment ticket expired" }
            require(ticket.bridgeIdentity == bridge.fingerprint) { "Bridge identity mismatch" }
            require(ticket.pairingGeneration == expectedPairingGeneration) { "pairing generation mismatch" }
            require(policy.revision >= ticket.minimumPolicyRevision) { "policy attestation is stale" }
            return VerifiedPairingTransportBinding(
                deviceId = ticket.deviceId,
                bridgeIdentity = bridge.fingerprint,
                pairingGeneration = expectedPairingGeneration,
                policyAttestationRevision = policy.revision,
                enrollmentTicketId = ticket.id,
            )
        }
    }
}

data class EnrollmentTicket(
    val id: String,
    val deviceId: String,
    val bridgeIdentity: String,
    val pairingGeneration: ULong,
    val minimumPolicyRevision: ULong,
    val expiresAtEpochSeconds: Long,
    val used: Boolean = false,
)

data class BridgeIdentity(val fingerprint: String)

data class PolicyAttestation(val revision: ULong, val digest: String)

enum class TransportCloseReason {
    USER_REQUEST,
    NETWORK_CHANGED,
    PROCESS_STOPPED,
    STALE_GENERATION,
    POLICY_REVOKED,
    FAILURE,
}

enum class TransportPath {
    DIRECT,
    RELAY,
    CONTROL_UNREACHABLE,
    APPROVAL_REQUIRED,
}

sealed interface PairingTransportStatus {
    data object Unpaired : PairingTransportStatus

    data class Pairing(val attempt: Int) : PairingTransportStatus

    data class Connecting(val generation: ULong, val attempt: Int) : PairingTransportStatus

    data class Connected(val generation: ULong, val path: TransportPath) : PairingTransportStatus

    data class Reconnecting(
        val generation: ULong,
        val attempt: Int,
        val cause: TransportCloseReason,
    ) : PairingTransportStatus

    data class Closed(val reason: TransportCloseReason) : PairingTransportStatus

    data class Failed(val reason: TransportFailure) : PairingTransportStatus
}

enum class TransportFailure {
    INVALID_BINDING,
    STALE_GENERATION,
    CONTROL_UNREACHABLE,
    APPROVAL_REQUIRED,
    NETWORK_BLOCKED,
}
