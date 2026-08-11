# Task 7 canonical-ready amendment

> Merge this text into Task 7 before implementation. Normative words (`MUST`, `MUST NOT`, `EXACTLY`) are part of the protocol contract. This draft closes readiness findings B1-B9 from `task-7-readiness-report.md`.
>
> **Readiness:** This is the canonical-ready amendment, not an implementation patch. It is docs-only and intentionally leaves the product/security-owner decisions in Section 11 open. No implementation may silently choose a product option; the selected option and decision record must be added before the `[EXTEND-T5]` replay work enters final integration.
>
> The frozen Task 5 baseline is commit `07a5c307` (which supersedes the originally inspected `dedb02c83ace804a8a996ff981193fa854ba83ef`). It provides opaque runtime brands, deterministic admission serialization, exact metadata re-projection and cached-receipt defensive copies. Its recovery backend is not a complete Task 7 persistence dependency: device snapshots retain only pending rows, omit finalized/abandoned rows and have no compaction; the adapter backend has no snapshot/restart/pending recovery, admits with zero retained-byte charge, and its load/resume/abandon/compact paths are `not_found`/no-op. Task 7 therefore consumes the stable authorities/value types but owns the durable extensions frozen below.

## Amendment status and dependency gates

Work is split by these gates:

- **`[INDEPENDENT]`** may start from `07a5c307`: operation reducer types/table, error registry/table, Task 7 schemas and fixed vectors, execution-ledger reference model, migration fixtures, and replay accounting value functions.
- **`[EXTEND-T5]`** also starts from stable `07a5c307`, consumes its opaque authorities/value types, and implements the missing durable device/adapter recovery, exact retained-byte accounting, finalized/abandoned persistence and compaction required by this amendment. It MUST NOT restore structural casts or a caller-selectable union admission method.
- **`[WAIT-T6]`** starts only after Task 6 commits the exact capability/revision contract below. This gate covers submit semantic validation, authorization/revision admission, WAIT_READ derivation, command/receipt revision binding and receipt replay.
- No Task 7 branch may enter final integration until all three gates are green.

## Replace Task 7 Files list with this exact list

**Files:**

- Create: `docs/protocol/v1/operations.md`
- Create: `protocol/schemas/v1/operation.schema.json`
- Create: `protocol/schemas/v1/receipt.schema.json`
- Create: `protocol/schemas/v1/migration-receipt.schema.json`
- Create: `protocol/schemas/v1/error-response.schema.json`
- Create: `protocol/schemas/v1/errors-registry.schema.json`
- Create: `protocol/schemas/v1/replay-policies-registry.schema.json`
- Modify: `protocol/src/schema-catalog.ts`
- Modify: `protocol/test/schema-validator.test.ts`
- Create: `protocol/registries/v1/errors.json`
- Create: `protocol/registries/v1/replay-policies.json`
- Modify: `protocol/registries/v1/messages.json`
- Create: `protocol/model/v1/operation-transitions.json`
- Create: `protocol/model/v1/error-precedence.json`
- Create: `protocol/test-only/replay/v1/intent-metadata-device-v1.json`
- Create: `protocol/test-only/replay/v1/intent-metadata-adapter-v1.json`
- Create: `protocol/test-only/replay/v1/intent-metadata-adapter-empty-scope-v1.json`
- Create: `protocol/test-only/replay/v1/compaction-recovery-vectors.json`
- Create: `protocol/test-only/operation/v1/operation-state-vectors.json`
- Create: `protocol/test-only/operation/v1/result-digest-vectors.json`
- Create: `protocol/test-only/operation/v1/reconciliation-evidence-vectors.json`
- Create: `protocol/test-only/operation/v1/receipt-replay-vectors.json`
- Create: `protocol/test-only/migration/v0.9/profile.json`
- Create: `protocol/test-only/migration/v0.9/pending-operation.schema.json`
- Create: `protocol/test-only/migration/v0.9/pending-operation.json`
- Create: `protocol/test-only/migration/v0.9/pending-operation-signature.json`
- Create: `protocol/src/operation-machine.ts`
- Create: `protocol/src/execution-ledger.ts`
- Create: `protocol/src/error-model.ts`
- Modify: `protocol/src/control-envelope.ts`
- Modify: `protocol/src/adapter-admission.ts`
- Modify: `protocol/src/replay-window.ts`
- Create: `protocol/src/migration.ts`
- Create: `protocol/test/operation-machine.test.ts`
- Create: `protocol/test/execution-ledger.test.ts`
- Create: `protocol/test/error-model.test.ts`
- Modify: `protocol/test/control-envelope.test.ts`
- Modify: `protocol/test/adapter-admission.test.ts`
- Modify: `protocol/test/replay-window.test.ts`
- Create: `protocol/test/migration.test.ts`
- Modify: `protocol/test/messages-registry.test.ts`

`protocol/src/schema-catalog.ts` MUST import all six new schema documents and list every new document, message, header and envelope `$id`. The final commit command MUST include `protocol/test-only/replay`, `protocol/test-only/operation`, `protocol/src/schema-catalog.ts`, `protocol/test/schema-validator.test.ts`, both new registry schemas, both new registries and `protocol/src/error-model.ts`.

Document `$id` values are exact:

- `urn:agent-life:protocol:v1:operation`
- `urn:agent-life:protocol:v1:receipt`
- `urn:agent-life:protocol:v1:migration-receipt`
- `urn:agent-life:protocol:v1:error-response`
- `urn:agent-life:protocol:v1:errors-registry`
- `urn:agent-life:protocol:v1:replay-policies-registry`

Registry instances use `$schema` equal to their registry-schema `$id`, `registry_id` equal to `urn:agent-life:protocol:v1:registry:errors` or `urn:agent-life:protocol:v1:registry:replay-policies`, and `protocol_version:"1.0"`.

## 1. Freeze opaque authorities and operation binding

### Runtime authority types

Task 7 MUST add these constructor-private types. Only the upstream Agent runtime may mint an invocation handle; only its authority store may resolve the handle. Envelope payload/model arguments are never accepted by the mint path.

```ts
declare const authenticatedAdapterInvocationHandleBrand: unique symbol;
declare const authenticatedAdapterInvocationBrand: unique symbol;

export type AuthenticatedAdapterInvocationHandle = Readonly<{
  handleId: string;
  readonly [authenticatedAdapterInvocationHandleBrand]: true;
}>;

export type SessionOrJob =
  | Readonly<{ kind: "session"; sessionId: string }>
  | Readonly<{ kind: "job"; jobId: string }>;

export type AuthenticatedAdapterInvocation = Readonly<{
  adapter: Extract<AuthenticatedBindingContext, { kind: "adapter" }>;
  invocation: SessionOrJob;
  readonly [authenticatedAdapterInvocationBrand]: true;
}>;

export interface AuthenticatedAdapterInvocationAuthority {
  load(handle: AuthenticatedAdapterInvocationHandle):
    Promise<AuthenticatedAdapterInvocation | { kind: "not_authenticated" }>;
}
```

Tests may obtain handles only from a deterministic test authority that owns them in a `WeakMap`/private map. A structural object, payload-derived object, handle from another authority or an object containing both/neither session and job MUST fail before operation lookup, authorization, replay or cached-receipt access.

### Exact binding and scoped lookup

```ts
export type StoredOperationBinding = Readonly<{
  tenantId: string;
  humanPrincipalId: string;
  agentPrincipalId: string;
  agentInstanceId: string;
  workspaceId: string;
  sessionOrJob: SessionOrJob;
  deviceId: string;
  operationId: string;
  capability: string;
  parametersDigest: string;
}>;

export type OperationScopeKey = Readonly<Pick<StoredOperationBinding,
  | "tenantId" | "humanPrincipalId" | "agentPrincipalId"
  | "agentInstanceId" | "workspaceId" | "sessionOrJob"
  | "deviceId" | "operationId"
>>;

export interface OperationBindingStore {
  loadScoped(scope: OperationScopeKey): Promise<
    | { kind: "match"; binding: StoredOperationBinding }
    | { kind: "not_found_or_mismatch" }
  >;
  createOrMatch(input: Readonly<{
    binding: StoredOperationBinding;
    registrationReplayClaimId: string;
  }>): Promise<"created" | "same_exact_binding" | "conflict_or_mismatch">;
}
```

