import { describe, expect, it } from "vitest";

import requestSignaturesDocument from "../vectors/request-signatures.json" with { type: "json" };

import {
  canonicalRequestSignatureInput,
  canonicalRequestTarget,
  type SignedRequestInput,
} from "../src/request-signature.js";

const fixedPreimageHex =
  "4147454e542d4c4946452d524551554553542d56320a4745540a2f6167656e742d6c6966652f76322f6576656e74733f637572736f723d6576745f31267a3d6c6173740a616363745f310a6465765f310a736573735f310a7265715f310a323032362d30382d32375430303a30303a30302e3030305a0a414141414141414141414141414141414141414141410a65336230633434323938666331633134396166626634633839393666623932343237616534316534363439623933346361343935393931623738353262383535";

const baseInput = (): SignedRequestInput => ({
  method: "GET",
  target: "/agent-life/v2/events?cursor=evt_1&z=last",
  accountId: "acct_1",
  deviceId: "dev_1",
  sessionId: "sess_1",
  requestId: "req_1",
  timestamp: "2026-08-27T00:00:00.000Z",
  nonce: "AAAAAAAAAAAAAAAAAAAAAA",
  body: new Uint8Array(),
});

const signatureHex = (input: SignedRequestInput): string =>
  Buffer.from(canonicalRequestSignatureInput(input)).toString("hex");

type SignatureVector = {
  input: {
    method: SignedRequestInput["method"];
    target: string;
    accountId: string;
    deviceId: string;
    sessionId: string;
    requestId: string;
    timestamp: string;
    nonce: string;
    bodyHex: string;
  };
  expected: { outcome: "value"; value: { preimageHex: string } };
};

const signatureOracleVector = (
  requestSignaturesDocument as unknown as { cases: Array<{ id: string; operation: string }> }
).cases.find(
  (vectorCase) => vectorCase.id === "request-signature-oracle" && vectorCase.operation === "request.signature",
) as unknown as SignatureVector;

