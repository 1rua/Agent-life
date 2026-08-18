/**
 * Shared value objects for the WP-06 contract service.
 *
 * This package is intentionally an in-memory adapter seam. It does not open
 * sockets, perform Tailscale dialing, or claim durable database semantics.
 */
export class BridgeServiceError extends Error {
    code;
    constructor(code) {
        super(code);
        this.name = "BridgeServiceError";
        this.code = code;
    }
}
export const freezeRecord = (value) => Object.freeze({ ...value });
export const identityKey = (identity) => [identity.tenantId, identity.humanPrincipalId, identity.deviceId].join("\u0000");
export const sessionKey = (identity) => [identityKey(identity), identity.agentInstanceId, identity.workspaceId, identity.sessionId, identity.jobId ?? "", identity.pairingGeneration, identity.policyAttestationRevision].join("\u0000");
export const equalIdentity = (left, right) => sessionKey(left) === sessionKey(right);
export function assertNonEmpty(value, code) {
    if (typeof value !== "string" || value.length === 0)
        throw new BridgeServiceError(code);
}
export const compareCodePoints = (left, right) => {
    const a = [...left].map((value) => value.codePointAt(0) ?? 0);
    const b = [...right].map((value) => value.codePointAt(0) ?? 0);
    for (let index = 0; index < Math.min(a.length, b.length); index += 1) {
        if (a[index] !== b[index])
            return a[index] - b[index];
    }
    return a.length - b.length;
};
export const PACKAGE_NAME = /^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$/;
