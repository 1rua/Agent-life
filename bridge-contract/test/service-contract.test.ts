import { describe, expect, it } from "vitest";
import {
  PairingService,
  type PairingTicketInput,
} from "../src/pairing-service.js";
import {
  NotificationStore,
  validateNotificationRecord,
  type NotificationRecordV1,
} from "../src/notification-store.js";
import { OperationDispatcher } from "../src/operation-dispatch.js";
import { SubscriptionStore } from "../src/subscription-store.js";
import {
  NotificationService,
  type BridgeSessionIdentity,
} from "../src/notification-service.js";
import {
  AssistantChatService,
  ZERO_RETENTION_UNAVAILABLE,
  type ZeroRetentionEvidence,
} from "../src/assistant-chat-service.js";

const ticketInput = (overrides: Partial<PairingTicketInput> = {}): PairingTicketInput => ({
  tenantId: "tenant-a",
  humanPrincipalId: "human-a",
  deviceId: "device-a",
  bridgeFingerprint: "bridge-a",
  pairingGeneration: 1n,
  policyAttestationRevision: 3n,
  ...overrides,
});

const session = (overrides: Partial<BridgeSessionIdentity> = {}): BridgeSessionIdentity => ({
  tenantId: "tenant-a",
  humanPrincipalId: "human-a",
  deviceId: "device-a",
  agentInstanceId: "agent-a",
  workspaceId: "workspace-a",
  sessionId: "session-a",
  pairingGeneration: 1n,
  policyAttestationRevision: 3n,
  ...overrides,
});

const record = (overrides: Partial<NotificationRecordV1> = {}): NotificationRecordV1 => ({
  kind: "upsert",
  recordId: "record-1",
  packageId: "com.example.mail",
  title: "title",
  content: "body",
  sourceEpoch: 1n,
  cursor: 1n,
  captureRevision: 3n,
  ...overrides,
});

const zeroRetention = (overrides: Partial<ZeroRetentionEvidence> = {}): ZeroRetentionEvidence => ({
  provider: "fixture-provider",
  profileId: "fixture-zero-v1",
  revision: "2026-08-11.1",
  expiresAt: "2099-01-01T00:00:00.000Z",
  providerObjectRetention: "none",
  requestResponseLoggingDisabled: true,
  trainingDisabled: true,
  humanReviewDisabled: true,
  ...overrides,
});

describe("in-memory Bridge pairing seam", () => {
  it("accepts a ticket once, rejects replay/expiry, and preserves generation continuity", () => {
    let now = 1000;
    const pairing = new PairingService({ clock: () => now, ticketTtlMs: 100 });
    const ticket = pairing.issueTicket(ticketInput());
    const accepted = pairing.acceptTicket(ticket);
    expect(accepted).toMatchObject({ tenantId: "tenant-a", deviceId: "device-a", pairingGeneration: 1n });
    expect(() => pairing.acceptTicket(ticket)).toThrowError(/PAIRING_TICKET_REPLAY/);

    const nextTicket = pairing.issueTicket(ticketInput({ pairingGeneration: 2n }));
    expect(pairing.acceptTicket(nextTicket).pairingGeneration).toBe(2n);
    const skipped = pairing.issueTicket(ticketInput({ pairingGeneration: 4n }));
    expect(() => pairing.acceptTicket(skipped)).toThrowError(/PAIRING_GENERATION_GAP/);

    const expired = pairing.issueTicket(ticketInput({ deviceId: "device-b" }));
    now += 101;
    expect(() => pairing.acceptTicket(expired)).toThrowError(/PAIRING_TICKET_EXPIRED/);
  });

  it("rejects a ticket whose binding fields were changed by the caller", () => {
    const pairing = new PairingService({ clock: () => 1000 });
    const ticket = pairing.issueTicket(ticketInput());
    expect(() => pairing.acceptTicket({ ...ticket, deviceId: "device-b" })).toThrowError(/PAIRING_TICKET_TAMPERED/);
  });
});

