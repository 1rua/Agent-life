# Task 6 canonical-plan amendment — authorization closure

## Purpose and merge instruction

This is a canonical, plan-only patch.  In
`docs/superpowers/plans/2026-08-08-p0a-protocol-security-model.md`, replace the
entire existing `### Task 6:` block (from that heading through the line before
`### Task 7:`) with this document's **Files**, **Interfaces**, **Replacement
RED-first execution steps**, and **Readiness gates** sections.  Preserve the
plan's global constraints and task numbering.  Do not implement this patch in
production source as part of the documentation change.

The patch is written against the approved specification, Task 6 brief, frozen
Task 5 candidate `dedb02c83ace804a8a996ff981193fa854ba83ef`, and the current
Task 5 repair diff as an interface-only reference.  Implementation is gated on
the Task 5 prerequisite below; no structural casts or caller-selected
authorities may be introduced while applying the patch.

The amendment preserves the ownership split: Task 5 authenticates ingress,
owns scope-name syntax/copy/code-point order/duplicate rejection/freezing and
mints opaque binding contexts; Task 6 owns capability-registry membership,
current authorization, grant semantics, policy and egress evidence.  No
caller supplies a registry, a verifier result, an authorization state, a
scope ceiling, a replay policy, a clock result, or a local-user fact as a
structurally forged value.

## Recorded product decision — capability-specific closed filters

The product decision for Task 6 is now recorded as **Option A**: use a closed,
capability-specific filter schema for every capability.  Notification grants
MUST support explicit package and field selection; no open selector language or
caller-defined schema is permitted.  Other capabilities start with their own
minimal closed schema (often `{ "kind": "all" }`) and may gain additional
fields only through a later versioned contract amendment.

| option | registry/filter contract | prerequisite before GREEN |
|---|---|---|
| **A — capability-specific closed filters (SELECTED)** | Every registry row points to a named closed schema. Notifications use `urn:open-android-intelligence:protocol:v1:filter:notifications_v1` with explicit package IDs and selected fields (`metadata`, `content`); other capabilities use their own minimal closed schema. | **Recorded by product owner in this turn.** Registry rows, schemas and JCS vectors MUST use the selected literal. |
| B — generic `filter_all_v1` | Every capability accepts only `{ "kind": "all" }`, with no package/field/object selector. | Rejected for Task 6 because notification grants require per-app/per-field authorization. |

The shared contract is invariant across both options: a grant always carries a
present, schema-valid `filter`; `filter_hash` is
`b64u(SHA-256(RFC8785_UTF8(filter)))`; the decoded digest is compared in
constant time before grant lookup, mutation, or replay admission; and an
unknown capability or unresolved filter schema fails closed/startup.  RED
tests MUST include the selected notification package/field vectors and a
negative generic-selector vector.  The implementation MUST NOT infer policy
from fixture presence or silently widen a capability schema.

## Replace Task 6 Files with

**Task-5 review prerequisite — modify only if its review accepts this
interface amendment:**

- Modify: `protocol/src/control-envelope.ts`
- Modify: `protocol/test/control-envelope.test.ts`

Task 5 must export the constructor-private `ScopeCeiling` brand and preserve it
in both binding branches.  It must add `enrollmentScopeCeiling: ScopeCeiling`
to the device credential and device `AuthenticatedBindingContext`; the adapter
branch’s existing `scopeCeiling` becomes `ScopeCeiling`.  The loader copies,
sorts by Unicode code point, rejects duplicates, recursively freezes, and
brands the array after validating only Task 4 `scope` syntax.  It must not
consult `capabilities.json`.  The Task 5 test proves an ASCII-valid unknown
scope survives this boundary unchanged; Task 6 rejects it later.

```ts
declare const scopeCeilingBrand: unique symbol;
export type ScopeCeiling = readonly string[] & {
  readonly [scopeCeilingBrand]: "ScopeCeiling";
};
```

**Task-6 implementation files:**

- Create: `docs/protocol/v1/authorization.md`
- Create: `protocol/schemas/v1/authorization.schema.json`
- Create: `protocol/schemas/v1/authorization-revision.schema.json`
- Create: `protocol/schemas/v1/data-query-grant.schema.json`
- Create: `protocol/schemas/v1/capability-manifest.schema.json`
- Create: `protocol/schemas/v1/zero-retention-profile.schema.json`
- Create: `protocol/schemas/v1/capabilities-registry.schema.json`
- Create: `protocol/schemas/v1/revision-events.schema.json`
- Modify: `protocol/src/schema-catalog.ts`
- Modify: `protocol/profile/v1.json`
- Modify: `protocol/registries/v1/messages.json`
- Create: `protocol/registries/v1/capabilities.json`
- Create: `protocol/model/v1/revision-events.json`
- Modify: `protocol/src/control-envelope.ts`
- Modify: `protocol/src/outbound-envelope.ts`
- Modify: `protocol/src/replay-window.ts`
- Create: `protocol/src/capability-registry.ts`
- Create: `protocol/src/authorization.ts`
- Create: `protocol/src/authorization-revision.ts`
- Create: `protocol/src/data-query-grant.ts`
- Create: `protocol/src/capability-manifest.ts`
- Create: `protocol/src/risk-policy.ts`
- Create: `protocol/src/egress-policy.ts`
- Modify: `protocol/test/messages-registry.test.ts`
- Modify: `protocol/test/schema-validator.test.ts`
- Modify: `protocol/test/control-envelope.test.ts`
- Modify: `protocol/test/outbound-envelope.test.ts`
- Modify: `protocol/test/replay-window.test.ts`
- Create: `protocol/test/capability-registry.test.ts`
- Create: `protocol/test/authorization.test.ts`
- Create: `protocol/test/authorization-revision.test.ts`
- Create: `protocol/test/data-query-grant.test.ts`
- Create: `protocol/test/capability-manifest.test.ts`
- Create: `protocol/test/risk-policy.test.ts`
- Create: `protocol/test/egress-policy.test.ts`

The added schemas are immutable v1 documents.  Every object has
`unevaluatedProperties: false`; all new schema IDs are added to
`PROTOCOL_SCHEMA_DOCUMENTS` and `REQUIRED_PROTOCOL_SCHEMA_IDS`.  No Task 6
logic imports a JSON fixture except through its constructor-private locked
loader.

## Replace Task 6 Interfaces with

### 1. Cumulative signed control integration and replay policy

```ts
export type Task6MessageType =
  | "authorization_revision_update"
  | "authorization_revision_ack"
  | "data_query_grant_update"
  | "data_query_grant_ack"
  | "capability_manifest";

export type P0aControlMessageType = Task5MessageType | Task6MessageType;

export const P0A_CONTROL_MAX_LIFETIME_SECONDS: Readonly<Record<P0aControlMessageType, 60 | 300>>;
// Task 5 values remain unchanged.  Task 6 values are all exactly 300 seconds.

export type PreReplayValidation =
  | { readonly ok: true }
  | { readonly ok: false; readonly error: "INTEGRITY_FAILED" };

export interface PreReplayIntegrityGate {
  validate(
    envelope: VerifiedSignedEnvelope<P0aControlMessageType>,
    context: AuthenticatedBindingContext,
  ): PreReplayValidation;
}

export interface EnvelopeAuthorizationGate {
  evaluate(
    messageType: P0aControlMessageType,
    payload: unknown,
    context: AuthenticatedBindingContext,
  ): { allowed: true } | {
    allowed: false;
    denial: "PAIRING_INACTIVE" | "SCOPE_DENIED" | "REVISION_MISMATCH" | "POLICY_BLOCKED";
  };
}
```

