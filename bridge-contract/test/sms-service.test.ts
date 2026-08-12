import { describe, expect, it } from "vitest";
import { PairingService, type PairingTicketInput } from "../src/pairing-service.js";
import { OperationDispatcher } from "../src/operation-dispatch.js";
import {
  SmsStore,
  validateSmsRecord,
  type SmsRecordV1,
} from "../src/sms-store.js";
import { SmsSubscriptionStore } from "../src/sms-subscription-store.js";
import { SmsService, type BridgeSessionIdentity } from "../src/sms-service.js";

const ticketInput = (overrides: Partial<PairingTicketInput> = {}): PairingTicketInput => ({
  tenantId: "tenant-a",
  humanPrincipalId: "human-a",
  deviceId: "device-a",
  bridgeFingerprint: "bridge-a",
  pairingGeneration: 1n,
  policyAttestationRevision: 7n,
  ...overrides,
});

const record = (overrides: Partial<SmsRecordV1> = {}): SmsRecordV1 => ({
  recordId: "sms:42",
  senderAddress: "+8613800000000",
  threadId: "9",
  messageAtEpochMs: 1_700_000_000_000n,
  observedAtEpochMs: 1_700_000_000_100n,
  read: false,
  subscriptionId: 1,
  body: "complete body",
  sourceEpoch: 1n,
  cursorProviderId: 42n,
  captureRevision: 7n,
  policyRevision: 7n,
  ...overrides,
});

const createService = () => {
  const pairing = new PairingService({ clock: () => 1_000 });
  const operations = new OperationDispatcher();
  const store = new SmsStore();
  const subscriptions = new SmsSubscriptionStore();
  const service = new SmsService({
    pairing,
    operations,
    store,
    subscriptions,
    authorize: ({ policyRevision, session }) => ({
      allowed: policyRevision === session.policyAttestationRevision,
      policyRevision: session.policyAttestationRevision,
    }),
  });
  const paired = service.pair(pairing.issueTicket(ticketInput()));
  return { pairing, operations, store, subscriptions, service, paired };
};

describe("closed Bridge SMS record and store", () => {
  it("accepts only the exact inbox record shape and preserves an empty body", () => {
    const empty = record({ body: "", senderAddress: null, threadId: null, subscriptionId: null });
    expect(() => validateSmsRecord(empty)).not.toThrow();
    expect(Object.keys(empty).sort()).toEqual([
      "body", "captureRevision", "cursorProviderId", "messageAtEpochMs", "observedAtEpochMs",
      "policyRevision", "read", "recordId", "senderAddress", "sourceEpoch", "subscriptionId", "threadId",
    ]);

    for (const malformed of [
      { ...empty, recordId: "sms:0" },
      { ...empty, recordId: "sms:01" },
      { ...empty, recordId: "mms:42" },
      { ...empty, cursorProviderId: -1n },
      { ...empty, messageAtEpochMs: "1700000000000" },
      { ...empty, subscriptionId: -1 },
      { ...empty, read: "false" },
      { ...empty, body: null },
      { ...empty, mmsParts: [] },
      { ...empty, attachments: [] },
      { ...empty, packageId: "com.example.sms" },
      { ...empty, url: "https://example.invalid" },
      { ...empty, tenantId: "tenant-forged" },
      { ...empty, sessionId: "session-forged" },
      { ...empty, modelId: "model-forged" },
    ]) {
      expect(() => validateSmsRecord(malformed as SmsRecordV1)).toThrowError(/SMS_RECORD_INVALID/);
    }
  });

  it("advances equal-time cursors by provider ID, rejects replay/conflict, and retains clones", () => {
    const store = new SmsStore();
    const first = { ...record({ recordId: "sms:41", cursorProviderId: 41n }), body: "retained" };
    expect(store.append("device-a", first)).toBe(true);
    first.body = "mutated after append";
    expect(store.append("device-a", record({ recordId: "sms:42", cursorProviderId: 42n }))).toBe(true);
    expect(store.read("device-a", 10).map((entry) => [entry.recordId, entry.body])).toEqual([
      ["sms:41", "retained"],
      ["sms:42", "complete body"],
    ]);
    expect(store.append("device-a", record({ recordId: "sms:42", cursorProviderId: 42n }))).toBe(false);
    expect(() => store.append("device-a", record({ recordId: "sms:42", cursorProviderId: 43n })))
      .toThrowError(/SMS_RECORD_CONFLICT/);
    expect(() => store.append("device-a", record({ recordId: "sms:40", cursorProviderId: 40n })))
      .toThrowError(/SMS_CURSOR_REPLAY/);
    for (const limit of [0, 10_001, 1.5]) expect(() => store.read("device-a", limit)).toThrowError(/LIMIT_INVALID/);
  });
});