`OperationScopeKey` is built only after schema validation and byte-for-byte comparison of payload tenant/human/agent/instance/workspace/session-or-job to `AuthenticatedAdapterInvocation`; its device and operation IDs come from the closed payload. `loadScoped` queries all eight key components. Missing, cross-binding and changed-device IDs return the same result and perform no second query. A returned binding is compared again to the complete authenticated/payload scope before exposure.

`verifyAdapterInvocationBinding(payload, authenticated)` returns only `{ok:true; scope}` or `{ok:false; error:"AUTH_BINDING_MISMATCH"}`. It MUST NOT load the operation store.

**RED:** compile tests reject structural authority values; runtime tests cover neither/both session/job, cross-authority handle, every long-lived binding mutation, device mutation, one-lookup maximum and identical opaque error/timing class.

## 2. Freeze wire schemas, IDs and payloads

### IDs and registry rows

Each of the 13 existing Task 7 message rows keeps the direction/domain/schema ID already listed in Task 7. Additionally, every message `M` MUST define:

- header ID `urn:agent-life:protocol:v1:header:M`;
- envelope ID `urn:agent-life:protocol:v1:envelope:M`;
- a closed payload `$id` equal to the `messages.json` `schema_id`;
- a closed envelope `{header,payload,signature}` with no unevaluated members.

Ownership is exact:

- `operation.schema.json`: `operation_submit`, `operation_get`, `operation_wait`, `operation_cancel`, `operation_reconcile`, `operation_command`, `operation_snapshot`, operation state and binding definitions.
- `receipt.schema.json`: `operation_receipt`, `operation_receipt_ack`, `receipt_replay`, result and reconciliation-summary definitions.
- `error-response.schema.json`: shared error payload plus `device_protocol_error`, `bridge_protocol_error`, `adapter_protocol_error` message/header/envelope branches.
- `migration-receipt.schema.json`: persisted migration record only; no `messages.json` row.

### Shared wire facts

All identifiers use the existing common opaque/UUID definitions; all digests use canonical 43-character SHA-256 Base64url; all timestamps use UTC milliseconds; all integers use canonical decimal-u64 strings.

```ts
export type WireSessionOrJob =
  | Readonly<{ kind: "session"; session_id: string; job_id: null }>
  | Readonly<{ kind: "job"; session_id: null; job_id: string }>;

export type AdapterOperationRequestBase = Readonly<{
  tenant_id: string;
  human_principal_id: string;
  agent_principal_id: string;
  agent_instance_id: string;
  workspace_id: string;
  session_or_job: WireSessionOrJob;
  device_id: string;
  operation_id: string;
}>;

export type RevisionSnapshotWire = Readonly<{
  pairing_generation: string;
  authorization_epoch: string;
  scope_revisions: Readonly<Record<string, string>>;
}>;

export type BoundOperationWireFacts = AdapterOperationRequestBase & Readonly<{
  capability: string;
  parameters_digest: string;
  revision_snapshot: RevisionSnapshotWire;
}>;
```

`scope_revisions` is closed to keys required by the locked capability entry, has no extra/missing keys, and is serialized in Unicode-code-point order by JCS. Task 6 owns its validated branded runtime form.

### Exact payload branches

| Message | Exact payload additions to the named base |
|---|---|
| `operation_submit` | `AdapterOperationRequestBase + {capability, parameters, parameters_digest, revision_snapshot, operation_expires_at}`. `parameters` MUST validate against Task 6's exact capability `parametersSchemaId`; `operation_expires_at` is at most 900 seconds after accepted Bridge time and never exceeds the capability policy. |
| `operation_get` | `AdapterOperationRequestBase` only. |
| `operation_wait` | `AdapterOperationRequestBase + {after_state_revision, wait_timeout_ms}`; both are decimal-u64 strings and numeric `wait_timeout_ms <= 30000`. |
| `operation_cancel` | `AdapterOperationRequestBase` only. |
| `operation_reconcile` | `AdapterOperationRequestBase` only; outcome/evidence/result members are forbidden. |
| `operation_command` | `BoundOperationWireFacts + {parameters, operation_expires_at}`; parameters revalidate against the locked capability schema and digest before device registration/claim. |
| `operation_snapshot` | `BoundOperationWireFacts + {state_revision, state, reconciliation}`; it contains no `parameters`, result body, evidence descriptor or command body. “Content-free” means content-free beyond these state/binding facts, not an empty payload. |
| `operation_receipt` | `BoundOperationWireFacts + {state_revision, state, reconciliation, result_digest}` and exactly one of the result branches below. |
| `operation_receipt_ack` | `BoundOperationWireFacts + {receipt_message_id, receipt_envelope_digest, accepted_state_revision, bridge_ack_at}`. |
| `receipt_replay` | exactly `{original_receipt_wire_b64, original_receipt_digest}` as already specified; no copied inner facts. |
| protocol errors | exactly the error payload frozen in section 8. |

`operation_receipt` is a closed `oneOf`:

1. no-result branch: `result_digest:null`, `result` forbidden;
2. result branch: `result` required, `result_digest` required/non-null, and `result` validates against the Task 6 capability `resultSchemaId` before digest comparison.

The verified runtime receipt is exact:

```ts
export interface SignedOperationReceipt {
  readonly operationId: string;
  readonly parametersDigest: string;
  readonly stateRevision: bigint;
  readonly state: WireOperationState;
  readonly resultDigest: string | null;
  readonly envelopeDigest: string;
  readonly messageId: string;
  readonly canonicalBytes: ExactWireBytes;
}
```

It is minted only after full schema, signature, binding, revision, parameter and result-digest verification. A caller cannot construct it from decoded payload fields.

The fixed typed result `system_ui_handoff` is exactly:

```json
{
  "kind": "system_ui_handoff",
  "external_side_effect_status": "not_observed"
}
```

No branch may encode it as confirmed send success.

Every command, receipt, ACK and snapshot carries the complete `BoundOperationWireFacts` and is compared to the authoritative stored binding/digest/revision. This closes the committed design requirement that results bind every principal, session/job, device, operation, capability, parameters and revision.

### Digest and historical-receipt rules

- `parameters_digest = b64u(SHA-256(JCS_UTF8(parameters)))` after capability-specific closed normalization. Bridge stores only its recomputation.
- `result_digest = b64u(SHA-256(JCS_UTF8(result)))`; absent result means literal `null`, never digest-of-null.
- Reconciliation descriptor is exactly `{evidence_kind,evidence_source,observed_outcome,observed_at,reference_id}` where `evidence_kind` is `"late_signed_receipt" | "verified_external_state"`, `evidence_source` is an opaque server ID, `observed_outcome` is `"succeeded" | "failed"`, and `reference_id` is `string | null`. `evidence_digest` hashes the complete five-member descriptor including explicit null.
- A direct receipt is subject to current envelope expiry. `receipt_replay` validates the fresh outer envelope normally; it verifies the historical inner registry tuple, signature, payload digest, lifetime interval and operation binding but MUST NOT reject the inner solely because its signed `expires_at` is before current time. It MUST reject an inner whose original interval was invalid or exceeded the original receipt lifetime.
- Outer replay authorization is evaluated against current Task 6 state and the authoritative operation binding. Inner connection generation/revision is historical evidence and is not rewritten.

**RED:** one fixture per message, every unknown/omitted/extra/null mutation, action/payload cross-use, full-binding mutation, both digest mismatch paths with zero durable delta, historical inner expired-now acceptance, invalid-original-lifetime rejection and exact outer/inner byte vectors.

## 3. Freeze replay eligibility, classes and TTL

Create `protocol/registries/v1/replay-policies.json` as the only replay-eligible projection. It is a closed registry with exactly:

```ts
export interface ReplayPolicyRegistryRow<T extends P0aReplayMessageType> {
  readonly message_type: T;
  readonly class_id: "task5_default" | "operation_security_ledger";
  readonly retention_rule_id:
    | "retain_until_max_expires_at_or_admitted_at_plus_86400_seconds_v1"
    | "retain_until_max_operation_expires_at_or_bridge_ack_at_plus_2592000_seconds_v1";
  readonly max_lifetime_seconds: string;
}

export interface ReplayPoliciesRegistry {
  readonly $schema:"urn:agent-life:protocol:v1:replay-policies-registry";
  readonly registry_id:"urn:agent-life:protocol:v1:registry:replay-policies";
  readonly protocol_version:"1.0";
  readonly policies:readonly ReplayPolicyRegistryRow<P0aReplayMessageType>[];
}
```