`verifyTransportFrame` receives both gates, neither optional and neither
caller-selectable after the server/device composition root has constructed the
Task 6 authorization service.  Its fixed post-schema precedence is:

```text
message-size → canonical/container/header schema → locked messages-registry tuple
→ trusted binding/lease → signature/payload digest/TTL → Task 6 authorization gate
→ Task 6 pre-replay integrity gate → replay read/commit.
```

An authorization denial returns the existing public `NOT_AUTHORIZED`; a
pre-replay integrity denial returns `INTEGRITY_FAILED`.  The integrity gate is
called exactly once, is synchronous/pure, and receives the already
signature-verified immutable envelope.  No replay lookup, replay row, bitmap,
receipt reservation, grant row, outbox row, or UI state may be read or mutated
after it returns `INTEGRITY_FAILED`.  This is intentionally after authorization
to avoid disclosing filter-integrity details to an unauthorized binding.

Extend `VerifiedSignedEnvelope`, `AcceptedTransportFrame`, `ReplayClaim`,
`ReplayStore`, `DeviceReplayAdmissionStore`, `AdapterReplayAdmissionStore` and
all deterministic Task 5 reference backends from their Task-5-only default to
`P0aControlMessageType`; do not cast a Task 6 message to `Task5MessageType`.
`protocol/src/outbound-envelope.ts` must also accept the bridge-to-app Task 6
ACK types and retain their exact canonical bytes under its existing
constructor-private outbound authority.

The locked replay policy classifier has exactly two outcomes for the complete
Task 5/6 message set:

```ts
export const TASK6_DEFAULT_REPLAY_MESSAGE_TYPES = Object.freeze([
  "authorization_revision_update", "authorization_revision_ack",
  "data_query_grant_update", "data_query_grant_ack", "capability_manifest",
] as const);
// Every Task 6 type maps exactly to:
// { class_id: "task5_default",
//   retention_rule_id: "retain_until_max_expires_at_or_admitted_at_plus_86400_seconds_v1" }
```

It shares the one replay namespace, conflict index and Task-5-default limits;
no Task 6 caller can select a class, receipt reservation or retention rule.
Startup deep-compares the complete classifier against `messages.json`; missing,
extra or reclassified Task 5/6 names fail startup.  Task 7 remains the sole
owner of `operation_security_ledger`.

Append exactly these `messages.json` rows and create a matching closed payload,
header and envelope `$id` in the named Task 6 schema document:

| type | direction | domain | payload `$id` | header/envelope document |
|---|---|---|---|---|
| `authorization_revision_update` | `app-to-bridge` | `control/app-to-bridge` | `urn:open-android-intelligence:protocol:v1:message:authorization_revision_update` | `authorization-revision.schema.json` |
| `authorization_revision_ack` | `bridge-to-app` | `control/bridge-to-app` | `urn:open-android-intelligence:protocol:v1:message:authorization_revision_ack` | `authorization-revision.schema.json` |
| `data_query_grant_update` | `app-to-bridge` | `control/app-to-bridge` | `urn:open-android-intelligence:protocol:v1:message:data_query_grant_update` | `data-query-grant.schema.json` |
| `data_query_grant_ack` | `bridge-to-app` | `control/bridge-to-app` | `urn:open-android-intelligence:protocol:v1:message:data_query_grant_ack` | `data-query-grant.schema.json` |
| `capability_manifest` | `app-to-bridge` | `control/app-to-bridge` | `urn:open-android-intelligence:protocol:v1:message:capability_manifest` | `capability-manifest.schema.json` |

Each header `$id` is exactly `urn:open-android-intelligence:protocol:v1:header:<message_type>`;
each envelope `$id` is exactly `urn:open-android-intelligence:protocol:v1:envelope:<message_type>`.
Every Task 6 header uses the existing paired-device family header, therefore
requires the common signed fields plus `device_id`, `pairing_generation` and
`connection_generation`; every envelope has only `header`, `payload` and
`signature`.  `control-envelope.schema.json` continues to provide the shared
family shell only.  The named Task 6 schema owns the concrete closed leaves.

### 2. Locked capability registry and capability-specific grant filters

```ts
export type CapabilityRisk = "L0" | "L1" | "L2" | "L3" | "L4";
export type DataSensitivity = "none" | "metadata" | "content" | "sensitive" | "control";
export type OfflinePolicy = "WAIT_READ" | "EVENT_QUEUE" | "FAIL_OFFLINE";
export type MinimumApproval = "continuous" | "time_limited_session" | "per_operation_on_device" | "deny";
export type BackendClass = "B0" | "B1";

export type CapabilityMatch =
  | Readonly<{ kind: "exact"; value: string }>
  | Readonly<{ kind: "segments"; segments: readonly (string | "{data_type}" | "{sensor}" | "{typed_action}")[] }>;

export interface CapabilityRegistryEntry {
  readonly capabilityId: string;
  readonly match: CapabilityMatch;
  readonly riskFloor: CapabilityRisk;
  readonly dataSensitivity: DataSensitivity;
  readonly offlinePolicy: OfflinePolicy;
  readonly minimumApproval: MinimumApproval;
  readonly allowedBackends: readonly BackendClass[];
  readonly filterSchemaId: string;
  readonly specialInvariants: readonly string[];
}

export type FilterPolicyV1 = "closed_all_v1" | "capability_specific_v1";

export interface LockedCapabilityRegistry {
  readonly filterPolicy: FilterPolicyV1;
  resolve(scope: string): CapabilityRegistryEntry | null;
  validateCeiling(ceiling: ScopeCeiling): void;
}

export function loadLockedCapabilityRegistry(): LockedCapabilityRegistry;
export function validateCapabilityFilter(
  capability: CapabilityRegistryEntry,
  filter: unknown,
): Readonly<Record<string, unknown>>;
```

`capabilities.json` is validated by the new
`urn:open-android-intelligence:protocol:v1:capabilities-registry` schema, recursively frozen,
and branded once.  Both options use literal top-level fields
`$schema`, `registry_id`, `protocol_version: "1.0"`, `registry_version: "1"`,
`filter_policy` and a non-empty `filter_schema_ids` array.  `filter_policy` is
exactly one of `closed_all_v1` or `capability_specific_v1`; each listed schema
ID must resolve to a closed schema document and every registry row must point
to one listed ID.

`filter_policy` is a locked, non-caller-selectable value copied from the
approved decision record.  The notation above is a contract-level union, not a
checked-in registry instance and not a product choice.  Before any GREEN run,
the selected literal and all corresponding schema IDs must be present, and the
registry/schema-lock assertion must prove that the approved decision and
registry value are identical.
The loader rejects a missing, unknown or decision-mismatched policy at startup.

For the selected **capability-specific** policy, the notification closed branch
`urn:open-android-intelligence:protocol:v1:filter:notifications_v1` in
`authorization.schema.json` is:

