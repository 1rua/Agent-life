import { BRIDGE_STORE_NAMESPACES, } from "../../bridge-contract/src/durable-store.js";
import { assertConnectedSqliteBridgeAdapter, } from "../../bridge-contract/src/persistence.js";
import { BridgeServiceError, compareCodePoints } from "../../bridge-contract/src/service-types.js";
const namespaceSet = new Set(BRIDGE_STORE_NAMESPACES);
const drillError = (code) => new BridgeServiceError(code);
const assertVersion = (value) => {
    if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 0) {
        throw drillError("BACKUP_RESTORE_SCHEMA_INVALID");
    }
    return value;
};
const canonical = (value, seen = new WeakSet()) => {
    if (value === null || typeof value === "string" || typeof value === "boolean")
        return JSON.stringify(value);
    if (typeof value === "number" && Number.isFinite(value))
        return JSON.stringify(Object.is(value, -0) ? 0 : value);
    if (typeof value !== "object" || value === null || seen.has(value))
        throw drillError("BACKUP_RESTORE_STATE_INVALID");
    seen.add(value);
    let encoded;
    if (Array.isArray(value)) {
        encoded = `[${value.map((item) => canonical(item, seen)).join(",")}]`;
    }
    else {
        const prototype = Object.getPrototypeOf(value);
        if (prototype !== Object.prototype && prototype !== null)
            throw drillError("BACKUP_RESTORE_STATE_INVALID");
        encoded = `{${Object.keys(value).sort(compareCodePoints).map((key) => `${JSON.stringify(key)}:${canonical(value[key], seen)}`).join(",")}}`;
    }
    seen.delete(value);
    return encoded;
};
const scan = async (adapter, scope, namespaces) => adapter.transact(scope, async (transaction) => {
    const snapshot = new Map();
    for (const namespace of namespaces) {
        const entries = [...await transaction.scan(namespace)]
            .sort((left, right) => compareCodePoints(left.key, right.key));
        if (entries.some((entry, index) => index > 0 && entries[index - 1].key === entry.key)) {
            throw drillError("BACKUP_RESTORE_STATE_INVALID");
        }
        snapshot.set(namespace, Object.freeze(entries));
    }
    return snapshot;
});
const assertSameSnapshot = (before, restored, namespaces) => {
    for (const namespace of namespaces) {
        const sourceEntries = before.get(namespace) ?? [];
        const restoredEntries = restored.get(namespace) ?? [];
        if (sourceEntries.length !== restoredEntries.length)
            throw drillError("BACKUP_RESTORE_CONTENT_MISMATCH");
        for (let index = 0; index < sourceEntries.length; index += 1) {
            const sourceEntry = sourceEntries[index];
            const restoredEntry = restoredEntries[index];
            if (sourceEntry.key !== restoredEntry.key || canonical(sourceEntry.value) !== canonical(restoredEntry.value)) {
                throw drillError("BACKUP_RESTORE_CONTENT_MISMATCH");
            }
        }
    }
};
/**
 * Executes an offline/lease-exclusive backup and restore verification drill.
 *
 * The external adapters own database I/O and isolation. This function never
 * opens SQLite itself; it verifies their evidence against a separate restore
 * target and compares only the explicitly implemented durable namespaces.
 */
export const runBridgeBackupRestoreDrill = async (options) => {
    if (!options || typeof options !== "object")
        throw drillError("BACKUP_RESTORE_OPTIONS_INVALID");
    const source = assertConnectedSqliteBridgeAdapter(options.source);
    const restoreTarget = assertConnectedSqliteBridgeAdapter(options.restoreTarget);
    if (source.databasePath === restoreTarget.databasePath)
        throw drillError("BACKUP_RESTORE_TARGET_NOT_ISOLATED");
    if (typeof options.destination !== "string" || options.destination.length === 0
        || options.destination === source.databasePath || options.destination === restoreTarget.databasePath) {
        throw drillError("BACKUP_RESTORE_DESTINATION_INVALID");
    }
    if (!Array.isArray(options.namespaces) || options.namespaces.length === 0
        || new Set(options.namespaces).size !== options.namespaces.length
        || options.namespaces.some((namespace) => typeof namespace !== "string" || !namespaceSet.has(namespace))) {
        throw drillError("BACKUP_RESTORE_NAMESPACES_INVALID");
    }
    const namespaces = Object.freeze([...options.namespaces]);
    const sourceVersion = assertVersion(await source.schemaVersion());
    const before = await scan(source, "bridge.backup.source-snapshot", namespaces);
    const artifact = await source.backup(options.destination);
    if (!artifact || artifact.artifact !== "backup" || artifact.path !== options.destination
        || assertVersion(artifact.schemaVersion) !== sourceVersion
        || (artifact.digest !== undefined && (typeof artifact.digest !== "string" || artifact.digest.length === 0))) {
        throw drillError("BACKUP_ARTIFACT_INVALID");
    }
    const restore = await restoreTarget.restore(artifact.path);
    if (!restore || restore.restored !== true || assertVersion(restore.schemaVersion) !== sourceVersion
        || (artifact.digest !== undefined && restore.digest !== artifact.digest)
        || (artifact.digest === undefined && restore.digest !== undefined)) {
        throw drillError("BACKUP_RESTORE_EVIDENCE_MISMATCH");
    }
    const recovery = await restoreTarget.recover();
    if (!recovery || recovery.recovered !== true || assertVersion(recovery.schemaVersion) !== sourceVersion) {
        throw drillError("BACKUP_RESTORE_RECOVERY_INVALID");
    }
    const targetVersion = assertVersion(await restoreTarget.schemaVersion());
    if (targetVersion !== sourceVersion)
        throw drillError("BACKUP_RESTORE_SCHEMA_MISMATCH");
    const restored = await scan(restoreTarget, "bridge.backup.restored-snapshot", namespaces);
    assertSameSnapshot(before, restored, namespaces);
    return Object.freeze({
        verified: true,
        schemaVersion: sourceVersion,
        ...(artifact.digest === undefined ? {} : { digest: artifact.digest }),
        namespaces: Object.freeze(namespaces.map((namespace) => Object.freeze({
            namespace,
            entries: before.get(namespace)?.length ?? 0,
        }))),
    });
};
