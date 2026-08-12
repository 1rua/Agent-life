import { describe, expect, it } from "vitest";
import { createHermesAdapter, HERMES_PLUGIN_MANIFEST } from "../hermes/adapter.js";
import { createOpenClawAdapter, OPENCLAW_PLUGIN_MANIFEST } from "../openclaw/adapter.js";
import {
  FROZEN_SMS_TOOLS,
  createFakeAdapter,
  fixtureBinding,
  fixtureContext,
  fixtureZeroRetentionEvidence,
  type SmsRecord,
} from "./adapter.js";

const smsRecord = (overrides: Partial<SmsRecord> = {}): SmsRecord => ({
  recordId: "sms:42",
  senderAddress: "+8613800000000",
  threadId: "9",
  messageAtEpochMs: 1_700_000_000_000n,
  observedAtEpochMs: 1_700_000_000_100n,
  read: false,
  subscriptionId: 1,
  body: "first line\nsecond line",
  sourceEpoch: 1n,
  cursorProviderId: 42n,
  captureRevision: 7n,
  policyRevision: 7n,
  ...overrides,
});

const bytes = (value: unknown): string => JSON.stringify(value, (_key, current) =>
  typeof current === "bigint" ? current.toString() : current);

describe("shared Hermes/OpenClaw SMS contract", () => {
  it("exposes exactly the three frozen SMS tools in both provider manifests", () => {
    expect(FROZEN_SMS_TOOLS).toEqual([
      "mobile.sms.query",
      "mobile.sms.subscribe",
      "mobile.sms.unsubscribe",
    ]);
    expect(Object.isFrozen(FROZEN_SMS_TOOLS)).toBe(true);
    expect(HERMES_PLUGIN_MANIFEST.tools).toEqual(FROZEN_SMS_TOOLS);
    expect(OPENCLAW_PLUGIN_MANIFEST.tools).toEqual(FROZEN_SMS_TOOLS);
  });

  it("returns byte-equivalent complete SMS query records for Hermes and OpenClaw", async () => {
    const options = {
      context: fixtureContext(),
      zeroRetention: fixtureZeroRetentionEvidence(),
      onDemandSms: async () => [smsRecord({ body: "" }), smsRecord({
        recordId: "sms:43",
        cursorProviderId: 43n,
        messageAtEpochMs: 1_700_000_000_001n,
        body: "complete untruncated body\nwith a second line",
      })],
    };
    const hermes = createHermesAdapter(options);
    const openclaw = createOpenClawAdapter(options);
    await hermes.pair(fixtureBinding());
    await openclaw.pair(fixtureBinding());

    const input = { toolCallId: "sms-call-1", deviceId: "device-a", limit: 10_000 };
    const hermesResult = await hermes.querySms(input);
    const openclawResult = await openclaw.querySms(input);
    expect(bytes(hermesResult)).toBe(bytes(openclawResult));
    expect(hermesResult.map((entry) => entry.body)).toEqual(["", "complete untruncated body\nwith a second line"]);
    expect(Object.keys(hermesResult[0]!).sort()).toEqual([
      "body", "captureRevision", "cursorProviderId", "messageAtEpochMs", "observedAtEpochMs",
      "policyRevision", "read", "recordId", "senderAddress", "sourceEpoch", "subscriptionId", "threadId",
    ]);
  });

  it("uses runtime tool-call identity and rejects operation, identity, model, capability, and MMS input", async () => {
    const adapter = createFakeAdapter({ context: fixtureContext(), zeroRetention: fixtureZeroRetentionEvidence() });
    await adapter.pair(fixtureBinding());

    for (const forbidden of [
      { operationId: "model-operation" },
      { tenantId: "tenant-other" },
      { sessionId: "session-other" },
      { modelId: "model-other" },
    ]) {
      await expect(adapter.querySms({ toolCallId: "sms-forged", deviceId: "device-a", limit: 1, ...forbidden } as never))
        .rejects.toMatchObject({ code: "MODEL_IDENTITY_FIELD" });
    }
    await expect(adapter.querySms({ toolCallId: "sms-capability", deviceId: "device-a", limit: 1, capability: "shell" } as never))
      .rejects.toMatchObject({ code: "REQUEST_FIELDS_INVALID" });
    await expect(adapter.querySms({ toolCallId: "sms-mms", deviceId: "device-a", limit: 1, attachments: [] } as never))
      .rejects.toMatchObject({ code: "REQUEST_FIELDS_INVALID" });
    await expect(adapter.invokeTool("mobile.sms.shell", {})).rejects.toMatchObject({ code: "UNKNOWN_TOOL" });
    await expect(adapter.querySms({ toolCallId: "sms-too-many", deviceId: "device-a", limit: 10_001 }))
      .rejects.toMatchObject({ code: "LIMIT_INVALID" });
  });

  it("keeps SMS query idempotent and binds auto-send delivery to its paired session", async () => {
    let reads = 0;
    const adapter = createFakeAdapter({
      context: fixtureContext(),
      zeroRetention: fixtureZeroRetentionEvidence(),
      onDemandSms: async () => { reads += 1; return [smsRecord()]; },
    });
    await adapter.pair(fixtureBinding());
    const input = { toolCallId: "sms-retry", deviceId: "device-a", limit: 1 };
    expect(await adapter.querySms(input)).toEqual(await adapter.querySms(input));
    expect(reads).toBe(1);

    const { subscriptionId } = await adapter.subscribeSms({ deviceId: "device-a" });
    const event = adapter.emitSmsAutoSend(smsRecord({ body: "complete event body" }));
    await expect(adapter.receiveSmsEvent({ ...event, binding: { ...event.binding, sessionId: "session-other" } }))
      .rejects.toMatchObject({ code: "EVENT_BINDING_MISMATCH" });
    await expect(adapter.receiveSmsEvent(event)).resolves.toMatchObject({
      subscriptionId,
      record: { recordId: "sms:42", body: "complete event body" },
    });
    await expect(adapter.unsubscribeSms()).resolves.toEqual({ removed: true });
  });
});
