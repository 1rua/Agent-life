/**
 * Small, backend-neutral adapter contract used by the Hermes and OpenClaw
 * fixtures.  It deliberately has no dependency on protocol/src or Android.
 * The fake bridge below is in-memory and is only a deterministic test double.
 */

export const FROZEN_NOTIFICATION_TOOLS = Object.freeze([
  "mobile.notifications.query",
  "mobile.notifications.subscribe",
  "mobile.notifications.unsubscribe",
] as const);

export const FROZEN_SMS_TOOLS = Object.freeze([
  "mobile.sms.query",
  "mobile.sms.subscribe",
  "mobile.sms.unsubscribe",
] as const);

export type NotificationToolName = typeof FROZEN_NOTIFICATION_TOOLS[number];
export type SmsToolName = typeof FROZEN_SMS_TOOLS[number];
export type AdapterToolName = NotificationToolName | SmsToolName;

export const FROZEN_PROVIDER_TOOLS: readonly AdapterToolName[] = Object.freeze([
  ...FROZEN_NOTIFICATION_TOOLS,
  ...FROZEN_SMS_TOOLS,
]);

export const ASSISTANT_ATTACHMENT_LIMITS = Object.freeze({
  maxFiles: 4,
  maxFileBytes: 25 * 1024 * 1024,
  maxAudioBytes: 10 * 1024 * 1024,
  maxAudioDurationMs: 120000,
  maxMessageBytes: 50 * 1024 * 1024,
});

export const ZERO_RETENTION_UNAVAILABLE = "ZERO_RETENTION_UNAVAILABLE" as const;
const ARTIFACT_ID = /^[A-Za-z0-9._~-]{1,128}$/;

export type AdapterIdentity = Readonly<{
  tenantId: string;
  humanPrincipalId: string;
  deviceId: string;
  agentInstanceId: string;
  workspaceId: string;
  sessionId: string;
  jobId?: string;
}>;

export type AdapterBinding = AdapterIdentity & Readonly<{
  authorizedDeviceIds: readonly string[];
}>;

export type PairedBinding = Readonly<{
  tenantId: string;
  humanPrincipalId: string;
  deviceId: string;
  bridgeFingerprint: string;
  pairingGeneration: bigint;
  policyAttestationRevision: bigint;
}>;

export type NotificationRecord = Readonly<{
  kind: "upsert" | "delete_tombstone" | "loss_marker";
  recordId: string;
  packageId: string | null;
  title: string | null;
  content: string | null;
}>;

export type NotificationEvent = Readonly<{
  eventId: string;
  subscriptionId: string;
  binding: AdapterIdentity;
  record: NotificationRecord;
}>;

export type SmsRecord = Readonly<{
  recordId: string;
  senderAddress: string | null;
  threadId: string | null;
  messageAtEpochMs: bigint;
  observedAtEpochMs: bigint;
  read: boolean;
  subscriptionId: number | null;
  body: string;
  sourceEpoch: bigint;
  cursorProviderId: bigint;
  captureRevision: bigint;
  policyRevision: bigint;
}>;

export type SmsEvent = Readonly<{
  eventId: string;
  subscriptionId: string;
  binding: AdapterIdentity;
  record: SmsRecord;
}>;

export type ZeroRetentionEvidence = Readonly<{
  provider: string;
  profileId: string;
  revision: string;
  expiresAt: string;
  /** The provider must not return a durable object/retention identifier. */
  providerObjectRetention: "none" | "provider_retains";
  requestResponseLoggingDisabled: boolean;
  trainingDisabled: boolean;
  humanReviewDisabled: boolean;
}>;

type AssistantAttachmentBase = Readonly<{
  artifactId: string;
  filename: string;
  sizeBytes: number;
  sha256: string;
}>;

export type AssistantAttachment =
  | (AssistantAttachmentBase & Readonly<{ kind: "image"; mimeType: "image/jpeg" | "image/png" | "image/webp" }>)
  | (AssistantAttachmentBase & Readonly<{ kind: "file"; mimeType: "application/pdf" | "text/plain" }>)
  | (AssistantAttachmentBase & Readonly<{ kind: "audio"; mimeType: "audio/mp4"; durationMs: number }>);

export type AssistantMessageInput = Readonly<{
  messageId: string;
  text: string;
  attachments?: readonly AssistantAttachment[];
}>;

export type AssistantMessageResult = Readonly<{
  messageId: string;
  status: "accepted";
  reply: string;
}>;

export type NotificationQueryInput = Readonly<{
  toolCallId: string;
  deviceId: string;
  mode: "on_demand" | "auto_send";
  limit: number;
  content?: "metadata" | "content";
  packages?: readonly string[];
}>;

export type NotificationSubscriptionInput = Readonly<{ deviceId: string; packages?: readonly string[]; content?: "metadata" | "content" }>;

export type SmsQueryInput = Readonly<{
  toolCallId: string;
  deviceId: string;
  limit: number;
}>;

export type SmsSubscriptionInput = Readonly<{ deviceId: string }>;

