export const CONNECTION_FENCED = "CONNECTION_FENCED";
export const createPairedBinding = (binding) => Object.freeze({ ...binding });
class BridgeError extends Error {
    code;
    constructor(code) { super(code); this.code = code; }
}
const bindingKey = (binding) => `${binding.tenantId}\u0000${binding.humanPrincipalId}\u0000${binding.deviceId}\u0000${binding.pairingGeneration}`;
export class FakeBridge {
    #binding = null;
    #generation = 0n;
    #sessionOpen = false;
    #traceRows = [];
    #operations = new Map();
    #claims = new Map();
    #subscriptions = new Map();
    #events = new Map();
    #eventSequence = 0;
    #dropAcknowledgement = false;
    #policyRevoked = new Set();
    #onDemandCapture = null;
    async open(binding) {
        const wasSameBinding = this.#binding !== null && bindingKey(this.#binding) === bindingKey(binding);
        if (this.#sessionOpen)
            this.#fenceSession();
        this.#binding = createPairedBinding(binding);
        this.#generation += 1n;
        this.#sessionOpen = true;
        if (!wasSameBinding)
            this.#traceRows.push({ kind: "binding_accepted" });
        this.#traceRows.push({ kind: "session_opened" });
        return this.#session();
    }
    async reconnect(binding) { return this.open(binding); }
    #fenceSession() {
        if (this.#sessionOpen)
            this.#traceRows.push({ kind: "session_fenced" });
        this.#sessionOpen = false;
    }
    #session() {
        const generation = this.#generation;
        return Object.freeze({
            connectionGeneration: generation,
            sendControl: async (_wire) => {
                if (!this.#sessionOpen || generation !== this.#generation)
                    throw new BridgeError(CONNECTION_FENCED);
                this.#traceRows.push({ kind: "control_sent" });
            },
        });
    }
    setOnDemandCapture(capture) { this.#onDemandCapture = capture; }
    async queryNotifications(request) {
        this.#assertBinding(request.tenantId, request.humanPrincipalId, request.deviceId);
        const filter = validateNotificationFilter(request.filter);
        const previous = this.#operations.get(request.operationId);
        if (previous) {
            if (previous.sessionId !== request.sessionId)
                throw new BridgeError("OPERATION_IDENTITY_MISMATCH");
            return previous.result;
        }
        const result = request.mode === "on_demand" && this.#onDemandCapture
            ? Object.freeze(applyNotificationFilter(await this.#onDemandCapture(), filter))
            : Object.freeze([]);
        this.#operations.set(request.operationId, Object.freeze({ sessionId: request.sessionId, result }));
        this.#claims.set(request.operationId, (this.#claims.get(request.operationId) ?? 0) + 1);
        return result;
    }
    async subscribe(subscription) {
        if (!this.#sessionOpen || !this.#binding)
            throw new BridgeError(CONNECTION_FENCED);
        if (this.#binding.tenantId !== subscription.tenantId || this.#binding.humanPrincipalId !== subscription.humanPrincipalId || this.#binding.deviceId !== subscription.deviceId) {
            throw new BridgeError("SUBSCRIPTION_BINDING_MISMATCH");
        }
        this.#subscriptions.set(subscription.subscriptionId, Object.freeze({ ...subscription, filter: validateNotificationFilter(subscription.filter) }));
        return Object.freeze({ subscriptionId: subscription.subscriptionId });
    }
    async publishAutoSend(subscriptionId, record) {
        const subscription = this.#subscriptions.get(subscriptionId);
        if (!subscription)
            throw new BridgeError("SUBSCRIPTION_NOT_FOUND");
        if (this.#policyRevoked.has(subscription.deviceId))
            throw new BridgeError("POLICY_REVOKED");
        const eventId = `event-${++this.#eventSequence}`;
        const filtered = applyNotificationFilter([record], subscription.filter)[0];
        if (!filtered)
            throw new BridgeError("FILTER_EXCLUDED");
        this.#events.set(eventId, Object.freeze({ eventId, subscriptionId, record: Object.freeze({ ...filtered }), acknowledged: false }));
        this.#traceRows.push({ kind: "event_sent", eventId });
        return Object.freeze({ eventId });
    }
    dropNextAcknowledgement() { this.#dropAcknowledgement = true; }
    async acknowledgeEvent(subscriptionId, eventId) {
        const event = this.#events.get(eventId);
        if (!event || event.subscriptionId !== subscriptionId)
            return false;
        if (this.#dropAcknowledgement) {
            this.#dropAcknowledgement = false;
            return false;
        }
        this.#events.set(eventId, Object.freeze({ ...event, acknowledged: true }));
        this.#traceRows.push({ kind: "event_ack", eventId });
        return true;
    }
    async recoverUnacknowledged(subscriptionId) {
        return Object.freeze([...this.#events.values()]
            .filter((event) => event.subscriptionId === subscriptionId && !event.acknowledged)
            .map((event) => Object.freeze({ eventId: event.eventId })));
    }
    revokePolicy(deviceId) { this.#policyRevoked.add(deviceId); this.#traceRows.push({ kind: "policy_revoked" }); }
    operationClaims() {
        return Object.freeze([...this.#claims].map(([operationId, claims]) => Object.freeze({ operationId, claims })));
    }
    trace() { return Object.freeze(this.#traceRows.map((entry) => Object.freeze({ ...entry }))); }
    restart() {
        const copy = new FakeBridge();
        copy.#binding = this.#binding;
        copy.#generation = this.#generation;
        copy.#sessionOpen = this.#sessionOpen;
        copy.#traceRows = [...this.#traceRows];
        copy.#operations = new Map(this.#operations);
        copy.#claims = new Map(this.#claims);
        copy.#subscriptions = new Map(this.#subscriptions);
        copy.#events = new Map(this.#events);
        copy.#eventSequence = this.#eventSequence;
        copy.#policyRevoked = new Set(this.#policyRevoked);
        copy.#onDemandCapture = this.#onDemandCapture;
        return copy;
    }
    #assertBinding(tenantId, humanPrincipalId, deviceId) {
        if (!this.#sessionOpen || !this.#binding || bindingKey(this.#binding) !== `${tenantId}\u0000${humanPrincipalId}\u0000${deviceId}\u0000${this.#binding.pairingGeneration}`) {
            throw new BridgeError(CONNECTION_FENCED);
        }
    }
}
const compareCodePoints = (left, right) => {
    const a = Array.from(left).map((item) => item.codePointAt(0) ?? 0);
    const b = Array.from(right).map((item) => item.codePointAt(0) ?? 0);
    for (let index = 0; index < Math.min(a.length, b.length); index += 1) {
        if (a[index] !== b[index])
            return (a[index] ?? 0) - (b[index] ?? 0);
    }
    return a.length - b.length;
};
const validateNotificationFilter = (filter) => {
    if (filter === undefined)
        return undefined;
    const keys = Object.keys(filter).sort();
    if (keys.some((key) => key !== "fields" && key !== "packages") || keys.length === 0)
        throw new BridgeError("FILTER_INVALID");
    const packages = filter.packages === undefined ? undefined : [...filter.packages];
    const fields = filter.fields === undefined ? undefined : [...filter.fields];
    if (packages !== undefined && (packages.length < 1 || packages.some((value) => !/^[A-Za-z0-9][A-Za-z0-9._-]{0,254}$/.test(value))))
        throw new BridgeError("FILTER_INVALID");
    if (fields !== undefined && (fields.length < 1 || fields.length > 2 || fields.some((value) => value !== "metadata" && value !== "content")))
        throw new BridgeError("FILTER_INVALID");
    for (const values of [packages, fields]) {
        if (values && values.some((value, index) => index > 0 && compareCodePoints(values[index - 1], value) >= 0))
            throw new BridgeError("FILTER_INVALID");
    }
    return Object.freeze({ ...(packages ? { packages: Object.freeze(packages) } : {}), ...(fields ? { fields: Object.freeze(fields) } : {}) });
};
const applyNotificationFilter = (records, filter) => {
    if (!filter)
        return records.map((record) => Object.freeze({ ...record, title: null, content: null }));
    return records.filter((record) => filter.packages === undefined || filter.packages.includes(record.packageId)).map((record) => {
        const wantsContent = filter.fields?.includes("content") === true;
        return Object.freeze({ ...record, title: wantsContent ? record.title : null, content: wantsContent ? record.content : null });
    });
};