Startup MUST prove:

1. exactly 27 unique rows;
2. exactly the 9 `Task5MessageType`, 5 `Task6ReplayMessageType` and 13 `Task7ReplayMessageType` literals;
3. each row resolves to exactly one `messages.json` row and its locked header/envelope schemas;
4. exactly 10 security and 17 ordinary rows;
5. no enrollment/connect row is in this registry; additional non-replay `messages.json` rows do not fail the 27-row check;
6. class/retention pair is exactly the pair already frozen for that class, with no cross-pair/default arm.

Task 7 TTL rows are exact:

| message types | `max_lifetime_seconds` |
|---|---:|
| `operation_submit/get/wait/cancel/reconcile`, `operation_snapshot`, all three protocol errors, `receipt_replay` | `60` |
| `operation_command`, `operation_receipt`, `operation_receipt_ack` | `300` |

Task 5's nine TTLs MUST equal its committed table. Task 6 MUST supply its five exact TTLs before registry integration; Task 7 MUST import/copy them from the frozen Task 6 contract and test equality rather than choose them.

The verifier replaces its hard-coded Task 5 set/static family router with: bounded canonical parse -> message type -> exact replay-policy row (for this path) -> exact messages row -> exact header/envelope schema -> lifetime row. Non-replay enrollment/connect remain on their existing dedicated intake functions.

**RED:** full-registry 33-vs-27 regression, missing/additional/duplicate/misclassified row, wrong pair, wrong lifetime, unknown schema, and each exact lifetime boundary.

## 4. Freeze the operation reducer

### Types

```ts
export type RequestStatus =
  | "created" | "waiting_device" | "dispatching" | "accepted_device"
  | "awaiting_approval" | "approved" | "executing";

export type TerminalOutcome =
  | "succeeded" | "failed" | "denied" | "cancelled" | "expired" | "result_unknown";

export type OperationReason =
  | "NOT_AUTHORIZED" | "POLICY_BLOCKED" | "USER_DENIED" | "PLATFORM_UNSUPPORTED"
  | "BACKEND_UNAVAILABLE" | "DEVICE_OFFLINE" | "DEVICE_LOCKED"
  | "PAYLOAD_TOO_LARGE" | "RATE_LIMITED" | "SECURITY_LEDGER_FULL"
  | "QUEUE_LIMIT" | "INTERNAL_ERROR";

export type WireOperationState =
  | Readonly<{request_status: RequestStatus; terminal_outcome: null; operation_reason: null}>
  | Readonly<{request_status: null; terminal_outcome: TerminalOutcome; operation_reason: OperationReason | null}>;

export interface OperationRecord {
  readonly binding: StoredOperationBinding;
  readonly revisionSnapshot: RevisionSnapshot;
  readonly operationExpiresAt: string;
  readonly offlinePolicy: "WAIT_READ" | "FAIL_OFFLINE";
  readonly stateRevision: bigint;
  readonly state: WireOperationState;
  readonly reconciliation: null | Readonly<{
    outcome: "succeeded" | "failed";
    evidenceDigest: string;
    observedAt: string;
  }>;
}

export type PersistedOperationRecord = Readonly<{
  operation_id: string;
  binding: BoundOperationWireFacts;
  operation_expires_at: string;
  offline_policy: "WAIT_READ" | "FAIL_OFFLINE";
  state_revision: string;
  state: WireOperationState;
  reconciliation: null | Readonly<{
    outcome: "succeeded" | "failed";
    evidence_digest: string;
    observed_at: string;
  }>;
}>;

export type OperationEvent =
  | {type:"queue_wait_read"}
  | {type:"dispatch"}
  | {type:"device_accepted"}
  | {type:"approval_required"}
  | {type:"approval_not_required"}
  | {type:"approval_granted"}
  | {type:"approval_denied"}
  | {type:"execution_claimed"}
  | {type:"fail_before_claim"; reason: Extract<OperationReason,
      "PLATFORM_UNSUPPORTED"|"BACKEND_UNAVAILABLE"|"DEVICE_OFFLINE"|
      "DEVICE_LOCKED"|"PAYLOAD_TOO_LARGE"|"SECURITY_LEDGER_FULL"|
      "QUEUE_LIMIT"|"INTERNAL_ERROR">}
  | {type:"execution_succeeded"}
  | {type:"execution_failed"; reason: Extract<OperationReason,
      "PLATFORM_UNSUPPORTED"|"BACKEND_UNAVAILABLE"|"DEVICE_OFFLINE"|
      "DEVICE_LOCKED"|"PAYLOAD_TOO_LARGE"|"SECURITY_LEDGER_FULL"|
      "QUEUE_LIMIT"|"INTERNAL_ERROR">}
  | {type:"deny"; reason: Extract<OperationReason,
      "NOT_AUTHORIZED"|"POLICY_BLOCKED"|"USER_DENIED"|"RATE_LIMITED">}
  | {type:"execution_result_unknown"}
  | {type:"cancel"}
  | {type:"expire"}
  | {type:"reconcile_evidence"; outcome:"succeeded"|"failed";
      evidenceDigest:string; observedAt:string};

export type OperationReduction =
  | {ok:true; record:OperationRecord; changed:boolean}
  | {ok:false; error:"INVALID_STATE_TRANSITION"|"RESULT_CONFLICT"; record:OperationRecord};

export function reduceOperation(record:OperationRecord,event:OperationEvent):OperationReduction;
export function toWireOperationState(record:OperationRecord):WireOperationState;
```

`toWireOperationState` is the only mapping and always emits all three keys with explicit nulls.

### Exact legal edges

`operation-transitions.json` MUST encode only these edges:

| From | Event | To |
|---|---|---|
| `created` | `queue_wait_read` | `waiting_device` (only `WAIT_READ`) |
| `created`, `waiting_device` | `dispatch` | `dispatching` |
| `dispatching` | `device_accepted` | `accepted_device` |
| `accepted_device` | `approval_required` | `awaiting_approval` |
| `accepted_device` | `approval_not_required` | `approved` |
| `awaiting_approval` | `approval_granted` | `approved` |
| `awaiting_approval` | `approval_denied` | terminal `denied/USER_DENIED` |
| `approved` | `execution_claimed` | `executing` |
| any nonterminal before `executing` | `fail_before_claim(reason)` | terminal `failed/reason` |
| `executing` | `execution_succeeded` | terminal `succeeded/null` |
| `executing` | `execution_failed(reason)` | terminal `failed/reason` |
| `executing` | `execution_result_unknown` | terminal `result_unknown/null` |
| any nonterminal before `executing` | `cancel` | terminal `cancelled/null` |
| any nonterminal before `executing` | `expire` | terminal `expired/null` |
| any nonterminal before `executing` | `deny(reason)` | terminal `denied/reason` |
| `result_unknown` | first `reconcile_evidence` | unchanged terminal plus reconciliation |

`FAIL_OFFLINE` at create is `fail_before_claim(DEVICE_OFFLINE)`; it MUST NOT enter `waiting_device`. Device locked is `fail_before_claim(DEVICE_LOCKED)`. Execution-claim storage exhaustion is `fail_before_claim(SECURITY_LEDGER_FULL)`. `queue_wait_read` additionally requires a Task 6 registry entry whose offline policy is exactly WAIT_READ and an operation expiry no more than 900 seconds away.

Every successful state change increments `stateRevision` exactly once. First reconciliation increments it once; byte-identical evidence digest/outcome/time is idempotent with `changed:false`; any different second reconciliation is `RESULT_CONFLICT` and retains the first. All other unlisted pairs are `INVALID_STATE_TRANSITION`, preserve object value/durable bytes and do not increment.

Terminal reason matrix is exact: `succeeded/cancelled/expired/result_unknown` require null; `denied` requires one of `NOT_AUTHORIZED/POLICY_BLOCKED/USER_DENIED/RATE_LIMITED`; `failed` requires one of the eight reasons shared by `fail_before_claim` and `execution_failed`. A protocol error is not an `OperationEvent`.

**RED:** generate Cartesian state/event coverage from the JSON table, reason-matrix mutations, revision increments, WAIT_READ/FAIL_OFFLINE, 900-second boundary, terminal immutability and first/same/conflicting reconciliation.

## 5. Freeze durable stores, transactions, recovery and linearization

### Execution/operation store