export type AdapterProfile = Readonly<{
  kind: "chat" | "tool" | "event";
  id: string;
  authoritative: boolean;
}>;

export type AdapterOptions = Readonly<{
  context: AdapterBinding;
  zeroRetention?: ZeroRetentionEvidence;
  /** Optional locked profile ID supplied by a provider-specific adapter. */
  zeroRetentionProfileId?: string;
  onDemand?: () => Promise<readonly NotificationRecord[]>;
  onDemandSms?: () => Promise<readonly SmsRecord[]>;
  allowNotificationContent?: boolean;
  profiles?: readonly AdapterProfile[];
}>;

type TraceEntry = Readonly<{ kind: string; eventId?: string; operationId?: string; messageId?: string }>;

export class AdapterError extends Error {
  readonly code: string;
  constructor(code: string) {
    super(code);
    this.name = "AdapterError";
    this.code = code;
  }
}

const cloneRecord = (record: NotificationRecord): NotificationRecord => Object.freeze({ ...record });
const cloneSmsRecord = (record: SmsRecord): SmsRecord => Object.freeze({ ...record });

const MAX_U64 = 18_446_744_073_709_551_615n;
const MAX_SMS_PROVIDER_ID = 9_223_372_036_854_775_807n;
const SMS_RECORD_ID = /^sms:[1-9][0-9]*$/;
const SMS_RECORD_KEYS = new Set([
  "recordId", "senderAddress", "threadId", "messageAtEpochMs", "observedAtEpochMs", "read",
  "subscriptionId", "body", "sourceEpoch", "cursorProviderId", "captureRevision", "policyRevision",
]);

const isU64 = (value: unknown): value is bigint => typeof value === "bigint" && value >= 0n && value <= MAX_U64;
const isSmsProviderId = (value: unknown): value is bigint =>
  typeof value === "bigint" && value > 0n && value <= MAX_SMS_PROVIDER_ID;
const isSmsRecordId = (value: unknown): value is string =>
  typeof value === "string" && SMS_RECORD_ID.test(value) && BigInt(value.slice(4)) <= MAX_SMS_PROVIDER_ID;

const validateSmsRecord = (record: SmsRecord): void => {
  if (typeof record !== "object" || record === null || Array.isArray(record)) throw new AdapterError("SMS_RECORD_INVALID");
  const keys = Object.keys(record);
  if (keys.length !== SMS_RECORD_KEYS.size || keys.some((key) => !SMS_RECORD_KEYS.has(key))) throw new AdapterError("SMS_RECORD_INVALID");
  if (!isSmsRecordId(record.recordId)) throw new AdapterError("SMS_RECORD_INVALID");
  if ((record.senderAddress !== null && typeof record.senderAddress !== "string")
    || (record.threadId !== null && typeof record.threadId !== "string")) throw new AdapterError("SMS_RECORD_INVALID");
  if (![record.messageAtEpochMs, record.observedAtEpochMs, record.sourceEpoch, record.captureRevision, record.policyRevision].every(isU64)
    || !isSmsProviderId(record.cursorProviderId)) {
    throw new AdapterError("SMS_RECORD_INVALID");
  }
  if (record.recordId !== `sms:${record.cursorProviderId}`) throw new AdapterError("SMS_RECORD_INVALID");
  if (typeof record.read !== "boolean" || typeof record.body !== "string") throw new AdapterError("SMS_RECORD_INVALID");
  if (record.subscriptionId !== null
    && (!Number.isSafeInteger(record.subscriptionId) || record.subscriptionId < 0 || record.subscriptionId > 2_147_483_647)) {
    throw new AdapterError("SMS_RECORD_INVALID");
  }
};

const bindingKey = (binding: Pick<AdapterBinding, "tenantId" | "humanPrincipalId" | "deviceId" | "agentInstanceId" | "workspaceId" | "sessionId" | "jobId">): string =>
  [binding.tenantId, binding.humanPrincipalId, binding.deviceId, binding.agentInstanceId, binding.workspaceId, binding.sessionId, binding.jobId ?? ""].join("\u0000");

const pairKey = (binding: PairedBinding): string =>
  [binding.tenantId, binding.humanPrincipalId, binding.deviceId, binding.bridgeFingerprint, binding.pairingGeneration].join("\u0000");

const unicodeCodePointCompare = (left: string, right: string): number => {
  const a = [...left].map((value) => value.codePointAt(0) ?? 0);
  const b = [...right].map((value) => value.codePointAt(0) ?? 0);
  for (let index = 0; index < Math.min(a.length, b.length); index += 1) {
    if (a[index] !== b[index]) return a[index] - b[index];
  }
  return a.length - b.length;
};

const PACKAGE_NAME = /^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$/;

export const validatePackageFilter = (packages: readonly string[] | undefined): readonly string[] | undefined => {
  if (packages === undefined) return undefined;
  if (!Array.isArray(packages) || packages.length < 1) throw new AdapterError("PACKAGE_FILTER_INVALID");
  const copy = [...packages];
  if (copy.some((value) => typeof value !== "string" || !PACKAGE_NAME.test(value))) throw new AdapterError("PACKAGE_FILTER_INVALID");
  if (new Set(copy).size !== copy.length) throw new AdapterError("PACKAGE_FILTER_INVALID");
  for (let index = 1; index < copy.length; index += 1) {
    if (unicodeCodePointCompare(copy[index - 1], copy[index]) >= 0) throw new AdapterError("PACKAGE_FILTER_INVALID");
  }
  return Object.freeze(copy);
};