describe("paired Bridge SMS service", () => {
  it("reuses a query result by operation ID and fences revisions and operation/session changes", async () => {
    const { service, store, paired } = createService();
    store.append(paired.deviceId, record({ body: "" }));
    const request = { operationId: "sms-op-1", session: paired, limit: 10_000, policyRevision: 7n };
    const first = await service.query(request);
    store.append(paired.deviceId, record({
      recordId: "sms:43", cursorProviderId: 43n, messageAtEpochMs: 1_700_000_000_001n,
    }));
    const retry = await service.query(request);
    expect(retry).toEqual(first);
    expect(retry).toEqual([expect.objectContaining({ recordId: "sms:42", body: "" })]);
    expect(service.operationClaims()).toEqual([{ operationId: "sms-op-1", claims: 1 }]);
    await expect(service.query({ ...request, limit: 1 })).rejects.toMatchObject({ code: "OPERATION_PARAMETERS_MISMATCH" });
    await expect(service.query({ ...request, policyRevision: 6n })).rejects.toMatchObject({ code: "AUTHORIZATION_REVISION_STALE" });
    await expect(service.query({ ...request, session: { ...paired, policyAttestationRevision: 8n }, policyRevision: 8n }))
      .rejects.toMatchObject({ code: "CONNECTION_FENCED" });
  });

  it("binds subscriptions and events to one paired session and validates publish and ACK cursors", async () => {
    const { pairing, service, paired } = createService();
    const other = service.pair(pairing.issueTicket(ticketInput()), { workspaceId: "workspace-b", sessionId: "session-b" });
    const { subscriptionId } = await service.subscribe({ subscriptionId: "sms-sub-1", session: paired, policyRevision: 7n });

    await expect(service.publishAuthorized(subscriptionId, paired, record({ policyRevision: 6n }), 7n))
      .rejects.toMatchObject({ code: "SMS_POLICY_REVISION_MISMATCH" });
    await expect(service.publishAuthorized(subscriptionId, paired, { ...record(), mmsParts: [] } as SmsRecordV1, 7n))
      .rejects.toMatchObject({ code: "SMS_RECORD_INVALID" });

    const event = await service.publishAuthorized(subscriptionId, paired, record(), 7n);
    expect(event).toMatchObject({
      eventId: "sms-event-1",
      subscriptionId,
      record: { recordId: "sms:42", body: "complete body" },
    });
    expect(service.pendingEvents(subscriptionId, paired)).toHaveLength(1);
    expect(() => service.pendingEvents(subscriptionId, other)).toThrowError(/SUBSCRIPTION_BINDING_MISMATCH/);
    await expect(service.acknowledge({
      subscriptionId, eventId: event.eventId, session: paired,
      sourceEpoch: 1n, messageAtEpochMs: 1_700_000_000_000n, cursorProviderId: 43n,
    })).rejects.toMatchObject({ code: "EVENT_ACK_INVALID" });
    await expect(service.acknowledge({
      subscriptionId, eventId: event.eventId, session: other,
      sourceEpoch: 1n, messageAtEpochMs: 1_700_000_000_000n, cursorProviderId: 42n,
    })).rejects.toMatchObject({ code: "SUBSCRIPTION_BINDING_MISMATCH" });
    await expect(service.acknowledge({
      subscriptionId, eventId: event.eventId, session: paired,
      sourceEpoch: 1n, messageAtEpochMs: 1_700_000_000_000n, cursorProviderId: 42n,
    })).resolves.toMatchObject({ eventId: "sms-event-1" });
    await expect(service.unsubscribe(subscriptionId, other, 7n)).rejects.toMatchObject({ code: "SUBSCRIPTION_BINDING_MISMATCH" });
    await expect(service.unsubscribe(subscriptionId, paired, 7n)).resolves.toEqual({ subscriptionId, removed: true });
  });

  it("rechecks pairing immediately before SMS event egress", async () => {
    const pairing = new PairingService({ clock: () => 1_000 });
    let release!: (decision: { allowed: boolean; policyRevision: bigint }) => void;
    const pending = new Promise<{ allowed: boolean; policyRevision: bigint }>((resolve) => { release = resolve; });
    let gate = false;
    const service = new SmsService({
      pairing,
      authorize: async () => gate ? pending : { allowed: true, policyRevision: 7n },
    });
    const paired = service.pair(pairing.issueTicket(ticketInput()));
    await service.subscribe({ subscriptionId: "sms-sub-race", session: paired, policyRevision: 7n });
    gate = true;
    const publish = service.publishAuthorized("sms-sub-race", paired, record(), 7n);
    pairing.acceptTicket(pairing.issueTicket(ticketInput({ pairingGeneration: 2n })));
    release({ allowed: true, policyRevision: 7n });
    await expect(publish).rejects.toMatchObject({ code: "CONNECTION_FENCED" });
  });
});
