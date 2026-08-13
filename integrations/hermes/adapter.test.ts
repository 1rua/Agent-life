import { describe, expect, it } from "vitest";
import { createHermesAdapter, HERMES_PLUGIN_MANIFEST } from "./adapter.js";
import { fixtureBinding, fixtureContext, fixtureZeroRetentionEvidence } from "../shared/adapter.js";

describe("Hermes adapter", () => {
  it("uses one authoritative chat/tool/event profile and preserves binding", async () => {
    const adapter = createHermesAdapter({ context: fixtureContext(), zeroRetention: fixtureZeroRetentionEvidence() });
    await adapter.pair(fixtureBinding());
    expect(HERMES_PLUGIN_MANIFEST.authoritativeProfiles).toEqual({ chat: "platform", tool: "plugin", event: "plugin-hook" });
    await expect(adapter.sendAssistantMessage({ messageId: "m-1", text: "hello" }))
      .resolves.toMatchObject({ status: "accepted" });
    expect(adapter.binding()).toMatchObject({ tenantId: "tenant-a", humanPrincipalId: "human-a", deviceId: "device-a" });
  });

  it("accepts the shared bounded audio attachment contract", async () => {
    const adapter = createHermesAdapter({ context: fixtureContext(), zeroRetention: fixtureZeroRetentionEvidence() });
    await adapter.pair(fixtureBinding());
    await expect(adapter.sendAssistantMessage({
      messageId: "voice-1", text: "analyze this",
      attachments: [{ kind: "audio", artifactId: "artifact-audio", filename: "voice.m4a", mimeType: "audio/mp4", sizeBytes: 512, sha256: "c".repeat(64), durationMs: 5000 }],
    })).resolves.toMatchObject({ status: "accepted" });
  });

  it("rejects duplicate or absent authoritative profiles at startup", () => {
    expect(() => createHermesAdapter({ context: fixtureContext(), zeroRetention: fixtureZeroRetentionEvidence(), profiles: [] }))
      .toThrowError("AUTHORITATIVE_PROFILE_REQUIRED");
    expect(() => createHermesAdapter({ context: fixtureContext(), zeroRetention: fixtureZeroRetentionEvidence(), profiles: [
      ...HERMES_PLUGIN_MANIFEST.profiles,
      { kind: "chat", id: "duplicate", authoritative: true },
    ] }))
      .toThrowError("AUTHORITATIVE_PROFILE_DUPLICATE");
  });
});
