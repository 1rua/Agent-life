import { describe, expect, it } from "vitest";

import {
  nextAttachmentState,
  nextDeviceRequestState,
  maximumDeviceRequestQueueSeconds,
  type AttachmentEvent,
  type AttachmentState,
  type DeviceRequestEvent,
  type DeviceRequestState,
} from "../src/state-machines.js";

const attachmentStates: readonly AttachmentState[] = [
  "created",
  "uploading",
  "verified",
  "delivered",
  "acknowledged",
  "failed",
  "expired",
  "deleted",
];
const attachmentEvents: readonly AttachmentEvent[] = [
  "begin_upload",
  "verify",
  "deliver",
  "acknowledge",
  "fail",
  "expire",
  "cleanup",
];
const attachmentLegalTransitions: Readonly<Record<string, Readonly<Record<string, AttachmentState>>>> = {
  created: { begin_upload: "uploading", fail: "failed", expire: "expired" },
  uploading: { verify: "verified", fail: "failed", expire: "expired" },
  verified: { deliver: "delivered", fail: "failed", expire: "expired" },
  delivered: { acknowledge: "acknowledged", expire: "expired" },
  acknowledged: { cleanup: "deleted" },
  failed: { cleanup: "deleted" },
  expired: { cleanup: "deleted" },
  deleted: {},
};

const deviceRequestStates: readonly DeviceRequestState[] = [
  "pending",
  "claimed",
  "cancel_requested",
  "succeeded",
  "failed",
  "denied",
  "cancelled",
  "expired",
  "outcome_unknown",
];
const deviceRequestEvents: readonly DeviceRequestEvent[] = [
  "claim",
  "cancel",
  "expire",
  "result_succeeded",
  "result_failed",
  "result_denied",
  "result_cancelled",
  "result_outcome_unknown",
  "recover_outcome_unknown",
];
const deviceRequestLegalTransitions: Readonly<
  Record<string, Readonly<Record<string, DeviceRequestState>>>
> = {
  pending: { claim: "claimed", cancel: "cancelled", expire: "expired" },
  claimed: {
    cancel: "cancel_requested",
    expire: "outcome_unknown",
    result_succeeded: "succeeded",
    result_failed: "failed",
    result_denied: "denied",
    result_cancelled: "cancelled",
    result_outcome_unknown: "outcome_unknown",
    recover_outcome_unknown: "outcome_unknown",
  },
  cancel_requested: {
    expire: "outcome_unknown",
    result_succeeded: "succeeded",
    result_failed: "failed",
    result_denied: "denied",
    result_cancelled: "cancelled",
    result_outcome_unknown: "outcome_unknown",
    recover_outcome_unknown: "outcome_unknown",
  },
  succeeded: {},
  failed: {},
  denied: {},
  cancelled: {},
  expired: {},
  outcome_unknown: {},
};

describe("Gateway Protocol v2 pure state machines", () => {
  it("implements every legal attachment transition", () => {
    for (const [current, transitions] of Object.entries(attachmentLegalTransitions)) {
      for (const [event, next] of Object.entries(transitions)) {
        expect(nextAttachmentState(current as AttachmentState, event as AttachmentEvent)).toBe(next);
      }
    }
  });

  it("rejects every unlisted attachment state/event pair", () => {
    for (const current of attachmentStates) {
      for (const event of attachmentEvents) {
        if (attachmentLegalTransitions[current]![event] !== undefined) continue;
        expect(() => nextAttachmentState(current, event)).toThrow("INVALID_STATE_TRANSITION");
      }
    }
  });

  it("implements every legal device request claim/result transition", () => {
    for (const [current, transitions] of Object.entries(deviceRequestLegalTransitions)) {
      for (const [event, next] of Object.entries(transitions)) {
        expect(
          nextDeviceRequestState(current as DeviceRequestState, event as DeviceRequestEvent),
        ).toBe(next);
      }
    }
  });

  it("rejects every unlisted device request state/event pair and preserves outcome_unknown", () => {
    for (const current of deviceRequestStates) {
      for (const event of deviceRequestEvents) {
        if (deviceRequestLegalTransitions[current]![event] !== undefined) continue;
        expect(() => nextDeviceRequestState(current, event)).toThrow("INVALID_STATE_TRANSITION");
      }
    }
    expect(() =>
      nextDeviceRequestState("outcome_unknown", "result_succeeded"),
    ).toThrow("INVALID_STATE_TRANSITION");
  });

  it("does not coerce unknown runtime state or event values", () => {
    expect(() =>
      nextAttachmentState({ toString: () => "created" } as never, "begin_upload"),
    ).toThrow("INVALID_STATE_TRANSITION");
    expect(() =>
      nextAttachmentState("created", { toString: () => "begin_upload" } as never),
    ).toThrow("INVALID_STATE_TRANSITION");
    expect(() =>
      nextDeviceRequestState({ toString: () => "pending" } as never, "claim"),
    ).toThrow("INVALID_STATE_TRANSITION");
    expect(() =>
      nextDeviceRequestState("pending", { toString: () => "claim" } as never),
    ).toThrow("INVALID_STATE_TRANSITION");
  });

  it("returns the three fixed queue limits and rejects unknown risk", () => {
    expect(maximumDeviceRequestQueueSeconds("read")).toBe(86400);
    expect(maximumDeviceRequestQueueSeconds("sync")).toBe(86400);
    expect(maximumDeviceRequestQueueSeconds("write")).toBe(900);
    expect(maximumDeviceRequestQueueSeconds("high-privilege-ephemeral")).toBe(0);
    expect(() =>
      maximumDeviceRequestQueueSeconds("unknown" as never),
    ).toThrow("SCHEMA_INVALID");
  });
});
