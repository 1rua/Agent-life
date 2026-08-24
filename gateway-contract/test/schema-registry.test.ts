import { describe, expect, it } from "vitest";

import {
  schemaFor,
  validateGatewayValue,
  type GatewaySchemaName,
} from "../src/schema-registry.js";

const hexDigest = "a".repeat(64);
const prefixedDigest = `sha256:${hexDigest}`;

const validValues: Record<GatewaySchemaName, unknown> = {
  "negotiate.request": {
    negotiationId: "neg_1",
    protocol: { major: 2, minor: 0 },
    client: {
      installationId: "install_1",
      appVersion: "2.0.0",
      platform: "android",
      platformApi: 35,
    },
    features: {
      auth: ["password", "account-invitation", "refresh", "device-key"],
      messages: ["chat-v1"],
      attachments: ["staged-sha256-v1"],
      events: ["sse-cursor-v1"],
      deviceRequests: ["risk-queue-v1"],
    },
    schemaHashes: { core: prefixedDigest },
  },
  "negotiate.response": {
    protocol: { major: 2, minor: 0 },
    features: {
      auth: ["password", "account-invitation", "refresh", "device-key"],
      messages: "chat-v1",
      attachments: "staged-sha256-v1",
      events: "sse-cursor-v1",
      deviceRequests: "risk-queue-v1",
    },
    limits: {
      maxSingleAttachmentBytes: 26_214_400,
      maxMessageAttachmentBytes: 52_428_800,
      allowedMediaTypes: ["application/pdf", "audio/mp4"],
      attachmentTtlSeconds: 3_600,
      eventRetentionSeconds: 86_400,
      maxClockSkewSeconds: 120,
    },
    gatewayIdentity: {
      deploymentId: "deploy_1",
      tlsSpkiSha256: prefixedDigest,
    },
  },
  "session.password": {
    negotiationId: "neg_1",
    username: "alice",
    password: "user-entered-secret",
    installation: {
      installationId: "install_1",
      displayName: "Alice's phone",
      devicePublicKey: "YWJjZA",
    },
  },
  "session.refresh": {
    negotiationId: "neg_1",
    accountId: "account_1",
    installationId: "install_1",
    deviceId: "device_1",
    refreshCredential: "refresh-secret",
  },
  "session.device": {
    negotiationId: "neg_1",
    accountId: "account_1",
    installationId: "install_1",
    deviceId: "device_1",
    challenge: "challenge_1",
    signature: "YWJjZA",
  },
  "conversation.create": {
    clientConversationId: "conversation_1",
    title: "A conversation",
  },
  "message.create": {
    clientMessageId: "message_1",
    text: "Summarize the attachment",
    attachments: [{ attachmentId: "attachment_1" }],
  },
  "attachment.create": {
    clientAttachmentId: "attachment_1",
    filename: "report.pdf",
    mediaType: "application/pdf",
    sizeBytes: 102_400,
    sha256: hexDigest,
  },
  event: {
    correlationId: "correlation_1",
    occurredAt: "2026-08-24T12:00:00.000Z",
    payload: {},
  },
  "device.request": {
    requestId: "device_request_1",
    capability: { id: "org.agentlife.sms.query", version: "1.0.0" },
    provider: {
      pluginId: "org.agentlife.sms",
      authorKeyId: prefixedDigest,
    },
    parameters: {},
    risk: "read",
    grantRevision: 7,
    createdAt: "2026-08-24T12:00:00.000Z",
    expiresAt: "2026-08-25T12:00:00.000Z",
    requiresForegroundConfirmation: false,
  },
};

const clone = <T>(value: T): T => structuredClone(value);

