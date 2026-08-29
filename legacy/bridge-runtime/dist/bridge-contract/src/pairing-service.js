import { BridgeServiceError, assertNonEmpty, freezeRecord, } from "./service-types.js";
const pairKey = (value) => [value.tenantId, value.humanPrincipalId, value.deviceId].join("\u0000");
const sameTicketPayload = (left, right) => left.ticketId === right.ticketId
    && left.tenantId === right.tenantId
    && left.humanPrincipalId === right.humanPrincipalId
    && left.deviceId === right.deviceId
    && left.bridgeFingerprint === right.bridgeFingerprint
    && left.pairingGeneration === right.pairingGeneration
    && left.policyAttestationRevision === right.policyAttestationRevision
    && left.issuedAtMs === right.issuedAtMs
    && left.expiresAtMs === right.expiresAtMs;
/**
 * Single-use enrollment ticket verifier for the contract service.
 *
 * This is deliberately a process-local seam for WP-06 tests. A production
 * Bridge wires the same interface to its signed-ticket verifier and durable
 * ticket ledger; this class must not be read as a database implementation.
 */
export class PairingService {
    #clock;
    #ticketTtlMs;
    #ticketId;
    #tickets = new Map();
    #current = new Map();
    #sequence = 0;
    constructor(options = {}) {
        this.#clock = options.clock ?? (() => Date.now());
        this.#ticketTtlMs = options.ticketTtlMs ?? 5 * 60_000;
        if (!Number.isSafeInteger(this.#ticketTtlMs) || this.#ticketTtlMs <= 0)
            throw new BridgeServiceError("PAIRING_TTL_INVALID");
        this.#ticketId = options.ticketId ?? (() => `ticket-${++this.#sequence}`);
    }
    issueTicket(input) {
        this.#validateInput(input);
        const issuedAtMs = this.#clock();
        if (!Number.isFinite(issuedAtMs))
            throw new BridgeServiceError("PAIRING_CLOCK_INVALID");
        const ticket = freezeRecord({
            ...input,
            ticketId: this.#ticketId(),
            issuedAtMs,
            expiresAtMs: issuedAtMs + this.#ticketTtlMs,
        });
        if (this.#tickets.has(ticket.ticketId))
            throw new BridgeServiceError("PAIRING_TICKET_ID_REUSED");
        this.#tickets.set(ticket.ticketId, { ticket, consumed: false });
        return ticket;
    }
    acceptTicket(candidate) {
        const state = this.#tickets.get(candidate.ticketId);
        if (!state || !sameTicketPayload(state.ticket, candidate))
            throw new BridgeServiceError("PAIRING_TICKET_TAMPERED");
        if (state.consumed)
            throw new BridgeServiceError("PAIRING_TICKET_REPLAY");
        if (this.#clock() >= state.ticket.expiresAtMs)
            throw new BridgeServiceError("PAIRING_TICKET_EXPIRED");
        const previous = this.#current.get(pairKey(state.ticket));
        if (previous && state.ticket.bridgeFingerprint !== previous.bridgeFingerprint) {
            throw new BridgeServiceError("PAIRING_BINDING_MISMATCH");
        }
        if (previous && state.ticket.pairingGeneration < previous.pairingGeneration) {
            throw new BridgeServiceError("PAIRING_GENERATION_ROLLBACK");
        }
        if (previous && state.ticket.pairingGeneration > previous.pairingGeneration + 1n) {
            throw new BridgeServiceError("PAIRING_GENERATION_GAP");
        }
        state.consumed = true;
        const accepted = freezeRecord({
            tenantId: state.ticket.tenantId,
            humanPrincipalId: state.ticket.humanPrincipalId,
            deviceId: state.ticket.deviceId,
            bridgeFingerprint: state.ticket.bridgeFingerprint,
            pairingGeneration: state.ticket.pairingGeneration,
            policyAttestationRevision: state.ticket.policyAttestationRevision,
        });
        this.#current.set(pairKey(accepted), accepted);
        return accepted;
    }
    current(identity) {
        return this.#current.get(pairKey({
            tenantId: identity.tenantId,
            humanPrincipalId: identity.humanPrincipalId,
            deviceId: identity.deviceId,
            bridgeFingerprint: "",
            pairingGeneration: 0n,
            policyAttestationRevision: 0n,
        })) ?? null;
    }
    #validateInput(input) {
        for (const value of [input.tenantId, input.humanPrincipalId, input.deviceId, input.bridgeFingerprint])
            assertNonEmpty(value, "PAIRING_ID_INVALID");
        if (typeof input.pairingGeneration !== "bigint" || input.pairingGeneration < 0n)
            throw new BridgeServiceError("PAIRING_GENERATION_INVALID");
        if (typeof input.policyAttestationRevision !== "bigint" || input.policyAttestationRevision < 0n)
            throw new BridgeServiceError("PAIRING_REVISION_INVALID");
    }
}
export { BridgeServiceError } from "./service-types.js";
