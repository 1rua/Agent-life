import { BridgeServiceError } from "./service-types.js";
import { BRIDGE_PERSISTENCE_NAMESPACES } from "./persistence.js";
/** Stable identifier for the storage port consumed by a production Bridge. */
export const DURABLE_BRIDGE_STORE_PORT = "agent-life.bridge-store.v1";
/**
 * State partitions that the WP-06 runtime is allowed to persist through this
 * port. The names are deliberately closed so an adapter cannot silently add a
 * second, unreviewed persistence domain. Authorization grants/revisions and
 * notification cursor positions are separate partitions so revocation and
 * cursor fencing can be committed atomically with their owning records.
 */
export const BRIDGE_STORE_NAMESPACES = BRIDGE_PERSISTENCE_NAMESPACES;
const TRANSACTION_SCOPE = /^[a-z0-9][a-z0-9._/-]{0,127}$/;
/** Runtime guard used by Bridge composition roots before accepting a store. */
export const isDurableBridgeStore = (value) => {
    if (typeof value !== "object" || value === null || Array.isArray(value))
        return false;
    const candidate = value;
    return candidate.port === DURABLE_BRIDGE_STORE_PORT
        && candidate.durability === "durable"
        && typeof candidate.transact === "function";
};
/**
 * Fail-closed composition guard. Process-local stores intentionally do not
 * carry the durable marker and therefore cannot be passed as production
 * persistence by accident.
 */
export const assertDurableBridgeStore = (value) => {
    if (!isDurableBridgeStore(value))
        throw new BridgeServiceError("DURABLE_STORE_REQUIRED");
    return value;
};
/**
 * Shared entry point for runtime code. Keeping scope validation here ensures
 * every caller uses the same port and makes an accidental empty/audit-less
 * transaction visible as a contract error.
 */
export const runDurableBridgeTransaction = (store, scope, work) => {
    if (typeof scope !== "string" || !TRANSACTION_SCOPE.test(scope)) {
        return Promise.reject(new BridgeServiceError("TRANSACTION_SCOPE_INVALID"));
    }
    if (typeof work !== "function") {
        return Promise.reject(new BridgeServiceError("TRANSACTION_WORK_INVALID"));
    }
    // Normalize adapter/marker failures to a rejected promise as well. Callers
    // can therefore handle invalid composition roots with one async path.
    return Promise.resolve().then(() => assertDurableBridgeStore(store).transact(scope, work));
};