describe("Gateway Protocol v2 Schema registry", () => {
  it.each(Object.entries(validValues) as [GatewaySchemaName, unknown][])(
    "accepts a valid %s value",
    (name, value) => {
      expect(validateGatewayValue(name, value)).toEqual({ ok: true });
    },
  );

  it("rejects unknown fields at top-level and nested static objects", () => {
    const topLevel = {
      ...(clone(validValues["negotiate.request"]) as Record<string, unknown>),
      unexpected: true,
    };
    const nested = clone(validValues["negotiate.request"]) as {
      client: Record<string, unknown>;
    };
    nested.client.unexpected = true;

    expect(validateGatewayValue("negotiate.request", topLevel)).toMatchObject({ ok: false });
    expect(validateGatewayValue("negotiate.request", nested)).toMatchObject({ ok: false });
  });

  it.each(["accountId", "deviceId", "principalId"])(
    "rejects caller-supplied %s in conversation, message, and attachment business bodies",
    (identityField) => {
      for (const name of [
        "conversation.create",
        "message.create",
        "attachment.create",
      ] as const) {
        const value = {
          ...(clone(validValues[name]) as Record<string, unknown>),
          [identityField]: "attacker",
        };
        expect(validateGatewayValue(name, value)).toMatchObject({ ok: false });
      }
    },
  );

  it("rejects identity overrides nested inside static message attachment references", () => {
    const value = clone(validValues["message.create"]) as {
      attachments: Array<Record<string, unknown>>;
    };
    value.attachments[0]!.accountId = "attacker";

    expect(validateGatewayValue("message.create", value)).toMatchObject({ ok: false });
  });

  it("enforces opaque ID length and visible ASCII boundaries", () => {
    const validateId = (clientConversationId: string) =>
      validateGatewayValue("conversation.create", { clientConversationId });

    expect(validateId("")).toMatchObject({ ok: false });
    expect(validateId("a")).toEqual({ ok: true });
    expect(validateId("a".repeat(128))).toEqual({ ok: true });
    expect(validateId("a".repeat(129))).toMatchObject({ ok: false });
    expect(validateId("contains space")).toMatchObject({ ok: false });
    expect(validateId("line\nbreak")).toMatchObject({ ok: false });
    expect(validateId("nul\0byte")).toMatchObject({ ok: false });
    expect(validateId("设备")).toMatchObject({ ok: false });
  });

  it("accepts only RFC 3339 UTC timestamps with exactly millisecond precision", () => {
    const value = clone(validValues.event) as Record<string, unknown>;

    for (const occurredAt of [
      "2026-08-24T12:00:00Z",
      "2026-08-24T12:00:00.00Z",
      "2026-08-24T12:00:00.0000Z",
      "2026-08-24T20:00:00.000+08:00",
      "2026-08-24 12:00:00.000Z",
    ]) {
      expect(validateGatewayValue("event", { ...value, occurredAt })).toMatchObject({ ok: false });
    }
  });

  it("distinguishes lowercase bare and sha256-prefixed digests", () => {
    const attachment = clone(validValues["attachment.create"]) as Record<string, unknown>;
    expect(validateGatewayValue("attachment.create", attachment)).toEqual({ ok: true });
    expect(
      validateGatewayValue("attachment.create", { ...attachment, sha256: prefixedDigest }),
    ).toMatchObject({ ok: false });
    expect(
      validateGatewayValue("attachment.create", { ...attachment, sha256: hexDigest.toUpperCase() }),
    ).toMatchObject({ ok: false });

    const negotiation = clone(validValues["negotiate.request"]) as {
      schemaHashes: Record<string, unknown>;
    };
    expect(
      validateGatewayValue("negotiate.request", {
        ...negotiation,
        schemaHashes: { core: hexDigest },
      }),
    ).toMatchObject({ ok: false });
    expect(
      validateGatewayValue("negotiate.request", {
        ...negotiation,
        schemaHashes: { core: `sha256:${hexDigest.toUpperCase()}` },
      }),
    ).toMatchObject({ ok: false });
  });

  it("rejects unknown negotiated feature names and values", () => {
    const value = clone(validValues["negotiate.request"]) as {
      features: Record<string, unknown>;
    };

    expect(
      validateGatewayValue("negotiate.request", {
        ...value,
        features: { ...value.features, dangerousFutureFeature: ["enabled"] },
      }),
    ).toMatchObject({ ok: false });
    expect(
      validateGatewayValue("negotiate.request", {
        ...value,
        features: { ...value.features, deviceRequests: ["unknown-risk-v2"] },
      }),
    ).toMatchObject({ ok: false });
  });

  it("does not coerce types, remove unknown fields, or inject defaults", () => {
    const wrongType = {
      ...(clone(validValues["attachment.create"]) as Record<string, unknown>),
      sizeBytes: "102400",
    };
    const withUnknown = {
      ...(clone(validValues["conversation.create"]) as Record<string, unknown>),
      unknown: true,
    };
    const withoutOptionalTitle = { clientConversationId: "conversation_1" };

    expect(validateGatewayValue("attachment.create", wrongType)).toMatchObject({ ok: false });
    expect(wrongType.sizeBytes).toBe("102400");
    expect(validateGatewayValue("conversation.create", withUnknown)).toMatchObject({ ok: false });
    expect(withUnknown).toHaveProperty("unknown", true);
    expect(validateGatewayValue("conversation.create", withoutOptionalTitle)).toEqual({ ok: true });
    expect(withoutOptionalTitle).not.toHaveProperty("title");
  });

  it("returns stable frozen validation error arrays", () => {
    const first = validateGatewayValue("conversation.create", {});
    const second = validateGatewayValue("conversation.create", {});

    expect(first).toEqual(second);
    expect(first).toMatchObject({ ok: false });
    if (!first.ok) {
      expect(first.errors.length).toBeGreaterThan(0);
      expect(first.errors.every((error) => typeof error === "string")).toBe(true);
      expect(Object.isFrozen(first.errors)).toBe(true);
    }
  });

  it("returns defensive Schema copies that cannot pollute validation or the registry", () => {
    const exposed = schemaFor("conversation.create") as Record<string, unknown>;
    exposed.additionalProperties = true;

    expect(schemaFor("conversation.create")).toMatchObject({ additionalProperties: false });
    expect(
      validateGatewayValue("conversation.create", {
        clientConversationId: "conversation_1",
        accountId: "attacker",
      }),
    ).toMatchObject({ ok: false });
  });

  it("keeps event.payload as an object dispatch point requiring event-type sub-Schema validation", () => {
    const value = clone(validValues.event) as Record<string, unknown>;
    value.payload = { eventSpecificField: true, accountId: "untrusted-payload-data" };

    expect(validateGatewayValue("event", value)).toEqual({ ok: true });
    expect(
      validateGatewayValue("event", { ...value, payload: ["not", "an", "object"] }),
    ).toMatchObject({ ok: false });
  });

  it("keeps device.request.parameters as an object dispatch point requiring capability sub-Schema validation", () => {
    const value = clone(validValues["device.request"]) as Record<string, unknown>;
    value.parameters = { capabilitySpecificField: true, principalId: "untrusted-parameter-data" };

    expect(validateGatewayValue("device.request", value)).toEqual({ ok: true });
    expect(
      validateGatewayValue("device.request", { ...value, parameters: "not-an-object" }),
    ).toMatchObject({ ok: false });
  });

  it("requires all static object Schemas to reject additional properties", () => {
    const assertStrictObjects = (schema: unknown, dynamic = false): void => {
      if (typeof schema !== "object" || schema === null || Array.isArray(schema)) return;
      const record = schema as Record<string, unknown>;
      if (record.type === "object") {
        if (dynamic) {
          expect(record.additionalProperties).not.toBe(false);
        } else {
          expect(record.additionalProperties).toBe(false);
        }
      }
      const properties = record.properties;
      if (typeof properties === "object" && properties !== null && !Array.isArray(properties)) {
        for (const [key, child] of Object.entries(properties)) {
          assertStrictObjects(child, key === "payload" || key === "parameters");
        }
      }
      if (record.items !== undefined) assertStrictObjects(record.items);
      for (const keyword of ["allOf", "anyOf", "oneOf"] as const) {
        const children = record[keyword];
        if (Array.isArray(children)) children.forEach((child) => assertStrictObjects(child));
      }
    };

    for (const name of Object.keys(validValues) as GatewaySchemaName[]) {
      assertStrictObjects(schemaFor(name));
    }
  });
});
