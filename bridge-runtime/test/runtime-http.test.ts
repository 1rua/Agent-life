import { describe, expect, it } from "vitest";
import { BridgeHealth } from "../src/health.js";
import { createRuntimeHttpHandler } from "../src/runtime-http.js";

const health = new BridgeHealth([{ name: "database", check: () => ({ ok: true }) }]);

describe("runtime HTTP boundary", () => {
  it("serves health without authentication", async () => {
    const handler = createRuntimeHttpHandler({ health, control: async () => undefined });
    await expect(handler({ method: "GET", path: "/health/live", headers: {} }))
      .resolves.toMatchObject({ statusCode: 200, body: `{"status":"ok"}` });
  });

  it("requires exactly one sidecar peer fingerprint for control routes", async () => {
    const handler = createRuntimeHttpHandler({ health, control: async () => undefined });
    await expect(handler({ method: "GET", path: "/v1/control", headers: {} }))
      .resolves.toMatchObject({ statusCode: 401, body: `{"error":"TAILNET_PEER_FINGERPRINT_REQUIRED"}` });
    await expect(handler({
      method: "GET", path: "/v1/control",
      headers: { "x-agent-life-peer-fingerprint": ["sha256:a", "sha256:b"] },
    })).resolves.toMatchObject({ statusCode: 400, body: `{"error":"TAILNET_PEER_FINGERPRINT_INVALID"}` });
  });

  it("passes an authenticated fingerprint to the control boundary", async () => {
    let observed = "";
    const handler = createRuntimeHttpHandler({
      health,
      control: async (request) => {
        observed = request.peerFingerprint;
        return { statusCode: 204 };
      },
    });
    await expect(handler({
      method: "GET", path: "/v1/control",
      headers: { "x-agent-life-peer-fingerprint": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" },
    })).resolves.toMatchObject({ statusCode: 204 });
    expect(observed).toBe("sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
  });
});