`ExecutionLedgerStore` is the reference transaction boundary for operation state plus execution claim/result. `cancelBeforeClaim` no longer guesses status from a claim-only row.

```ts
export type ExecutionLedgerEntry =
  | Readonly<{kind:"registered"; record:OperationRecord; registrationReplayClaimId:string}>
  | Readonly<{kind:"claimed"; record:OperationRecord; claimId:string;
      claimedAt:string; parametersDigest:string}>
  | Readonly<{kind:"result"; record:OperationRecord; claimId:string;
      parametersDigest:string; receipt:SignedOperationReceipt}>;

declare const trustedExecutionReconcilerBrand: unique symbol;
export type TrustedExecutionReconciler = Readonly<{
  reconcilerId:string;
  readonly [trustedExecutionReconcilerBrand]:true;
}>;

export interface ExecutionLedgerStore {
  register(input: Readonly<{record:OperationRecord; registrationReplayClaimId:string}>):
    Promise<"new"|"same"|"conflict">;
  claim(input: Readonly<{operationId:string; parametersDigest:string; claimedAt:string}>): Promise<
    | {kind:"claimed"; claimId:string}
    | {kind:"already_claimed"; claimId:string}
    | {kind:"already_result"; receipt:SignedOperationReceipt}
    | {kind:"invalid_state"; state:WireOperationState}
    | {kind:"digest_conflict"}
    | {kind:"unavailable"; record:OperationRecord}
  >;
  cancelBeforeClaim(scope: OperationScopeKey): Promise<
    | {kind:"cancelled"; record:OperationRecord}
    | {kind:"lost"; record:OperationRecord}
    | {kind:"not_found_or_mismatch"}
  >;
  putResult(input: Readonly<{operationId:string; parametersDigest:string;
    claimId:string; receipt:SignedOperationReceipt}>): Promise<
    "stored"|"same"|"result_conflict"|"digest_conflict"|"claim_mismatch"|"invalid_state"
  >;
  getScoped(scope: OperationScopeKey): Promise<ExecutionLedgerEntry|undefined>;
  recoverClaimedWithoutResult(reconciler: TrustedExecutionReconciler):
    Promise<Readonly<{recoveredOperationIds:readonly string[]}>>;
}

declare const verifiedReconciliationEvidenceBrand: unique symbol;
export type VerifiedReconciliationEvidence = Readonly<{
  descriptor: Readonly<{
    evidence_kind:"late_signed_receipt"|"verified_external_state";
    evidence_source:string;
    observed_outcome:"succeeded"|"failed";
    observed_at:string;
    reference_id:string|null;
  }>;
  evidenceDigest:string;
  readonly [verifiedReconciliationEvidenceBrand]:true;
}>;

export function registerOperation(
  store:ExecutionLedgerStore,
  record:OperationRecord,
  registrationReplayClaimId:string,
):Promise<"new"|"same"|"conflict">;

export function claimExecution(
  store:ExecutionLedgerStore,
  input:Readonly<{operationId:string; parametersDigest:string; claimedAt:string}>,
):Promise<
  | {kind:"claimed"; claimId:string}
  | {kind:"already_claimed"; claimId:string}
  | {kind:"already_result"; receipt:SignedOperationReceipt}
  | {kind:"not_claimable"; record:OperationRecord}
  | {kind:"security_ledger_full"; record:OperationRecord}
>;

export function recordResult(
  store:ExecutionLedgerStore,
  input:Readonly<{operationId:string; parametersDigest:string;
    claimId:string; receipt:SignedOperationReceipt}>,
):Promise<"stored"|"same"|"RESULT_CONFLICT"|"INTEGRITY_FAILED"|"INVALID_STATE_TRANSITION">;

export function reconcile(
  record:OperationRecord,
  evidence:VerifiedReconciliationEvidence,
):OperationReduction;
```

`TrustedExecutionReconciler` is minted only by the owning backend, matching Task 5's stable reconciler authority pattern under `[EXTEND-T5]`.
`VerifiedReconciliationEvidence` is minted only after a late signed receipt or the closed adapter/server evidence procedure has been verified and its digest recomputed. `operation_reconcile` payload never mints this fact.

### Atomicity and crash rules

- Global operation ID uniqueness linearizes at `OperationBindingStore.createOrMatch`. Same full binding + authoritative digest is same; every other collision is opaque conflict.
- Replay admission linearizes before operation registration. The accepted pending replay row stores `registrationReplayClaimId`; a crash between the commits is recovered only by the trusted pending-frame reconciler, which repeats `createOrMatch/register` and then finalizes the same replay claim. Retried wire while pending never creates a second operation.
- `claim` atomically checks digest/state/capacity, writes claim and changes `approved -> executing` in one transaction. If claim storage capacity is unavailable but the operation transaction remains writable, it applies `fail_before_claim(SECURITY_LEDGER_FULL)` and returns that record atomically. No external callback runs inside this transaction.
- `cancelBeforeClaim`, expiry and `claim` serialize on the same operation CAS. If cancel/expiry wins, claim returns invalid state; if claim wins, cancel returns the current executing/terminal record. Exactly one wins.
- The caller invokes the external effect exactly once only after `{kind:"claimed"}`. `already_claimed` and every other result MUST NOT invoke.
- `putResult` atomically validates claim/digest, retains the first trustworthy receipt and changes operation state. Exact same canonical receipt bytes are `same`; any different trustworthy receipt is `result_conflict`, preserves the first and emits `RESULT_CONFLICT`.
- Restart scans durable claimed entries with no result and atomically changes each to terminal `result_unknown`; it never uses a fault-hook assertion that the external call had not started. Registered-but-unclaimed entries retain their current state.
- Receipt replay/finalization and operation result commit use one transaction when backed by the same database. If separate stores are used, an outbox record containing operation ID, claim ID, receipt digest and exact receipt bytes is committed with the result; restart idempotently finalizes replay from that outbox before delivery.
- Bridge ACK persistence and ACK-envelope generation use the same transaction/outbox rule. ACK loss repeats exact signed ACK bytes; it never forgets the accepted result.

`claimExecution` returns a typed union, not an untyped throw. Replay-partition exhaustion is handled before operation registration as a protocol error; execution-claim storage unavailability terminalizes an existing operation as `failed/SECURITY_LEDGER_FULL` and invokes no effect.

**RED crash cuts:** before replay commit; after replay/before register; after register/before finalize; before claim; after claim/before effect; after effect/before result; after result/before replay finalize; after finalize/before delivery; after Bridge result persist/before ACK generation; after ACK generation/before delivery; after device ACK persist/before compaction rewrite. Restart each cut twice and assert one operation, one claim, at most one invocation, first result, stable exact receipts/ACKs and recomputed state.

## 6. Freeze one replay namespace with two capacity partitions

### Durable keys and rows

There is one namespace per `ReplaySpace`:

- one sequence-window row keyed only by `ReplaySpace`;
- one conflict index keyed only by `(ReplaySpace,message_id)` and storing full-envelope digest plus row reference;
- capacity counters keyed by `(ReplaySpace,class_id)` only;
- policy class is never part of the window or conflict key.

```ts
export type ReplayRowStatus = "pending"|"finalized"|"abandoned";

export interface ActiveReplayRow<T extends P0aReplayMessageType> {
  readonly status: ReplayRowStatus;
  readonly claim: ReplayClaim<T>;
  readonly operationRef: null | Readonly<{operationId:string; operationExpiresAt:string}>;
  readonly bridgeAckAt: string | null;
  readonly rawWire: ExactWireBytes;
  readonly receipt: ExactWireBytes | null;
  readonly recoveryIntent: ReplayRecoveryIntent<T>;
  readonly inboundRawByteLength: number;
  readonly intentMetadataByteLength: number;
  readonly chargedBytes: bigint;
}

export interface SecurityReplayTombstone<T extends Task7SecurityReplayMessageType> {
  readonly envelope_digest: string;
  readonly message_id: string;
  readonly message_type: T;
  readonly sequence: string;
  readonly space: PersistedReplaySpace;
  readonly status: "compacted";
}
```

Tombstone JCS has exactly those six keys. Exact duplicate after tombstone returns `{kind:"rejected",error:"REPLAY_REJECTED",denial:"COMPACTED_DUPLICATE"}` and never executes; changed digest returns `INTEGRITY_FAILED/MESSAGE_ID_CONFLICT`.

