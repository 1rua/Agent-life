/**
 * Closed MVP wire codec.
 *
 * Bridge/Android internals use ergonomic camel-case value objects. This module
 * is the only conversion seam to the versioned snake-case JSON contracts. It
 * deliberately rejects unknown fields before a payload crosses the boundary;
 * identity and authorization facts are never encoded into these records.
 */

const PACKAGE_NAME = /^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$/;
const MAX_U64 = 18_446_744_073_709_551_615n;
const MAX_SMS_PROVIDER_ID = 9_223_372_036_854_775_807n;
const DECIMAL_U64 = /^(0|[1-9][0-9]*)$/;
const isDecimalU64 = (value: unknown): value is string =>
  typeof value === "string" && value.length <= 20 && DECIMAL_U64.test(value) && BigInt(value) <= MAX_U64;
const isPositiveU64 = (value: unknown): value is string =>
  isDecimalU64(value) && value !== "0";
const isPositiveSmsProviderId = (value: unknown): value is string =>
  isPositiveU64(value) && BigInt(value) <= MAX_SMS_PROVIDER_ID;
const SHA256 = /^[A-Fa-f0-9]{64}$/;
const MEDIA_TYPES = new Set(["image/jpeg", "image/png", "image/webp", "application/pdf", "text/plain"]);
const AUDIO_MAX_BYTES = 10 * 1024 * 1024;
const AUDIO_MAX_DURATION_MS = 120000;
const ASSISTANT_MAX_ATTACHMENT_BYTES = 50 * 1024 * 1024;
const ARTIFACT_ID = /^[A-Za-z0-9._~-]{1,128}$/;

type RuntimeNotificationRecord = Readonly<{
  kind: "upsert" | "delete_tombstone" | "loss_marker";
  recordId: string;
  packageId: string | null;
  title: string | null;
  content: string | null;
  sourceEpoch: bigint;
  cursor: bigint;
  captureRevision: bigint;
}>;

type NotificationWireOptions = Readonly<{
  capturedAtEpochMs?: bigint;
  recordRevision?: bigint;
  appLabel?: string | null;
  channelId?: string | null;
  postedAtEpochMs?: bigint;
  loss?: Readonly<{ lostFromCursor: bigint; lostToCursor: bigint; reason: string }>;
}>;

export type WireNotificationRecord = Readonly<{
  kind: RuntimeNotificationRecord["kind"];
  source_epoch: string;
  occurrence_id: string;
  record_key: string;
  record_revision: string;
  cursor: string;
  captured_at_epoch_ms: string;
  capture_revision: string;
  metadata: Readonly<{ package_name: string; app_label: string | null; channel_id: string | null; posted_at_epoch_ms: string }> | null;
  content: Readonly<{ title: string | null; body: string | null }> | null;
  loss: Readonly<{ lost_from_cursor: string; lost_to_cursor: string; reason: string }> | null;
}>;

export type RuntimeAssistantAttachment =
  | Readonly<{ kind: "image" | "file"; artifactId: string; filename: string; mimeType: string; sizeBytes: number; sha256: string }>
  | Readonly<{ kind: "audio"; artifactId: string; filename: string; mimeType: "audio/mp4"; sizeBytes: number; sha256: string; durationMs: number }>;

export type WireAssistantMessage = Readonly<Record<string, unknown>>;

const exactKeys = (value: Record<string, unknown>, keys: readonly string[]): boolean => {
  const actual = Object.keys(value).sort();
  const expected = [...keys].sort();
  return actual.length === expected.length && actual.every((key, index) => key === expected[index]);
};

const recordObject = (value: unknown): value is Record<string, unknown> => typeof value === "object" && value !== null && !Array.isArray(value);
const stringValue = (value: unknown): value is string => typeof value === "string";
const compareCodePoints = (left: string, right: string): number => {
  const a = [...left].map((value) => value.codePointAt(0) ?? 0);
  const b = [...right].map((value) => value.codePointAt(0) ?? 0);
  for (let index = 0; index < Math.min(a.length, b.length); index += 1) if (a[index] !== b[index]) return a[index]! - b[index]!;
  return a.length - b.length;
};
const bigintString = (value: bigint, positive = false): string => {
  if (value < 0n || value > MAX_U64 || (positive && value === 0n)) throw new Error("WIRE_RECORD_UNREPRESENTABLE");
  return value.toString(10);
};

