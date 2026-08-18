import { BridgeServiceError } from "./service-types.js";
const assertValidEvent = (event) => {
    if (!event || typeof event !== "object" || !["delta", "complete", "failed"].includes(event.kind)
        || typeof event.operationId !== "string" || event.operationId.length === 0
        || typeof event.messageId !== "string" || event.messageId.length === 0
        || typeof event.sequence !== "bigint"
        || typeof event.text !== "string" || event.text.length > 50_000) {
        throw new BridgeServiceError("ASSISTANT_EVENT_INVALID");
    }
    if (event.kind === "failed") {
        if (typeof event.error !== "string" || event.error.length === 0)
            throw new BridgeServiceError("ASSISTANT_EVENT_INVALID");
    }
    else if (event.error !== undefined) {
        throw new BridgeServiceError("ASSISTANT_EVENT_INVALID");
    }
};
export class InMemoryAssistantReplyEventStore {
    #events = new Map();
    append(event) {
        assertValidEvent(event);
        const previous = this.#events.get(event.operationId) ?? [];
        if (previous.some((stored) => stored.messageId !== event.messageId)) {
            throw new BridgeServiceError("ASSISTANT_EVENT_MESSAGE_MISMATCH");
        }
        if (previous.some((stored) => stored.kind === "complete" || stored.kind === "failed")) {
            throw new BridgeServiceError("ASSISTANT_EVENT_TERMINAL");
        }
        const expected = BigInt(previous.length + 1);
        if (event.sequence <= 0n || event.sequence !== expected)
            throw new BridgeServiceError("ASSISTANT_EVENT_SEQUENCE_INVALID");
        previous.push(Object.freeze({ ...event }));
        this.#events.set(event.operationId, previous);
    }
    replay(operationId, afterSequence) {
        if (typeof operationId !== "string" || operationId.length === 0 || typeof afterSequence !== "bigint" || afterSequence < 0n) {
            throw new BridgeServiceError("ASSISTANT_EVENT_INVALID");
        }
        return Object.freeze((this.#events.get(operationId) ?? [])
            .filter((event) => event.sequence > afterSequence)
            .map((event) => Object.freeze({ ...event })));
    }
}