### Exact derivation and charging

- Registry identity alone selects the frozen policy; requests/callers cannot pass class, partition, reservation or retention.
- Ordinary pending/abandoned charge is exact raw wire + 16,384 reservation + its Task 5 actual retained metadata charge. Ordinary finalized charge replaces reservation with exact receipt length. Existing Task 5 row/byte/retention behavior remains unchanged.
- Security pending/abandoned charge is `rawWire.byteLength + 262144 + intentMetadataByteLength`.
- Security finalized charge is `rawWire.byteLength + receipt.byteLength + intentMetadataByteLength`.
- Security compacted charge is exact tombstone JCS UTF-8 length.
- All arithmetic/counters use `bigint`; persisted counters are canonical decimal-u64 strings. The 9,663,676,416 limit is logical charged bytes, not physical database bytes.
- Admission accepts equality and rejects strictly greater. Intent metadata accepts 65,536 and rejects 65,537 before any mutation. Receipt accepts 262,144 and rejects 262,145 before mutation. Tombstone accepts 2,048 and rejects 2,049 without replacing the active row.
- Admission evaluates sequence and capacity from one transaction snapshot and commits window, conflict index, active row and partition counters together. Capacity rejection commits none of them.

### ACK retention and compaction

Every security row stores an operation reference. Before a verified signed Bridge ACK fact, it is ineligible for compaction. Recording the first ACK for the authoritative terminal receipt atomically:

1. verifies complete binding, receipt message ID/digest and accepted state revision;
2. sets the operation-level `bridgeAckAt` to the signed `bridge_ack_at`;
3. rewrites every locally owned active security row for that operation to `retentionUntil = max(operationExpiresAt, bridgeAckAt + 2592000 seconds)`;
4. regenerates persisted metadata and exact length;
5. adjusts affected security counters in the same transaction.

Exact same ACK is idempotent. A different valid ACK for the same receipt is `RESULT_CONFLICT` and preserves the first. Compaction eligibility is `now >= retentionUntil`; therefore just-before rejects, exact equality admits and one millisecond after admits.

Security compaction atomically replaces the active row and conflict-index target with the permanent tombstone, leaves partition row count unchanged, replaces old byte charge with tombstone charge and deletes raw/receipt/recovery bytes only after the replacement commits. Ordinary compaction remains Task 5 deletion and releases ordinary row/bytes.

Restart ignores cached totals; validates the exact 27-row policy registry; re-resolves each active row/tombstone from message type; validates policy descriptors, registry identity, metadata re-projection/length, component ceilings, ACK/retention derivation and conflict-index uniqueness; recomputes both partitions and the shared window; then compares recomputation with persisted counters. Any mismatch fails startup closed.

### Capacity rollover semantics

The existing permanent-tombstone requirement implies a finite lifetime for one `ReplaySpace`: at 16,384 security messages it stays full forever. Key activation legitimately creates a different `ReplaySpace` because `keyId` is part of the frozen space. Capacity MUST NOT auto-rotate keys, weaken retention or delete old tombstones. After exhaustion, the old space returns `SECURITY_LEDGER_FULL`; only the normal authenticated key-rotation protocol can create a fresh space. Old spaces remain read-only and retained.

This behavior is protocol-exact, but product acceptance is required in the final section because polling messages consume permanent rows.

**RED:** shared-class same message-ID conflict; shared bitmap across classes; no quota borrowing; all component/total boundaries; exact ordinary regression; ACK exact/conflict; just-before/equal/after compaction; compact crash cuts; duplicate/conflict after bitmap eviction/compaction/restart; corrupted counters/metadata/index/policy; full old space plus authenticated new-key space.

### Quota formula and immutable compaction/recovery vectors

The security quota is a logical accounting limit. It is not a disk-size promise and MUST NOT be computed from allocator/database overhead. Let:

```text
N = 16,384 rows per ReplaySpace
I = 262,144-byte inbound raw-wire ceiling per security row
R = 262,144-byte receipt reservation per pending/abandoned security row
M = 65,536-byte intent-metadata ceiling per security row
T = 2,048-byte compacted-tombstone ceiling per security row

max security charge = N × (I + R + M)
                   = 16,384 × (262,144 + 262,144 + 65,536)
                   = 9,663,676,416 bytes
```

The implementation MUST use `bigint` for intermediate and persisted counter arithmetic (persisted as canonical decimal-u64 strings). A pending or abandoned row charges `raw_wire_length + R + intent_metadata_length`; a finalized row charges `raw_wire_length + receipt_length + intent_metadata_length`; a compacted row charges `JCS_UTF8_BYTE_LENGTH(tombstone_projection)` and no longer charges raw wire, receipt or recovery metadata. `raw_wire_length <= I`, `receipt_length <= R`, `intent_metadata_length <= M`, and `tombstone_length <= T` are independently enforced before mutation. Equality is admitted; one byte above any component or the total is rejected. The row cap counts active rows plus permanent tombstones, while conflict-index entries and physical storage overhead do not enter the logical charge. Security and ordinary partition counters are independent: no row, byte or reservation may be borrowed across classes.

`protocol/test-only/replay/v1/compaction-recovery-vectors.json` is a closed, immutable vector set. Every vector contains a `vector_id`, semantic input, expected decision/state, exact persisted row or tombstone projection, standard padded Base64 of every complete expected JCS byte string, and canonical decimal-u64 byte lengths. Tests reconstruct and byte-compare all expected bytes; they MUST NOT trust a supplied digest or length. The set MUST contain at least:

| Vector family | Required IDs/coverage | Required assertion |
|---|---|---|
| Row/component quota | `security-row-count-{16383,16384,16385}`, `security-inbound-{262143,262144,262145}`, `security-receipt-{262143,262144,262145}`, `security-metadata-{65535,65536,65537}` | one-below/equal admits where valid; one-above rejects before window, index, row, operation or ledger mutation |
| Total quota | `security-total-{9663676415,9663676416,9663676417}` | exact formula above; no quota borrowing; synthetic sparse rows only (never allocate 9.6 GiB) |
| Tombstone charge | `tombstone-{2047,2048,2049}` | 2,048 admits and atomically replaces old charge; 2,049 rejects while active row remains unchanged |
| Retention boundaries | `compact-before-operation-expiry`, `compact-at-operation-expiry`, `compact-after-operation-expiry`, `compact-before-ack-retention`, `compact-at-ack-retention`, `compact-after-ack-retention` | no ACK means ineligible; `now < retention_until` rejects; `now == retention_until` and one millisecond after admit |
| ACK rewrite | `ack-first`, `ack-idempotent-retry`, `ack-conflict`, `ack-clock-rollback` | first valid ACK sets `max(operation_expires_at, bridge_ack_at + 2,592,000s)`; exact retry is byte-identical; conflicting/rollback ACK preserves first and emits typed conflict |
| Compaction crash cuts | `compact-before-replace`, `compact-after-tombstone-index-before-counter`, `compact-after-counter-before-payload-delete`, `compact-after-payload-delete` | restart yields either the pre-transaction active row or the complete post-transaction tombstone/counters/index; never a mixed state or released row |
| ACK crash cuts | `ack-before-commit`, `ack-after-retention-before-metadata`, `ack-after-metadata-before-counter`, `ack-after-counter-before-delivery` | restart is idempotent; metadata, byte charge, retention and ACK receipt agree |
| Restart/recovery integrity | `restart-active`, `restart-finalized`, `restart-abandoned`, `restart-tombstone`, `restart-counter-mismatch`, `restart-metadata-mismatch`, `restart-index-mismatch`, `restart-policy-registry-mismatch` | recompute rows/partitions/window/index and fail closed on every mismatch; never trust cached totals |
| Post-compaction dedupe | `duplicate-after-tombstone`, `changed-digest-after-tombstone`, `duplicate-after-restart` | exact duplicate is non-executing `REPLAY_REJECTED/COMPACTED_DUPLICATE`; changed digest is `INTEGRITY_FAILED/MESSAGE_ID_CONFLICT`; neither mutates or reclaims the tombstone |

The vectors MUST also include full crash/restart cuts for replay admission, operation registration, execution claim, result persistence, ACK persistence and receipt finalization described in Section 5. A fault hook may identify the cut for testing, but recovery MUST use only durable facts and always map a durable claim without a trustworthy result to `result_unknown`.

## 7. Freeze receipt replay and migration APIs