export const encodeNotificationRecord = (
  record: RuntimeNotificationRecord,
  options: NotificationWireOptions = {},
): WireNotificationRecord => {
  if (record.kind === "loss_marker") {
    const loss = options.loss;
    if (!loss || typeof loss.reason !== "string" || loss.reason.length === 0) throw new Error("WIRE_RECORD_UNREPRESENTABLE");
    const encodedLoss = {
      lost_from_cursor: bigintString(loss.lostFromCursor),
      lost_to_cursor: bigintString(loss.lostToCursor),
      reason: loss.reason,
    };
    const wire = Object.freeze({
      kind: record.kind,
      source_epoch: bigintString(record.sourceEpoch),
      occurrence_id: record.recordId,
      record_key: record.recordId,
      record_revision: bigintString(options.recordRevision ?? (record.captureRevision || 1n), true),
      cursor: bigintString(record.cursor, true),
      captured_at_epoch_ms: bigintString(options.capturedAtEpochMs ?? 0n),
      capture_revision: bigintString(record.captureRevision),
      metadata: null,
      content: null,
      loss: encodedLoss,
    });
    if (!validateWireNotificationRecord(wire)) throw new Error("WIRE_RECORD_UNREPRESENTABLE");
    return wire;
  }
  if (!record.packageId || !PACKAGE_NAME.test(record.packageId)) throw new Error("WIRE_RECORD_UNREPRESENTABLE");
  if (record.kind === "delete_tombstone" && (record.title !== null || record.content !== null)) {
    throw new Error("WIRE_RECORD_UNREPRESENTABLE");
  }
  const metadata = {
    package_name: record.packageId,
    app_label: options.appLabel ?? null,
    channel_id: options.channelId ?? null,
    posted_at_epoch_ms: bigintString(options.postedAtEpochMs ?? options.capturedAtEpochMs ?? 0n),
  };
  const wire = Object.freeze({
    kind: record.kind,
    source_epoch: bigintString(record.sourceEpoch),
    occurrence_id: record.recordId,
    record_key: record.recordId,
    record_revision: bigintString(options.recordRevision ?? (record.captureRevision || 1n), true),
    cursor: bigintString(record.cursor, true),
    captured_at_epoch_ms: bigintString(options.capturedAtEpochMs ?? 0n),
    capture_revision: bigintString(record.captureRevision),
    metadata,
    content: record.kind === "upsert" && (record.title !== null || record.content !== null)
      ? { title: record.title, body: record.content }
      : null,
    loss: null,
  });
  if (!validateWireNotificationRecord(wire)) throw new Error("WIRE_RECORD_UNREPRESENTABLE");
  return wire;
};

const validMetadata = (value: unknown): boolean => recordObject(value)
  && exactKeys(value, ["package_name", "app_label", "channel_id", "posted_at_epoch_ms"])
  && stringValue(value.package_name) && PACKAGE_NAME.test(value.package_name)
  && (value.app_label === null || stringValue(value.app_label))
  && (value.channel_id === null || stringValue(value.channel_id))
  && isDecimalU64(value.posted_at_epoch_ms);

const validContent = (value: unknown): boolean => recordObject(value)
  && exactKeys(value, ["title", "body"])
  && (value.title === null || stringValue(value.title))
  && (value.body === null || stringValue(value.body));

const validLoss = (value: unknown): boolean => recordObject(value)
  && exactKeys(value, ["lost_from_cursor", "lost_to_cursor", "reason"])
  && isPositiveU64(value.lost_from_cursor)
  && isPositiveU64(value.lost_to_cursor)
  && stringValue(value.reason) && value.reason.length > 0;

