import { BridgeServiceError } from "./service-types.js";
/** Stable identity for the SQLite-backed Bridge adapter boundary. */
export const SQLITE_BRIDGE_ADAPTER_PORT = "agent-life.bridge-sqlite-adapter.v1";
/**
 * Closed state partitions that a production persistence adapter may expose.
 * The list is deliberately shared with the generic durable-store contract so
 * an adapter cannot add a second, unreviewed persistence domain.
 */
export const BRIDGE_PERSISTENCE_NAMESPACES = Object.freeze([
    "pairing.tickets",
    "pairing.bindings",
    "authorization.grants",
    "authorization.revisions",
    "notification.records",
    "notification.positions",
    "subscription.bindings",
    "subscription.events",
    "operation.claims",
    "operation.replay-associations",
    "assistant.metadata",
]);
const callable = (value) => typeof value === "function";
/** Runtime guard for composition roots. It never turns a port into a driver. */
export const isSqliteBridgeAdapterPort = (value) => {
    if (typeof value !== "object" || value === null || Array.isArray(value))
        return false;
    const candidate = value;
    return candidate.port === SQLITE_BRIDGE_ADAPTER_PORT
        && candidate.backend === "sqlite"
        && candidate.driver === "external"
        && (candidate.status === "external-driver-required" || candidate.status === "connected")
        && typeof candidate.databasePath === "string"
        && candidate.databasePath.length > 0
        && callable(candidate.transact)
        && callable(candidate.schemaVersion)
        && callable(candidate.runMigration)
        && callable(candidate.backup)
        && callable(candidate.restore)
        && callable(candidate.recover);
};
export const assertSqliteBridgeAdapterPort = (value) => {
    if (!isSqliteBridgeAdapterPort(value)) {
        throw new BridgeServiceError("SQLITE_BRIDGE_ADAPTER_REQUIRED");
    }
    return value;
};
export const isBridgePersistencePort = isSqliteBridgeAdapterPort;
export const assertBridgePersistencePort = assertSqliteBridgeAdapterPort;
/** Production composition guard; source fakes intentionally remain pending. */
export const assertConnectedSqliteBridgeAdapter = (value) => {
    const adapter = assertSqliteBridgeAdapterPort(value);
    if (adapter.status !== "connected")
        throw new BridgeServiceError("SQLITE_DRIVER_PENDING");
    return adapter;
};
