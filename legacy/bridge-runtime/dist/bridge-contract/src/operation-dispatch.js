import { BridgeServiceError, freezeRecord, sessionKey, } from "./service-types.js";
const canonical = (value) => {
    if (typeof value === "bigint")
        return `bigint:${value.toString()}`;
    if (value === null || typeof value !== "object")
        return JSON.stringify(value);
    if (Array.isArray(value))
        return `[${value.map(canonical).join(",")}]`;
    return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${canonical(value[key])}`).join(",")}}`;
};
/**
 * Task-7-shaped operation claim/result ledger for the WP-06 in-memory seam.
 * Completed results survive `restart()`; pending claims are intentionally
 * released so the caller can retry after a simulated crash cut.
 */
export class OperationDispatcher {
    #operations = new Map();
    begin(request) {
        if (typeof request.operationId !== "string" || request.operationId.length === 0)
            throw new BridgeServiceError("OPERATION_ID_INVALID");
        const session = sessionKey(request.session);
        const parametersDigest = canonical(request.parameters ?? null);
        const previous = this.#operations.get(request.operationId);
        if (previous) {
            if (previous.sessionKey !== session)
                throw new BridgeServiceError("OPERATION_IDENTITY_MISMATCH");
            if (previous.parametersDigest !== parametersDigest)
                throw new BridgeServiceError("OPERATION_PARAMETERS_MISMATCH");
            if (previous.status === "completed")
                return freezeRecord({ operationId: request.operationId, existing: true, result: previous.result });
            throw new BridgeServiceError("OPERATION_IN_PROGRESS");
        }
        this.#operations.set(request.operationId, { sessionKey: session, parametersDigest, status: "pending", claims: 1 });
        return freezeRecord({ operationId: request.operationId, existing: false });
    }
    complete(request, result) {
        const previous = this.#operations.get(request.operationId);
        if (!previous)
            throw new BridgeServiceError("OPERATION_CLAIM_MISSING");
        if (previous.sessionKey !== sessionKey(request.session))
            throw new BridgeServiceError("OPERATION_IDENTITY_MISMATCH");
        if (previous.parametersDigest !== canonical(request.parameters ?? null))
            throw new BridgeServiceError("OPERATION_PARAMETERS_MISMATCH");
        if (previous.status === "completed")
            return previous.result;
        previous.status = "completed";
        previous.result = result;
        return result;
    }
    release(request) {
        const previous = this.#operations.get(request.operationId);
        if (!previous)
            return;
        if (previous.sessionKey !== sessionKey(request.session) || previous.parametersDigest !== canonical(request.parameters ?? null))
            throw new BridgeServiceError("OPERATION_IDENTITY_MISMATCH");
        if (previous.status === "pending")
            this.#operations.delete(request.operationId);
    }
    async execute(request, action) {
        const claim = this.begin(request);
        if (claim.existing)
            return claim.result;
        try {
            return this.complete(request, await action());
        }
        catch (error) {
            this.release(request);
            throw error;
        }
    }
    claims() {
        return Object.freeze([...this.#operations].map(([operationId, operation]) => freezeRecord({ operationId, claims: operation.claims })));
    }
    /** Simulate a process restart: completed results remain, pending claims are recoverable. */
    restart() {
        const next = new OperationDispatcher();
        for (const [operationId, operation] of this.#operations) {
            if (operation.status === "completed")
                next.#operations.set(operationId, { ...operation });
        }
        return next;
    }
}
export { BridgeServiceError } from "./service-types.js";