export const validateWireNotificationRecord = (value: unknown): value is WireNotificationRecord => {
  if (!recordObject(value) || !exactKeys(value, ["kind", "source_epoch", "occurrence_id", "record_key", "record_revision", "cursor", "captured_at_epoch_ms", "capture_revision", "metadata", "content", "loss"])) return false;
  if (!(value.kind === "upsert" || value.kind === "delete_tombstone" || value.kind === "loss_marker")) return false;
  if (!isDecimalU64(value.source_epoch)
    || !stringValue(value.occurrence_id) || value.occurrence_id.length === 0
    || !stringValue(value.record_key) || value.record_key.length === 0
    || !isPositiveU64(value.record_revision)
    || !isPositiveU64(value.cursor)
    || !isDecimalU64(value.captured_at_epoch_ms)
    || !isDecimalU64(value.capture_revision)) return false;
  if (value.kind === "loss_marker") return value.metadata === null && value.content === null && validLoss(value.loss);
  return validMetadata(value.metadata) && (value.content === null || validContent(value.content)) && value.loss === null
    && (value.kind === "upsert" || value.content === null);
};

type NotificationFilterWireInput = Readonly<{ packages?: readonly string[]; content?: "metadata" | "content" }>;

const validPackages = (value: unknown): value is readonly string[] => Array.isArray(value)
  && value.length > 0
  && value.every((item) => stringValue(item) && PACKAGE_NAME.test(item))
  && new Set(value).size === value.length
  && value.every((item, index, all) => index === 0 || compareCodePoints(all[index - 1] as string, item) < 0);

const filterFields = (input: NotificationFilterWireInput): Record<string, unknown> => ({
  ...(input.packages === undefined ? {} : { packages: Object.freeze([...input.packages]) }),
  ...(input.content === undefined ? {} : { content: input.content }),
});

export const encodeNotificationQuery = (input: Readonly<{
  operationId: string;
  mode: "on_demand" | "auto_send";
  policyRevision: bigint;
  limit: number;
} & NotificationFilterWireInput>): WireAssistantMessage => {
  const wire = Object.freeze({
    operation: "mobile.notifications.query", mode: input.mode, operation_id: input.operationId,
    policy_revision: bigintString(input.policyRevision), limit: input.limit, ...filterFields(input),
  });
  if (!validateWireNotificationOperation(wire)) throw new Error("WIRE_OPERATION_UNREPRESENTABLE");
  return wire;
};

export const encodeNotificationSubscribe = (input: Readonly<{
  subscriptionId: string;
  policyRevision: bigint;
} & NotificationFilterWireInput>): WireAssistantMessage => {
  const wire = Object.freeze({
    operation: "mobile.notifications.subscribe", subscription_id: input.subscriptionId,
    policy_revision: bigintString(input.policyRevision), ...filterFields(input),
  });
  if (!validateWireNotificationOperation(wire)) throw new Error("WIRE_OPERATION_UNREPRESENTABLE");
  return wire;
};

export const encodeNotificationUnsubscribe = (input: Readonly<{ subscriptionId: string; policyRevision: bigint }>): WireAssistantMessage => {
  const wire = Object.freeze({
    operation: "mobile.notifications.unsubscribe", subscription_id: input.subscriptionId,
    policy_revision: bigintString(input.policyRevision),
  });
  if (!validateWireNotificationOperation(wire)) throw new Error("WIRE_OPERATION_UNREPRESENTABLE");
  return wire;
};

export const validateWireNotificationOperation = (value: unknown): boolean => {
  if (!recordObject(value) || typeof value.operation !== "string") return false;
  const commonFilter = (keys: readonly string[]): boolean => {
    if (!exactKeys(value, keys)) return false;
    if (!isDecimalU64(value.policy_revision)) return false;
    if (Object.hasOwn(value, "packages") && !validPackages(value.packages)) return false;
    if (Object.hasOwn(value, "content") && value.content !== "metadata" && value.content !== "content") return false;
    return true;
  };
  if (value.operation === "mobile.notifications.query") {
    return commonFilter(["operation", "mode", "operation_id", "policy_revision", "limit", ...(Object.hasOwn(value, "packages") ? ["packages"] : []), ...(Object.hasOwn(value, "content") ? ["content"] : [])])
      && (value.mode === "on_demand" || value.mode === "auto_send")
      && stringValue(value.operation_id) && value.operation_id.length > 0
      && typeof value.limit === "number" && Number.isSafeInteger(value.limit) && value.limit >= 1 && value.limit <= 100;
  }
  if (value.operation === "mobile.notifications.subscribe") {
    return commonFilter(["operation", "subscription_id", "policy_revision", ...(Object.hasOwn(value, "packages") ? ["packages"] : []), ...(Object.hasOwn(value, "content") ? ["content"] : [])])
      && stringValue(value.subscription_id) && value.subscription_id.length > 0;
  }
  if (value.operation === "mobile.notifications.unsubscribe") {
    return exactKeys(value, ["operation", "subscription_id", "policy_revision"])
      && stringValue(value.subscription_id) && value.subscription_id.length > 0
      && isDecimalU64(value.policy_revision);
  }
  return false;
};