```ts
declare const currentReceiptReplayAuthorizationBrand: unique symbol;

export interface CurrentReceiptReplayAuthorization {
  readonly operation: StoredOperationBinding;
  readonly revisionSnapshot: RevisionSnapshot;
  readonly deviceContext: Extract<AuthenticatedBindingContext,{kind:"device"}>;
  readonly [currentReceiptReplayAuthorizationBrand]: true;
}

export interface ReceiptReplaySigner {
  signCurrentDeviceEnvelope(input: Readonly<{
    messageType:"receipt_replay";
    payload: Readonly<{original_receipt_wire_b64:string; original_receipt_digest:string}>;
    issuedAt:string;
    expiresAt:string;
  }>): Promise<ExactWireBytes>;
}

export function wrapReceiptReplay(
  originalReceiptWire: ExactWireBytes,
  authorization: CurrentReceiptReplayAuthorization,
  signer: ReceiptReplaySigner,
  clock: Clock,
): Promise<ExactWireBytes>;

export interface MigrationDependencies {
  readonly legacySchemaCatalog: LegacySchemaCatalog;
  readonly bridgeKeyRings: KeyRingStore;
  readonly bridgeSigner: MigrationBridgeSigner;
  readonly clock: Clock;
  readonly ids: { nextMigrationId(): string };
}

export interface LegacySchemaCatalog {
  validate(schemaId:string, value:unknown): void;
}

export interface MigrationBridgeSigner {
  sign(input: Readonly<{
    domain:"migration/bridge";
    header:Readonly<Record<string,unknown>>;
    payload:Readonly<Record<string,unknown>>;
  }>): Promise<Readonly<{keyId:string; signature:string}>>;
}

export function migrateSignedRecord(
  input: ExactWireBytes,
  targetVersion: "1.0",
  dependencies: MigrationDependencies,
): Promise<Readonly<{targetRecord:PersistedOperationRecord; migrationReceipt:ExactWireBytes}>>;
```

`CurrentReceiptReplayAuthorization` is minted only by Task 6 after current binding/revision/capability authorization of the authoritative stored operation. `wrapReceiptReplay` does not accept an unbranded context.

Migration digest/signature rules remain exactly as already specified. The target record MUST validate against the concrete `operation.schema.json` persisted-record branch; the migration receipt MUST validate before return. No global signer/catalog/clock is read.

**RED:** structural replay authorization rejection; stale revision; wrong operation/device; inner byte mutation; standard-Base64 padding; target-version mutation; wrong legacy schema/key/domain; source payload-only/signature-excluded/alternate-serialization digests; target mutation and rollback.

## 8. Freeze error registry, precedence and public mapping

### Registry namespaces

`errors.json` is a closed registry keyed by `(category,code)`. A literal may occur in more than one category; category is never inferred from spelling.

```ts
export interface ErrorRegistryRow {
  readonly category:"protocol_error"|"request_status"|"terminal_outcome"|
    "operation_reason"|"internal_decision_reason";
  readonly code:string;
  readonly retryable:boolean;
  readonly public_code:ProtocolErrorCode|null;
}

export interface ErrorsRegistry {
  readonly $schema:"urn:agent-life:protocol:v1:errors-registry";
  readonly registry_id:"urn:agent-life:protocol:v1:registry:errors";
  readonly protocol_version:"1.0";
  readonly entries:readonly ErrorRegistryRow[];
}
```

Protocol rows set `public_code` to themselves. Status/outcome/operation-reason rows set it to null. Internal rows set it to the exact mapping stated below or null when they are never wire-emitted. Only the protocol `RATE_LIMITED` row has `retryable:true`; all others are false.

- `protocol_error`: `MESSAGE_TOO_LARGE`, `SCHEMA_INVALID`, `AUTH_FAILED`, `INTEGRITY_FAILED`, `MESSAGE_EXPIRED`, `CONNECTION_FENCED`, `AUTH_BINDING_MISMATCH`, `VERSION_UNSUPPORTED`, `REPLAY_REJECTED`, `IDEMPOTENCY_CONFLICT`, `NOT_AUTHORIZED`, `SECURITY_LEDGER_FULL`, `FLOW_CONTROL_VIOLATION`, `INVALID_STATE_TRANSITION`, `RESULT_CONFLICT`, `RATE_LIMITED`, `INTERNAL_ERROR`.
- `request_status`: the seven exact `RequestStatus` literals.
- `terminal_outcome`: the six exact `TerminalOutcome` literals.
- `operation_reason`: the twelve exact `OperationReason` literals.
- `internal_decision_reason`: `ADAPTER_PRINCIPAL_MISSING`, `PAIRING_INACTIVE`, `SCOPE_DENIED`, `REVISION_MISMATCH`, `POLICY_BLOCKED`, `CAPACITY_EXHAUSTED`, `SECURITY_PARTITION_EXHAUSTED`, `MESSAGE_ID_CONFLICT`, `PENDING`, `WINDOW_REJECTED`, `COMPACTED_DUPLICATE`, `PENDING_LIMIT`, `COOLDOWN`, `CREDIT_EXHAUSTED`.

Only protocol `RATE_LIMITED` is retryable and requires decimal-string seconds `retry_after`. Every other protocol code forbids `retry_after`. `ADAPTER_PRINCIPAL_MISSING` maps to opaque `AUTH_BINDING_MISMATCH`; Task 6 authorization denials map to protocol `NOT_AUTHORIZED` before operation creation or an operation `denied` reason after creation. Ordinary/security capacity map exactly as specified. Pending/window/compacted duplicate map `REPLAY_REJECTED`. Pending-limit/cooldown map `RATE_LIMITED`; credit exhaustion maps `FLOW_CONTROL_VIOLATION`.

Execution-claim `SECURITY_LEDGER_FULL` is an operation reason on an existing operation. Replay-partition `SECURITY_LEDGER_FULL` is a protocol error before operation creation. This dual spelling is intentional and tested by category.

### Error payload and classifier

`error-response.schema.json` is exactly this `oneOf`:

```ts
export type ProtocolErrorCode =
  | "MESSAGE_TOO_LARGE" | "SCHEMA_INVALID" | "AUTH_FAILED"
  | "INTEGRITY_FAILED" | "MESSAGE_EXPIRED" | "CONNECTION_FENCED"
  | "AUTH_BINDING_MISMATCH" | "VERSION_UNSUPPORTED" | "REPLAY_REJECTED"
  | "IDEMPOTENCY_CONFLICT" | "NOT_AUTHORIZED" | "SECURITY_LEDGER_FULL"
  | "FLOW_CONTROL_VIOLATION" | "INVALID_STATE_TRANSITION"
  | "RESULT_CONFLICT" | "RATE_LIMITED" | "INTERNAL_ERROR";

export type InternalDecisionReason =
  | "ADAPTER_PRINCIPAL_MISSING" | "PAIRING_INACTIVE" | "SCOPE_DENIED"
  | "REVISION_MISMATCH" | "POLICY_BLOCKED" | "CAPACITY_EXHAUSTED"
  | "SECURITY_PARTITION_EXHAUSTED" | "MESSAGE_ID_CONFLICT" | "PENDING"
  | "WINDOW_REJECTED" | "COMPACTED_DUPLICATE" | "PENDING_LIMIT"
  | "COOLDOWN" | "CREDIT_EXHAUSTED";

export type InternalProtocolFailureReason = ProtocolErrorCode | InternalDecisionReason;

export type ProtocolStage =
  | "size" | "canonical_schema" | "registry_schema"
  | "authentication" | "payload_integrity" | "expiry"
  | "connection_fence" | "authenticated_binding" | "operation_binding"
  | "semantic_integrity" | "authorization_revision"
  | "replay_duplicate_sequence_capacity" | "operation_transition"
  | "execution_claim" | "result_persistence";

export type ErrorResponsePayload =
  | Readonly<{code:"RATE_LIMITED"; stage:ProtocolStage;
      correlation_message_id:string|null; retry_after:string}>
  | Readonly<{code:Exclude<ProtocolErrorCode,"RATE_LIMITED">;
      stage:ProtocolStage; correlation_message_id:string|null}>;

export type ProtocolFailure = Readonly<{
  stage: ProtocolStage;
  reason: InternalProtocolFailureReason;
  correlationMessageId: string | null;
  retryAfterSeconds?: bigint;
}>;

export function classifyProtocolFailure(failure: ProtocolFailure): ErrorResponsePayload;
```

