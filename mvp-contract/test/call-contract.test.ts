import { describe, expect, test } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { Ajv2020 } from "ajv/dist/2020.js";
import { decodeCallRecordJson, encodeCallRecord, validateWireCallRecord } from "../src/wire-codec.ts";

const released = {
  kind: "upsert",
  record_id: "call:42",
  source_epoch: "7",
  record_revision: "1",
  cursor_started_at_epoch_ms: "1700000000000",
  cursor_provider_id: "42",
  captured_at_epoch_ms: "1700000000999",
  capture_revision: "9",
  policy_revision: "9",
  metadata: {
    direction: "incoming",
    started_at_epoch_ms: "1700000000000",
    ended_at_epoch_ms: "1700000060000",
    duration_seconds: "60",
    observed_at_epoch_ms: "1700000000999",
    number_presentation: "allowed",
  },
  counterparty_number: { state: "released", value: "+15551234567" },
} as const;

describe("call record v1", () => {
  test("accepts the frozen released record and deterministic encoder", () => {
    expect(validateWireCallRecord(released)).toBe(true);
    expect(encodeCallRecord(released)).toEqual(released);
  });

  test("validates both shared frozen fixtures", () => {
    const schema = JSON.parse(readFileSync(resolve(process.cwd(), "mvp-contract/schemas/v1/call-record.schema.json"), "utf8"));
    const validateSchema = new Ajv2020({ strict: false }).compile(schema);
    for (const fixture of ["call-record-released.json", "call-record-withheld.json"]) {
      const parsed: unknown = JSON.parse(readFileSync(resolve(process.cwd(), "mvp-contract/fixtures/v1", fixture), "utf8"));
      expect(validateWireCallRecord(parsed)).toBe(true);
      expect(validateSchema(parsed)).toBe(true);
    }
  });

  test("requires exact identity, positive epoch, closed enums and canonical decimal strings", () => {
    expect(validateWireCallRecord({ ...released, record_id: "call:01" })).toBe(false);
    expect(validateWireCallRecord({ ...released, source_epoch: "0" })).toBe(false);
    expect(validateWireCallRecord({ ...released, record_revision: "2" })).toBe(false);
    expect(validateWireCallRecord({ ...released, cursor_provider_id: "43" })).toBe(false);
    expect(validateWireCallRecord({ ...released, source_epoch: "18446744073709551616" })).toBe(false);
    expect(validateWireCallRecord({ ...released, source_epoch: "18446744073709551615" })).toBe(true);
    expect(validateWireCallRecord({ ...released, metadata: { ...released.metadata, direction: "blocked" } })).toBe(false);
    expect(validateWireCallRecord({ ...released, counterparty_number: { state: "withheld", value: "+1" } })).toBe(false);
  });

  test("enforces frozen objects, cross-field timestamps and byte-bounded numbers", () => {
    expect(validateWireCallRecord({ ...released, unexpected: true })).toBe(false);
    expect(validateWireCallRecord({ ...released, metadata: { ...released.metadata, observed_at_epoch_ms: "1" } })).toBe(false);
    expect(validateWireCallRecord({ ...released, metadata: { ...released.metadata, ended_at_epoch_ms: "1700000060001" } })).toBe(false);
    expect(validateWireCallRecord({ ...released, counterparty_number: { state: "released", value: "x".repeat(257) } })).toBe(false);
    expect(validateWireCallRecord({ ...released, counterparty_number: { state: "withheld" } })).toBe(true);
  });

  test("strict decoder rejects duplicate keys and trailing material", () => {
    const encoded = new TextEncoder().encode(JSON.stringify(released));
    expect(decodeCallRecordJson(encoded)).toEqual(released);
    expect(() => decodeCallRecordJson(new TextEncoder().encode('{"kind":"upsert","kind":"upsert"}'))).toThrow("WIRE_RECORD_UNREPRESENTABLE");
    expect(() => decodeCallRecordJson(new Uint8Array([...encoded, 0x20, 0x21]))).toThrow("WIRE_RECORD_UNREPRESENTABLE");
    expect(() => decodeCallRecordJson(new Uint8Array([...encoded, 0x20]))).toThrow("WIRE_RECORD_UNREPRESENTABLE");
    expect(() => decodeCallRecordJson(new Uint8Array([0xff]))).toThrow("WIRE_RECORD_UNREPRESENTABLE");
    expect(() => decodeCallRecordJson(new TextEncoder().encode('{"kind":"upsert","record_id":"\\uD800"}'))).toThrow("WIRE_RECORD_UNREPRESENTABLE");
  });
});