```json
{ "$id": "urn:open-android-intelligence:protocol:v1:filter:notifications_v1", "type": "object",
  "required": ["kind", "packages", "fields"],
  "properties": {
    "kind": { "const": "notifications" },
    "packages": { "type": "array", "minItems": 1, "uniqueItems": true,
      "items": { "type": "string", "pattern": "^[a-zA-Z0-9_][a-zA-Z0-9_.-]{0,254}$" } },
    "fields": { "type": "array", "minItems": 1, "maxItems": 2, "uniqueItems": true,
      "items": { "enum": ["metadata", "content"] } }
  },
  "unevaluatedProperties": false }
```

Every other capability gets a named closed branch in the same schema document;
its exact shape is frozen in the registry before implementation.  An open
selector grammar, an unlisted family member, or an invented typed-action/filter
shape is invalid.  The shared grant rule is that `filter` is present and
validates against its resolved row; absent, `null`, rewritten or semantically
coerced filters are rejected.  `filter_hash` is exactly
`b64u(SHA-256(RFC8785_UTF8(filter)))`, with no semantic rewrite before hashing.

The registry contains exactly these 28 non-overlapping matches.  Segment
variables must match `^[a-z][a-z0-9_-]{0,127}$` and occupy one complete dot
segment; a pattern matches only the same number of segments.  `resolve` returns
the one matching row only; zero or more than one match is failure.  Loader
rejects duplicate `capabilityId`, duplicate exact match, overlapping segment
patterns, wrong protocol/registry version, unknown invariant ID, an unresolved
filter schema `$id`, or any forbidden lexical surface before returning a
registry.  A registry failure is process startup failure, never an allowed
unknown scope.

| match | risk | sensitivity | offline | approval | backends | invariants |
|---|---:|---|---|---|---|---|
| `device.status` | L0 | metadata | WAIT_READ | continuous | B0 | `none` |
| `notifications.metadata` | L1 | metadata | WAIT_READ | continuous | B0 | `none` |
| `notifications.content` | L2 | content | WAIT_READ | continuous | B0 | `none` |
| `sms.read` | L2 | sensitive | WAIT_READ | continuous | B0 | `none` |
| `sms.send` | L3 | control | FAIL_OFFLINE | per_operation_on_device | B0,B1 | `sms_exact_confirmation` |
| `calls.metadata` | L2 | sensitive | WAIT_READ | continuous | B0 | `none` |
| `contacts.read` | L2 | sensitive | WAIT_READ | continuous | B0 | `none` |
| `clipboard.read` | L1 | sensitive | FAIL_OFFLINE | continuous | B0,B1 | `none` |
| `clipboard.write` | L2 | control | FAIL_OFFLINE | time_limited_session | B0 | `none` |
| `location.coarse` | L1 | sensitive | WAIT_READ | continuous | B0 | `none` |
| `location.precise` | L2 | sensitive | WAIT_READ | continuous | B0 | `none` |
| `health.{data_type}.read` | L2 | sensitive | WAIT_READ | continuous | B0 | `none` |
| `sensors.{sensor}.snapshot` | L1 | metadata | WAIT_READ | continuous | B0 | `none` |
| `sensors.{sensor}.stream` | L2 | sensitive | FAIL_OFFLINE | time_limited_session | B0 | `none` |
| `calendar.read` | L1 | sensitive | WAIT_READ | continuous | B0 | `none` |
| `calendar.write` | L3 | control | FAIL_OFFLINE | per_operation_on_device | B0 | `none` |
| `alarms.read_next` | L1 | metadata | WAIT_READ | continuous | B0 | `none` |
| `alarms.create` | L3 | control | FAIL_OFFLINE | per_operation_on_device | B0 | `none` |
| `alarms.modify_owned` | L3 | control | FAIL_OFFLINE | per_operation_on_device | B0 | `none` |
| `window.metadata` | L2 | sensitive | FAIL_OFFLINE | time_limited_session | B0 | `none` |
| `window.tree` | L2 | sensitive | FAIL_OFFLINE | time_limited_session | B0 | `none` |
| `screen.view` | L2 | sensitive | FAIL_OFFLINE | time_limited_session | B0 | `screen_session` |
| `screen.stream` | L3 | sensitive | FAIL_OFFLINE | time_limited_session | B0 | `screen_session` |
| `ui.control.typed` | L3 | control | FAIL_OFFLINE | time_limited_session | B0,B1 | `no_credential_or_confirmation_action` |
| `ui.control.generic` | L3 | control | FAIL_OFFLINE | time_limited_session | B0,B1 | `no_credential_or_confirmation_action` |
| `root.actions.{typed_action}` | L3 | control | FAIL_OFFLINE | per_operation_on_device | B1 | `signed_typed_action_only` |
| `shell.restricted` | L3 | control | FAIL_OFFLINE | per_operation_on_device | B0,B1 | `fixed_template_only` |
| `device.notify` | L1 | metadata | FAIL_OFFLINE | continuous | B0 | `agent_device_rate_limited` |

`L4` is not a registry floor for an available capability.  It is a
deterministic input correction and always denies.  There is no match for
`shell.root`, `shell.root.*`, `exec`, `*.exec`, `script`, `*.script`,
`dynamic_action`, or any string containing `root_shell`; each resolves to
`null`.  Tests use representative unknowns from every category and scan the
registry/schema/test helper surfaces for forbidden names.

### 3. Current authorization, revisions and manifest non-authority

