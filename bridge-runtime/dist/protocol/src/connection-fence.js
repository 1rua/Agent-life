const connectionLeaseBrand = Symbol("connection-lease");
const validateLeasePersistenceId = (value) => {
    const decoded = Buffer.from(value, "base64url");
    if (decoded.byteLength !== 32 || Buffer.from(decoded).toString("base64url") !== value)
        throw new Error("INTEGRITY_FAILED");
    return value;
};
/** A deterministic, restartable reference implementation used to execute the
 * fencing contract without pretending to be a production database. */
export class DeterministicConnectionFenceStore {
    #key;
    #state;
    #leases = new WeakMap();
    #leaseIds = new WeakMap();
    #leaseIdSource;
    constructor(key, snapshot, options = {}) {
        this.#key = Object.freeze({ ...key });
        this.#state = Object.freeze({ ...snapshot });
        this.#leaseIdSource = options.leaseIdSource ?? (() => randomBytes(32).toString("base64url"));
    }
    snapshot() {
        return Object.freeze({ ...this.#state });
    }
    async allocateNext(key, connectionId, transportProfileId) {
        if (key.credentialId !== this.#key.credentialId
            || key.pairingGeneration !== this.#key.pairingGeneration
            || this.#state.generation === 18446744073709551615n
            || this.#state.fenceRevision === 18446744073709551615n) {
            return { kind: "exhausted" };
        }
        const leasePersistenceId = validateLeasePersistenceId(this.#leaseIdSource());
        const next = Object.freeze({
            generation: this.#state.generation + 1n,
            fenceRevision: this.#state.fenceRevision + 1n,
            connectionId,
            transportProfileId,
            leasePersistenceId,
        });
        this.#state = next;
        const lease = this.#mintLease(next);
        this.#leases.set(lease, next);
        return {
            kind: "allocated",
            allocation: Object.freeze({ generation: next.generation, fenceRevision: next.fenceRevision, lease }),
        };
    }
    #mintLease(snapshot) {
        const lease = Object.freeze({ [connectionLeaseBrand]: true });
        this.#leases.set(lease, snapshot);
        const persistedId = validateLeasePersistenceId(snapshot.leasePersistenceId ?? this.#leaseIdSource());
        this.#leaseIds.set(lease, persistedId);
        return lease;
    }
    restoreCurrentLease() {
        if (this.#state.connectionId === null || this.#state.transportProfileId === null)
            throw new Error("CONNECTION_FENCED");
        return this.#mintLease(this.#state);
    }
    persistenceId(lease) {
        return this.#leaseIds.get(lease) ?? null;
    }
    async inspect(lease) {
        const allocation = this.#leases.get(lease);
        return allocation !== undefined
            && allocation.generation === this.#state.generation
            && allocation.fenceRevision === this.#state.fenceRevision
            && allocation.connectionId === this.#state.connectionId
            && allocation.transportProfileId === this.#state.transportProfileId
            ? { kind: "current", generation: allocation.generation, fenceRevision: allocation.fenceRevision }
            : { kind: "fenced" };
    }
}
export async function allocateConnectionGeneration(store, key, connectionId, transportProfileId) {
    const decision = await store.allocateNext(key, connectionId, transportProfileId);
    return decision.kind === "allocated"
        ? { ok: true, allocation: decision.allocation }
        : { ok: false, error: "CONNECTION_FENCED" };
}
export function fenceConnection(inspection, allocatedGeneration, headerGeneration) {
    if (inspection.kind !== "current"
        || inspection.generation !== allocatedGeneration
        || headerGeneration !== allocatedGeneration) {
        return { ok: false, error: "CONNECTION_FENCED" };
    }
    return { ok: true };
}
/// <reference types="node" />
import { randomBytes } from "node:crypto";