const hasOwn = (value: object, key: string): boolean => Object.prototype.hasOwnProperty.call(value, key);

function assertObject(value: unknown, code = "REQUEST_INVALID"): asserts value is object {
  if (value === null || typeof value !== "object" || Array.isArray(value)) throw new AdapterError(code);
}

const assertNoModelIdentity = (value: object): void => {
  // Principal, operation and session identity are supplied by the authenticated
  // adapter runtime.  Rejecting these fields catches accidental model injection
  // instead of silently ignoring an attacker-controlled value.
  for (const key of [
    "tenantId", "humanPrincipalId", "agentInstanceId", "workspaceId", "sessionId", "jobId", "operationId",
    "model", "modelId", "modelIdentity", "providerModelId",
  ]) {
    if (hasOwn(value, key)) throw new AdapterError("MODEL_IDENTITY_FIELD");
  }
};

const assertExactKeys = (value: object, allowed: readonly string[], code = "REQUEST_FIELDS_INVALID"): void => {
  const allowedSet = new Set(allowed);
  if (Object.keys(value).some((key) => !allowedSet.has(key))) throw new AdapterError(code);
};

export const isCurrentZeroRetention = (evidence: ZeroRetentionEvidence | undefined, now = new Date()): evidence is ZeroRetentionEvidence => {
  if (!evidence || !evidence.provider || !evidence.profileId || !evidence.revision) return false;
  if (evidence.providerObjectRetention !== "none") return false;
  if (!evidence.requestResponseLoggingDisabled || !evidence.trainingDisabled || !evidence.humanReviewDisabled) return false;
  const expiry = Date.parse(evidence.expiresAt);
  return Number.isFinite(expiry) && expiry > now.getTime();
};

type StoredOperation = Readonly<{ sessionId: string; mode: NotificationQueryInput["mode"]; packagesKey: string; content: "metadata" | "content"; result: readonly NotificationRecord[] }>;
type StoredEvent = Readonly<{ event: NotificationEvent; acknowledged: boolean }>;
type StoredSubscription = Readonly<{ binding: AdapterBinding; packages?: readonly string[]; content: "metadata" | "content" }>;
type StoredSmsOperation = Readonly<{ sessionId: string; deviceId: string; limit: number; result: readonly SmsRecord[] }>;
type StoredSmsEvent = Readonly<{ event: SmsEvent; acknowledged: boolean }>;
type StoredSmsSubscription = Readonly<{ binding: AdapterBinding }>;

/** Deterministic paired Bridge fake.  No endpoint, socket, or generic dial API is exposed. */
class InMemoryPairedBridge {
  #binding: PairedBinding | null = null;
  #generation = 0n;
  #sessionOpen = false;
  #operations = new Map<string, StoredOperation>();
  #operationKinds = new Map<string, "notification" | "sms">();
  #smsOperations = new Map<string, StoredSmsOperation>();
  #claims = new Map<string, number>();
  #subscriptions = new Map<string, StoredSubscription>();
  #events = new Map<string, StoredEvent>();
  #smsSubscriptions = new Map<string, StoredSmsSubscription>();
  #smsEvents = new Map<string, StoredSmsEvent>();
  #eventSequence = 0;
  #onDemand: (() => Promise<readonly NotificationRecord[]>) | undefined;
  #onDemandSms: (() => Promise<readonly SmsRecord[]>) | undefined;

  constructor(onDemand: (() => Promise<readonly NotificationRecord[]>) | undefined, onDemandSms: (() => Promise<readonly SmsRecord[]>) | undefined) {
    this.#onDemand = onDemand;
    this.#onDemandSms = onDemandSms;
  }

