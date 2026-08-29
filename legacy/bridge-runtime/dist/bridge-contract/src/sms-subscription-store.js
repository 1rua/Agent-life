import { BridgeServiceError, equalIdentity, freezeRecord, } from "./service-types.js";
import { validateSmsRecord } from "./sms-store.js";
const cloneSession = (session) => freezeRecord({ ...session });
const cloneRecord = (record) => freezeRecord({ ...record });
/** Process-local SMS subscription and ACK ledger; no transport is exposed. */
export class SmsSubscriptionStore {
    #subscriptions = new Map();
    #events = new Map();
    #eventSequence = 0;
    subscribe(input) {
        if (typeof input.subscriptionId !== "string" || input.subscriptionId.length === 0)
            throw new BridgeServiceError("SUBSCRIPTION_ID_INVALID");
        const existing = this.#subscriptions.get(input.subscriptionId);
        if (existing && !equalIdentity(existing.session, input.session))
            throw new BridgeServiceError("SUBSCRIPTION_BINDING_MISMATCH");
        this.#subscriptions.set(input.subscriptionId, freezeRecord({
            subscriptionId: input.subscriptionId,
            session: cloneSession(input.session),
            lastSourceEpoch: 0n,
            lastMessageAtEpochMs: 0n,
            lastCursorProviderId: -1n,
        }));
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
        validateSmsRecord(record);
        const staleCursor = record.messageAtEpochMs < subscription.lastMessageAtEpochMs
            || (record.messageAtEpochMs === subscription.lastMessageAtEpochMs && record.cursorProviderId <= subscription.lastCursorProviderId);
        if (staleCursor)
            throw new BridgeServiceError("EVENT_CURSOR_REPLAY");
        const event = freezeRecord({
            eventId: `sms-event-${++this.#eventSequence}`,
            subscriptionId,
            binding: cloneSession(subscription.session),
            record: cloneRecord(record),
        });
        this.#events.set(event.eventId, freezeRecord({ event, acknowledged: false }));
        this.#subscriptions.set(subscriptionId, freezeRecord({
            ...subscription,
            lastSourceEpoch: record.sourceEpoch,
            lastMessageAtEpochMs: record.messageAtEpochMs,
            lastCursorProviderId: record.cursorProviderId,
        }));
        return event;
    }
    acknowledge(input) {
        const subscription = this.get(input.subscriptionId, input.session);
        const stored = this.#events.get(input.eventId);
        if (!stored || stored.event.subscriptionId !== input.subscriptionId || !equalIdentity(stored.event.binding, subscription.session)) {
            throw new BridgeServiceError("EVENT_NOT_FOUND");
        }
        const record = stored.event.record;
        if (record.sourceEpoch !== input.sourceEpoch || record.messageAtEpochMs !== input.messageAtEpochMs || record.cursorProviderId !== input.cursorProviderId) {
            throw new BridgeServiceError("EVENT_ACK_INVALID");
        }
        this.#events.set(input.eventId, freezeRecord({ event: stored.event, acknowledged: true }));
        return stored.event;
    }
    pending(subscriptionId, session) {
        this.get(subscriptionId, session);
        return Object.freeze([...this.#events.values()]
            .filter((entry) => entry.event.subscriptionId === subscriptionId && !entry.acknowledged)
            .map((entry) => entry.event));
    }
}