describe("Gateway Protocol v2 request signature", () => {
  it("builds the prescribed ten-field preimage for the fixed oracle", () => {
    const input = baseInput();
    const preimage = new TextDecoder().decode(canonicalRequestSignatureInput(input));

    expect(signatureHex(input)).toBe(fixedPreimageHex);
    expect(preimage.split("\n")).toHaveLength(10);
    expect(preimage.endsWith("\n")).toBe(false);
    expect(preimage.endsWith("\r")).toBe(false);
    expect(preimage.includes("\0")).toBe(false);
    expect(preimage.match(/\n/g)).toHaveLength(9);
  });

  it("consumes the request.signature vector using the exact body bytes", () => {
    const input = signatureOracleVector.input;
    const actual = canonicalRequestSignatureInput({
      method: input.method,
      target: input.target,
      accountId: input.accountId,
      deviceId: input.deviceId,
      sessionId: input.sessionId,
      requestId: input.requestId,
      timestamp: input.timestamp,
      nonce: input.nonce,
      body: Uint8Array.from(Buffer.from(input.bodyHex, "hex")),
    });

    expect(Buffer.from(actual).toString("hex")).toBe(
      signatureOracleVector.expected.value.preimageHex,
    );
  });

  it("changes the preimage when any signed method, ID, timestamp, nonce, or body changes", () => {
    const baseline = signatureHex(baseInput());
    const changes: SignedRequestInput[] = [
      { ...baseInput(), method: "POST", target: "/agent-life/v2/events" },
      { ...baseInput(), accountId: "acct_2" },
      { ...baseInput(), deviceId: "dev_2" },
      { ...baseInput(), sessionId: "sess_2" },
      { ...baseInput(), requestId: "req_2" },
      { ...baseInput(), timestamp: "2026-08-27T00:00:00.001Z" },
      { ...baseInput(), nonce: "AQEBAQEBAQEBAQEBAQEBAQ" },
      { ...baseInput(), body: Uint8Array.from([0x7b, 0x7d]) },
    ];

    for (const changed of changes) expect(signatureHex(changed)).not.toBe(baseline);
  });

  it("requires exact fields, wire IDs, UTC millisecond timestamps, canonical nonce, and bytes", () => {
    expect(() =>
      canonicalRequestSignatureInput({ ...baseInput(), method: "get" } as unknown as SignedRequestInput),
    ).toThrow("SCHEMA_INVALID");
    expect(() =>
      canonicalRequestSignatureInput({ ...baseInput(), accountId: "contains space" }),
    ).toThrow("SCHEMA_INVALID");
    expect(() =>
      canonicalRequestSignatureInput({ ...baseInput(), timestamp: "2026-08-27T00:00:00Z" }),
    ).toThrow("SCHEMA_INVALID");
    expect(() =>
      canonicalRequestSignatureInput({ ...baseInput(), nonce: "AAAAAAAAAAAAAAAAAAAAAA==" }),
    ).toThrow("SCHEMA_INVALID");
    expect(() =>
      canonicalRequestSignatureInput({ ...baseInput(), body: "{}" } as unknown as SignedRequestInput),
    ).toThrow("SCHEMA_INVALID");
    expect(() =>
      canonicalRequestSignatureInput({
        ...baseInput(),
        authorKeyId: "sha256:" + "a".repeat(64),
      } as unknown as SignedRequestInput),
    ).toThrow("SCHEMA_INVALID");
    expect(() =>
      canonicalRequestSignatureInput({ ...baseInput(), requestId: "a".repeat(129) }),
    ).toThrow("SCHEMA_INVALID");
  });

  it("canonicalizes path bytes and query pairs without form-url-encoding semantics", () => {
    expect(canonicalRequestTarget("/agent-life/%76%32/events?z=%7e&a+b=x+y&cursor")).toBe(
      "/agent-life/v2/events?a%2Bb=x%2By&cursor=&z=~",
    );
    expect(canonicalRequestTarget("/agent-life/v2/events?a=2&a=1&a=1")).toBe(
      "/agent-life/v2/events?a=1&a=1&a=2",
    );
    expect(canonicalRequestTarget("/agent-life/v2/a!")).toBe("/agent-life/v2/a%21");
    expect(canonicalRequestTarget("/agent-life/v2/")).toBe("/agent-life/v2/");
  });

  it.each([
    ["absolute-form", "https://example.test/agent-life/v2/events"],
    ["authority-form", "example.test:443"],
    ["asterisk-form", "*"],
    ["fragment", "/agent-life/v2/events#fragment"],
    ["space", "/agent-life/v2/events?cursor=has space"],
    ["tab", "/agent-life/v2/events?cursor=has\t tab"],
    ["control", "/agent-life/v2/events?cursor=has\u0000nul"],
    ["outside base", "/other/v2/events"],
    ["dot segment", "/agent-life/v2/./events"],
    ["encoded dot segment", "/agent-life/v2/%2e/events"],
    ["dotdot segment", "/agent-life/v2/../events"],
    ["encoded dotdot segment", "/agent-life/v2/%2e%2e/events"],
    ["double slash", "/agent-life/v2//events"],
    ["non-root trailing slash", "/agent-life/v2/events/"],
    ["encoded slash", "/agent-life/v2/events%2fnext"],
    ["encoded backslash", "/agent-life/v2/events%5cnext"],
    ["malformed percent", "/agent-life/v2/events%2"],
    ["empty query", "/agent-life/v2/events?"],
    ["empty query pair", "/agent-life/v2/events?cursor=evt_1&&z=last"],
    ["empty query name", "/agent-life/v2/events?=evt_1"],
    ["query question mark", "/agent-life/v2/events?cursor=evt_1?z=last"],
  ])("rejects the non-canonical target boundary: %s", (_name, target) => {
    expect(() => canonicalRequestTarget(target)).toThrow("SCHEMA_INVALID");
  });

  it("rejects a valid but non-canonical target from the signature preimage", () => {
    expect(() =>
      canonicalRequestSignatureInput({
        ...baseInput(),
        target: "/agent-life/v2/events?z=last&cursor=evt_1",
      }),
    ).toThrow("NON_CANONICAL_TARGET");
  });
});