```ts
export interface RevisionSnapshot {
  readonly pairingGeneration: bigint;
  readonly authorizationEpoch: bigint;
  readonly scopeRevisions: ReadonlyMap<string, bigint>;
}

export type DeviceLifecycle = "active" | "paused" | "revoked";

export interface CurrentDeviceAuthorization {
  readonly lifecycle: DeviceLifecycle;
  readonly activePairing: boolean;
  readonly enrollmentScopeCeiling: ScopeCeiling;
  readonly localConsent: ReadonlySet<string>;
  readonly systemPermission: ReadonlySet<string>;
  readonly backendAvailable: ReadonlySet<BackendClass>;
  readonly revision: RevisionSnapshot;
}

export interface CurrentAuthorizationStore {
  loadDevice(context: Extract<AuthenticatedBindingContext, { kind: "device" }>): CurrentDeviceAuthorization | null;
  loadAdapter(context: Extract<AuthenticatedBindingContext, { kind: "adapter" }>): Readonly<{
    readonly active: boolean;
    readonly scopeCeiling: ScopeCeiling;
    readonly tenantId: string;
    readonly humanPrincipalId: string;
    readonly agentPrincipalId: string;
    readonly agentInstanceId: string;
    readonly workspaceId: string;
  }> | null;
}

export interface ServerAgentSessionBinding {
  readonly tenantId: string;
  readonly humanPrincipalId: string;
  readonly agentPrincipalId: string;
  readonly agentInstanceId: string;
  readonly workspaceId: string;
  readonly sessionId: string;
  readonly deviceId: string;
  readonly agentScopeCeiling: ScopeCeiling;
}

export interface DeviceAccessInput {
  readonly binding: Extract<AuthenticatedBindingContext, { kind: "device" }>;
  readonly requester: ServerAgentSessionBinding;
  readonly capability: string;
  readonly requiredRevision: RevisionSnapshot;
  readonly current: CurrentDeviceAuthorization;
}

export interface StoredRecordLineage {
  readonly tenantId: string;
  readonly humanPrincipalId: string;
  readonly deviceId: string;
  readonly capability: string;
  readonly filterHash: string;
  readonly sourceId: string;
  readonly expiresAt: string;
  readonly tombstoned: boolean;
}

export interface StoredQueryInput {
  readonly requester: ServerAgentSessionBinding;
  readonly key: DataQueryGrantKey;
  readonly grant: DataQueryGrantState;
  readonly record: StoredRecordLineage;
  readonly clock: Clock;
}

export type VerifiedCapabilityManifest = Readonly<{
  readonly frame: AcceptedTransportFrame<"capability_manifest">;
  readonly manifestGeneration: bigint;
  readonly entries: readonly CapabilityManifestEntry[];
  readonly [verifiedCapabilityManifestBrand]: true;
}>;
export type EffectiveCapability = Readonly<{
  readonly capability: string;
  readonly manifestGeneration: bigint;
  readonly revision: RevisionSnapshot;
  readonly allowed: true;
}>;

export type AuthorizationDecision =
  | { readonly allowed: true }
  | { readonly allowed: false; readonly reason:
      "PAIRING_INACTIVE" | "BINDING_MISMATCH" | "SCOPE_DENIED"
      | "SYSTEM_PERMISSION_MISSING" | "BACKEND_UNAVAILABLE" | "REVISION_MISMATCH"
      | "GRANT_REVOKED" | "LINEAGE_MISMATCH" | "RECORD_EXPIRED" | "RECORD_DELETED" };

export function createEnvelopeAuthorizationGate(current: CurrentAuthorizationStore): EnvelopeAuthorizationGate;
export function evaluateDeviceAccess(input: DeviceAccessInput): AuthorizationDecision;
export function evaluateStoredQuery(input: StoredQueryInput): AuthorizationDecision;
export function effectiveCapabilities(
  manifest: VerifiedCapabilityManifest,
  binding: AuthenticatedBindingContext,
  current: CurrentAuthorizationStore,
): readonly EffectiveCapability[];
```

`createEnvelopeAuthorizationGate` obtains only the locked registry from the
composition root; the public factory has no registry parameter in production
exports.  At construction it validates the locked registry and fails startup.
At each `evaluate`, it first asks `current` for the binding’s current state,
validates the relevant branded ceilings against the locked registry, and then
performs deny-first access evaluation.  It never interprets network metadata,
transport profile, a model-supplied `user_id`, or a manifest field as identity
authority.

`DeviceAccessInput` contains the trusted Task 5 device context, a server-owned
agent/session binding tuple, requested capability, required revision tuple and
the current state loaded above.  It compares all tenant/human/device/agent/
instance/workspace/session values and checks exactly:

```text
active pairing and lifecycle active
AND all trusted binding values equal
AND scope resolves in registry and lies in agent ∩ enrollment ∩ local-consent ceilings
AND required system permission is present
AND an allowed backend is currently available
AND pairing_generation, authorization_epoch and the capability scope_revision exactly match.
```

`StoredQueryInput` contains a server-authenticated tenant/human/agent/
instance/workspace/session tuple, device, capability, `DataQueryGrantKey`,
current grant, record lineage, record expiry/tombstone and trusted query time.
It deliberately has no Android system-permission, device backend or device
authorization-epoch input.  It checks the independent §7.2 conjunction only.
The only path that can create the input’s agent/session tuple is the
server-authenticated runtime adapter; Task 7 binds concrete operation messages
to it when those message types exist.

`effectiveCapabilities` receives a `VerifiedCapabilityManifest`, not raw JSON,
and reloads current state through `CurrentAuthorizationStore` for every entry.
It returns an entry only when both the manifest snapshot and a fresh
`evaluateDeviceAccess` result allow it.  A stale `manifest_generation`, stale
revision or unavailable `current` store returns an empty effective list; it
never returns `allowed` based solely on the manifest.

Revision events use opaque sources so a signed device message is not mistaken
for on-device user authentication:

```ts
export type AuthenticatedLocalUserTransition = Readonly<{
  readonly deviceId: string;
  readonly pairingGeneration: bigint;
  readonly transitionId: string;
  readonly kind: "resume";
  readonly authenticatedAt: string;
  readonly [authenticatedLocalUserTransitionBrand]: true;
}>;

export type RevisionEvent =
  | { readonly kind: "new_pairing"; readonly nextPairingGeneration: bigint }
  | { readonly kind: "pause" | "emergency_stop" | "system_permission_change" | "backend_credential_change" }
  | { readonly kind: "resume"; readonly localUser: AuthenticatedLocalUserTransition }
  | { readonly kind: "scope_policy_revoke" | "scope_policy_regrant"; readonly capability: string }
  | { readonly kind: "bridge_command_key_activated"; readonly fact: RotationActivationFact };

export function applyRevisionEvent(state: AuthorizationRevisionState, event: RevisionEvent): AuthorizationRevisionState;
```

The `resume` branch verifies that the local fact is minted by the same
constructor-private local-user authenticator, names the same device/pairing,
and is used once.  `revoked` accepts only `new_pairing`, whose generation is
exactly previous + 1; it permanently marks every old pairing binding revoked.
All global events advance epoch exactly one; scope revoke/regrant additionally
advance only the named capability revision exactly one.  `bridge_command_key_activated`
accepts only `fact.owner === "bridge-command"`, a non-null fact pairing equal
to current pairing, null adapter-generation fields, and a device credential
correlation already bound to the revision state.  Adapter credential reissue
is rejected by this reducer and remains only an adapter-generation transition.

`revision-events.json` has literal top-level
`registry_id: "urn:open-android-intelligence:protocol:v1:model:revision-events"`,
`protocol_version: "1.0"`, `model_version: "1"`, and one closed row per event
above.  The row fixes allowed source lifecycle, target lifecycle, `epoch_delta`
(`"0"` or `"1"`), `pairing_generation_delta` (`"0"` or `"1"`), and
`scope_revision_delta` (`"none"` or `"one_named_capability"`).  Its schema
and loader reject duplicate event names, unlisted source/target states, any
other delta, or an event that attempts an adapter-generation update.  The JSON
table is the sole transition authority; TypeScript dispatches by it rather than
a parallel handwritten transition list.

The revision wire payloads are closed exactly as follows.  Their `event_digest`
is `b64u(SHA-256(RFC8785_UTF8({event_id,event_kind,capability,previous_revision,
next_revision,target_lifecycle})))`; `null` members are present as shown.

```ts
export type DecimalU64 = string;
export type LowercaseUuidV4 = string;
export type Sha256B64u = string;
export type RevisionSnapshotWire = Readonly<{
  pairing_generation: DecimalU64;
  authorization_epoch: DecimalU64;
  scope_revisions: Readonly<Record<string, DecimalU64>>;
}>;
type RevisionWireBody = Readonly<{
  event_id: LowercaseUuidV4;
  event_kind: "new_pairing" | "pause" | "resume" | "emergency_stop"
    | "scope_policy_revoke" | "scope_policy_regrant"
    | "system_permission_change" | "backend_credential_change"
    | "bridge_command_key_activated";
  capability: string | null;
  previous_revision: RevisionSnapshotWire;
  next_revision: RevisionSnapshotWire;
  target_lifecycle: DeviceLifecycle;
  event_digest: Sha256B64u;
}>;
type AuthorizationRevisionUpdate = RevisionWireBody;
type AuthorizationRevisionAck = RevisionWireBody & Readonly<{ bridge_effective: true }>;
```