  async open(binding: PairedBinding): Promise<FakeSession> {
    if (this.#sessionOpen) this.#sessionOpen = false;
    this.#binding = Object.freeze({ ...binding });
    this.#generation += 1n;
    this.#sessionOpen = true;
    return this.session();
  }

  async reconnect(binding: PairedBinding): Promise<FakeSession> {
    if (!this.#binding || pairKey(this.#binding) !== pairKey(binding)) throw new AdapterError("PAIRING_BINDING_MISMATCH");
    return this.open(binding);
  }

  session(): FakeSession {
    const generation = this.#generation;
    return Object.freeze({
      connectionGeneration: generation,
      sendControl: async (_wire: string): Promise<void> => {
        if (!this.#sessionOpen || generation !== this.#generation) throw new AdapterError("CONNECTION_FENCED");
      },
    });
  }

  #assertSession(binding: AdapterBinding): void {
    if (!this.#sessionOpen || !this.#binding || this.#binding.tenantId !== binding.tenantId || this.#binding.humanPrincipalId !== binding.humanPrincipalId || this.#binding.deviceId !== binding.deviceId) {
      throw new AdapterError("CONNECTION_FENCED");
    }
  }

  async query(binding: AdapterBinding, operationId: string, sessionId: string, mode: NotificationQueryInput["mode"], packages: readonly string[] | undefined, content: "metadata" | "content"): Promise<readonly NotificationRecord[]> {
    this.#assertSession(binding);
    if (this.#operationKinds.get(operationId) === "sms") throw new AdapterError("OPERATION_PARAMETERS_MISMATCH");
    const previous = this.#operations.get(operationId);
    if (previous) {
      if (previous.sessionId !== sessionId) throw new AdapterError("OPERATION_IDENTITY_MISMATCH");
      if (previous.mode !== mode || previous.packagesKey !== (packages?.join("\u0000") ?? "") || previous.content !== content) throw new AdapterError("OPERATION_PARAMETERS_MISMATCH");
      return previous.result;
    }
    const captured = mode === "on_demand" && this.#onDemand ? [...(await this.#onDemand())].map(cloneRecord) : [];
    const filtered = packages ? captured.filter((record) => record.packageId === null || packages.includes(record.packageId)) : captured;
    const result = Object.freeze(filtered.map((record) => content === "metadata" ? Object.freeze({ ...record, content: null }) : record));
    this.#operations.set(operationId, Object.freeze({ sessionId, mode, packagesKey: packages?.join("\u0000") ?? "", content, result }));
    this.#operationKinds.set(operationId, "notification");
    this.#claims.set(operationId, (this.#claims.get(operationId) ?? 0) + 1);
    return result;
  }

  async querySms(binding: AdapterBinding, operationId: string, sessionId: string, deviceId: string, limit: number): Promise<readonly SmsRecord[]> {
    this.#assertSession(binding);
    if (this.#operationKinds.get(operationId) === "notification") throw new AdapterError("OPERATION_PARAMETERS_MISMATCH");
    const previous = this.#smsOperations.get(operationId);
    if (previous) {
      if (previous.sessionId !== sessionId) throw new AdapterError("OPERATION_IDENTITY_MISMATCH");
      if (previous.deviceId !== deviceId || previous.limit !== limit) throw new AdapterError("OPERATION_PARAMETERS_MISMATCH");
      return previous.result;
    }
    const captured = this.#onDemandSms ? await this.#onDemandSms() : [];
    const result = Object.freeze([...captured].map((record) => {
      validateSmsRecord(record);
      return cloneSmsRecord(record);
    }).slice(0, limit));
    this.#smsOperations.set(operationId, Object.freeze({ sessionId, deviceId, limit, result }));
    this.#operationKinds.set(operationId, "sms");
    this.#claims.set(operationId, (this.#claims.get(operationId) ?? 0) + 1);
    return result;
  }

  operationClaims(): readonly Readonly<{ operationId: string; claims: number }>[] {
    return Object.freeze([...this.#claims].map(([operationId, claims]) => Object.freeze({ operationId, claims })));
  }

  async subscribe(binding: AdapterBinding, subscriptionId: string, packages: readonly string[] | undefined, content: "metadata" | "content"): Promise<void> {
    this.#assertSession(binding);
    this.#subscriptions.set(subscriptionId, Object.freeze({ binding: Object.freeze({ ...binding, authorizedDeviceIds: [...binding.authorizedDeviceIds] }), packages, content }));
  }

  async unsubscribe(binding: AdapterBinding, subscriptionId: string): Promise<boolean> {
    this.#assertSession(binding);
    const subscription = this.#subscriptions.get(subscriptionId);
    if (!subscription || bindingKey(subscription.binding) !== bindingKey(binding)) return false;
    this.#subscriptions.delete(subscriptionId);
    return true;
  }

  publish(subscriptionId: string, record: NotificationRecord): NotificationEvent | null {
    const subscription = this.#subscriptions.get(subscriptionId);
    if (!subscription) throw new AdapterError("SUBSCRIPTION_NOT_FOUND");
    if (subscription.packages && record.packageId !== null && !subscription.packages.includes(record.packageId)) return null;
    const eventRecord = subscription.content === "metadata" ? Object.freeze({ ...record, content: null }) : cloneRecord(record);
    const event: NotificationEvent = Object.freeze({
      eventId: `event-${++this.#eventSequence}`,
      subscriptionId,
      binding: subscription.binding,
      record: eventRecord,
    });
    this.#events.set(event.eventId, Object.freeze({ event, acknowledged: false }));
    return event;
  }

  acknowledge(event: NotificationEvent): NotificationEvent {
    const stored = this.#events.get(event.eventId);
    if (!stored || stored.event.subscriptionId !== event.subscriptionId) throw new AdapterError("EVENT_NOT_FOUND");
    if (bindingKey(stored.event.binding) !== bindingKey(event.binding)) throw new AdapterError("EVENT_NOT_FOUND");
    this.#events.set(event.eventId, Object.freeze({ event: stored.event, acknowledged: true }));
    return stored.event;
  }

  async subscribeSms(binding: AdapterBinding, subscriptionId: string): Promise<void> {
    this.#assertSession(binding);
    this.#smsSubscriptions.set(subscriptionId, Object.freeze({
      binding: Object.freeze({ ...binding, authorizedDeviceIds: [...binding.authorizedDeviceIds] }),
    }));
  }

  async unsubscribeSms(binding: AdapterBinding, subscriptionId: string): Promise<boolean> {
    this.#assertSession(binding);
    const subscription = this.#smsSubscriptions.get(subscriptionId);
    if (!subscription || bindingKey(subscription.binding) !== bindingKey(binding)) return false;
    this.#smsSubscriptions.delete(subscriptionId);
    return true;
  }

  publishSms(subscriptionId: string, record: SmsRecord): SmsEvent {
    const subscription = this.#smsSubscriptions.get(subscriptionId);
    if (!subscription) throw new AdapterError("SUBSCRIPTION_NOT_FOUND");
    validateSmsRecord(record);
    const event = Object.freeze({
      eventId: `sms-event-${++this.#eventSequence}`,
      subscriptionId,
      binding: subscription.binding,
      record: cloneSmsRecord(record),
    });
    this.#smsEvents.set(event.eventId, Object.freeze({ event, acknowledged: false }));
    return event;
  }

  acknowledgeSms(event: SmsEvent): SmsEvent {
    const stored = this.#smsEvents.get(event.eventId);
    if (!stored || stored.event.subscriptionId !== event.subscriptionId
      || bindingKey(stored.event.binding) !== bindingKey(event.binding)) throw new AdapterError("EVENT_NOT_FOUND");
    this.#smsEvents.set(event.eventId, Object.freeze({ event: stored.event, acknowledged: true }));
    return stored.event;
  }
}

export type FakeSession = Readonly<{
  connectionGeneration: bigint;
  sendControl: (wire: string) => Promise<void>;
}>;

export type FakeAdapter = Readonly<{
  pair: (binding: PairedBinding) => Promise<FakeSession>;
  reconnect: (binding?: PairedBinding) => Promise<FakeSession>;
  binding: () => PairedBinding | null;
  tools: () => readonly AdapterToolName[];
  queryNotifications: (input: NotificationQueryInput) => Promise<readonly NotificationRecord[]>;
  subscribeNotifications: (input: NotificationSubscriptionInput) => Promise<Readonly<{ subscriptionId: string }>>;
  unsubscribeNotifications: () => Promise<Readonly<{ removed: boolean }>>;
  receiveNotificationEvent: (event: NotificationEvent) => Promise<Readonly<{ eventId: string; subscriptionId: string; record: NotificationRecord }>>;
  /** Test-only server event source; production adapters receive this from the Bridge event hook. */
  emitAutoSend: (record: NotificationRecord) => NotificationEvent | null;
  querySms: (input: SmsQueryInput) => Promise<readonly SmsRecord[]>;
  subscribeSms: (input: SmsSubscriptionInput) => Promise<Readonly<{ subscriptionId: string }>>;
  unsubscribeSms: () => Promise<Readonly<{ removed: boolean }>>;
  receiveSmsEvent: (event: SmsEvent) => Promise<Readonly<{ eventId: string; subscriptionId: string; record: SmsRecord }>>;
  /** Test-only server event source; production adapters receive this from the Bridge event hook. */
  emitSmsAutoSend: (record: SmsRecord) => SmsEvent;
  sendAssistantMessage: (input: AssistantMessageInput) => Promise<AssistantMessageResult>;
  invokeTool: (name: string, input: unknown) => Promise<unknown>;
  operationClaims: () => readonly Readonly<{ operationId: string; claims: number }>[];
  acknowledgedEvents: () => readonly string[];
  assistantMetadata: () => Readonly<{ messageId: string; attachmentCount: number; attachments: readonly AssistantAttachment[] }> | null;
  diagnostics: () => readonly TraceEntry[];
}>;

const validateProfiles = (profiles: readonly AdapterProfile[] | undefined): void => {
  if (!profiles) return;
  for (const kind of ["chat", "tool", "event"] as const) {
    const authoritative = profiles.filter((profile) => profile.kind === kind && profile.authoritative);
    if (authoritative.length === 0) throw new AdapterError("AUTHORITATIVE_PROFILE_REQUIRED");
    if (authoritative.length > 1) throw new AdapterError("AUTHORITATIVE_PROFILE_DUPLICATE");
  }
};

export const createFakeAdapter = (options: AdapterOptions): FakeAdapter => {
  validateProfiles(options.profiles);
  const context = Object.freeze({ ...options.context, authorizedDeviceIds: [...options.context.authorizedDeviceIds] });
  const bridge = new InMemoryPairedBridge(options.onDemand, options.onDemandSms);
  const allowNotificationContent = options.allowNotificationContent === true;
  let pairedBinding: PairedBinding | null = null;
  let session: FakeSession | null = null;
  let subscriptionId: string | null = null;
  let smsSubscriptionId: string | null = null;
  let lastMetadata: Readonly<{ messageId: string; attachmentCount: number; attachments: readonly AssistantAttachment[] }> | null = null;
  const trace: TraceEntry[] = [];
  const acknowledged: string[] = [];
  const deliveredEvents = new Set<string>();

  const assertDevice = (deviceId: string): void => {
    if (!context.authorizedDeviceIds.includes(deviceId)) throw new AdapterError("DEVICE_NOT_AUTHORIZED");
    if (!pairedBinding || pairedBinding.deviceId !== deviceId) throw new AdapterError("DEVICE_NOT_PAIRED");
  };
  const assertConnected = (): void => {
    if (!session || !pairedBinding) throw new AdapterError("CONNECTION_FENCED");
  };
  const assertZeroRetention = (): void => {
    if (!isCurrentZeroRetention(options.zeroRetention) || (options.zeroRetentionProfileId !== undefined && options.zeroRetention?.profileId !== options.zeroRetentionProfileId)) throw new AdapterError(ZERO_RETENTION_UNAVAILABLE);
  };

  const adapter: FakeAdapter = {
    pair: async (binding) => {
      if (binding.tenantId !== context.tenantId || binding.humanPrincipalId !== context.humanPrincipalId || !context.authorizedDeviceIds.includes(binding.deviceId)) throw new AdapterError("PAIRING_BINDING_MISMATCH");
      if (pairedBinding && pairKey(pairedBinding) !== pairKey(binding)) throw new AdapterError("PAIRING_BINDING_MISMATCH");
      pairedBinding = Object.freeze({ ...binding });
      session = await bridge.open(binding);
      trace.push({ kind: "paired" });
      return session;
    },
    reconnect: async (binding = pairedBinding ?? (() => { throw new AdapterError("NOT_PAIRED"); })()) => {
      if (!pairedBinding || pairKey(binding) !== pairKey(pairedBinding)) throw new AdapterError("PAIRING_BINDING_MISMATCH");
      session = await bridge.reconnect(binding);
      pairedBinding = Object.freeze({ ...binding });
      trace.push({ kind: "reconnected" });
      return session;
    },
    binding: () => pairedBinding,
    queryNotifications: async (input) => {
      assertObject(input);
      assertNoModelIdentity(input as object);
      assertExactKeys(input, ["toolCallId", "deviceId", "mode", "limit", "content", "packages"]);
      if (!Number.isInteger(input.limit) || input.limit < 1 || input.limit > 100) throw new AdapterError("LIMIT_INVALID");
      if (input.mode !== "on_demand" && input.mode !== "auto_send") throw new AdapterError("MODE_INVALID");
      if (input.content !== undefined && input.content !== "metadata" && input.content !== "content") throw new AdapterError("CONTENT_MODE_INVALID");
      const packages = validatePackageFilter(input.packages);
      assertConnected();
      assertDevice(input.deviceId);
      if (input.content === "content" && !allowNotificationContent) throw new AdapterError("CONTENT_DENIED");
      const result = await bridge.query(context, input.toolCallId, context.sessionId, input.mode, packages, input.content ?? "metadata");
      trace.push({ kind: "notification_query", operationId: input.toolCallId });
      const bounded = result.slice(0, input.limit).map((record) => input.content === "content" ? record : Object.freeze({ ...record, content: null }));
      return Object.freeze(bounded);
    },
    subscribeNotifications: async (input) => {
      assertObject(input);
      assertNoModelIdentity(input as object);
      assertExactKeys(input, ["deviceId", "packages", "content"]);
      const packages = validatePackageFilter(input.packages);
      if (input.content !== undefined && input.content !== "metadata" && input.content !== "content") throw new AdapterError("CONTENT_MODE_INVALID");
      const content = input.content ?? "metadata";
      assertConnected();
      assertDevice(input.deviceId);
      if (content === "content" && !allowNotificationContent) throw new AdapterError("CONTENT_DENIED");
      if (subscriptionId) await bridge.unsubscribe(context, subscriptionId);
      subscriptionId = `sub-${context.tenantId}-${context.humanPrincipalId}-${input.deviceId}-${context.workspaceId}-${context.sessionId}-${packages?.join(".") ?? "all"}-${content}`;
      await bridge.subscribe(context, subscriptionId, packages, content);
      trace.push({ kind: "notification_subscribe" });
      return Object.freeze({ subscriptionId });
    },
    unsubscribeNotifications: async () => {
      assertConnected();
      if (!subscriptionId) return Object.freeze({ removed: false });
      const removed = await bridge.unsubscribe(context, subscriptionId);
      subscriptionId = null;
      trace.push({ kind: "notification_unsubscribe" });
      return Object.freeze({ removed });
    },
    receiveNotificationEvent: async (event) => {
      assertConnected();
      if (!subscriptionId || event.subscriptionId !== subscriptionId || bindingKey(event.binding) !== bindingKey(context)) throw new AdapterError("EVENT_BINDING_MISMATCH");
      if (!allowNotificationContent && event.record.content !== null) throw new AdapterError("CONTENT_DENIED");
      const canonical = bridge.acknowledge(event);
      if (deliveredEvents.has(event.eventId)) return Object.freeze({ eventId: canonical.eventId, subscriptionId: canonical.subscriptionId, record: canonical.record });
      deliveredEvents.add(event.eventId);
      acknowledged.push(event.eventId);
      trace.push({ kind: "notification_event", eventId: event.eventId });
      return Object.freeze({ eventId: canonical.eventId, subscriptionId: canonical.subscriptionId, record: canonical.record });
    },
    emitAutoSend: (record) => {
      assertConnected();
      if (!subscriptionId) throw new AdapterError("SUBSCRIPTION_NOT_FOUND");
      return bridge.publish(subscriptionId, record);
    },
    querySms: async (input) => {
      assertObject(input);
      assertNoModelIdentity(input as object);
      assertExactKeys(input, ["toolCallId", "deviceId", "limit"]);
      if (typeof input.toolCallId !== "string" || input.toolCallId.length === 0) throw new AdapterError("TOOL_CALL_ID_INVALID");
      if (!Number.isSafeInteger(input.limit) || input.limit < 1 || input.limit > 10_000) throw new AdapterError("LIMIT_INVALID");
      assertConnected();
      assertDevice(input.deviceId);
      assertZeroRetention();
      const result = await bridge.querySms(context, input.toolCallId, context.sessionId, input.deviceId, input.limit);
      trace.push({ kind: "sms_query", operationId: input.toolCallId });
      return result;
    },
    subscribeSms: async (input) => {
      assertObject(input);
      assertNoModelIdentity(input as object);
      assertExactKeys(input, ["deviceId"]);
      assertConnected();
      assertDevice(input.deviceId);
      if (smsSubscriptionId) await bridge.unsubscribeSms(context, smsSubscriptionId);
      smsSubscriptionId = `sms-sub-${context.tenantId}-${context.humanPrincipalId}-${input.deviceId}-${context.workspaceId}-${context.sessionId}`;
      await bridge.subscribeSms(context, smsSubscriptionId);
      trace.push({ kind: "sms_subscribe" });
      return Object.freeze({ subscriptionId: smsSubscriptionId });
    },
    unsubscribeSms: async () => {
      assertConnected();
      if (!smsSubscriptionId) return Object.freeze({ removed: false });
      const removed = await bridge.unsubscribeSms(context, smsSubscriptionId);
      smsSubscriptionId = null;
      trace.push({ kind: "sms_unsubscribe" });
      return Object.freeze({ removed });
    },
    receiveSmsEvent: async (event) => {
      assertConnected();
      if (!smsSubscriptionId || event.subscriptionId !== smsSubscriptionId || bindingKey(event.binding) !== bindingKey(context)) {
        throw new AdapterError("EVENT_BINDING_MISMATCH");
      }
      assertZeroRetention();
      const canonical = bridge.acknowledgeSms(event);
      if (deliveredEvents.has(event.eventId)) return Object.freeze({
        eventId: canonical.eventId,
        subscriptionId: canonical.subscriptionId,
        record: canonical.record,
      });
      deliveredEvents.add(event.eventId);
      acknowledged.push(event.eventId);
      trace.push({ kind: "sms_event", eventId: event.eventId });
      return Object.freeze({ eventId: canonical.eventId, subscriptionId: canonical.subscriptionId, record: canonical.record });
    },
    emitSmsAutoSend: (record) => {
      assertConnected();
      if (!smsSubscriptionId) throw new AdapterError("SUBSCRIPTION_NOT_FOUND");
      return bridge.publishSms(smsSubscriptionId, record);
    },
    sendAssistantMessage: async (input) => {
      assertObject(input);
      assertNoModelIdentity(input as object);
      assertExactKeys(input, ["messageId", "text", "attachments"]);
      assertConnected();
      assertZeroRetention();
      if (!input.messageId || typeof input.text !== "string" || input.text.length === 0) throw new AdapterError("ASSISTANT_TEXT_REQUIRED");
      if (input.attachments !== undefined && !Array.isArray(input.attachments)) throw new AdapterError("ATTACHMENT_INVALID");
      const attachments = [...(input.attachments ?? [])];
      if (attachments.length > ASSISTANT_ATTACHMENT_LIMITS.maxFiles) throw new AdapterError("ATTACHMENT_LIMIT");
      let totalBytes = 0;
      const metadata = attachments.map((attachment) => {
        if (attachment === null || typeof attachment !== "object" || Array.isArray(attachment)) throw new AdapterError("ATTACHMENT_INVALID");
        const kind = (attachment as { kind?: unknown }).kind;
        if (kind !== "image" && kind !== "file" && kind !== "audio") throw new AdapterError("ATTACHMENT_INVALID");
        const isAudio = kind === "audio";
        assertExactKeys(attachment, isAudio ? ["kind", "artifactId", "filename", "mimeType", "sizeBytes", "sha256", "durationMs"] : ["kind", "artifactId", "filename", "mimeType", "sizeBytes", "sha256"], "ATTACHMENT_INVALID");
        const sizeLimit = isAudio ? ASSISTANT_ATTACHMENT_LIMITS.maxAudioBytes : ASSISTANT_ATTACHMENT_LIMITS.maxFileBytes;
        if (typeof attachment.artifactId !== "string" || !ARTIFACT_ID.test(attachment.artifactId) || !attachment.filename || !/^[a-fA-F0-9]{64}$/.test(attachment.sha256) || !Number.isSafeInteger(attachment.sizeBytes) || attachment.sizeBytes < 0 || attachment.sizeBytes > sizeLimit) throw new AdapterError("ATTACHMENT_INVALID");
        const durationMs = isAudio ? (attachment as { durationMs?: unknown }).durationMs : undefined;
        if (isAudio && (typeof durationMs !== "number" || !Number.isSafeInteger(durationMs) || durationMs < 1 || durationMs > ASSISTANT_ATTACHMENT_LIMITS.maxAudioDurationMs)) throw new AdapterError("ATTACHMENT_INVALID");
        const normalizedDurationMs = durationMs as number;
        const normalizedSha256 = attachment.sha256.toLowerCase();
        if (attachment.filename.includes("/") || attachment.filename.includes("\\")) throw new AdapterError("ATTACHMENT_INVALID");
        if (kind === "image" && !["image/jpeg", "image/png", "image/webp"].includes(attachment.mimeType)) throw new AdapterError("ATTACHMENT_UNSUPPORTED");
        if (kind === "file" && !["application/pdf", "text/plain"].includes(attachment.mimeType)) throw new AdapterError("ATTACHMENT_UNSUPPORTED");
        if (isAudio && attachment.mimeType !== "audio/mp4") throw new AdapterError("ATTACHMENT_UNSUPPORTED");
        totalBytes += attachment.sizeBytes;
        return Object.freeze(isAudio
          ? { kind, artifactId: attachment.artifactId, filename: attachment.filename, mimeType: attachment.mimeType, sizeBytes: attachment.sizeBytes, sha256: normalizedSha256, durationMs: normalizedDurationMs }
          : { kind, artifactId: attachment.artifactId, filename: attachment.filename, mimeType: attachment.mimeType, sizeBytes: attachment.sizeBytes, sha256: normalizedSha256 });
      });
      if (totalBytes > ASSISTANT_ATTACHMENT_LIMITS.maxMessageBytes) throw new AdapterError("ATTACHMENT_LIMIT");
      // Only metadata is retained for deterministic diagnostics. Text and bytes
      // are handed to the selected provider ephemerally and never logged/spooled.
      lastMetadata = Object.freeze({ messageId: input.messageId, attachmentCount: metadata.length, attachments: Object.freeze(metadata) });
      trace.push({ kind: "assistant_message", messageId: input.messageId });
      return Object.freeze({ messageId: input.messageId, status: "accepted", reply: "fixture-reply" });
    },
    invokeTool: async (name, input) => {
      if (name === "mobile.notifications.query") return adapter.queryNotifications(input as NotificationQueryInput);
      if (name === "mobile.notifications.subscribe") return adapter.subscribeNotifications(input as NotificationSubscriptionInput);
      if (name === "mobile.notifications.unsubscribe") return adapter.unsubscribeNotifications();
      if (name === "mobile.sms.query") return adapter.querySms(input as SmsQueryInput);
      if (name === "mobile.sms.subscribe") return adapter.subscribeSms(input as SmsSubscriptionInput);
      if (name === "mobile.sms.unsubscribe") {
        assertObject(input);
        assertNoModelIdentity(input);
        assertExactKeys(input, []);
        return adapter.unsubscribeSms();
      }
      throw new AdapterError("UNKNOWN_TOOL");
    },
    tools: () => FROZEN_PROVIDER_TOOLS,
    operationClaims: () => bridge.operationClaims(),
    acknowledgedEvents: () => Object.freeze([...acknowledged]),
    assistantMetadata: () => lastMetadata,
    diagnostics: () => Object.freeze(trace.map((entry) => Object.freeze({ ...entry }))),
  };
  return adapter;
};

export const fixtureContext = (): AdapterBinding => Object.freeze({
  tenantId: "tenant-a",
  humanPrincipalId: "human-a",
  deviceId: "device-a",
  agentInstanceId: "agent-a",
  workspaceId: "workspace-a",
  sessionId: "session-a",
  jobId: "job-a",
  authorizedDeviceIds: Object.freeze(["device-a"]),
});

export const fixtureBinding = (): PairedBinding => Object.freeze({
  tenantId: "tenant-a",
  humanPrincipalId: "human-a",
  deviceId: "device-a",
  bridgeFingerprint: "bridge-a",
  pairingGeneration: 1n,
  policyAttestationRevision: 1n,
});

export const fixtureZeroRetentionEvidence = (): ZeroRetentionEvidence => Object.freeze({
  provider: "fixture-provider",
  profileId: "fixture-zero-retention-v1",
  revision: "2026-08-11.1",
  expiresAt: "2099-01-01T00:00:00.000Z",
  providerObjectRetention: "none",
  requestResponseLoggingDisabled: true,
  trainingDisabled: true,
  humanReviewDisabled: true,
});