type RuntimeSmsRecord = Readonly<{
  recordId: string;
  sourceEpoch: bigint;
  recordRevision: bigint;
  cursorMessageAtEpochMs: bigint;
  cursorProviderId: bigint;
  capturedAtEpochMs: bigint;
  captureRevision: bigint;
  policyRevision: bigint;
  senderAddress: string | null;
  threadId: string | null;
  messageAtEpochMs: bigint;
  observedAtEpochMs: bigint;
  read: boolean;
  subscriptionId: number | null;
  body: string;
}>;

export type WireSmsRecord = Readonly<{
  kind: "upsert";
  record_id: string;
  source_epoch: string;
  record_revision: string;
  cursor_message_at_epoch_ms: string;
  cursor_provider_id: string;
  captured_at_epoch_ms: string;
  capture_revision: string;
  policy_revision: string;
  metadata: Readonly<{
    sender_address: string | null;
    thread_id: string | null;
    message_at_epoch_ms: string;
    observed_at_epoch_ms: string;
    read: boolean;
    subscription_id: number | null;
  }>;
  content: Readonly<{ body: string }>;
}>;

const validSmsSubscriptionId = (value: unknown): value is number =>
  typeof value === "number" && Number.isSafeInteger(value) && value >= 0 && value <= 2_147_483_647;

const validSmsRecordId = (value: unknown): value is string =>
  typeof value === "string" && value.startsWith("sms:") && isPositiveSmsProviderId(value.slice(4));

const validSmsMetadata = (value: unknown): boolean => recordObject(value)
  && exactKeys(value, ["sender_address", "thread_id", "message_at_epoch_ms", "observed_at_epoch_ms", "read", "subscription_id"])
  && (value.sender_address === null || stringValue(value.sender_address))
  && (value.thread_id === null || stringValue(value.thread_id))
  && isDecimalU64(value.message_at_epoch_ms)
  && isDecimalU64(value.observed_at_epoch_ms)
  && typeof value.read === "boolean"
  && (value.subscription_id === null || validSmsSubscriptionId(value.subscription_id));

const validSmsContent = (value: unknown): boolean => recordObject(value)
  && exactKeys(value, ["body"])
  && stringValue(value.body);

export const validateWireSmsRecord = (value: unknown): value is WireSmsRecord => recordObject(value)
  && exactKeys(value, [
    "kind", "record_id", "source_epoch", "record_revision", "cursor_message_at_epoch_ms", "cursor_provider_id",
    "captured_at_epoch_ms", "capture_revision", "policy_revision", "metadata", "content",
  ])
  && value.kind === "upsert"
  && validSmsRecordId(value.record_id)
  && isDecimalU64(value.source_epoch)
  && isPositiveU64(value.record_revision)
  && isDecimalU64(value.cursor_message_at_epoch_ms)
  && isPositiveSmsProviderId(value.cursor_provider_id)
  && isDecimalU64(value.captured_at_epoch_ms)
  && isDecimalU64(value.capture_revision)
  && isDecimalU64(value.policy_revision)
  && validSmsMetadata(value.metadata)
  && recordObject(value.metadata)
  && value.record_id === `sms:${value.cursor_provider_id}`
  && value.cursor_message_at_epoch_ms === value.metadata.message_at_epoch_ms
  && validSmsContent(value.content);