For `new_pairing`, `capability` is null, target lifecycle is `active`, pairing
delta is one and all epoch/scope revisions are fresh values in the signed
payload.  For the two scope events capability is a resolved registry scope;
all other events require null.  The local-user fact and rotation fact are
never serialized as user-controlled wire data; they are local/server
constructor-private authorities correlated to the signed `event_id`.

### 4. Verified data-query grants, atomic bridge mutation and recovery

```ts
export type DataQueryGrantKey = Readonly<{
  readonly tenantId: string;
  readonly humanPrincipalId: string;
  readonly deviceId: string;
  readonly capability: string;
  readonly filterHash: string;
}>;

export type BridgeGrantState = "effective" | "revoked";
export type DeviceGrantUiState = "effective" | "grant_pending_ack" | "revoked_pending_ack" | "revoked";

export interface DataQueryGrantState {
  readonly key: DataQueryGrantKey;
  readonly grantRevision: bigint;
  readonly bridgeEffectiveState: BridgeGrantState;
  readonly deviceUiState: DeviceGrantUiState;
  readonly lastProposalDigest: string | null;
}

export type GrantExpansionAuthority = Readonly<{
  readonly key: DataQueryGrantKey;
  readonly humanPrincipalId: string;
  readonly authenticatedSessionId: string;
  readonly origin: "authenticated_device" | "authenticated_agent_session";
  readonly [grantExpansionAuthorityBrand]: true;
}>;

export type VerifiedGrantUpdate = Readonly<{
  readonly frame: AcceptedTransportFrame<"data_query_grant_update">;
  readonly key: DataQueryGrantKey;
  readonly proposalDigest: string;
  readonly proposedState: BridgeGrantState;
  readonly grantRevision: bigint;
  readonly expansion: GrantExpansionAuthority | null;
  readonly [verifiedGrantUpdateBrand]: true;
}>;
export type VerifiedGrantAck = Readonly<{
  readonly frame: AcceptedTransportFrame<"data_query_grant_ack">;
  readonly key: DataQueryGrantKey;
  readonly proposalDigest: string;
  readonly bridgeEffectiveState: BridgeGrantState;
  readonly grantRevision: bigint;
  readonly [verifiedGrantAckBrand]: true;
}>;

export interface DataQueryGrantStore {
  applyBridgeUpdateAtomically(update: VerifiedGrantUpdate): Promise<
    | { readonly kind: "applied"; readonly state: DataQueryGrantState; readonly ackOutboxId: string }
    | { readonly kind: "same"; readonly state: DataQueryGrantState; readonly ackOutboxId: string }
    | { readonly kind: "rejected"; readonly error: "AUTH_FAILED" | "INVALID_STATE_TRANSITION" }
  >;
  loadAckOutbox(id: string): Promise<SendableOutboundEnvelope>;
  markAckSent(id: string): Promise<void>;
  recoverUnsentAcks(): AsyncIterable<SendableOutboundEnvelope>;
}

export function verifyDataQueryGrantUpdate(
  frame: AcceptedTransportFrame<"data_query_grant_update">,
  registry: LockedCapabilityRegistry,
  expansion: GrantExpansionAuthority | null,
): VerifiedGrantUpdate;
export function verifyDataQueryGrantAck(
  frame: AcceptedTransportFrame<"data_query_grant_ack">,
): VerifiedGrantAck;
export function reduceDataQueryGrant(state: DataQueryGrantState, event: VerifiedGrantUpdate | VerifiedGrantAck): DataQueryGrantState;
```

The `data_query_grant_update` payload has exactly:

```json
{
  "grant_key": {
    "tenant_id": "opaque server ID",
    "human_principal_id": "opaque server ID",
    "device_id": "opaque server ID",
    "capability": "Task 4 scope syntax",
    "filter_hash": "43-character SHA-256 base64url"
  },
  "filter": { "kind": "all" },
  "grant_revision": "decimal-u64",
  "proposed_state": "effective|revoked",
  "proposal_digest": "43-character SHA-256 base64url"
}
```

`proposal_digest` is exactly
`b64u(SHA-256(RFC8785_UTF8({filter,grant_key,grant_revision,proposed_state})))`.
The `data_query_grant_ack` payload has exactly `grant_key`, `grant_revision`,
`proposal_digest`, and `bridge_effective_state: "effective|revoked"`.  These
payloads are the leaf schemas named above; header signing covers the entire
payload.  Tenant, human and device values in an app-originated update are only
consistency declarations and must equal the verified Task 5 device context;
they never create authority.

`verifyDataQueryGrantUpdate` first resolves capability through the locked
registry and validates the exact `filter` with that row’s filter schema.  It
then recomputes the decoded 32-byte `filter_hash` and `proposal_digest`, using
constant-time byte comparison.  Any mismatch throws `INTEGRITY_FAILED` before
grant lookup, before reducer/store invocation and before replay admission via
the pre-replay gate.  It rejects non-device directions, non-human principal
bindings, unknown scope, key/context mismatch, non-monotonic revision, and an
attempt to create/regrant an effective grant without a same-human
`GrantExpansionAuthority` whose origin is either the verified target-device
binding or an authenticated target-human Agent session.  No administrator
context can mint that authority.

The atomic bridge transaction, in order, is:

```text
1. Re-validate opaque VerifiedGrantUpdate ownership and its replay claim.
2. Read the one grant row by the exact five-part key under serializable isolation.
3. Reject stale/out-of-order revision or forbidden generic regrant with no writes.
4. Persist bridgeEffectiveState and revision; a revoke becomes effective in this same commit.
5. Persist an immutable ACK outbox record containing the complete ACK payload,
   update message/claim identity, and exact prepared outbound bytes.
6. Atomically finalize/associate the inbound replay claim with that outbox receipt.
7. Commit.  Only then send the retained ACK bytes; send failure leaves the outbox pending.
```

After a committed revoke, new stored queries are denied immediately even before
the ACK can be sent.  Restart scans the durable outbox and retransmits the same
prepared bytes; it never recalculates a different ACK/sequence or rolls a
revoke back.  A duplicate update returns the retained outbox record and has no
second grant/replay mutation.  A crash before commit changes neither grant nor
outbox; a crash after commit/before send recovers the exact ACK.  This store is
the required transaction boundary; a pure reducer alone is not a durability
claim.

On the device, a verified outgoing effective update enters `grant_pending_ack`;
a verified revoke enters `revoked_pending_ack` while retaining its prior Bridge
effective state.  Only a `VerifiedGrantAck` with all of key, proposal digest,
revision and bridge effective state equal changes UI to `effective` or
`revoked`.  Forged signatures, stale revisions, a different key/digest and
out-of-order ACKs throw without changing either state.  A revoked grant may be
made effective only by a later, same-human `GrantExpansionAuthority`; an
unbranded `{grant_revision: n}` object never reactivates it.

### 5. Capability manifest and deterministic risk policy

The `capability_manifest` payload has literal fields:

