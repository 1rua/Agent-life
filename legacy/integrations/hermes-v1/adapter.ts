import {
  createFakeAdapter,
  FROZEN_PROVIDER_TOOLS,
  type AdapterOptions,
  type AdapterProfile,
  type FakeAdapter,
} from "../../../integrations/shared/adapter.js";

export const HERMES_PLUGIN_MANIFEST = Object.freeze({
  backend: "hermes",
  upstream: Object.freeze({ release: "locked-fixture", tag: "hermes-fixture-v1", commit: "fixture" }),
  protocolVersion: "mobile-bridge-v1",
  capabilitySchemaHash: "fixture-capability-schema-v1",
  authoritativeProfiles: Object.freeze({ chat: "platform", tool: "plugin", event: "plugin-hook" }),
  profiles: Object.freeze([
    Object.freeze({ kind: "chat", id: "platform", authoritative: true }),
    Object.freeze({ kind: "tool", id: "plugin", authoritative: true }),
    Object.freeze({ kind: "event", id: "plugin-hook", authoritative: true }),
  ] as const),
  zeroRetention: Object.freeze({ required: true, profileId: "fixture-zero-retention-v1", providerObjectRetention: "none", bodyEgress: "fail_closed" }),
  tools: FROZEN_PROVIDER_TOOLS,
});

export type HermesAdapterOptions = Omit<AdapterOptions, "profiles"> & Readonly<{ profiles?: readonly AdapterProfile[] }>;

export const createHermesAdapter = (options: HermesAdapterOptions): FakeAdapter => createFakeAdapter({
  ...options,
  zeroRetentionProfileId: HERMES_PLUGIN_MANIFEST.zeroRetention.profileId,
  profiles: options.profiles === undefined ? HERMES_PLUGIN_MANIFEST.profiles : options.profiles,
});
