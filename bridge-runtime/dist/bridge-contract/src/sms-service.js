import { BridgeServiceError, equalIdentity, freezeRecord, identityKey, } from "./service-types.js";
import { OperationDispatcher } from "./operation-dispatch.js";
import { SmsStore, validateSmsRecord } from "./sms-store.js";
import { SmsSubscriptionStore } from "./sms-subscription-store.js";
/** Paired, capability-closed SMS service with no endpoint or generic execution surface. */
export class SmsService {
    #pairing;
    #operations;
    #store;
    #subscriptions;
    #authorize;
    #active = new Map();
    constructor(options) {
        this.#pairing = options.pairing;
        this.#operations = options.operations ?? new OperationDispatcher();
        this.#store = options.store ?? new SmsStore();
        this.#subscriptions = options.subscriptions ?? new SmsSubscriptionStore();
        this.#authorize = options.authorize ?? (() => ({ allowed: false, policyRevision: 0n, reason: "NO_AUTHORIZER" }));
    }
    pair(ticket, sessionOverrides = {}) {
        const accepted = this.#pairing.acceptTicket(ticket);
        const session = freezeRecord({
            tenantId: accepted.tenantId,
            humanPrincipalId: accepted.humanPrincipalId,
            deviceId: accepted.deviceId,
            agentInstanceId: sessionOverrides.agentInstanceId ?? "agent-a",
            workspaceId: sessionOverrides.workspaceId ?? "workspace-a",
            sessionId: sessionOverrides.sessionId ?? "session-a",
            ...(sessionOverrides.jobId === undefined ? {} : { jobId: sessionOverrides.jobId }),
            pairingGeneration: accepted.pairingGeneration,
            policyAttestationRevision: accepted.policyAttestationRevision,
        });
        this.#active.set(this.#baseKey(session), session);
        return session;
    }
    async query(request) {
        this.#assertSession(request.session);
        await this.#assertAuthorized("mobile.sms.query", request.session, request.policyRevision);
        this.#assertSession(request.session);
        const result = await this.#operations.execute({
            operationId: request.operationId,
            session: request.session,
            parameters: { limit: request.limit, policyRevision: request.policyRevision },
        }, () => this.#store.read(identityKey(request.session), request.limit));
        this.#assertSession(request.session);
        return result;
    }
    async subscribe(request) {
        this.#assertSession(request.session);
        await this.#assertAuthorized("mobile.sms.subscribe", request.session, request.policyRevision);
        this.#assertSession(request.session);
        return this.#subscriptions.subscribe({ subscriptionId: request.subscriptionId, session: request.session });
    }
    async unsubscribe(subscriptionId, session, policyRevision) {
        this.#assertSession(session);
        await this.#assertAuthorized("mobile.sms.unsubscribe", session, policyRevision);
        this.#assertSession(session);
        return freezeRecord({ subscriptionId, removed: this.#subscriptions.unsubscribe(subscriptionId, session) });
    }
    ingest(session, record) {
        this.#assertSession(session);
        return this.#store.append(identityKey(session), record);
    }
    async publish(subscriptionId, session, record, policyRevision) {
        if (policyRevision === undefined)
            throw new BridgeServiceError("AUTHORIZATION_REQUIRED");
        return this.publishAuthorized(subscriptionId, session, record, policyRevision);
    }
    async publishAuthorized(subscriptionId, session, record, policyRevision) {
        this.#assertSession(session);
        validateSmsRecord(record);
        if (record.policyRevision !== policyRevision)
            throw new BridgeServiceError("SMS_POLICY_REVISION_MISMATCH");
        await this.#assertAuthorized("mobile.sms.subscribe", session, policyRevision);
        // Pairing and policy may change while authorization is pending. This is the
        // final synchronous check before the event enters the egress ledger.
        this.#assertSession(session);
        return this.#subscriptions.publish(subscriptionId, session, record);
    }
    async acknowledge(input) {
        this.#assertSession(input.session);
        return this.#subscriptions.acknowledge(input);
    }
    pendingEvents(subscriptionId, session) {
        this.#assertSession(session);
        return this.#subscriptions.pending(subscriptionId, session);
    }
    operationClaims() {
        return this.#operations.claims();
    }
    #assertSession(session) {
        const active = this.#active.get(this.#baseKey(session));
        if (!active || !equalIdentity(active, session)
            || active.pairingGeneration !== session.pairingGeneration
            || active.policyAttestationRevision !== session.policyAttestationRevision)
            throw new BridgeServiceError("CONNECTION_FENCED");
        const paired = this.#pairing.current(session);
        if (!paired || paired.pairingGeneration !== session.pairingGeneration
            || paired.policyAttestationRevision !== session.policyAttestationRevision)
            throw new BridgeServiceError("CONNECTION_FENCED");
    }
    async #assertAuthorized(capability, session, policyRevision) {
        if (policyRevision !== session.policyAttestationRevision)
            throw new BridgeServiceError("AUTHORIZATION_REVISION_STALE");
        const decision = await this.#authorize({ capability, session, policyRevision });
        if (decision.policyRevision !== policyRevision)
            throw new BridgeServiceError("AUTHORIZATION_REVISION_STALE");
        if (!decision.allowed)
            throw new BridgeServiceError(decision.reason ?? "NOT_AUTHORIZED");
    }
    #baseKey(session) {
        return [session.tenantId, session.humanPrincipalId, session.deviceId, session.agentInstanceId, session.workspaceId, session.sessionId, session.jobId ?? ""].join("\u0000");
    }
}