```ts
export interface CapabilityManifestEntry {
  readonly capability: string;
  readonly schemaSupported: boolean;
  readonly backendAvailable: boolean;
  readonly backendKind: "B0" | "B1" | null;
  readonly systemPermission: boolean;
  readonly localConsent: boolean;
  readonly agentScopeEffective: boolean;
  readonly offlinePolicy: OfflinePolicy;
  readonly freshnessLimitSeconds: bigint;
  readonly revision: RevisionSnapshot;
  readonly modelDestination: string | null;
  readonly zeroRetentionEvidenceRevision: bigint | null;
  readonly zeroRetentionStatus: "verified" | "missing" | "expired" | "drifted" | "provider_object_returned";
}
type CapabilityManifestPayload = Readonly<{
  manifest_generation: DecimalU64;
  entries: readonly Readonly<{
    capability: string;
    schema_supported: boolean;
    backend_available: boolean;
    backend_kind: "B0" | "B1" | null;
    system_permission: boolean;
    local_consent: boolean;
    agent_scope_effective: boolean;
    offline_policy: OfflinePolicy;
    freshness_limit_seconds: DecimalU64;
    constraints: Readonly<Record<string, never>>;
    revision: RevisionSnapshotWire;
    model_destination: string | null;
    zero_retention_evidence_revision: DecimalU64 | null;
    zero_retention_status: "verified" | "missing" | "expired" | "drifted" | "provider_object_returned";
  }>;
}>;
```

`entries` is sorted by Unicode code point capability and unique; every
capability must resolve in the locked registry.  `constraints` is initially the
closed empty object, not an open vendor extension point.  `model_destination`
is an opaque destination ID, never an authorization identity.  The signed
manifest is verified through the normal Task 6 bridge-to-app message path and
then branded `VerifiedCapabilityManifest`; raw objects cannot call
`effectiveCapabilities`.

`evaluateRiskPolicy` accepts a resolved registry entry, data-sensitivity
correction, user override, selected backend and `enhancedBackendSessionActive`.
It takes the strictest approval in this exact order:

```text
deny > per_operation_on_device > time_limited_session > continuous.
```

L4 always returns `{allowed:false, reason:"POLICY_BLOCKED"}`.  B1 without an
active enhanced-backend session returns the same result.  `sms.send` always
returns `per_operation_on_device`, regardless of user override or session; its
approval binding is exactly recipient, complete body, SIM/subscription, direct
or system-UI path, operation ID, expiry and RevisionSnapshot.  Keep
`PolicyDecision` separate from `AuthorizationDecision` so `POLICY_BLOCKED` is
not accidentally added to a stored-query access result.

### 6. Signed zero-retention evidence and body-egress decision

Add exactly `"zero-retention/bridge"` to `protocol/profile/v1.json` signature
domains.  The new schema root ID is
`urn:open-android-intelligence:protocol:v1:zero-retention-profile`; its evidence branch ID is
`urn:open-android-intelligence:protocol:v1:zero-retention-evidence`.

```ts
export type ProviderObjectObservation = "none" | "returned" | "unknown";
export interface ExpectedEgressDeployment {
  readonly providerDestination: string;
  readonly accountProfileId: string;
  readonly contractConfigDigest: string;
  readonly evidenceRevision: bigint;
}
export interface ZeroRetentionVerifier {
  verify(keyId: string, preimage: Uint8Array, signature: string): boolean;
}
export type VerifiedZeroRetentionEvidence = Readonly<{
  readonly providerDestination: string;
  readonly accountProfileId: string;
  readonly evidenceRevision: bigint;
  readonly contractConfigDigest: string;
  readonly verifiedAt: string;
  readonly expiresAt: string;
  readonly [verifiedZeroRetentionEvidenceBrand]: true;
}>;
export function verifyZeroRetentionEvidence(
  evidence: unknown,
  expected: ExpectedEgressDeployment,
  verifier: ZeroRetentionVerifier,
): VerifiedZeroRetentionEvidence;
export function evaluateEgressProfile(input: Readonly<{
  evidence: VerifiedZeroRetentionEvidence | null;
  expected: ExpectedEgressDeployment;
  clock: Clock;
  providerObject: ProviderObjectObservation;
}>): { allowed: true } | { allowed: false; reason: "POLICY_BLOCKED" };
```

The closed evidence JSON requires:

```text
provider_destination, account_profile_id, evidence_revision, contract_config_digest,
verified_at, expires_at, key_id, signature_domain, retention_statement, signature
```

`signature_domain` is the literal `zero-retention/bridge`; all integer/time/
signature encodings reuse the global common formats.  `retention_statement` is
a closed object with exactly four closed objects `request`, `response`,
`attachment`, `tool_payload`; each has exactly `log`, `training`, `review`,
`cache`, `backup`, and every value is the literal `false`.  This is the exact
no-provider-object statement, not a prose string.

The evidence preimage is UTF-8:

```text
"zero-retention/bridge" || NUL || u32be(len(RFC8785_UTF8(evidence without signature)))
|| RFC8785_UTF8(evidence without signature)
```

where `evidence without signature` includes `key_id` and `signature_domain`.
`verifyZeroRetentionEvidence` schema-validates, compares all expected
destination/account/config-digest/revision values, checks the literal false
matrix, and verifies the P-256 signature before minting the brand.  The
evaluator allows body egress only if the branded evidence is present, expected
values still match, `verified_at <= now < expires_at`, and
`providerObject === "none"`.  Missing evidence, bad signature/domain, unknown
or drifted expected values, expiry, a returned provider object and unknown
provider observation all return `POLICY_BLOCKED`.  The provider remains a
plaintext processing boundary and never receives a deletion receipt target.

## Replacement RED-first execution steps

### Step 1 — write the transport/catalog RED tests

Before creating any Task 6 source, extend `messages-registry.test.ts` with the
five exact rows, payload/header/envelope closed fixtures and the complete
Task-5/6 replay classification expectation.  Extend `control-envelope.test.ts`
to assert each new signed message is accepted only in its registered direction,
has a 300-second maximum lifetime, and fails every tuple/header/payload swap.
Extend `schema-validator.test.ts` through its existing catalog assertions to
require all seven Task 6 schema roots, five payload leaves, five header leaves,
five envelope leaves, the filter-schema IDs selected by the locked
`filter_policy` decision, capability-registry and revision-events schema IDs.
Add a selected-policy fixture assertion that fails startup when the decision
record and registry policy disagree; do not silently widen the selected
notification package/field branch.

Add these decisive pre-replay tests using backend diagnostics already present
in the Task 5 repair model:

```ts
it("rejects a validly signed grant update with a wrong decoded filter_hash before replay", async () => {
  const result = await verifyTransportFrame(wireWith({ filter_hash: OTHER_32 }), deviceIngress, deps);
  expect(result).toEqual({ ok: false, error: "INTEGRITY_FAILED" });
  expect(backend.diagnostics()).toEqual({ replayLookups: 0n, replayMutations: 0n });
  expect(grants.snapshot()).toEqual(before);
});
```