No free text or echoed input is permitted.

### Exact precedence

`error-precedence.json` is the ordered list below; a failure stops evaluation unless an earlier Task 4 enrollment mutation exception already committed:

1. `size`
2. `canonical_schema`
3. `registry_schema`
4. `authentication` (key, direction, domain, signature)
5. `payload_integrity` (envelope payload digest)
6. `expiry` (registry lifetime)
7. `connection_fence`
8. `authenticated_binding` (including opaque invocation/payload equality)
9. `operation_binding` (at most one read-only scoped lookup)
10. `semantic_integrity` (capability parameter schema/digest; inner receipt; result/evidence digest)
11. `authorization_revision`
12. `replay_duplicate_sequence_capacity`
13. `operation_transition`
14. `execution_claim`
15. `result_persistence`

Fresh submit has no operation lookup at stage 9. It creates binding/operation only after stage 12 returns an accepted replay claim. External invocation is not a classification stage and occurs only after stage 14 commits a new claim. Rejected correlated wire never commits its replay state; atomic stage-12 capacity rejection also commits no previewed window/index state.

**RED:** every pair of simultaneous failures returns the earlier code; parameter digest beats authorization; binding beats lookup/replay/cache; authorization beats duplicate; capacity beats operation registration; transition beats claim; claim unavailability creates operation failure rather than replay protocol error; retry field oneOf mutations; protocol error never rewrites terminal state.

## 9. Task 5 and Task 6 contracts required by Task 7

### Task 5 baseline and Task 7-owned extensions (`[EXTEND-T5]`)

Stable commit `07a5c307` is the minimum source baseline. Task 7 MUST preserve its committed capabilities and add the missing capabilities below, regardless of concrete class names:

1. constructor-private ingress, binding, lease, replay-claim, metadata-authority and trusted-reconciler facts;
2. generic `VerifiedSignedEnvelope<T>`, `AcceptedTransportFrame<T>`, device/adapter admission stores and typed capacity denials;
3. exact retained bytes with defensive copy;
4. complete device **and adapter** row serialization and restart reconstruction from exact raw wire plus exact persisted metadata for pending rows;
5. deterministic same-wire concurrency: one accepted claim, other contender pending/duplicate, never two claims;
6. metadata recovery validation for registry identity, canonical lease IDs, branch nulls, sorted/unique scope and exact JCS re-projection;
7. snapshot/restart coverage for finalized and abandoned device/adapter rows, including exact cached receipt bytes, status, persisted metadata and charged bytes;
8. non-no-op ordinary compaction plus Task 7 security tombstone compaction, with trusted reconciler ownership and exact counter updates;
9. exact adapter retained-byte charging; zero is invalid whenever raw wire/reservation/metadata produce a nonzero charge;
10. an extension point for Task 7's policy registry and two partitions without exposing caller-selected policy/class;
11. current ordinary 4,096/67,108,864/16,384-byte behavior unchanged.

Coverage assessment is exact: `07a5c307` covers items 1-3 and the deterministic-concurrency/metadata portions of items 5-6; it partially covers item 4 for device pending rows only (`protocol/src/control-envelope.ts@07a5c307`, lines 654-677, 727-800). Device abandon/compact remain no-op (lines 1014-1018). Adapter admission uses the ledger's default zero charge and its load/resume/abandon/compact methods are absent/no-op (lines 1105-1123, 1153-1156; the default is `protocol/src/replay-window.ts@07a5c307`, lines 587-591). Task 7 owns adapter pending persistence/restart, finalized/abandoned persistence, nonzero adapter accounting, both compaction paths and items 7-11. Tests MUST demonstrate those additions rather than treating the Task 5 deterministic backend as a complete durable store.

### Task 6 contract (`[WAIT-T6]`)

Before Task 6 is declared complete, its canonical Files list MUST also include:

- Create: `protocol/schemas/v1/capabilities-registry.schema.json`
- Create: `protocol/src/capability-registry.ts`
- Modify: `protocol/src/schema-catalog.ts`
- Modify: `protocol/test/schema-validator.test.ts`
- Test: `protocol/test/capability-manifest.test.ts`

`capabilities.json` and its schema MUST require `parameters_schema_id`, `result_schema_id` and `offline_policy` on every entry. Each referenced schema must already be an exact closed schema in the catalog; startup fails if either reference is absent, open, duplicated or belongs to another capability. If Task 6 uses existing schema documents rather than creating capability-specific files, the exact referenced `$id` list is frozen in `capabilities.json` and covered by the same startup test.

Task 6 MUST export these exact concepts (names may differ only if Task 7 brief is amended consistently before implementation):

```ts
export interface LockedCapabilityEntry {
  readonly capability: string;
  readonly parametersSchemaId: string;
  readonly resultSchemaId: string;
  readonly offlinePolicy: "WAIT_READ"|"FAIL_OFFLINE";
  readonly requiredScopeNames: readonly string[];
  readonly riskFloor: "L0"|"L1"|"L2"|"L3"|"L4";
  readonly allowedBackendClasses: readonly string[];
}

export interface LockedCapabilityRegistry {
  lookup(capability:string): LockedCapabilityEntry | undefined;
}

export type AuthorizedOperationFact = Readonly<{
  binding: StoredOperationBinding;
  revisionSnapshot: RevisionSnapshot;
  capability: LockedCapabilityEntry;
  readonly [authorizedOperationFactBrand]: true;
}>;

export interface EnvelopeAuthorizationGate {
  evaluateOperation(input: Readonly<{
    messageType:P0aReplayMessageType;
    payload:unknown;
    context:AuthenticatedBindingContext;
    storedOperation:StoredOperationBinding|null;
  }>): {allowed:true; fact:AuthorizedOperationFact}
    | {allowed:false; denial:"PAIRING_INACTIVE"|"SCOPE_DENIED"|
        "REVISION_MISMATCH"|"POLICY_BLOCKED"};
}
```

Task 6 also MUST freeze:

- one canonical `computeParametersDigest(entry,parameters)` function;
- one closed `RevisionSnapshot` runtime/wire converter and exact equality function;
- exact five `Task6ReplayMessageType` TTL values;
- capability startup checks for unique name, resolvable parameter/result schemas, offline policy and exact required scopes;
- a mint path from current authorization to `CurrentReceiptReplayAuthorization` without accepting historical payload claims as current authority.

Task 7 MUST NOT independently parse `capabilities.json`, recreate revision equality, choose Task 6 TTLs or fall back when this contract is unavailable.

## RED fixture and test inventory

Every fixed vector file is a closed object with a `vector_id`, exact semantic input, standard padded Base64 of complete expected JCS bytes and decimal-u64 byte length/digest fields. Tests decode and byte-compare; they never trust an expected digest/length without reconstruction.

- `operation-state-vectors.json`: every pending/terminal three-column state, legal reason matrix and invalid cross-products.
- `result-digest-vectors.json`: no-result and result branches, explicit null vs absence, `system_ui_handoff`, every field mutation and alternate serialization.
- `reconciliation-evidence-vectors.json`: both kinds/outcomes, null/non-null reference, every field mutation and exact digest.
- `receipt-replay-vectors.json`: exact inner bytes, standard Base64 padding, inner digest, current outer header/payload preimage and historical-expiry case.
- three replay-intent vectors: exact eight-member metadata documents already specified, both branches and empty/nonempty adapter scope.
- migration fixtures: exact legacy full-record source digest and exact target-record digest.

Boundary tests MUST use synthetic counters/sparse rows; no test allocates 9.6 GB. Receipt/component boundary tests allocate at most one 262,145-byte fixture.

## Blocker closure matrix

