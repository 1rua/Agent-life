import { assertDurableBridgeStore, DURABLE_BRIDGE_STORE_PORT, } from "../../bridge-contract/src/durable-store.js";
import { assertConnectedSqliteBridgeAdapter } from "../../bridge-contract/src/persistence.js";
import { NotificationService, } from "../../bridge-contract/src/notification-service.js";
import { BridgeServiceError } from "../../bridge-contract/src/service-types.js";
import { assertDurableOperationDispatcher, DurableOperationDispatcher, } from "./durable-operation-dispatcher.js";
import { DurableBridgeStateRepositories } from "./durable-state-repositories.js";
import { assertBridgeLease, assertConnectedBridgeLeaseCoordinator, assertConnectedPairingTicketVerifier, } from "./production-ports.js";
/**
 * Explicit Bridge composition root for the currently durable service slice.
 *
 * The durable store and operation dispatcher are mandatory at this boundary;
 * passing `NotificationStore` or `OperationDispatcher` as either one fails
 * closed. Pairing/notification/subscription stores are accepted only as
 * explicitly named process-local fixture seams and are surfaced in the
 * returned metadata. This is not a production deployment claim.
 */
export const createDurableBridgeComposition = async (options) => {
    if (!options || typeof options !== "object")
        throw new BridgeServiceError("BRIDGE_COMPOSITION_OPTIONS_INVALID");
    const durableStore = assertDurableBridgeStore(options.durableStore);
    const operations = options.operations === undefined
        ? await DurableOperationDispatcher.open({ store: durableStore })
        : assertDurableOperationDispatcher(options.operations);
    const notificationOptions = {
        pairing: options.pairing,
        operations,
        ...(options.notificationStore === undefined ? {} : { store: options.notificationStore }),
        ...(options.subscriptionStore === undefined ? {} : { subscriptions: options.subscriptionStore }),
        ...(options.authorize === undefined ? {} : { authorize: options.authorize }),
    };
    return Object.freeze({
        durableStore,
        operations,
        notifications: new NotificationService(notificationOptions),
        durableNamespaces: Object.freeze(["operation.claims"]),
        processLocalNamespaces: Object.freeze([
            "pairing.tickets",
            "pairing.bindings",
            "notification.records",
            "notification.positions",
            "subscription.bindings",
            "subscription.events",
        ]),
    });
};
const FENCED_COMPOSITION_SCOPE = "bridge.runtime";
class FencedDurableBridgeStore {
    port = DURABLE_BRIDGE_STORE_PORT;
    durability = "durable";
    #leases;
    #lease;
    constructor(leases, lease) {
        this.#leases = leases;
        this.#lease = lease;
    }
    transact(scope, work) {
        return this.#leases.transact(this.#lease, scope, work);
    }
    async renew() {
        const renewed = await this.#leases.renew(this.#lease);
        this.#lease = assertBridgeLease(renewed, { scope: this.#lease.scope, ownerId: this.#lease.ownerId });
        return this.#lease;
    }
    close() {
        return this.#leases.release(this.#lease);
    }
}
/**
 * Production-shaped, fail-closed composition boundary.
 *
 * Construction requires externally connected SQLite, ticket-verification and
 * lease ports. The repository does not ship any of those adapters and this
 * function therefore remains source-level composition evidence only.
 */
export const createFencedDurableBridgeComposition = async (options) => {
    if (!options || typeof options !== "object")
        throw new BridgeServiceError("BRIDGE_COMPOSITION_OPTIONS_INVALID");
    const persistence = assertConnectedSqliteBridgeAdapter(options.persistence);
    const leases = assertConnectedBridgeLeaseCoordinator(options.leases);
    const pairingVerifier = assertConnectedPairingTicketVerifier(options.pairingVerifier);
    if (typeof options.ownerId !== "string" || options.ownerId.length === 0)
        throw new BridgeServiceError("BRIDGE_LEASE_OWNER_INVALID");
    if (!Number.isSafeInteger(options.leaseTtlMs) || options.leaseTtlMs < 1)
        throw new BridgeServiceError("BRIDGE_LEASE_TTL_INVALID");
    const acquired = assertBridgeLease(await leases.acquire({
        scope: FENCED_COMPOSITION_SCOPE,
        ownerId: options.ownerId,
        ttlMs: options.leaseTtlMs,
    }), { scope: FENCED_COMPOSITION_SCOPE, ownerId: options.ownerId });
    const fencedStore = new FencedDurableBridgeStore(leases, acquired);
    try {
        const state = await DurableBridgeStateRepositories.open({
            store: fencedStore,
            ...(options.clock === undefined ? {} : { clock: options.clock }),
        });
        const operations = await DurableOperationDispatcher.open({ store: fencedStore });
        return Object.freeze({
            persistence,
            state,
            operations,
            pairing: Object.freeze({
                accept: async (candidate) => {
                    const verified = await pairingVerifier.verify(candidate);
                    return state.pairing.acceptVerified(verified);
                },
                current: state.pairing.current.bind(state.pairing),
            }),
            durableNamespaces: Object.freeze([
                "pairing.tickets",
                "pairing.bindings",
                "notification.records",
                "notification.positions",
                "subscription.bindings",
                "subscription.events",
                "operation.claims",
                "operation.replay-associations",
            ]),
            productionClaim: "source-seam-only",
            pendingDependencyLocks: Object.freeze(["MVP-DEP-BRIDGE", "MVP-DEP-TSNET"]),
            renewLease: fencedStore.renew.bind(fencedStore),
            close: fencedStore.close.bind(fencedStore),
        });
    }
    catch (caught) {
        await fencedStore.close().catch(() => undefined);
        throw caught;
    }
};