describe("in-memory notification contract service", () => {
  it("rejects tombstones that cannot be represented by the closed wire schema", () => {
    expect(() => validateNotificationRecord(record({ kind: "delete_tombstone", title: null, content: null }))).not.toThrow();
    expect(() => validateNotificationRecord(record({ kind: "delete_tombstone", packageId: null, title: "stale", content: null })))
      .toThrowError(/NOTIFICATION_RECORD_INVALID/);
    expect(() => validateNotificationRecord(record({ kind: "delete_tombstone", title: null, content: "stale" })))
      .toThrowError(/NOTIFICATION_RECORD_INVALID/);
  });

  const createService = (now = 1000) => {
    const pairing = new PairingService({ clock: () => now });
    const operations = new OperationDispatcher();
    const store = new NotificationStore();
    const subscriptions = new SubscriptionStore();
    const service = new NotificationService({
      pairing,
      operations,
      store,
      subscriptions,
      authorize: ({ policyRevision, session: current }) => ({
        allowed: policyRevision === current.policyAttestationRevision,
        policyRevision: current.policyAttestationRevision,
      }),
    });
    const ticket = pairing.issueTicket(ticketInput());
    const paired = service.pair(ticket);
    return { service, store, subscriptions, paired };
  };

  it("queries a captured record once, applies closed package/field filters, and reuses the result on retry", async () => {
    const { service, store, paired } = createService();
    store.append(paired.deviceId, record());
    store.append(paired.deviceId, record({ recordId: "chat", packageId: "com.example.chat", cursor: 2n }));
    const request = {
      operationId: "op-1",
      session: paired,
      mode: "on_demand" as const,
      limit: 100,
      policyRevision: 3n,
      filter: { packages: ["com.example.mail"], fields: ["metadata"] as const },
    };
    const first = await service.query(request);
    const retry = await service.query(request);
    expect(first).toEqual(retry);
    expect(first).toEqual([expect.objectContaining({ recordId: "record-1", title: null, content: null })]);
    expect(service.operationClaims()).toEqual([{ operationId: "op-1", claims: 1 }]);
    await expect(service.query({ ...request, session: session({ sessionId: "other" }) }))
      .rejects.toMatchObject({ code: "CONNECTION_FENCED" });
  });

  it("fails closed on an authorization revision mismatch and isolates another session", async () => {
    const { service, paired } = createService();
    await expect(service.query({ operationId: "bad-revision", session: paired, mode: "on_demand", limit: 1, policyRevision: 2n }))
      .rejects.toThrowError(/AUTHORIZATION_REVISION_STALE/);
    await expect(service.query({ operationId: "other-user", session: session({ humanPrincipalId: "human-b" }), mode: "on_demand", limit: 1, policyRevision: 3n }))
      .rejects.toThrowError(/CONNECTION_FENCED/);
    await expect(service.query({ operationId: "forged-revision", session: { ...paired, policyAttestationRevision: 99n }, mode: "on_demand", limit: 1, policyRevision: 99n }))
      .rejects.toThrowError(/CONNECTION_FENCED/);
  });

  it("routes only through a bound subscription and validates cursor/source epoch on ACK", async () => {
    const { service, store, paired } = createService();
    const subscription = await service.subscribe({
      subscriptionId: "sub-1",
      session: paired,
      policyRevision: 3n,
      filter: { packages: ["com.example.mail"], fields: ["content"] },
    });
    store.append(paired.deviceId, record());
    const event = await service.publishAuthorized(subscription.subscriptionId, paired, record(), 3n);
    expect(event).toMatchObject({ eventId: "event-1", sourceEpoch: 1n, cursor: 1n });
    expect(service.pendingEvents(subscription.subscriptionId, paired)).toHaveLength(1);
    await expect(service.acknowledge({
      subscriptionId: subscription.subscriptionId,
      eventId: event.eventId,
      session: paired,
      sourceEpoch: 1n,
      cursor: 2n,
    })).rejects.toMatchObject({ code: "EVENT_ACK_INVALID" });
    await expect(service.acknowledge({
      subscriptionId: subscription.subscriptionId,
      eventId: event.eventId,
      session: paired,
      sourceEpoch: 1n,
      cursor: 1n,
    })).resolves.toMatchObject({ eventId: "event-1" });
    await expect(service.acknowledge({
      subscriptionId: subscription.subscriptionId,
      eventId: event.eventId,
      session: session({ workspaceId: "other" }),
      sourceEpoch: 1n,
      cursor: 1n,
    })).rejects.toThrowError(/CONNECTION_FENCED/);
  });

  it("rechecks the Task-6 subscription grant before egress", async () => {
    let allowed = true;
    const pairing = new PairingService({ clock: () => 1000 });
    const operations = new OperationDispatcher();
    const service = new NotificationService({
      pairing,
      operations,
      store: new NotificationStore(),
      subscriptions: new SubscriptionStore(),
      authorize: ({ policyRevision }) => ({ allowed, policyRevision }),
    });
    const ticket = pairing.issueTicket(ticketInput());
    const paired = service.pair(ticket);
    const subscription = await service.subscribe({ subscriptionId: "sub-revoked", session: paired, policyRevision: 3n });
    allowed = false;
    await expect(service.publishAuthorized(subscription.subscriptionId, paired, record(), 3n))
      .rejects.toMatchObject({ code: "NOT_AUTHORIZED" });
  });

  it("fences a generation change that occurs while authorization is pending", async () => {
    const pairing = new PairingService({ clock: () => 1000 });
    const ticket = pairing.issueTicket(ticketInput());
    const operations = new OperationDispatcher();
    let resolveAuthorization!: (value: { allowed: boolean; policyRevision: bigint }) => void;
    const authorization = new Promise<{ allowed: boolean; policyRevision: bigint }>((resolve) => { resolveAuthorization = resolve; });
    let gate = false;
    const service = new NotificationService({
      pairing, operations, store: new NotificationStore(), subscriptions: new SubscriptionStore(),
      authorize: async () => gate ? authorization : { allowed: true, policyRevision: 3n },
    });
    const paired = service.pair(ticket);
    await service.subscribe({ subscriptionId: "sub-race", session: paired, policyRevision: 3n });
    gate = true;
    const publish = service.publishAuthorized("sub-race", paired, record(), 3n);
    pairing.acceptTicket(pairing.issueTicket(ticketInput({ pairingGeneration: 2n })));
    resolveAuthorization({ allowed: true, policyRevision: 3n });
    await expect(publish).rejects.toThrowError(/CONNECTION_FENCED/);
  });

  it("does not expose an unauthorised publish shortcut", async () => {
    const { service, paired } = createService();
    await expect(service.publish("missing-auth", paired, record()))
      .rejects.toMatchObject({ code: "AUTHORIZATION_REQUIRED" });
  });
});

