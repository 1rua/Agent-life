export type AttachmentState =
  | "created"
  | "uploading"
  | "verified"
  | "delivered"
  | "acknowledged"
  | "failed"
  | "expired"
  | "deleted";

export type AttachmentEvent =
  | "begin_upload"
  | "verify"
  | "deliver"
  | "acknowledge"
  | "fail"
  | "expire"
  | "cleanup";

export type DeviceRequestState =
  | "pending"
  | "claimed"
  | "cancel_requested"
  | "succeeded"
  | "failed"
  | "denied"
  | "cancelled"
  | "expired"
  | "outcome_unknown";

export type DeviceRequestEvent =
  | "claim"
  | "cancel"
  | "expire"
  | "result_succeeded"
  | "result_failed"
  | "result_denied"
  | "result_cancelled"
  | "result_outcome_unknown"
  | "recover_outcome_unknown";

export type DeviceRequestRisk = "read" | "sync" | "write" | "high-privilege-ephemeral";

const attachmentTransitions: Readonly<Record<string, Readonly<Record<string, AttachmentState>>>> = {
  created: { begin_upload: "uploading", fail: "failed", expire: "expired" },
  uploading: { verify: "verified", fail: "failed", expire: "expired" },
  verified: { deliver: "delivered", fail: "failed", expire: "expired" },
  delivered: { acknowledge: "acknowledged", expire: "expired" },
  acknowledged: { cleanup: "deleted" },
  failed: { cleanup: "deleted" },
  expired: { cleanup: "deleted" },
  deleted: {},
};

const deviceRequestTransitions: Readonly<
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

const invalidTransition = (): never => {
  throw new Error("INVALID_STATE_TRANSITION");
};

export const nextAttachmentState = (
  current: AttachmentState,
  event: AttachmentEvent,
): AttachmentState => {
  const transitions =
    typeof current === "string" ? attachmentTransitions[current] : undefined;
  const next = typeof event === "string" ? transitions?.[event] : undefined;
  return next ?? invalidTransition();
};

export const nextDeviceRequestState = (
  current: DeviceRequestState,
  event: DeviceRequestEvent,
): DeviceRequestState => {
  const transitions =
    typeof current === "string" ? deviceRequestTransitions[current] : undefined;
  const next = typeof event === "string" ? transitions?.[event] : undefined;
  return next ?? invalidTransition();
};

export const maximumDeviceRequestQueueSeconds = (
  risk: DeviceRequestRisk,
): 86400 | 900 | 0 => {
  switch (risk) {
    case "read":
    case "sync":
      return 86400;
    case "write":
      return 900;
    case "high-privilege-ephemeral":
      return 0;
    default:
      throw new Error("SCHEMA_INVALID");
  }
};
