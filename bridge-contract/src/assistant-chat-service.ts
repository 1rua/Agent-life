import {
  BridgeServiceError,
  freezeRecord,
  equalIdentity,
  type Authorize,
  type BridgeSessionIdentity,
} from "./service-types.js";
import { OperationDispatcher, type OperationDispatcherPort } from "./operation-dispatch.js";
import { PairingService } from "./pairing-service.js";

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

export type AssistantAttachment = Readonly<{
  kind: "image" | "file";
  artifactId: string;
  filename: string;
  mimeType: "image/jpeg" | "image/png" | "image/webp" | "application/pdf" | "text/plain";
  sizeBytes: number;
  sha256: string;
}>;

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
  respond?: (text: string, attachments: readonly AssistantAttachment[]) => Promise<string> | string;
}>;

type Metadata = Readonly<{
  operationId: string;
  messageId: string;
  attachments: readonly Readonly<Pick<AssistantAttachment, "kind" | "filename" | "mimeType" | "sizeBytes" | "sha256">>[];
}>;

const MAX_FILES = 4;
const MAX_FILE_BYTES = 25 * 1024 * 1024;
const MAX_TOTAL_BYTES = 50 * 1024 * 1024;

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
  readonly #respond: (text: string, attachments: readonly AssistantAttachment[]) => Promise<string> | string;
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
    this.#respond = options.respond ?? (() => "in-memory-fixture-reply");
  }

  async send(request: AssistantMessageRequest): Promise<AssistantMessageResult> {
    this.#validateRequest(request);
    this.#assertSession(request.session);
    if (!isCurrentZeroRetention(request.zeroRetention, new Date(this.#clock()))) throw new BridgeServiceError(ZERO_RETENTION_UNAVAILABLE);
    const decision = await this.#authorize({ capability: "assistant.chat", session: request.session, policyRevision: request.session.policyAttestationRevision });
    if (decision.policyRevision !== request.session.policyAttestationRevision) throw new BridgeServiceError("AUTHORIZATION_REVISION_STALE");
    if (!decision.allowed) throw new BridgeServiceError(decision.reason ?? "NOT_AUTHORIZED");
    const attachments = Object.freeze([...(request.attachments ?? [])].map((attachment) => freezeRecord({ ...attachment })));
    return this.#operations.execute({
      operationId: request.operationId,
      session: request.session,
      parameters: { messageId: request.messageId, text: request.text, attachments, profileId: request.zeroRetention.profileId },
    }, async () => {
      const reply = await this.#respond(request.text, attachments);
      const result = freezeRecord({ operationId: request.operationId, messageId: request.messageId, status: "accepted" as const, reply });
      this.#lastMetadata = freezeRecord({
        operationId: request.operationId,
        messageId: request.messageId,
        attachments: Object.freeze(attachments.map(({ kind, filename, mimeType, sizeBytes, sha256 }) => freezeRecord({ kind, filename, mimeType, sizeBytes, sha256 }))),
      });
      this.#diagnostics.push(freezeRecord({ kind: "assistant_message", operationId: request.operationId, messageId: request.messageId }));
      return result;
    });
  }

  metadata(): Metadata | null { return this.#lastMetadata; }

  diagnostics(): readonly Readonly<{ kind: string; operationId?: string; messageId?: string }>[] {
    return Object.freeze(this.#diagnostics.map((entry) => freezeRecord({ ...entry })));
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
      if (attachment.kind !== "image" && attachment.kind !== "file") throw new BridgeServiceError("ATTACHMENT_INVALID");
      if (!["image/jpeg", "image/png", "image/webp", "application/pdf", "text/plain"].includes(attachment.mimeType)) throw new BridgeServiceError("ATTACHMENT_UNSUPPORTED");
      if (!Number.isSafeInteger(attachment.sizeBytes) || attachment.sizeBytes < 0 || attachment.sizeBytes > MAX_FILE_BYTES) throw new BridgeServiceError("ATTACHMENT_INVALID");
      if (!/^[a-fA-F0-9]{64}$/.test(attachment.sha256)) throw new BridgeServiceError("ATTACHMENT_INVALID");
      if (attachment.kind === "image" && !attachment.mimeType.startsWith("image/")) throw new BridgeServiceError("ATTACHMENT_INVALID");
      if (attachment.kind === "file" && attachment.mimeType.startsWith("image/")) throw new BridgeServiceError("ATTACHMENT_INVALID");
      total += attachment.sizeBytes;
    }
    if (total > MAX_TOTAL_BYTES) throw new BridgeServiceError("ATTACHMENT_LIMIT");
  }
}

export { BridgeServiceError } from "./service-types.js";
