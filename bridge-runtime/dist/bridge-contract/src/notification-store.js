import { BridgeServiceError, compareCodePoints, freezeRecord, PACKAGE_NAME, } from "./service-types.js";
const clone = (record) => freezeRecord({ ...record });
const recordKey = (record) => `${record.sourceEpoch}\u0000${record.cursor}`;
/** Validate the closed capability-specific notification filter. */
export const validateNotificationFilter = (input) => {
    if (input === undefined)
        return undefined;
    if (typeof input !== "object" || input === null || Array.isArray(input))
        throw new BridgeServiceError("FILTER_INVALID");
    const keys = Object.keys(input);
    if (keys.some((key) => key !== "packages" && key !== "fields") || keys.length === 0)
        throw new BridgeServiceError("FILTER_INVALID");
    let packages;
    if (input.packages !== undefined) {
        if (!Array.isArray(input.packages) || input.packages.length < 1)
            throw new BridgeServiceError("FILTER_INVALID");
        packages = [...input.packages];
        if (packages.some((value) => typeof value !== "string" || !PACKAGE_NAME.test(value)))
            throw new BridgeServiceError("FILTER_INVALID");
        if (new Set(packages).size !== packages.length)
            throw new BridgeServiceError("FILTER_INVALID");
        for (let index = 1; index < packages.length; index += 1) {
            if (compareCodePoints(packages[index - 1], packages[index]) >= 0)
                throw new BridgeServiceError("FILTER_INVALID");
        }
    }
    let fields;
    if (input.fields !== undefined) {
        if (!Array.isArray(input.fields) || input.fields.length < 1 || input.fields.length > 2)
            throw new BridgeServiceError("FILTER_INVALID");
        fields = [...input.fields];
        if (fields.some((value) => value !== "metadata" && value !== "content"))
            throw new BridgeServiceError("FILTER_INVALID");
        if (new Set(fields).size !== fields.length)
            throw new BridgeServiceError("FILTER_INVALID");
        for (let index = 1; index < fields.length; index += 1) {
            if (compareCodePoints(fields[index - 1], fields[index]) >= 0)
                throw new BridgeServiceError("FILTER_INVALID");
        }
    }
    return freezeRecord({
        ...(packages ? { packages: Object.freeze([...packages]) } : {}),
        ...(fields ? { fields: Object.freeze([...fields]) } : {}),
    });
};
/** Apply the closed notification filter before an egress or outbox boundary. */
export const applyNotificationFilter = (record, input) => {
    const filter = validateNotificationFilter(input);
    if (filter?.packages !== undefined && (record.packageId === null || !filter.packages.includes(record.packageId)))
        return null;
    const wantsContent = filter?.fields?.includes("content") === true;
    return clone({ ...record, title: wantsContent ? record.title : null, content: wantsContent ? record.content : null });
};
/** Runtime closed-record check shared by device ingress and subscription egress. */
export const validateNotificationRecord = (record) => {
    if (typeof record !== "object" || record === null || Array.isArray(record))
        throw new BridgeServiceError("NOTIFICATION_RECORD_INVALID");
    const allowedKeys = new Set(["kind", "recordId", "packageId", "title", "content", "sourceEpoch", "cursor", "captureRevision"]);
    if (Object.keys(record).some((key) => !allowedKeys.has(key)))
        throw new BridgeServiceError("NOTIFICATION_RECORD_INVALID");
    if (!["upsert", "delete_tombstone", "loss_marker"].includes(record.kind))
        throw new BridgeServiceError("NOTIFICATION_RECORD_INVALID");
    if (typeof record.recordId !== "string" || record.recordId.length === 0)
        throw new BridgeServiceError("NOTIFICATION_RECORD_INVALID");
    if (record.packageId !== null && (typeof record.packageId !== "string" || !PACKAGE_NAME.test(record.packageId)))
        throw new BridgeServiceError("NOTIFICATION_RECORD_INVALID");
    for (const value of [record.sourceEpoch, record.cursor, record.captureRevision]) {
        if (typeof value !== "bigint" || value < 0n)
            throw new BridgeServiceError("NOTIFICATION_RECORD_INVALID");
    }
    if ((record.title !== null && typeof record.title !== "string") || (record.content !== null && typeof record.content !== "string")) {
        throw new BridgeServiceError("NOTIFICATION_RECORD_INVALID");
    }
    if (record.kind === "delete_tombstone" && (record.packageId === null || record.title !== null || record.content !== null)) {
        throw new BridgeServiceError("NOTIFICATION_RECORD_INVALID");
    }
    if (record.kind === "upsert" && record.packageId === null)
        throw new BridgeServiceError("NOTIFICATION_RECORD_INVALID");
    if (record.kind === "loss_marker" && (record.packageId !== null || record.title !== null || record.content !== null)) {
        throw new BridgeServiceError("NOTIFICATION_RECORD_INVALID");
    }
};
const comparePosition = (left, right) => {
    if (left.sourceEpoch !== right.sourceEpoch)
        return left.sourceEpoch < right.sourceEpoch ? -1 : 1;
    if (left.cursor !== right.cursor)
        return left.cursor < right.cursor ? -1 : 1;
    return 0;
};
/**
 * Process-local notification ledger used by the WP-06 contract service.
 * It validates source epoch/cursor ordering but intentionally does not claim
 * crash-safe or database durability; the durable implementation is a later
 * Bridge runtime seam.
 */
export class NotificationStore {
    #records = new Map();
    #positions = new Map();
    append(deviceId, record) {
        validateNotificationRecord(record);
        const byRecord = this.#records.get(deviceId) ?? new Map();
        const existing = byRecord.get(record.recordId);
        if (existing) {
            if (recordKey(existing) !== recordKey(record) || JSON.stringify(this.#json(existing)) !== JSON.stringify(this.#json(record))) {
                throw new BridgeServiceError("NOTIFICATION_RECORD_CONFLICT");
            }
            return false;
        }
        const previous = this.#positions.get(deviceId);
        if (previous && comparePosition(record, previous) <= 0)
            throw new BridgeServiceError("NOTIFICATION_CURSOR_REPLAY");
        byRecord.set(record.recordId, clone(record));
        this.#records.set(deviceId, byRecord);
        this.#positions.set(deviceId, clone(record));
        return true;
    }
    read(deviceId, limit, filter) {
        if (!Number.isSafeInteger(limit) || limit < 1 || limit > 100)
            throw new BridgeServiceError("LIMIT_INVALID");
        validateNotificationFilter(filter);
        const records = [...(this.#records.get(deviceId)?.values() ?? [])].sort(comparePosition);
        const bounded = [];
        for (const current of records) {
            const filtered = applyNotificationFilter(current, filter);
            if (filtered)
                bounded.push(filtered);
            if (bounded.length >= limit)
                break;
        }
        return Object.freeze(bounded);
    }
    latest(deviceId) {
        const record = this.#positions.get(deviceId);
        return record ? clone(record) : null;
    }
    #json(record) {
        return {
            kind: record.kind,
            recordId: record.recordId,
            packageId: record.packageId,
            title: record.title,
            content: record.content,
            sourceEpoch: record.sourceEpoch.toString(),
            cursor: record.cursor.toString(),
            captureRevision: record.captureRevision.toString(),
        };
    }
}