Add a separately named test for the selected closed notification filter with
its matching digest, `grant_key.capability:
"sms.read"`, and a Task 5 adapter ceiling that omits `sms.read`; it must
return `{ ok:false, error:"NOT_AUTHORIZED" }` with both replay diagnostics
still zero.  Add one assertion per literal member of
`TASK6_DEFAULT_REPLAY_MESSAGE_TYPES` that its resolved classifier object is
exactly the two-string `task5_default` policy shown above.

Run:

```bash
tools/run-node24 npm test -- protocol/test/messages-registry.test.ts protocol/test/schema-validator.test.ts protocol/test/control-envelope.test.ts protocol/test/outbound-envelope.test.ts protocol/test/replay-window.test.ts
```

Expected: fail because Task 6 catalogs, schemas, types and gates do not exist.

### Step 2 — implement cumulative schema/catalog/transport integration

Create all schema documents and the capability/revision registry schemas; add
the documents/required IDs in `schema-catalog.ts`.  Implement the cumulative
type/lifetime/registry classifier, named closed leaves, outbound support and
pre-replay gate ordering exactly above.  Do not add a generic envelope leaf or
a fallback namespace.  Run the Step 1 command; expected pass.

### Step 3 — write capability and authorization RED tests

`capability-registry.test.ts` must prove: immutable singleton; every literal
row in the 28-row table; exact segment matching; unknown scope denial; all
forbidden names denial; duplicate/overlap/wrong-version/unresolvable schema
startup failures; filter-policy decision mismatch startup failure; the selected
closed filter branches and exact hash vectors; and the Task 5/6
scope-boundary matrix.  `authorization.test.ts`
must prove all §7.2 conjunct failures, cross-tenant and same-tenant
cross-human/device/agent/session rejection, admin cannot expand, model
`user_id` and network metadata do not change authority, and stored-query
independence from Android permission/backend/device epoch.  It must also prove
manifest-only/stale-manifest denial and fresh-state re-evaluation.

`authorization-revision.test.ts` must generate every table edge and reject all
others with no state change, cover paired old-binding permanent revocation,
every one-step epoch/scope change, forged/reused/wrong-device local-user
resume, invalid bridge-command rotation facts and adapter credential reissue.

Run:

```bash
tools/run-node24 npm test -- protocol/test/capability-registry.test.ts protocol/test/authorization.test.ts protocol/test/authorization-revision.test.ts protocol/test/capability-manifest.test.ts protocol/test/risk-policy.test.ts
```

Expected: fail because Task 6 evaluators/loaders do not exist.

### Step 4 — implement registry, authorization, revision, manifest and policy

Implement the locked loader, Task 5 ceiling validation boundary, current-state
ports, deny-first evaluators, executable revision table, manifest branding and
risk policy.  Use injected stores/clocks only.  Run Step 3 command; expected
pass.

### Step 5 — write grant/evidence RED tests

`data-query-grant.test.ts` must cover closed payload omission/null/type/unknown
fields; every selected filter-policy branch; exact JCS hash bytes; malformed
base64url; wrong decoded hash; capability filter mismatch;
cross-tenant/human/device key substitution;
non-human/admin expansion; forged signature; stale/late/out-of-order ACK;
pending UI honesty; revoke immediate query block; revoked generic-object
regrant rejection; same-human opaque expansion; duplicate update; and all
crash cuts (before transaction, after grant/outbox/replay atomic commit, before
send, restart retransmit exact bytes).  Every integrity failure asserts zero
grant and replay mutation.

`egress-policy.test.ts` must use fixed test-only Bridge evidence keys and prove
the exact preimage, each false matrix member, wrong domain/key/signature,
missing/unknown/expired/drifted evidence, mismatched config revision, returned
or unknown provider object, and a current matching `none` observation.  No
ambient clock is permitted.

Run:

```bash
tools/run-node24 npm test -- protocol/test/data-query-grant.test.ts protocol/test/egress-policy.test.ts
```

Expected: fail because verified grant/evidence types, transactional store and
egress evaluator do not exist.

### Step 6 — implement grant transaction/recovery and egress evidence

Implement the verified-event constructors, pre-replay validation, deterministic
transaction/outbox reference store, state reducer and recovery behavior.  Add
the profile domain and exact signed-evidence verifier/evaluator.  Run:

```bash
tools/run-node24 npm test -- protocol/test/authorization.test.ts protocol/test/authorization-revision.test.ts protocol/test/data-query-grant.test.ts protocol/test/capability-manifest.test.ts protocol/test/risk-policy.test.ts protocol/test/egress-policy.test.ts protocol/test/capability-registry.test.ts protocol/test/messages-registry.test.ts protocol/test/control-envelope.test.ts protocol/test/outbound-envelope.test.ts protocol/test/replay-window.test.ts && tools/run-node24 npm run typecheck
```

Expected: all named tests and typecheck pass.

### Step 7 — document and commit

`authorization.md` must cite the two independent §7.2 predicates, the exact
scope-resolver/filter registry rule, pre-replay integrity precedence, grant
transaction/recovery timeline, revision table authority, manifest
non-authority, immutable risk floors and signed zero-retention egress rule.

```bash
git add docs/protocol/v1/authorization.md protocol/profile/v1.json protocol/schemas/v1 protocol/registries/v1 protocol/model/v1 protocol/src protocol/test
git commit -m "feat(protocol): add authorization reference model"
```

## Blocker-closure mapping

| readiness blocker | amendment closure |
|---|---|
| 1. New signed messages unreachable | Cumulative union, literal 300-second lifetimes, named leaves/catalog IDs, outbound/replay changes, locked `task5_default` classifier and files/tests. |
| 2. Registry/filter ambiguity | Version-locked registry schema, deterministic non-overlap resolver, full 28-row policy table, and the recorded capability-specific filter policy with notification package/field closure. |
| 3. Filter mismatch after replay | Mandatory `PreReplayIntegrityGate`, fixed precedence and zero-mutation diagnostics/tests. |
| 4. ACK/durability authority gap | Opaque verified update/ACK facts, exact wire/preimage fields, serializable grant+outbox+replay atomic transaction and crash recovery. |
| 5. Device/local-user/current-state gap | Task 5 branded ceilings, injected current-state store, opaque local user fact, RotationActivationFact predicate and three-argument `effectiveCapabilities`. |
| 6. Egress evidence gap | Profile signature domain, closed false-matrix evidence schema, exact signed preimage/verifier, expected deployment binding and injected clock/provider observation. |

## Suggested parallel TDD batches after the Task 5 prerequisite is approved

1. **Batch A — schema/transport/replay:** catalog, message registry, concrete
leaves, 300-second lifetime union, outbound/replay classifier and pre-replay
hook.  This must land first because every signed Task 6 test needs it.
2. **Batch B — registry/current authorization/revision:** capability loader,
Task 5 ceiling boundary, current-state evaluation, revision table and manifest
non-authority.  It can run in parallel with Batch C after Batch A’s exported
transport types stabilize.
3. **Batch C — grant transaction:** verified update/ACK parsing, pre-replay
filter hash, reducer, transactional store/outbox and recovery.  It depends on
Batch A and Batch B’s registry/current-authority contracts.
4. **Batch D — risk/egress/docs:** risk-policy table, zero-retention evidence,
manifest integration and normative documentation.  It depends on Batch B’s
registry types but is otherwise independent of Batch C.
5. **Final integration:** cumulative registry/schema/typecheck suite, then the
Task 7/8/10/11 deferred integrations already called out by Task 6.