export const encodeSmsRecord = (record: RuntimeSmsRecord): WireSmsRecord => {
  const metadata = Object.freeze({
    sender_address: record.senderAddress,
    thread_id: record.threadId,
    message_at_epoch_ms: bigintString(record.messageAtEpochMs),
    observed_at_epoch_ms: bigintString(record.observedAtEpochMs),
    read: record.read,
    subscription_id: record.subscriptionId,
  });
  const content = Object.freeze({ body: record.body });
  const wire = Object.freeze({
    kind: "upsert" as const,
    record_id: record.recordId,
    source_epoch: bigintString(record.sourceEpoch),
    record_revision: bigintString(record.recordRevision, true),
    cursor_message_at_epoch_ms: bigintString(record.cursorMessageAtEpochMs),
    cursor_provider_id: bigintString(record.cursorProviderId),
    captured_at_epoch_ms: bigintString(record.capturedAtEpochMs),
    capture_revision: bigintString(record.captureRevision),
    policy_revision: bigintString(record.policyRevision),
    metadata,
    content,
  });
  if (!validateWireSmsRecord(wire)) throw new Error("WIRE_RECORD_UNREPRESENTABLE");
  return wire;
};

export const encodeSmsQuery = (input: Readonly<{ operationId: string; policyRevision: bigint; limit: number }>): WireAssistantMessage => {
  const wire = Object.freeze({
    operation: "mobile.sms.query", operation_id: input.operationId,
    policy_revision: bigintString(input.policyRevision), limit: input.limit,
  });
  if (!validateWireSmsOperation(wire)) throw new Error("WIRE_OPERATION_UNREPRESENTABLE");
  return wire;
};

export const encodeSmsSubscribe = (input: Readonly<{ subscriptionId: string; policyRevision: bigint }>): WireAssistantMessage => {
  const wire = Object.freeze({
    operation: "mobile.sms.subscribe", subscription_id: input.subscriptionId,
    policy_revision: bigintString(input.policyRevision),
  });
  if (!validateWireSmsOperation(wire)) throw new Error("WIRE_OPERATION_UNREPRESENTABLE");
  return wire;
};

export const encodeSmsUnsubscribe = (input: Readonly<{ subscriptionId: string; policyRevision: bigint }>): WireAssistantMessage => {
  const wire = Object.freeze({
    operation: "mobile.sms.unsubscribe", subscription_id: input.subscriptionId,
    policy_revision: bigintString(input.policyRevision),
  });
  if (!validateWireSmsOperation(wire)) throw new Error("WIRE_OPERATION_UNREPRESENTABLE");
  return wire;
};

export const validateWireSmsOperation = (value: unknown): boolean => {
  if (!recordObject(value) || typeof value.operation !== "string") return false;
  if (value.operation === "mobile.sms.query") {
    return exactKeys(value, ["operation", "operation_id", "policy_revision", "limit"])
      && stringValue(value.operation_id) && value.operation_id.length > 0
      && isDecimalU64(value.policy_revision)
      && typeof value.limit === "number" && Number.isSafeInteger(value.limit) && value.limit >= 1 && value.limit <= 10_000;
  }
  if (value.operation === "mobile.sms.subscribe" || value.operation === "mobile.sms.unsubscribe") {
    return exactKeys(value, ["operation", "subscription_id", "policy_revision"])
      && stringValue(value.subscription_id) && value.subscription_id.length > 0
      && isDecimalU64(value.policy_revision);
  }
  return false;
};

export const encodeAssistantRequest = (input: Readonly<{ operationId: string; text: string; attachments: readonly RuntimeAssistantAttachment[] }>): WireAssistantMessage => {
  const wire = Object.freeze({
    kind: "request",
    operation_id: input.operationId,
    text: input.text,
    attachments: Object.freeze(input.attachments.map((attachment) => Object.freeze({
      kind: attachment.kind,
      artifact_id: attachment.artifactId,
      media_type: attachment.mimeType,
      byte_length: attachment.sizeBytes,
      sha256: attachment.sha256.toLowerCase(),
      display_name: attachment.filename,
      ...(attachment.kind === "audio" ? { duration_ms: attachment.durationMs } : {}),
    }))),
  });
  if (!validateWireAssistantMessage(wire)) throw new Error("WIRE_ASSISTANT_UNREPRESENTABLE");
  return wire;
};

