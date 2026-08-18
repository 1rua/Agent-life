import { BridgeServiceError, equalIdentity, freezeRecord, } from "./service-types.js";
import { NotificationStore } from "./notification-store.js";
import { OperationDispatcher } from "./operation-dispatch.js";
import { SubscriptionStore } from "./subscription-store.js";
/**
 * WP-06 notification API/event routing seam. All state is process-local and
 * intentionally replaceable by a durable Bridge runtime implementation.
 */
export class NotificationService {
    #pairing;
    #operations;
    #store;
    #subscriptions;
    #authorize;
    #active = new Map();
    constructor(options) {
        this.#pairing = options.pairing;
        this.#operations = options.operations ?? new OperationDispatcher();
        this.#store = options.store ?? new NotificationStore();
        this.#subscriptions = options.subscriptions ?? new SubscriptionStore();
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
        await this.#assertAuthorized("mobile.notifications.query", request.session, request.policyRevision, request.filter);
        return this.#operations.execute({
            operationId: request.operationId,
            session: request.session,
            parameters: { mode: request.mode, limit: request.limit, filter: request.filter, policyRevision: request.policyRevision },
        }, () => request.mode === "on_demand"
            ? this.#store.read(request.session.deviceId, request.limit, request.filter)
            : Object.freeze([]));
    }
    async subscribe(request) {
        this.#assertSession(request.session);
        await this.#assertAuthorized("mobile.notifications.subscribe", request.session, request.policyRevision, request.filter);
        return this.#subscriptions.subscribe({ subscriptionId: request.subscriptionId, session: request.session, filter: request.filter });
    }
    async unsubscribe(subscriptionId, session, policyRevision) {
        this.#assertSession(session);
        await this.#assertAuthorized("mobile.notifications.unsubscribe", session, policyRevision);
        return freezeRecord({ subscriptionId, removed: this.#subscriptions.unsubscribe(subscriptionId, session) });
    }
    ingest(session, record) {
        this.#assertSession(session);
        return this.#store.append(session.deviceId, record);
    }
    async publish(subscriptionId, session, record, policyRevision) {
        if (policyRevision === undefined)
            throw new BridgeServiceError("AUTHORIZATION_REQUIRED");
        return this.publishAuthorized(subscriptionId, session, record, policyRevision);
    }
    /** Recheck a current Task-6 grant before forwarding a device event. */
    async publishAuthorized(subscriptionId, session, record, policyRevision) {
        this.#assertSession(session);
        await this.#assertAuthorized("mobile.notifications.subscribe", session, policyRevision);
        // Authorization is asynchronous; the pairing may be revoked or fenced
        // while it is pending. Revalidate immediately before egress.
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
    async #assertAuthorized(capability, session, policyRevision, filter) {
        if (policyRevision !== session.policyAttestationRevision)
            throw new BridgeServiceError("AUTHORIZATION_REVISION_STALE");
        const decision = await this.#authorize({ capability, session, policyRevision, ...(filter === undefined ? {} : { filter }) });
        if (decision.policyRevision !== policyRevision)
            throw new BridgeServiceError("AUTHORIZATION_REVISION_STALE");
        if (!decision.allowed)
            throw new BridgeServiceError(decision.reason ?? "NOT_AUTHORIZED");
    }
    #baseKey(session) {
        return [session.tenantId, session.humanPrincipalId, session.deviceId, session.agentInstanceId, session.workspaceId, session.sessionId, session.jobId ?? ""].join("\u0000");
    }
}