describe("Task-7-shaped operation claim seam", () => {
  it("retains completed results across a simulated restart and releases a crash-cut claim", async () => {
    const first = new OperationDispatcher();
    const current = session();
    const request = { operationId: "crash-cut", session: current, parameters: { mode: "on_demand" } };
    expect(first.begin(request)).toMatchObject({ existing: false });
    const recovered = first.restart();
    let calls = 0;
    await expect(recovered.execute(request, async () => { calls += 1; return { records: [] }; })).resolves.toEqual({ records: [] });
    expect(calls).toBe(1);
    const afterCompleted = recovered.restart();
    await expect(afterCompleted.execute(request, async () => { calls += 1; return { records: ["unexpected"] }; })).resolves.toEqual({ records: [] });
    expect(calls).toBe(1);
  });
});

describe("in-memory assistant operation service", () => {
  it("requires a verified bound session before a permissive authorizer can run", async () => {
    const service = new AssistantChatService({ authorize: ({ policyRevision }) => ({ allowed: true, policyRevision }) });
    await expect(service.send({
      operationId: "unbound-chat", messageId: "unbound-message", session: session(), text: "hello", zeroRetention: zeroRetention(),
    })).rejects.toThrowError(/CONNECTION_FENCED/);
  });

  it("fences an assistant session after pairing generation advances", async () => {
    const pairing = new PairingService({ clock: () => 1000 });
    pairing.acceptTicket(pairing.issueTicket(ticketInput()));
    const current = session();
    const service = new AssistantChatService({ pairing, boundSession: current, authorize: ({ policyRevision }) => ({ allowed: true, policyRevision }) });
    pairing.acceptTicket(pairing.issueTicket(ticketInput({ pairingGeneration: 2n })));
    await expect(service.send({
      operationId: "stale-chat", messageId: "stale-message", session: current, text: "hello", zeroRetention: zeroRetention(),
    })).rejects.toThrowError(/CONNECTION_FENCED/);
  });

  it("requires current zero-retention evidence and keeps only metadata", async () => {
    const operations = new OperationDispatcher();
    const current = session();
    const service = new AssistantChatService({ boundSession: current, operations, authorize: ({ policyRevision }) => ({ allowed: true, policyRevision }), respond: async () => "fixture-reply" });
    const result = await service.send({
      operationId: "chat-op-1",
      messageId: "message-1",
      session: current,
      text: "secret body",
      zeroRetention: zeroRetention(),
      attachments: [{ kind: "image", artifactId: "artifact-1", filename: "photo.png", mimeType: "image/png", sizeBytes: 5, sha256: "a".repeat(64) }],
    });
    expect(result).toEqual({ operationId: "chat-op-1", messageId: "message-1", status: "accepted", reply: "fixture-reply" });
    expect(service.metadata()).toEqual({
      operationId: "chat-op-1",
      messageId: "message-1",
      attachments: [{ kind: "image", filename: "photo.png", mimeType: "image/png", sizeBytes: 5, sha256: "a".repeat(64) }],
    });
    expect(JSON.stringify(service.diagnostics())).not.toContain("secret body");
    await expect(service.send({
      operationId: "chat-op-stale",
      messageId: "message-stale",
      session: current,
      text: "x",
      zeroRetention: zeroRetention({ expiresAt: "2020-01-01T00:00:00.000Z" }),
    })).rejects.toThrowError(new RegExp(ZERO_RETENTION_UNAVAILABLE));
  });

  it("reuses a completed chat operation and rejects retained providers", async () => {
    let calls = 0;
    const current = session();
    const service = new AssistantChatService({ boundSession: current, operations: new OperationDispatcher(), authorize: ({ policyRevision }) => ({ allowed: true, policyRevision }), respond: async () => { calls += 1; return "reply"; } });
    const input = { operationId: "chat-op-2", messageId: "message-2", session: current, text: "hello", zeroRetention: zeroRetention() };
    expect(await service.send(input)).toEqual(await service.send(input));
    expect(calls).toBe(1);
    await expect(service.send({ ...input, operationId: "chat-retained", zeroRetention: zeroRetention({ providerObjectRetention: "provider_retains" }) }))
      .rejects.toThrowError(/ZERO_RETENTION_UNAVAILABLE/);
  });

  it("fails closed when no Task-6 assistant grant is supplied", async () => {
    const current = session();
    const service = new AssistantChatService({ boundSession: current, operations: new OperationDispatcher() });
    await expect(service.send({ operationId: "chat-no-grant", messageId: "message-no-grant", session: current, text: "hello", zeroRetention: zeroRetention() }))
      .rejects.toMatchObject({ code: "NOT_AUTHORIZED" });
  });
});