export const encodeAssistantResponse = (input: Readonly<{ operationId: string; reply: string; error?: string | null }>): WireAssistantMessage => {
  const wire = Object.freeze({
    kind: "response", operation_id: input.operationId, text: input.reply, status: input.error ? "failed" : "complete", ...(input.error === undefined ? {} : { error: input.error }),
  });
  if (!validateWireAssistantMessage(wire)) throw new Error("WIRE_ASSISTANT_UNREPRESENTABLE");
  return wire;
};

export const encodeAssistantEvent = (input: Readonly<{
  operationId: string;
  messageId: string;
  sequence: bigint;
  event: "delta" | "complete" | "failed";
  text: string;
  error?: string;
}>): WireAssistantMessage => {
  const wire = Object.freeze({
    kind: "event", operation_id: input.operationId, message_id: input.messageId,
    sequence: bigintString(input.sequence, true), event: input.event, text: input.text,
    ...(input.error === undefined ? {} : { error: input.error }),
  });
  if (!validateWireAssistantMessage(wire)) throw new Error("WIRE_ASSISTANT_UNREPRESENTABLE");
  return wire;
};

const validWireAttachment = (value: unknown): boolean => recordObject(value)
  && stringValue(value.artifact_id) && ARTIFACT_ID.test(value.artifact_id)
  && ((exactKeys(value, ["kind", "artifact_id", "media_type", "byte_length", "sha256", "display_name"])
    && (value.kind === "image" || value.kind === "file")
    && stringValue(value.media_type) && MEDIA_TYPES.has(value.media_type)
    && (value.kind !== "image" || value.media_type.startsWith("image/"))
    && (value.kind !== "file" || !value.media_type.startsWith("image/")))
    || (exactKeys(value, ["kind", "artifact_id", "media_type", "byte_length", "sha256", "display_name", "duration_ms"])
      && value.kind === "audio" && value.media_type === "audio/mp4"
      && Number.isSafeInteger(value.duration_ms) && (value.duration_ms as number) >= 1 && (value.duration_ms as number) <= AUDIO_MAX_DURATION_MS))
  && Number.isSafeInteger(value.byte_length) && (value.byte_length as number) >= 0 && (value.byte_length as number) <= 26_214_400
  && (value.kind !== "audio" || (value.byte_length as number) <= AUDIO_MAX_BYTES)
  && stringValue(value.sha256) && SHA256.test(value.sha256)
  && stringValue(value.display_name) && value.display_name.length > 0 && value.display_name.length <= 255
  && !value.display_name.includes("/") && !value.display_name.includes("\\")
;

export const validateWireAssistantMessage = (value: unknown): value is WireAssistantMessage => {
  if (!recordObject(value) || typeof value.kind !== "string") return false;
  if (value.kind === "request") return exactKeys(value, ["kind", "operation_id", "text", "attachments"])
    && stringValue(value.operation_id) && value.operation_id.length > 0
    && stringValue(value.text) && value.text.length <= 50_000
    && Array.isArray(value.attachments) && value.attachments.length <= 4
    && value.attachments.every(validWireAttachment)
    && value.attachments.reduce((total, attachment) => total + (attachment as Record<string, unknown>).byte_length as number, 0) <= ASSISTANT_MAX_ATTACHMENT_BYTES;
  if (value.kind === "response") return (exactKeys(value, ["kind", "operation_id", "text", "status"]) || exactKeys(value, ["kind", "operation_id", "text", "status", "error"]))
    && stringValue(value.operation_id) && value.operation_id.length > 0
    && stringValue(value.text) && value.text.length <= 50_000
    && (value.status === "complete" || value.status === "failed")
    && (!Object.hasOwn(value, "error") || value.error === null || stringValue(value.error));
  if (value.kind === "event") return (exactKeys(value, ["kind", "operation_id", "message_id", "sequence", "event", "text"])
    || exactKeys(value, ["kind", "operation_id", "message_id", "sequence", "event", "text", "error"]))
    && stringValue(value.operation_id) && value.operation_id.length > 0
    && stringValue(value.message_id) && value.message_id.length > 0
    && isPositiveU64(value.sequence)
    && (value.event === "delta" || value.event === "complete" || value.event === "failed")
    && stringValue(value.text) && value.text.length <= 50_000
    && (value.event === "failed" ? stringValue(value.error) && value.error.length > 0 : !Object.hasOwn(value, "error"));
  return false;
};