## Readiness gates and dependency checklist

The amendment may be merged into the main plan without waiting for a product
reply.  It may not be marked implementation-ready or GREEN until every
required gate below is checked.  The filter-policy item is now recorded; the
remaining entries are engineering contracts that this patch freezes.

### RED readiness (before Task 6 implementation)

- [ ] **Approved baseline:** the approved spec and plan are available at the
  same traceable baseline; any later spec change is recorded before source
  implementation.
- [ ] **Task 5 prerequisite:** Task 5 review accepts the `ScopeCeiling` brand,
  device `enrollmentScopeCeiling`, cumulative `P0aControlMessageType` hooks,
  and the pre-replay boundary.  Task 6 does not reconstruct Task 5 contexts or
  use structural casts.
- [ ] **Catalog/transport RED:** five Task 6 message rows, closed
  payload/header/envelope IDs, 300-second lifetimes, outbound ACK branches,
  and replay classifier tests are present and fail for the missing Task 6
  implementation.
- [ ] **Registry RED:** capability-registry tests cover all 28 rows,
  exact/non-overlapping family resolution, forbidden names, startup failures,
  and Task 5/6 scope-boundary behavior. Filter tests include the selected
  `capability_specific_v1` notification package/field fixture and reject the
  generic `closed_all_v1` fallback.
- [ ] **Authorization/revision RED:** deny-first §7.2 conjunctions,
  cross-tenant/principal/device/session cases, stale-manifest denial,
  local-user resume authority, rotation-fact checks, and every revision-table
  edge have named failing tests.
- [ ] **Grant/evidence RED:** wrong filter hash is asserted to return
  `INTEGRITY_FAILED` before replay/grant mutation; ACK forgery/order,
  durability crash cuts, lineage/TTL/tombstone, and every zero-retention
  signature/provider-object case have named failing tests.
- [ ] **Deterministic commands:** RED commands use the pinned Node launcher;
  tests inject clocks/stores/keys and do not use ambient time, randomness,
  network or sleep.

Run the RED suites independently so a missing contract is visible at the
correct boundary:

```bash
tools/run-node24 npm test -- protocol/test/messages-registry.test.ts protocol/test/schema-validator.test.ts protocol/test/control-envelope.test.ts protocol/test/outbound-envelope.test.ts protocol/test/replay-window.test.ts
tools/run-node24 npm test -- protocol/test/capability-registry.test.ts protocol/test/authorization.test.ts protocol/test/authorization-revision.test.ts protocol/test/capability-manifest.test.ts protocol/test/risk-policy.test.ts
tools/run-node24 npm test -- protocol/test/data-query-grant.test.ts protocol/test/egress-policy.test.ts
```

Expected RED result: each command fails only on the not-yet-created Task 6
catalog, evaluator, grant, or egress implementation; unrelated Task 5 tests
remain green.

### GREEN readiness (before claiming Task 6 complete)

- [x] **Filter policy recorded:** `capability_specific_v1` is selected. The
  notification schema is closed and supports explicit package/field lists;
  generic `filter_all_v1` is rejected for notification grants. The locked
  loader rejects a missing or mismatched decision and never chooses a default.
- [ ] **Schema/catalog closure:** every new `$id` is imported and required by
  `schema-catalog.ts`; all objects close with `unevaluatedProperties:false`;
  cumulative registry, header, payload and envelope assertions pass.
- [ ] **Transport/replay closure:** all five messages are admitted only in the
  registered direction with exact lifetime/domain/schema tuples; the
  pre-replay integrity gate returns `INTEGRITY_FAILED` with zero replay/grant
  mutations and preserves the fixed public error precedence.
- [ ] **Capability/authorization closure:** locked registry startup checks,
  exact ceiling membership, current-state lookup, independent stored-query
  predicate, admin/model/network non-authority and stale-manifest denial pass.
- [ ] **Revision/grant closure:** executable revision table and monotonic
  generations are enforced; verified grant updates/ACKs, immediate revoke,
  pending UI state, serializable outbox ordering, duplicate recovery and
  generic-object regrant rejection pass.
- [ ] **Policy/egress closure:** risk floors, B1 session requirement,
  irreversible L4 denial, `sms.send` confirmation binding and signed
  zero-retention evidence/expiry/drift/provider-object fail-closed behavior
  pass with injected clocks.
- [ ] **Cross-task handoff:** Task 7/8/10/11 integration points consume the
  exported Task 6 types without restating registries, lifetimes, revision
  comparison or filter hashing.
- [ ] **Verification:** targeted tests, cumulative registry tests and
  typecheck pass; `git diff --check` is clean; the commit contains docs and
  Task 6 implementation files only, with no production-source edits in this
  docs-only amendment commit.

Run the GREEN gate with:

```bash
tools/run-node24 npm test -- protocol/test/authorization.test.ts protocol/test/authorization-revision.test.ts protocol/test/data-query-grant.test.ts protocol/test/capability-manifest.test.ts protocol/test/risk-policy.test.ts protocol/test/egress-policy.test.ts protocol/test/capability-registry.test.ts protocol/test/messages-registry.test.ts protocol/test/control-envelope.test.ts protocol/test/outbound-envelope.test.ts protocol/test/replay-window.test.ts
tools/run-node24 npm run typecheck
git diff --check
```

Expected GREEN result: all named tests and typecheck pass, the locked registry
and filter-policy decision agree, and no Task 6 authorization path depends on
manifest/network/model-supplied authority.

### Dependencies and ownership boundaries

| dependency | required contract | owner/consumer |
|---|---|---|
| Approved spec + global plan | protocol version, canonical encodings, digest domains, injected clock/store rules, scope list and filter decision record | Product/plan owner; Task 6 consumes |
| Task 4 identity/revision primitives | tenant, human principal, device, pairing and revision value semantics | Task 4 produces; Task 6 consumes |
| Task 5 verified transport | opaque `AuthenticatedBindingContext`, `ScopeCeiling`, cumulative schema/catalog/type/lifetime hooks, pre-replay gate and replay claim | Task 5 produces; Task 6 extends |
| Locked capability registry | exact scope membership, risk/sensitivity/backend/offline/approval policy, selected filter schema mapping | Task 6 owns; Task 7/8/10 consume |
| Current authorization store | current lifecycle, ceilings, consent/permission/backend sets and revision tuple | Task 6 owns port; composition root supplies authority |
| Durable grant store/outbox | serializable grant row, replay claim association, prepared ACK bytes and restart recovery | Task 6 owns reference contract; production persistence is later |
| Rotation/local-user authorities | verified `RotationActivationFact`, constructor-private authenticated local-user transition | Task 5/identity layer produces; Task 6 validates |
| Egress evidence authority | trusted Bridge signer, expected destination/config/revision and injected clock/provider observation | Composition root supplies; Task 6 verifies/evaluates |
| Deferred integrations | concrete operations, approval/ticket/adapter/end-to-end message types | Task 7, Task 8, Task 10 and Task 11 consume after Task 6 GREEN |

No Task 6 implementation may start from a caller-provided registry, raw
manifest, user-supplied principal, network metadata, ambient clock, or
unbranded revision/ACK/filter object.  The only input that remains intentionally
open is the product's recorded filter-policy option above.
