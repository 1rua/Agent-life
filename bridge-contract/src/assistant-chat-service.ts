import {
  BridgeServiceError,
  freezeRecord,
  equalIdentity,
  type Authorize,
  type BridgeSessionIdentity,
} from "./service-types.js";
import { OperationDispatcher, type OperationDispatcherPort } from "./operation-dispatch.js";
import { PairingService } from "./pairing-service.js";
import {
  InMemoryAssistantReplyEventStore,
  type AssistantReplyEvent,
  type AssistantReplyEventStore,
} from "./assistant-reply-events.js";

export const ZERO_RETENTION_UNAVAILABLE = "ZERO_RETENTION_UNAVAILABLE" as const;

export type ZeroRetentionEvidence = Readonly<{
  provider: string;
  profileId: string;
  revision: string;
  expiresAt: string;
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

export type AssistantArtifactCommitment = Readonly<{
  artifactId: string;
  status: "message_committed";
  session: BridgeSessionIdentity;
  pairingGeneration: bigint;
  connectionGeneration: bigint;
  policyRevision: bigint;
  kind: AssistantAttachment["kind"];
  mimeType: AssistantAttachment["mimeType"];
  sizeBytes: number;
  sha256: string;
  durationMs?: number;
}>;

export type AssistantArtifactResolver = (input: Readonly<{
  attachment: AssistantAttachment;
  session: BridgeSessionIdentity;
}>) => Promise<AssistantArtifactCommitment | null> | AssistantArtifactCommitment | null;

export type AssistantMessageRequest = Readonly<{
  operationId: string;
  messageId: string;
  session: BridgeSessionIdentity;
  text: string;
  attachments?: readonly AssistantAttachment[];
  zeroRetention: ZeroRetentionEvidence;
}>;

export type AssistantMessageResult = Readonly<{
  operationId: string;
  messageId: string;
  status: "accepted";
  reply: string;
}>;

export type AssistantChatServiceOptions = Readonly<{
  operations?: OperationDispatcherPort;
  authorize?: Authorize;
  /** The server-minted session this service instance is allowed to serve. */
  boundSession?: BridgeSessionIdentity;
  /** Optional durable pairing verifier for generation/revision fencing. */
  pairing?: PairingService;
  clock?: () => number;
  resolveArtifact?: AssistantArtifactResolver;
  eventStore?: AssistantReplyEventStore;
  /** Connection generation bound when this Bridge connection was opened. */
  boundConnectionGeneration?: bigint;
  respond?: (text: string, attachments: readonly AssistantAttachment[]) => Promise<string> | string;
  respondStream?: (text: string, attachments: readonly AssistantAttachment[]) => AsyncIterable<string> | Promise<AsyncIterable<string>>;
}>;

type Metadata = Readonly<{
  operationId: string;
  messageId: string;
  attachments: readonly Readonly<{ kind: AssistantAttachment["kind"]; filename: string; mimeType: AssistantAttachment["mimeType"]; sizeBytes: number; sha256: string; durationMs?: number }>[];
}>;

const MAX_FILES = 4;
const MAX_FILE_BYTES = 25 * 1024 * 1024;
const MAX_TOTAL_BYTES = 50 * 1024 * 1024;
const MAX_AUDIO_BYTES = 10_485_760;
const MAX_AUDIO_DURATION_MS = 120_000;

export const isCurrentZeroRetention = (evidence: ZeroRetentionEvidence | undefined, now = new Date()): evidence is ZeroRetentionEvidence => {
  if (!evidence || !evidence.provider || !evidence.profileId || !evidence.revision) return false;
  if (evidence.providerObjectRetention !== "none") return false;
  if (!evidence.requestResponseLoggingDisabled || !evidence.trainingDisabled || !evidence.humanReviewDisabled) return false;
  const expiry = Date.parse(evidence.expiresAt);
  return Number.isFinite(expiry) && expiry > now.getTime();
};

/**
 * Text-only MVP assistant operation seam. It retains only operation results
 * and attachment metadata in memory; no request body is logged or spooled.
 */
export class AssistantChatService {
  readonly #operations: OperationDispatcherPort;
  readonly #authorize: Authorize;
  readonly #boundSession: BridgeSessionIdentity | null;
  readonly #pairing: PairingService | null;
  readonly #clock: () => number;
  readonly #resolveArtifact: AssistantArtifactResolver | null;
  readonly #eventStore: AssistantReplyEventStore;
  readonly #boundConnectionGeneration: bigint | null;
  readonly #respond: (text: string, attachments: readonly AssistantAttachment[]) => Promise<string> | string;
  readonly #respondStream: ((text: string, attachments: readonly AssistantAttachment[]) => AsyncIterable<string> | Promise<AsyncIterable<string>>) | null;
  #lastMetadata: Metadata | null = null;
  readonly #diagnostics: Array<Readonly<{ kind: string; operationId?: string; messageId?: string }>> = [];

  constructor(options: AssistantChatServiceOptions = {}) {
    this.#operations = options.operations ?? new OperationDispatcher();
    // Callers must inject an authorization decision; an unconfigured bridge
    // must fail closed rather than silently accepting assistant commands.
    this.#authorize = options.authorize ?? ((request) => ({ allowed: false, policyRevision: request.policyRevision, reason: "NOT_AUTHORIZED" }));
    this.#boundSession = options.boundSession ? freezeRecord({ ...options.boundSession }) : null;
    this.#pairing = options.pairing ?? null;
    this.#clock = options.clock ?? (() => Date.now());
    this.#resolveArtifact = options.resolveArtifact ?? null;
    this.#eventStore = options.eventStore ?? new InMemoryAssistantReplyEventStore();
    this.#boundConnectionGeneration = options.boundConnectionGeneration ?? null;
    this.#respond = options.respond ?? (() => "in-memory-fixture-reply");
    this.#respondStream = options.respondStream ?? null;
  }

  async send(request: AssistantMessageRequest): Promise<AssistantMessageResult> {
    const attachments = await this.#prepare(request);
    return this.#operations.execute(this.#operation(request, attachments), async () => {
      const reply = await this.#respond(request.text, attachments);
      return this.#complete(request, attachments, reply);
    });
  }

  async stream(request: AssistantMessageRequest, sink: (event: AssistantReplyEvent) => Promise<void> | void): Promise<AssistantMessageResult> {
    const attachments = await this.#prepare(request);
    if (typeof sink !== "function") throw new BridgeServiceError("ASSISTANT_EVENT_INVALID");
    return this.#operations.execute(this.#operation(request, attachments), async () => {
      const previous = await this.#eventStore.replay(request.operationId, 0n);
      let sequence = (previous.at(-1)?.sequence ?? 0n) + 1n;
      let reply = "";
      const emit = async (kind: AssistantReplyEvent["kind"], text: string, error?: string): Promise<void> => {
        const event = Object.freeze({ kind, operationId: request.operationId, messageId: request.messageId, sequence, text, ...(error === undefined ? {} : { error }) });
        await this.#eventStore.append(event);
        await sink(event);
        sequence += 1n;
      };
      try {
        if (!this.#respondStream) {
          reply = await this.#respond(request.text, attachments);
        } else {
          const response = await this.#respondStream(request.text, attachments);
          for await (const delta of response) {
            if (typeof delta !== "string" || reply.length + delta.length > 50_000) throw new BridgeServiceError("ASSISTANT_REPLY_INVALID");
            reply += delta;
            await emit("delta", delta);
          }
        }
        if (reply.length > 50_000) throw new BridgeServiceError("ASSISTANT_REPLY_INVALID");
        await emit("complete", reply);
        return this.#complete(request, attachments, reply);
      } catch (error) {
        const code = error instanceof BridgeServiceError && error.code === "ASSISTANT_REPLY_INVALID"
          ? "ASSISTANT_REPLY_INVALID"
          : "ASSISTANT_REPLY_FAILED";
        await emit("failed", "", code);
        throw new BridgeServiceError(code);
      }
    });
  }

  metadata(): Metadata | null { return this.#lastMetadata; }

  diagnostics(): readonly Readonly<{ kind: string; operationId?: string; messageId?: string }>[] {
    return Object.freeze(this.#diagnostics.map((entry) => freezeRecord({ ...entry })));
  }

  #operation(request: AssistantMessageRequest, attachments: readonly AssistantAttachment[]) {
    return {
      operationId: request.operationId,
      session: request.session,
      parameters: { messageId: request.messageId, text: request.text, attachments, profileId: request.zeroRetention.profileId },
    };
  }

  async #prepare(request: AssistantMessageRequest): Promise<readonly AssistantAttachment[]> {
    this.#validateRequest(request);
    this.#assertSession(request.session);
    if (!isCurrentZeroRetention(request.zeroRetention, new Date(this.#clock()))) throw new BridgeServiceError(ZERO_RETENTION_UNAVAILABLE);
    const decision = await this.#authorize({ capability: "assistant.chat", session: request.session, policyRevision: request.session.policyAttestationRevision });
    this.#assertSession(request.session);
    if (decision.policyRevision !== request.session.policyAttestationRevision) throw new BridgeServiceError("AUTHORIZATION_REVISION_STALE");
    if (!decision.allowed) throw new BridgeServiceError(decision.reason ?? "NOT_AUTHORIZED");
    const attachments = Object.freeze([...(request.attachments ?? [])].map((attachment) => freezeRecord({ ...attachment }) as AssistantAttachment));
    await this.#assertArtifacts(attachments, request.session);
    this.#assertSession(request.session);
    return attachments;
  }

  async #assertArtifacts(attachments: readonly AssistantAttachment[], session: BridgeSessionIdentity): Promise<void> {
    if (attachments.length === 0) return;
    if (!this.#resolveArtifact) throw new BridgeServiceError("ARTIFACT_NOT_COMMITTED");
    if (this.#boundConnectionGeneration === null) throw new BridgeServiceError("CONNECTION_FENCED");
    for (const attachment of attachments) {
      const commitment = await this.#resolveArtifact({ attachment, session });
      if (!commitment) throw new BridgeServiceError("ARTIFACT_NOT_COMMITTED");
      const matching = commitment.status === "message_committed"
        && commitment.artifactId === attachment.artifactId
        && equalIdentity(commitment.session, session)
        && commitment.pairingGeneration === session.pairingGeneration
        && commitment.connectionGeneration === this.#boundConnectionGeneration
        && commitment.policyRevision === session.policyAttestationRevision
        && commitment.kind === attachment.kind
        && commitment.mimeType === attachment.mimeType
        && commitment.sizeBytes === attachment.sizeBytes
        && commitment.sha256 === attachment.sha256
        && (attachment.kind !== "audio" || commitment.durationMs === attachment.durationMs);
      if (!matching) throw new BridgeServiceError("ARTIFACT_FENCE_MISMATCH");
    }
  }

  #complete(request: AssistantMessageRequest, attachments: readonly AssistantAttachment[], reply: string): AssistantMessageResult {
    const result = freezeRecord({ operationId: request.operationId, messageId: request.messageId, status: "accepted" as const, reply });
    this.#lastMetadata = freezeRecord({
      operationId: request.operationId,
      messageId: request.messageId,
      attachments: Object.freeze(attachments.map((attachment) => freezeRecord({
        kind: attachment.kind, filename: attachment.filename, mimeType: attachment.mimeType,
        sizeBytes: attachment.sizeBytes, sha256: attachment.sha256,
        ...(attachment.kind === "audio" ? { durationMs: attachment.durationMs } : {}),
      }))),
    });
    this.#diagnostics.push(freezeRecord({ kind: "assistant_message", operationId: request.operationId, messageId: request.messageId }));
    return result;
  }

  #assertSession(session: BridgeSessionIdentity): void {
    if (!this.#boundSession || !equalIdentity(this.#boundSession, session)
      || this.#boundSession.policyAttestationRevision !== session.policyAttestationRevision) {
      throw new BridgeServiceError("CONNECTION_FENCED");
    }
    if (this.#pairing) {
      const current = this.#pairing.current(session);
      if (!current || current.pairingGeneration !== session.pairingGeneration
        || current.policyAttestationRevision !== session.policyAttestationRevision) {
        throw new BridgeServiceError("CONNECTION_FENCED");
      }
    }
  }

  #validateRequest(request: AssistantMessageRequest): void {
    if (!request.operationId || !request.messageId || typeof request.text !== "string" || request.text.length === 0 || request.text.length > 50_000) throw new BridgeServiceError("ASSISTANT_REQUEST_INVALID");
    if (!Array.isArray(request.attachments) && request.attachments !== undefined) throw new BridgeServiceError("ATTACHMENT_INVALID");
    const attachments = request.attachments ?? [];
    if (attachments.length > MAX_FILES) throw new BridgeServiceError("ATTACHMENT_LIMIT");
    let total = 0;
    for (const attachment of attachments) {
      if (!attachment || typeof attachment !== "object" || !attachment.artifactId || !attachment.filename || attachment.filename.length > 255 || attachment.filename.includes("/") || attachment.filename.includes("\\")) throw new BridgeServiceError("ATTACHMENT_INVALID");
      if (attachment.kind !== "image" && attachment.kind !== "file" && attachment.kind !== "audio") throw new BridgeServiceError("ATTACHMENT_INVALID");
      if (!["image/jpeg", "image/png", "image/webp", "application/pdf", "text/plain", "audio/mp4"].includes(attachment.mimeType)) throw new BridgeServiceError("ATTACHMENT_UNSUPPORTED");
      const maxBytes = attachment.kind === "audio" ? MAX_AUDIO_BYTES : MAX_FILE_BYTES;
      if (!Number.isSafeInteger(attachment.sizeBytes) || attachment.sizeBytes < 0 || attachment.sizeBytes > maxBytes) throw new BridgeServiceError("ATTACHMENT_INVALID");
      if (!/^[a-fA-F0-9]{64}$/.test(attachment.sha256)) throw new BridgeServiceError("ATTACHMENT_INVALID");
      if (attachment.kind === "image" && !attachment.mimeType.startsWith("image/")) throw new BridgeServiceError("ATTACHMENT_INVALID");
      if (attachment.kind === "file" && attachment.mimeType.startsWith("image/")) throw new BridgeServiceError("ATTACHMENT_INVALID");
      if (attachment.kind === "file" && attachment.mimeType === "audio/mp4") throw new BridgeServiceError("ATTACHMENT_INVALID");
      if (attachment.kind === "audio" && attachment.mimeType !== "audio/mp4") throw new BridgeServiceError("ATTACHMENT_INVALID");
      if (attachment.kind === "audio" && (!Number.isSafeInteger(attachment.durationMs) || attachment.durationMs < 1 || attachment.durationMs > MAX_AUDIO_DURATION_MS)) throw new BridgeServiceError("ATTACHMENT_INVALID");
      total += attachment.sizeBytes;
    }
    if (total > MAX_TOTAL_BYTES) throw new BridgeServiceError("ATTACHMENT_LIMIT");
  }
}

export { BridgeServiceError } from "./service-types.js";
