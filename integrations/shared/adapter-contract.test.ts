import { describe, expect, it } from "vitest";
import {
  ASSISTANT_ATTACHMENT_LIMITS,
  FROZEN_NOTIFICATION_TOOLS,
  ZERO_RETENTION_UNAVAILABLE,
  createFakeAdapter,
  fixtureBinding,
  fixtureContext,
  fixtureZeroRetentionEvidence,
  type NotificationEvent,
} from "./adapter.js";

describe("shared Agent adapter contract", () => {
  it("exposes exactly the three frozen notification tools", () => {
    expect(FROZEN_NOTIFICATION_TOOLS).toEqual([
      "mobile.notifications.query",
      "mobile.notifications.subscribe",
      "mobile.notifications.unsubscribe",
    ]);
  });

  it("pairs and reconnects while fencing the previous connection generation", async () => {
    const adapter = createFakeAdapter({
      context: fixtureContext(),
      zeroRetention: fixtureZeroRetentionEvidence(),
    });
    const first = await adapter.pair(fixtureBinding());
    const second = await adapter.reconnect(fixtureBinding());

    expect(first.connectionGeneration).toBe(1n);
    expect(second.connectionGeneration).toBe(2n);
    await expect(first.sendControl("ignored")).rejects.toMatchObject({ code: "CONNECTION_FENCED" });
    await expect(second.sendControl("control")).resolves.toBeUndefined();
  });

  it("queries on demand exactly once for a retried tool call", async () => {
    const adapter = createFakeAdapter({
      context: fixtureContext(),
      zeroRetention: fixtureZeroRetentionEvidence(),
      onDemand: async () => [{
        kind: "upsert",
        recordId: "notice-1",
        packageId: "com.example.mail",
        title: "untrusted title",
        content: null,
      }],
    });
    await adapter.pair(fixtureBinding());

    const first = await adapter.queryNotifications({ toolCallId: "call-1", deviceId: "device-a", mode: "on_demand", limit: 100 });
    const retry = await adapter.queryNotifications({ toolCallId: "call-1", deviceId: "device-a", mode: "on_demand", limit: 100 });

    expect(retry).toEqual(first);
    expect(adapter.operationClaims()).toEqual([{ operationId: "call-1", claims: 1 }]);
  });

  it("routes auto-send only to the bound workspace/session and acknowledges loss markers", async () => {
    const adapter = createFakeAdapter({ context: fixtureContext(), zeroRetention: fixtureZeroRetentionEvidence() });
    await adapter.pair(fixtureBinding());
    const subscription = await adapter.subscribeNotifications({ deviceId: "device-a" });
    const event: NotificationEvent = adapter.emitAutoSend({
      kind: "loss_marker",
      recordId: "gap-1",
      packageId: null,
      title: null,
      content: null,
    })!;

    await expect(adapter.receiveNotificationEvent(event)).resolves.toMatchObject({
      eventId: "event-1",
      subscriptionId: subscription.subscriptionId,
      record: { kind: "loss_marker" },
    });
    expect(adapter.acknowledgedEvents()).toEqual(["event-1"]);
    await expect(adapter.receiveNotificationEvent({ ...event, record: { ...event.record, recordId: "forged" } }))
      .resolves.toMatchObject({ eventId: "event-1", record: { recordId: "gap-1" } });
    expect(adapter.acknowledgedEvents()).toEqual(["event-1"]);
    await expect(adapter.receiveNotificationEvent({ ...event, binding: { ...fixtureContext(), workspaceId: "workspace-other" } }))
      .rejects.toMatchObject({ code: "EVENT_BINDING_MISMATCH" });
  });

  it("rejects model-supplied identity and devices outside the authenticated device set", async () => {
    const adapter = createFakeAdapter({ context: fixtureContext(), zeroRetention: fixtureZeroRetentionEvidence() });
    await adapter.pair(fixtureBinding());

    await expect(adapter.queryNotifications({
      toolCallId: "call-inject",
      deviceId: "device-a",
      mode: "on_demand",
      limit: 1,
      tenantId: "tenant-other",
    } as never)).rejects.toMatchObject({ code: "MODEL_IDENTITY_FIELD" });
    await expect(adapter.queryNotifications({ toolCallId: "call-foreign", deviceId: "device-b", mode: "on_demand", limit: 1 }))
      .rejects.toMatchObject({ code: "DEVICE_NOT_AUTHORIZED" });
  });

  it("maps assistant text and selected image/file metadata without retaining body bytes", async () => {
    const adapter = createFakeAdapter({ context: fixtureContext(), zeroRetention: fixtureZeroRetentionEvidence() });
    await adapter.pair(fixtureBinding());

    const result = await adapter.sendAssistantMessage({
      messageId: "message-1",
      text: "hello phone",
      attachments: [
        { kind: "image", artifactId: "artifact-image", filename: "photo.png", mimeType: "image/png", sizeBytes: 32, sha256: "a".repeat(64) },
        { kind: "file", artifactId: "artifact-file", filename: "notes.txt", mimeType: "text/plain", sizeBytes: 12, sha256: "b".repeat(64) },
      ],
    });

    expect(result).toEqual({ messageId: "message-1", status: "accepted", reply: "fixture-reply" });
    expect(adapter.assistantMetadata()).toEqual({
      messageId: "message-1",
      attachmentCount: 2,
      attachments: [
        { kind: "image", artifactId: "artifact-image", filename: "photo.png", mimeType: "image/png", sizeBytes: 32, sha256: "a".repeat(64) },
        { kind: "file", artifactId: "artifact-file", filename: "notes.txt", mimeType: "text/plain", sizeBytes: 12, sha256: "b".repeat(64) },
      ],
    });
    expect(JSON.stringify(adapter.diagnostics())).not.toContain("hello phone");
    expect(JSON.stringify(adapter.diagnostics())).not.toContain("photo.png");
    expect(ASSISTANT_ATTACHMENT_LIMITS).toEqual({ maxFiles: 4, maxFileBytes: 25 * 1024 * 1024, maxMessageBytes: 50 * 1024 * 1024 });
  });

  it("fails closed when zero-retention provider evidence is absent, stale, or provider-retained", async () => {
    const stale = { ...fixtureZeroRetentionEvidence(), expiresAt: "2020-01-01T00:00:00.000Z" };
    const retained = { ...fixtureZeroRetentionEvidence(), providerObjectRetention: "provider_retains" as const };
    for (const evidence of [undefined, stale, retained]) {
      const adapter = createFakeAdapter({ context: fixtureContext(), zeroRetention: evidence });
      await adapter.pair(fixtureBinding());
      await expect(adapter.sendAssistantMessage({ messageId: "m", text: "secret" }))
        .rejects.toMatchObject({ code: ZERO_RETENTION_UNAVAILABLE });
    }
  });

  it("rejects malformed attachment metadata and unknown tools", async () => {
    const adapter = createFakeAdapter({ context: fixtureContext(), zeroRetention: fixtureZeroRetentionEvidence() });
    await adapter.pair(fixtureBinding());
    await expect(adapter.sendAssistantMessage({ messageId: "m", text: "x", attachments: [{
      kind: "image", artifactId: "a", filename: "x.exe", mimeType: "application/octet-stream" as never, sizeBytes: 1, sha256: "0".repeat(64),
    }] })).rejects.toMatchObject({ code: "ATTACHMENT_UNSUPPORTED" });
    await expect(adapter.invokeTool("mobile.unknown", {})).rejects.toMatchObject({ code: "UNKNOWN_TOOL" });
  });

  it("keeps notification body content denied unless the runtime grants content", async () => {
    const denied = createFakeAdapter({
      context: fixtureContext(),
      zeroRetention: fixtureZeroRetentionEvidence(),
      onDemand: async () => [{ kind: "upsert", recordId: "body-1", packageId: "com.example.mail", title: "metadata", content: "body" }],
    });
    await denied.pair(fixtureBinding());
    await expect(denied.queryNotifications({ toolCallId: "body-call", deviceId: "device-a", mode: "on_demand", limit: 1, content: "content" }))
      .rejects.toMatchObject({ code: "CONTENT_DENIED" });
    await expect(denied.queryNotifications({ toolCallId: "metadata-call", deviceId: "device-a", mode: "on_demand", limit: 1 }))
      .resolves.toMatchObject([{ content: null }]);

    const granted = createFakeAdapter({
      context: fixtureContext(),
      zeroRetention: fixtureZeroRetentionEvidence(),
      allowNotificationContent: true,
      onDemand: async () => [{ kind: "upsert", recordId: "body-1", packageId: "com.example.mail", title: "metadata", content: "body" }],
    });
    await granted.pair(fixtureBinding());
    await expect(granted.queryNotifications({ toolCallId: "body-call", deviceId: "device-a", mode: "on_demand", limit: 1, content: "content" }))
      .resolves.toMatchObject([{ content: "body" }]);
  });

  it("validates sorted unique package filters and applies them to on-demand results", async () => {
    const adapter = createFakeAdapter({
      context: fixtureContext(),
      zeroRetention: fixtureZeroRetentionEvidence(),
      onDemand: async () => [
        { kind: "upsert", recordId: "mail", packageId: "com.example.mail", title: "mail", content: null },
        { kind: "upsert", recordId: "chat", packageId: "com.example.chat", title: "chat", content: null },
        { kind: "loss_marker", recordId: "gap", packageId: null, title: null, content: null },
      ],
    });
    await adapter.pair(fixtureBinding());
    const filtered = await adapter.queryNotifications({
      toolCallId: "packages-call",
      deviceId: "device-a",
      mode: "on_demand",
      limit: 100,
      packages: ["com.example.chat", "com.example.mail"],
    });
    expect(filtered.map((record) => record.recordId)).toEqual(["mail", "chat", "gap"]);
    await expect(adapter.queryNotifications({
      toolCallId: "packages-call", deviceId: "device-a", mode: "on_demand", limit: 100, packages: ["com.example.mail"],
    })).rejects.toMatchObject({ code: "OPERATION_PARAMETERS_MISMATCH" });

    for (const packages of [[], ["com.example.mail", "com.example.mail"], ["com.example.mail", "com.example.chat"], ["not a.package"]]) {
      await expect(adapter.queryNotifications({ toolCallId: `bad-${packages.join("-")}`, deviceId: "device-a", mode: "on_demand", limit: 1, packages }))
        .rejects.toMatchObject({ code: "PACKAGE_FILTER_INVALID" });
    }
    await expect(adapter.queryNotifications({ toolCallId: "bad-extra", deviceId: "device-a", mode: "on_demand", limit: 1, packages: ["com.example.mail"], unexpected: true } as never))
      .rejects.toMatchObject({ code: "REQUEST_FIELDS_INVALID" });
  });

  it("applies package filters to auto-send subscriptions while preserving loss markers", async () => {
    const adapter = createFakeAdapter({ context: fixtureContext(), zeroRetention: fixtureZeroRetentionEvidence() });
    await adapter.pair(fixtureBinding());
    await adapter.subscribeNotifications({ deviceId: "device-a", packages: ["com.example.mail"] });
    expect(adapter.emitAutoSend({ kind: "upsert", recordId: "chat", packageId: "com.example.chat", title: null, content: null })).toBeNull();
    const loss = adapter.emitAutoSend({ kind: "loss_marker", recordId: "gap", packageId: null, title: null, content: null });
    expect(loss?.record.recordId).toBe("gap");
    const mail = adapter.emitAutoSend({ kind: "upsert", recordId: "mail", packageId: "com.example.mail", title: null, content: null });
    expect(mail?.record.recordId).toBe("mail");
  });

  it("binds notification content selection to the auto-send subscription", async () => {
    const metadata = createFakeAdapter({ context: fixtureContext(), zeroRetention: fixtureZeroRetentionEvidence() });
    await metadata.pair(fixtureBinding());
    await metadata.subscribeNotifications({ deviceId: "device-a", content: "metadata" });
    const metadataEvent = metadata.emitAutoSend({ kind: "upsert", recordId: "mail", packageId: "com.example.mail", title: "subject", content: "secret body" });
    expect(metadataEvent?.record).toMatchObject({ title: "subject", content: null });

    const denied = createFakeAdapter({ context: fixtureContext(), zeroRetention: fixtureZeroRetentionEvidence() });
    await denied.pair(fixtureBinding());
    await expect(denied.subscribeNotifications({ deviceId: "device-a", content: "content" }))
      .rejects.toMatchObject({ code: "CONTENT_DENIED" });

    const granted = createFakeAdapter({ context: fixtureContext(), zeroRetention: fixtureZeroRetentionEvidence(), allowNotificationContent: true });
    await granted.pair(fixtureBinding());
    await granted.subscribeNotifications({ deviceId: "device-a", content: "content" });
    const contentEvent = granted.emitAutoSend({ kind: "upsert", recordId: "mail", packageId: "com.example.mail", title: "subject", content: "secret body" });
    expect(contentEvent?.record).toMatchObject({ title: "subject", content: "secret body" });
  });
});
