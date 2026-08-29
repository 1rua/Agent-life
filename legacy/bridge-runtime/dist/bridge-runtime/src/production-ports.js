import { BridgeServiceError } from "../../bridge-contract/src/service-types.js";
export const BRIDGE_LEASE_COORDINATOR_PORT = "agent-life.bridge-lease-coordinator.v1";
export const PAIRING_TICKET_VERIFIER_PORT = "agent-life.pairing-ticket-verifier.v1";
const callable = (value) => typeof value === "function";
export const assertConnectedBridgeLeaseCoordinator = (value) => {
    if (typeof value !== "object" || value === null || Array.isArray(value))
        throw new BridgeServiceError("BRIDGE_LEASE_COORDINATOR_REQUIRED");
    const candidate = value;
    if (candidate.port !== BRIDGE_LEASE_COORDINATOR_PORT || candidate.status !== "connected"
        || !callable(candidate.acquire) || !callable(candidate.renew)
        || !callable(candidate.transact) || !callable(candidate.release)) {
        throw new BridgeServiceError(candidate.port === BRIDGE_LEASE_COORDINATOR_PORT
            ? "BRIDGE_LEASE_COORDINATOR_PENDING"
            : "BRIDGE_LEASE_COORDINATOR_REQUIRED");
    }
    return candidate;
};
export const assertConnectedPairingTicketVerifier = (value) => {
    if (typeof value !== "object" || value === null || Array.isArray(value))
        throw new BridgeServiceError("PAIRING_TICKET_VERIFIER_REQUIRED");
    const candidate = value;
    if (candidate.port !== PAIRING_TICKET_VERIFIER_PORT || !callable(candidate.verify)) {
        throw new BridgeServiceError("PAIRING_TICKET_VERIFIER_REQUIRED");
    }
    if (candidate.status !== "connected")
        throw new BridgeServiceError("PAIRING_TICKET_VERIFIER_PENDING");
    return candidate;
};
export const assertBridgeLease = (value, expected) => {
    if (typeof value !== "object" || value === null || Array.isArray(value))
        throw new BridgeServiceError("BRIDGE_LEASE_INVALID");
    const lease = value;
    if (lease.scope !== expected.scope || lease.ownerId !== expected.ownerId
        || typeof lease.fencingToken !== "bigint" || lease.fencingToken < 1n
        || typeof lease.expiresAtMs !== "number" || !Number.isFinite(lease.expiresAtMs)) {
        throw new BridgeServiceError("BRIDGE_LEASE_INVALID");
    }
    return Object.freeze({
        scope: lease.scope,
        ownerId: lease.ownerId,
        fencingToken: lease.fencingToken,
        expiresAtMs: lease.expiresAtMs,
    });
};
