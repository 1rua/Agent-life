import { BridgeServiceError, equalIdentity, freezeRecord, } from "./service-types.js";
import { applyNotificationFilter, validateNotificationFilter, validateNotificationRecord } from "./notification-store.js";
const cloneSession = (session) => freezeRecord({ ...session });
/** Process-local subscription/event ledger; no network or durable DB is implied. */
export class SubscriptionStore {
    #subscriptions = new Map();
    #events = new Map();
    #eventSequence = 0;
    subscribe(input) {
        if (typeof input.subscriptionId !== "string" || input.subscriptionId.length === 0)
            throw new BridgeServiceError("SUBSCRIPTION_ID_INVALID");
        const filter = validateNotificationFilter(input.filter);
        const existing = this.#subscriptions.get(input.subscriptionId);
        if (existing && !equalIdentity(existing.session, input.session))
            throw new BridgeServiceError("SUBSCRIPTION_BINDING_MISMATCH");
        this.#subscriptions.set(input.subscriptionId, {
            subscriptionId: input.subscriptionId,
            session: cloneSession(input.session),
            filter,
            lastSourceEpoch: 0n,
            lastCursor: -1n,
        });
        return freezeRecord({ subscriptionId: input.subscriptionId });
    }
    unsubscribe(subscriptionId, session) {
        const stored = this.#subscriptions.get(subscriptionId);
        if (!stored)
            return false;
        if (!equalIdentity(stored.session, session))
            throw new BridgeServiceError("SUBSCRIPTION_BINDING_MISMATCH");
        this.#subscriptions.delete(subscriptionId);
        return true;
    }
    get(subscriptionId, session) {
        const stored = this.#subscriptions.get(subscriptionId);
        if (!stored)
            throw new BridgeServiceError("SUBSCRIPTION_NOT_FOUND");
        if (!equalIdentity(stored.session, session))
            throw new BridgeServiceError("SUBSCRIPTION_BINDING_MISMATCH");
        return stored;
    }
    publish(subscriptionId, session, record) {
        const subscription = this.get(subscriptionId, session);
        validateNotificationRecord(record);
        if (record.sourceEpoch < subscription.lastSourceEpoch || (record.sourceEpoch === subscription.lastSourceEpoch && record.cursor <= subscription.lastCursor)) {
            throw new BridgeServiceError(record.sourceEpoch < subscription.lastSourceEpoch ? "EVENT_SOURCE_EPOCH_STALE" : "EVENT_CURSOR_REPLAY");
        }
        const filtered = applyNotificationFilter(record, subscription.filter);
        if (!filtered) {
            this.#subscriptions.set(subscriptionId, {
                ...subscription,
                lastSourceEpoch: record.sourceEpoch,
                lastCursor: record.cursor,
            });
            return null;
        }
        const event = freezeRecord({
            ...filtered,
            eventId: `event-${++this.#eventSequence}`,
            subscriptionId,
            binding: cloneSession(subscription.session),
        });
        this.#events.set(event.eventId, { event, acknowledged: false });
        this.#subscriptions.set(subscriptionId, {
            ...subscription,
            lastSourceEpoch: record.sourceEpoch,
            lastCursor: record.cursor,
        });
        return event;
    }
    acknowledge(input) {
        const subscription = this.get(input.subscriptionId, input.session);
        const stored = this.#events.get(input.eventId);
        if (!stored || stored.event.subscriptionId !== input.subscriptionId || !equalIdentity(stored.event.binding, subscription.session))
            throw new BridgeServiceError("EVENT_NOT_FOUND");
        if (stored.event.sourceEpoch !== input.sourceEpoch || stored.event.cursor !== input.cursor)
            throw new BridgeServiceError("EVENT_ACK_INVALID");
        this.#events.set(input.eventId, { event: stored.event, acknowledged: true });
        return stored.event;
    }
    pending(subscriptionId, session) {
        this.get(subscriptionId, session);
        return Object.freeze([...this.#events.values()]
            .filter((entry) => entry.event.subscriptionId === subscriptionId && !entry.acknowledged)
            .map((entry) => entry.event));
    }
    restart() {
        const next = new SubscriptionStore();
        next.#eventSequence = this.#eventSequence;
        for (const [subscriptionId, subscription] of this.#subscriptions)
            next.#subscriptions.set(subscriptionId, { ...subscription });
        for (const [eventId, event] of this.#events)
            next.#events.set(eventId, { ...event });
        return next;
    }
}