| Readiness blocker | Amendment closure | Gate |
|---|---|---|
| B1 incomplete state machine | Section 4 freezes record/event/reduction types, every legal edge, reason matrix, revision and reconciliation conflict | `[INDEPENDENT]`, WAIT-T6 only for branded revision/offline lookup integration |
| B2 impossible ledger semantics | Section 5 replaces claim-only model with operation-aware CAS, exact result outcomes, crash/outbox/restart rules | `[INDEPENDENT]` value/reference model; `[EXTEND-T5]` replay recovery integration |
| B3 incomplete wire/receipt binding | Section 2 assigns all schema owners/IDs/fields, full binding, result/evidence/digest and snapshot/ACK contracts | `[INDEPENDENT]` schema skeleton; `[WAIT-T6]` capability result/parameter schema refs |
| B4 impossible scoped lookup/forgeable invocation | Section 1 adds device to exact scope key and opaque handle/authority facts | `[INDEPENDENT]`; integrate with stable `[EXTEND-T5]` authority pattern |
| B5 non-total errors/dual ledger-full | Section 8 freezes namespaces, codes, retryability, classifier, precedence and two ledger-full paths | `[INDEPENDENT]`; authorization reason integration `[WAIT-T6]` |
| B6 27-vs-full-registry contradiction | Section 3 creates exact 27-row replay-policy projection and permits six non-replay rows | `[INDEPENDENT]` Task5/7 rows; five rows `[WAIT-T6]` |
| B7 replay/tombstone/ACK/restart mismatch | Section 6 freezes rows, shared keys, partitions, charges, duplicate decision, ACK rewrite, equality, restart and rollover | `[EXTEND-T5]` durable admission surface; accounting functions `[INDEPENDENT]` |
| B8 hard-coded admission/Task6 gaps | Sections 3 and 9 freeze registry router/TTL plus required capability/revision contract | `[EXTEND-T5]` and `[WAIT-T6]` |
| B9 infeasible Files list | Replacement Files list includes catalog, registry schemas, error model and all fixed vectors/tests | `[INDEPENDENT]` |

## Parallel TDD batches

At most one batch may modify a given source file at once. The following can run in parallel after their gates:

1. **Batch A `[INDEPENDENT]` — schemas/registries/vectors:** new schemas, errors registry, replay-policy registry Task5/Task7 rows, schema catalog, schema/registry RED tests.
2. **Batch B `[INDEPENDENT]` — pure operation/error:** operation reducer/table, error model/precedence, state/evidence/result vectors and property tests. It does not touch `control-envelope.ts` or `replay-window.ts`.
3. **Batch C `[INDEPENDENT]` — execution reference store:** CAS/linearization/recovery/outbox model and crash tests. It consumes B's types after B's interface commit, so implementation starts after that small interface checkpoint.
4. **Batch D `[EXTEND-T5]` — replay partition:** starting from `07a5c307`, add adapter pending persistence/restart, finalized/abandoned snapshots, nonzero adapter accounting, ordinary/security compaction, policy resolver, shared window/index, exact counters, security rows/tombstones, ACK/compaction/restart and replay tests. It exclusively owns `replay-window.ts` during the batch.
5. **Batch E `[WAIT-T6]` — adapter semantic admission:** opaque invocation, scoped binding, five actions, parameter/revision/offline authorization and adapter tests. It exclusively owns `adapter-admission.ts`.
6. **Batch F `[EXTEND-T5][WAIT-T6]` — cumulative envelope/receipt:** registry-driven router/lifetime, full command/receipt/ACK/snapshot/error admission, receipt replay and `control-envelope.ts` tests. It exclusively owns `control-envelope.ts`.
7. **Batch G `[INDEPENDENT after schema + operation interfaces]` — migration:** legacy fixture verification, concrete target, migration receipt/signature and vectors.
8. **Batch H `[all gates]` — crash/integration:** cross-store outbox, all crash cuts, cumulative registry counts, both `SECURITY_LEDGER_FULL` paths, full test command and typecheck.

Review checkpoints: A+B interface freeze; C+D storage semantics; E+F wire/authorization; G migration isolation; H final invariant review.

## 11. Product/security decisions — recorded approval

The following are product/security decisions, not implementation defaults. The
user-confirmed record is mirrored in
`task-7-product-decisions.md`. It approves the reference contract; it does not
claim that the durable production backend has been implemented or reviewed.

### D1 — Permanent tombstones and polling exhaustion (blocking)

The approved design requires all security messages to share the replay namespace, while this amendment requires one permanent tombstone per compacted message. Therefore ordinary `operation_get`, `operation_wait` and `operation_snapshot` traffic consumes one irreversible row per unique message. At 16,384 rows in one `ReplaySpace`, further security admission returns public `SECURITY_LEDGER_FULL` before operation registration or execution; only authenticated key rotation (where `keyId` changes the `ReplaySpace`) opens a new space. Old spaces and tombstones remain read-only and retained. Automatic key rotation, tombstone deletion and quota borrowing are forbidden by the baseline.

| Option | Semantics | Security/product trade-off | Status |
|---|---|---|---|
| **A — Accept finite per-space behavior (baseline)** | Keep all ten security message types, permanent tombstones, 16,384 rows and explicit exhaustion. Clients use bounded polling/long-poll and normal authenticated key rotation when a space is exhausted. | Preserves the frozen dedupe/replay model and strongest post-compaction replay evidence; imposes a hard per-key/message-space lifetime and requires operational rotation/runbooks. | **SELECTED** |
| **B — Polling checkpoint/coalescing redesign** | Keep security commands/receipts tombstoned, but coalesce or move `get/wait/snapshot` to a separately specified bounded cursor/checkpoint class. | Better liveness for chatty polling, but changes the shared sequence/conflict and replay proof; requires a new registry class, threat analysis, vectors and an amendment to the approved design. | **PENDING design approval** |
| **C — Finite tombstone retention** | Delete/release compacted tombstones after an explicit horizon. | Recovers capacity but permits old duplicate messages after deletion unless a new epoch/nonce or equivalent proof is introduced; weakens the current permanent-dedupe guarantee. | **PENDING design approval** |
| **D — Automatic key rotation** | Rotate credentials when the security partition approaches exhaustion. | Does not release old rows, needs authenticated rotation, client fencing, durable key lifecycle and failure behavior; can hide a product quota and is not allowed as an implicit workaround. | **PENDING design approval** |

The user selected Option A. B/C/D remain unselected alternatives and require a
separate protocol amendment.

### D2 — Quota formula and accounting authority (blocking)

The owner MUST confirm that the logical security limit is exactly
`16,384 × (262,144 inbound + 262,144 receipt reservation + 65,536 intent metadata) = 9,663,676,416 bytes`, with `bigint` arithmetic, equality admitted, strict greater-than rejected, no cross-class borrowing and no physical-disk overhead included. The owner MUST also confirm that a compacted tombstone replaces (rather than adds to) its active row charge and remains counted against the 16,384 row cap. Any other quota (for example counting only active operations, charging allocator overhead, or allowing a tombstone to release a slot) is a protocol change requiring new vectors and security review.

**Status:** `ACCEPTED_USER_CONFIRMED`.

### D3 — Compaction and ACK retention policy (blocking)

The owner MUST confirm all of the following equality and crash semantics:

1. no security row compacts before a verified signed Bridge ACK;
2. `retention_until = max(operation_expires_at, bridge_ack_at + 2,592,000 seconds)`;
3. `now < retention_until` rejects, `now == retention_until` admits, and one millisecond after admits;
4. compaction atomically replaces active row/index/counters with a permanent tombstone; restart yields either the complete pre-state or complete post-state, never a mixed state;
5. exact duplicate after compaction is non-executing `REPLAY_REJECTED/COMPACTED_DUPLICATE`, while changed digest is `INTEGRITY_FAILED/MESSAGE_ID_CONFLICT`.

**Status:** `ACCEPTED_USER_CONFIRMED`.

### D4 — Recovery and result-unknown policy (blocking)

The owner MUST confirm that any durable execution claim lacking a trustworthy result at restart becomes terminal `result_unknown`, never auto-reexecutes, and can only gain an append-only reconciliation record from a verified late receipt or external-state evidence. ACK loss, outbox replay and compaction/restart are idempotent and preserve the first trustworthy result. A test fault hook MUST NOT be treated as production evidence that an external call did not start.

**Status:** `ACCEPTED_USER_CONFIRMED`.

### Decision record template

```text
decision_id: TASK7-D1 | TASK7-D2 | TASK7-D3 | TASK7-D4
selected_option_or_acceptance: <A/B/C/D or ACCEPT/REJECT>
approver_role: <product + security owner>
approver: <name/ID>
date_utc: <timestamp>
rationale: <bounded rationale and operational owner>
follow_up_amendment: <none or exact document/commit>
```

The D1–D4 choices are recorded and no longer block the reference artifacts.
Runtime durable replay/ACK/compaction integration remains a separate
implementation and review gate. The separate checklist
`task-7-product-decisions.md` mirrors this section so the approval record can
be reviewed without reading the full amendment.
