# P0a Protocol and Security Model Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 冻结并实现 Agent Life v1.0 的语言中立设备协议与安全参考模型，使 Android、Bridge 和 Agent adapter 能用同一组 schema、状态机与 golden vectors 做一致性验证。

**Architecture:** JSON Schema Draft 2020-12 是 wire contract 的唯一结构化来源；RFC 8785 JCS、SHA-256 与 P-256/ES256 定义字节级签名语义。TypeScript 参考核心实现确定性授权、重放防护、状态机与 fake actors，独立 Kotlin/JVM runner 消费同一固定向量，避免只验证单语言 encode/decode 自洽。P0a 不实现生产数据库、Android Keystore、真实 WSS/HTTPS、Hermes/OpenClaw 接入或任何手机数据采集。

**Tech Stack:** Node.js 24.18.0 LTS、TypeScript 7.0.2、JSON Schema 2020-12、Ajv 8.20.0、Vitest 4.1.10、fast-check 4.9.0、Kotlin 2.4.10、JDK 21、Gradle 9.6.1、kotlinx.serialization 1.11.0、JUnit 5、RFC 8785 JCS、SHA-256、ES256。

## Global Constraints

- 已批准基线是 `docs/superpowers/specs/2026-08-08-agent-bridge-android-design.md`；实施中发现冲突时立即停止相关 task，先在独立文档提交中同步修订 spec 与 plan 并经用户确认，不能由代码或 P0a 子规格暗自选择另一语义。
- 执行前提是当前 spec 与本 plan 已位于同一个可追溯 commit 且工作树干净；该 commit 就是 `p0a-acceptance.md` 记录的 approved design/plan baseline。
- 目标 Android 基线保持 API 34；P0a Kotlin 模块不得依赖 Android framework，以便 JVM CI 与未来 Android 复用。
- v1 wire JSON 必须是无 BOM、无尾随换行的 RFC 8785 canonical UTF-8；对象重复键、非 canonical 字节、`-0`、NaN、Infinity 和 lone surrogate 全部拒绝。
- v1 所有计数器、generation、revision、sequence、offset、长度和毫秒 duration 在 JSON 中使用无前导零的十进制字符串；绝对时间固定为 UTC RFC 3339、恰好三位小数毫秒且以 `Z` 结尾。
- v1 的 `message_id`、`operation_id`、`approval_id`、`artifact_id`、`stream_id`、`source_epoch` 和通道 `ticket_id` 固定为小写 UUIDv4；enrollment ticket 与 Bridge 分配的 tenant/principal/device/session ID 仅按不透明服务端值校验。
- v1 签名固定为 P-256 `SHA256withECDSA`；wire signature 使用 64-byte IEEE-P1363 `r || s`、base64url 无填充、low-S 规范化，验证器拒绝 high-S。
- 签名前像固定为 UTF-8 域前缀、NUL、四字节大端 canonical payload 长度和 JCS 字节；所有 digest 均是明确指定对象的 JCS 字节的 SHA-256，不允许实现自选 preimage。
- control envelope canonical UTF-8 总大小上限固定为 262,144 bytes；正文附件、屏幕帧和连续传感器数据必须走独立 artifact/stream 通道。
- `parameters_digest`、`payload_digest`、`filter_hash`、chunk hash 和最终 artifact digest 均为 SHA-256 后的 base64url 无填充字符串。
- 每个 credential、`pairing_generation`、方向和 key 的 sequence 空间在 HTTPS/WSS 间共享；receiver 使用 1,024 位持久滑动窗口容忍跨通道乱序，同时拒绝已见或窗口外旧序列。
- key rotation 只有旧可信 key 签名、接收方 ACK 后才能激活；旧/新 key 最长并存 15 分钟。设备安装 key 或 Bridge command trust 丢失时只能重新配对并推进 `pairing_generation`；adapter 凭据由服务端运维独立重签，推进 adapter credential generation 并 fence 旧会话，不改写设备 epoch。
- wire 唯一携带显式 `scope_revisions` map，不保留“或 authorization snapshot hash”的第二种编码。
- 主动 `device_event` 使用设备/source 授权谓词，不携带或选择 Agent principal/session；Bridge 只按服务端 subscription 路由。
- 任意 Root Shell、generic exec、脚本、解释器、动态 Root action 和自动化规则 DSL 不得出现在 schema、registry、fake actor 或测试辅助接口中。
- 模型 API 只按已批准规格作为零保留瞬时推理边界；P0a 仅定义 profile evidence/fail-closed 状态，不实现 provider 删除目标。
- 测试私钥必须位于明确标记的 `test-only` fixture 目录；生产接口只接收抽象 `Signer`/`Verifier`，不得加载 fixture 私钥。
- 所有纯逻辑使用注入的 `Clock`、`NonceSource` 和 store 接口；测试不得依赖真实时间、随机数、网络或 sleep。
- 每个 schema object 默认 `unevaluatedProperties: false`；旧 schema 永不原地放宽。新增字段、消息或算法必须创建新 schema/version 并更新锁文件。

## Planned Repository Map

```text
package.json / package-lock.json       Node 工具链、精确依赖和统一检查命令
tsconfig.json / vitest.config.ts       严格 TypeScript 与确定性测试配置
protocol/
  profile/v1.json                       算法、域、大小、时钟、重放与轮换常量的机器权威来源
  schemas/v1/                          不可变 JSON Schema 2020-12 contract
  registries/v1/                       message、error、capability、audit-field registry
  model/v1/                            状态迁移表与错误优先级
  src/                                 TypeScript 参考算法与纯状态机
  test/                                单元、属性、负向和 trace 测试
  vectors/v1/                          固定 wire bytes、签名、决策与 trace 结果
  test-only/keys/                      明确不可用于生产的固定 P-256 测试密钥
  spec-lock.json                       profile/schema/registry/model/vector/ABI 的 SHA-256 锁
conformance/SUT.md                     各语言统一的 NDJSON conformance ABI
conformance/kotlin/                    独立 Kotlin/JVM vector consumer/producer
docs/protocol/v1/                      规范性 wire、授权、状态机、威胁与迁移文档
.github/workflows/protocol.yml         Node 与 Kotlin 的 P0a 总门禁
```

---

### Task 1: Bootstrap the deterministic protocol workspace

**Files:**
- Create: `.gitignore`
- Create: `.nvmrc`
- Create: `package.json`
- Create: `package-lock.json`
- Create: `tsconfig.json`
- Create: `vitest.config.ts`
- Create: `protocol/src/version.ts`
- Create: `protocol/test/version.test.ts`
- Create: `protocol/README.md`

**Interfaces:**
- Produces: `PROTOCOL_VERSION: "1.0"`, `SCHEMA_LINE: "v1"`, root `npm test` and `npm run typecheck` commands.
- Consumes: none.

- [ ] **Step 1: Add the pinned test harness and failing workspace smoke test**

Set `.nvmrc` to `24.18.0`. Create `package.json` with these exact direct versions and scripts:

```json
{
  "name": "agent-life-protocol",
  "private": true,
  "type": "module",
  "engines": { "node": ">=24.18.0 <25" },
  "scripts": {
    "test": "vitest run",
    "typecheck": "tsc --noEmit"
  },
  "dependencies": {
    "@noble/curves": "2.2.0",
    "ajv": "8.20.0",
    "ajv-formats": "3.0.1",
    "canonicalize": "3.0.0"
  },
  "devDependencies": {
    "@types/node": "24.13.3",
    "fast-check": "4.9.0",
    "tsx": "4.23.1",
    "typescript": "7.0.2",
    "vitest": "4.1.10"
  }
}
```

Configure `tsconfig.json` with `strict`, `noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`, `resolveJsonModule`, `module`/`moduleResolution` set to `NodeNext`, target `ES2024`, and include `protocol/**/*.ts`. Configure Vitest for Node, UTC, one deterministic worker, and a 5-second per-test timeout. `.gitignore` contains exactly the generated local surfaces `/node_modules/`, `/.gradle/`, `**/.gradle/`, `**/build/`, test reports, coverage and editor/OS files; it must not ignore protocol vectors, locks, wrappers or dependency-verification metadata.

```ts
// protocol/test/version.test.ts
import { describe, expect, it } from "vitest";
import { PROTOCOL_VERSION, SCHEMA_LINE } from "../src/version.js";

describe("protocol baseline", () => {
  it("freezes the approved P0a line", () => {
    expect(PROTOCOL_VERSION).toBe("1.0");
    expect(SCHEMA_LINE).toBe("v1");
  });
});
```

- [ ] **Step 2: Install the locked harness, then verify the missing module failure**

Run: `npm install && npm test -- protocol/test/version.test.ts`

Expected: `package-lock.json` is created, then Vitest runs and FAILS because `protocol/src/version.ts` does not exist.

- [ ] **Step 3: Add the minimal protocol version implementation**

```ts
// protocol/src/version.ts
export const PROTOCOL_VERSION = "1.0" as const;
export const SCHEMA_LINE = "v1" as const;
```

- [ ] **Step 4: Run the baseline checks**

Run: `npm test -- protocol/test/version.test.ts && npm run typecheck`

Expected: one passing test and TypeScript exit 0.

- [ ] **Step 5: Commit the workspace baseline**

```bash
git add .gitignore .nvmrc package.json package-lock.json tsconfig.json vitest.config.ts protocol
git commit -m "chore(protocol): bootstrap P0a workspace"
```

### Task 2: Make the threat model and security invariants executable

**Files:**
- Create: `docs/protocol/v1/threat-model.md`
- Create: `protocol/registries/v1/p0a-requirements.json`
- Create: `protocol/registries/v1/security-properties.json`
- Create: `protocol/registries/v1/audit-fields.json`
- Create: `protocol/test/security-properties.test.ts`

**Interfaces:**
- Produces: stable P0a requirement IDs `P0A-001` through `P0A-022`, security property IDs `SEC-001` through `SEC-016`, and `allowed_audit_fields`/`forbidden_audit_fields` registries.
- Consumes: `PROTOCOL_VERSION` from Task 1.

- [ ] **Step 1: Write the failing invariant-registry test**

```ts
import { describe, expect, it } from "vitest";
import requirements from "../registries/v1/p0a-requirements.json" with { type: "json" };
import properties from "../registries/v1/security-properties.json" with { type: "json" };
import audit from "../registries/v1/audit-fields.json" with { type: "json" };

describe("P0a security registry", () => {
  it("maps every in-scope requirement to artifacts and evidence", () => {
    expect(requirements.map((r) => r.id)).toEqual(
      Array.from({ length: 22 }, (_, i) => `P0A-${String(i + 1).padStart(3, "0")}`),
    );
    expect(requirements.every((r) => r.phase === "P0a" && r.artifacts.length > 0 && r.evidence.length > 0)).toBe(true);
  });

  it("has sixteen unique, test-linked invariants", () => {
    expect(properties.map((p) => p.id)).toEqual(
      Array.from({ length: 16 }, (_, i) => `SEC-${String(i + 1).padStart(3, "0")}`),
    );
    expect(new Set(properties.flatMap((p) => p.tests)).size).toBeGreaterThanOrEqual(16);
  });

  it("never permits sensitive body fields in audit metadata", () => {
    expect(audit.forbidden_audit_fields).toEqual(
      expect.arrayContaining(["body", "token", "otp", "command_output", "screen_frame"]),
    );
  });
});
```

- [ ] **Step 2: Run the test and verify missing registries fail**

Run: `npm test -- protocol/test/security-properties.test.ts`

Expected: FAIL because both registry files are absent.

- [ ] **Step 3: Write the threat model and exact invariant matrix**

`threat-model.md` must enumerate assets, actors, trust boundaries, entry points, assumptions, abuse cases, controls, residual risks and out-of-scope compromises. Enter this exact registry mapping; every `tests` item is a stable test/vector identifier used by later tasks:

| ID | Required property | Primary evidence ID |
|---|---|---|
| SEC-001 | IP、MagicDNS、Tailscale identity/header 不产生授权 | `auth.network-metadata-denied` |
| SEC-002 | 服务端认证上下文是 principal/device binding 权威 | `auth.principal-injection-denied` |
| SEC-003 | 实时设备授权与 stored-query grant 是独立判定域 | `auth.stored-query-independent` |
| SEC-004 | pairing/epoch/scope revision 精确匹配，撤销重授不复活旧对象 | `auth.stale-revision-denied` |
| SEC-005 | execution claim 未持久化前不调用副作用 | `operation.ledger-full-fails-closed` |
| SEC-006 | 同一 operation 不因 transport/adapter 重试自动重复副作用 | `operation.ack-loss-executes-once` |
| SEC-007 | approval 单次使用并绑定完整参数、身份、设备和 revision | `approval.parameter-mutation-denied` |
| SEC-008 | tenant/user/device/session/artifact/stream 不能交叉复用 | `isolation.cross-tenant-denied` |
| SEC-009 | 新 connection generation fence 旧连接 | `connection.old-generation-fenced` |
| SEC-010 | audit 只含 allowlist 元数据且不记录正文 | `audit.forbidden-content-rejected` |
| SEC-011 | 零保留 profile 缺失、未知或漂移时正文外发 fail closed | `egress.zero-retention-unknown-denied` |
| SEC-012 | schema/registry/fake 中不存在任意 Root Shell 或 generic exec | `schema.root-shell-absent` |
| SEC-013 | adapter 无可信 principal 传播时启动失败 | `auth.adapter-principal-missing` |
| SEC-014 | capability manifest 只描述状态，不能充当授权 token | `auth.manifest-is-not-token` |
| SEC-015 | 每个正式 `sms.send` 均绑定完整参数并在手机逐条批准 | `policy.sms-send-always-confirms` |
| SEC-016 | L4 操作在所有类型化/restricted backend 上无条件拒绝 | `policy.l4-always-denied` |

`p0a-requirements.json` uses entries `{ id, spec_refs, phase, requirement, artifacts, evidence }` and freezes this exact scope map; event cursor persistence/gap recovery is intentionally assigned to P0b/P1a and therefore absent:

| ID | P0a normative requirement |
|---|---|
| P0A-001 | canonical profile, bounds, digest and ES256 bytes |
| P0A-002 | ticket-owned enrollment, mutual transcript and human short code |
| P0A-003 | closed version registry, highest-common selection and downgrade refusal |
| P0A-004 | message-specific signed unions and server-authoritative identity binding |
| P0A-005 | shared replay space, connection fence and authenticated key rotation |
| P0A-006 | real-time device authorization conjunction |
| P0A-007 | independent stored-query grant, signed ACK, lineage, TTL and tombstone |
| P0A-008 | pairing/authorization/scope revision transition and stale-object invalidation |
| P0A-009 | tenant plus same-tenant principal/device/session isolation |
| P0A-010 | descriptive-only capability manifest and adapter principal fail-closed |
| P0A-011 | risk/sensitivity/backend policy, SMS per-item approval and L4 denial |
| P0A-012 | zero-retention evidence and body-egress fail-closed decision |
| P0A-013 | separate request status, terminal outcome, operation reason and protocol error |
| P0A-014 | offline, expiry, cancel-race and immutable reconciliation semantics |
| P0A-015 | durable execution claim, idempotency, crash cuts and receipt replay |
| P0A-016 | approval binding, atomic consumption and anti-harassment limits |
| P0A-017 | device-event/event-ACK wire identity contract only |
| P0A-018 | artifact ticket, PoP, integrity, resume and lifecycle contract |
| P0A-019 | non-resumable live-stream ticket, credit and close contract |
| P0A-020 | closed error precedence and content-free audit contract |
| P0A-021 | no arbitrary Root Shell, generic exec, scripts or automation DSL in accepted surfaces |
| P0A-022 | immutable migration rules, deterministic fakes and independent cross-language evidence |

`audit-fields.json` 的 allowlist 固定为 `server_time`、`device_time`、`sequence`、`key_id`、`operation_id`、`message_id`、`tenant_id`、`human_principal_id`、`agent_principal_id`、`agent_instance_id`、`workspace_id`、`session_id`、`job_id`、`device_id`、`capability`、`risk_level`、`pairing_generation`、`authorization_epoch`、`scope_revisions`、`grant_revision`、`approval_method`、枚举 outcome、`duration_ms`、`byte_count`、`correlation_id`；自由文本结果摘要不在 allowlist。

- [ ] **Step 4: Run the registry tests**

Run: `npm test -- protocol/test/security-properties.test.ts`

Expected: three passing tests.

- [ ] **Step 5: Commit the threat baseline**

```bash
git add docs/protocol/v1/threat-model.md protocol/registries/v1 protocol/test/security-properties.test.ts
git commit -m "docs(protocol): define P0a threat model"
```

### Task 3: Freeze canonical wire bytes, hashes and signatures

**Files:**
- Create: `docs/protocol/v1/wire-format.md`
- Create: `protocol/profile/v1.json`
- Create: `protocol/schemas/v1/profile.schema.json`
- Create: `protocol/src/encoding.ts`
- Create: `protocol/src/crypto.ts`
- Create: `protocol/src/profile.ts`
- Create: `protocol/src/ports.ts`
- Create: `protocol/test/encoding.test.ts`
- Create: `protocol/test/crypto.test.ts`
- Create: `protocol/test-only/keys/device-a-private.jwk.json`
- Create: `protocol/test-only/keys/device-a-public.jwk.json`
- Create: `protocol/test-only/keys/device-a-next-private.jwk.json`
- Create: `protocol/test-only/keys/device-a-next-public.jwk.json`
- Create: `protocol/test-only/keys/bridge-command-private.jwk.json`
- Create: `protocol/test-only/keys/bridge-command-public.jwk.json`
- Create: `protocol/test-only/keys/bridge-command-next-private.jwk.json`
- Create: `protocol/test-only/keys/bridge-command-next-public.jwk.json`
- Create: `protocol/test-only/keys/adapter-a-private.jwk.json`
- Create: `protocol/test-only/keys/adapter-a-public.jwk.json`
- Create: `protocol/test-only/keys/adapter-a-next-private.jwk.json`
- Create: `protocol/test-only/keys/adapter-a-next-public.jwk.json`

**Interfaces:**
- Produces: `loadProtocolProfile()`, `parseCanonicalJson(raw)`, `canonicalBytes(value)`, `sha256B64Url(bytes)`, `signingPreimage(domain,value)`, `signTestOnly(privateJwk,preimage)`, `verifyEs256(publicJwk,preimage,signature)`, `isLowS(signature)`, and abstract `Signer`/`Verifier`/`Clock`/`NonceSource` ports.
- Consumes: P-256 key material only from `protocol/test-only/keys` in tests/vector generation.

- [ ] **Step 1: Write failing canonicalization and signature tests**

```ts
it("rejects valid but non-canonical JSON bytes", () => {
  expect(() => parseCanonicalJson(new TextEncoder().encode('{ "b":2,"a":1}')))
    .toThrowError("NON_CANONICAL_JSON");
});

it("domain-separates and verifies a low-S ES256 signature", () => {
  const value = { message_id: "018f4f9a-4444-4444-8444-444444444444", sequence: "1" };
  const preimage = signingPreimage("control/app-to-bridge", value);
  const signature = signTestOnly(devicePrivateJwk, preimage);
  expect(verifyEs256(devicePublicJwk, preimage, signature)).toBe(true);
  expect(verifyEs256(devicePublicJwk, signingPreimage("approval/device", value), signature)).toBe(false);
  expect(isLowS(signature)).toBe(true);
});

it("derives every runtime constant from the machine-readable profile", () => {
  expect(loadProtocolProfile()).toMatchObject({
    profile_id: "agent-life-json-es256/1.0",
    max_envelope_bytes: "262144",
    replay_window_size: "1024",
    key_rotation_grace_seconds: "900",
    wait_read_max_seconds: "900",
  });
});
```

Add cases for duplicate keys, `-0`, lone surrogate, payload mutation, high-S, invalid base64url, wrong curve, current/next key confusion, cross-role device/Bridge/adapter key substitution and 262,145-byte wire input.

- [ ] **Step 2: Run the tests and verify missing APIs fail**

Run: `npm test -- protocol/test/encoding.test.ts protocol/test/crypto.test.ts`

Expected: FAIL with missing `encoding.js`/`crypto.js` exports.

- [ ] **Step 3: Implement the frozen profile**

```ts
const profile = loadProtocolProfile();

export const CRYPTO_PROFILE = {
  profileId: profile.profile_id,
  canonicalization: profile.canonicalization,
  digest: profile.digest,
  signature: profile.signature,
  curve: profile.curve,
  signatureEncoding: profile.signature_encoding,
  maxEnvelopeBytes: Number(profile.max_envelope_bytes),
} as const;

export type SignatureDomain =
  | "enrollment/app-to-bridge"
  | "enrollment/bridge-to-app"
  | "control/app-to-bridge"
  | "control/bridge-to-app"
  | "adapter/adapter-to-bridge"
  | "key-rotation/app-to-bridge"
  | "key-rotation/bridge-to-app"
  | "key-rotation/adapter-to-bridge"
  | "approval/device"
  | "ticket/bridge"
  | "receipt/device"
  | "channel-pop/device"
  | "channel-pop/adapter";

export function signingPreimage(domain: SignatureDomain, value: unknown): Uint8Array {
  const prefix = new TextEncoder().encode(`agent-life/v1/${domain}\0`);
  const canonical = canonicalBytes(value);
  const length = new Uint8Array(4);
  new DataView(length.buffer).setUint32(0, canonical.byteLength, false);
  return Uint8Array.from([...prefix, ...length, ...canonical]);
}
```

`protocol/profile/v1.json` is the machine authority for profile ID, JCS version, signature preimage layout, domains, digest/signature profile, base64url policy, timestamp/ID formats, 262,144-byte envelope limit, 60-second clock skew, 1,024-slot replay window, 15-minute key grace, 15-minute `WAIT_READ` ceiling, five-minute channel ticket lifetime, artifact chunk/file/message limits and orphan lifetime. `profile.ts` validates it against `profile.schema.json` at startup; TypeScript constants are typed views over that object, not a second hand-maintained source.

`parseCanonicalJson` first enforces byte size and strict UTF-8, parses JSON, canonicalizes the parsed value, then compares the complete raw byte array with the canonical result. This comparison is what rejects whitespace, duplicate-key collapse and `-0`. `verifyEs256` decodes exactly 64 raw bytes, checks `1 <= r,s < n` and `s <= n/2`, converts to verifier format, then verifies `SHA256withECDSA`; test signing uses deterministic RFC 6979 from `@noble/curves`, never production key loading.

`ports.ts` defines production-safe interfaces only: `Clock` exposes injected wall and monotonic time; `NonceSource` returns a requested number of unpredictable bytes; `Signer` exposes key ID/role and signs an already constructed preimage; `Verifier` resolves an authenticated role/key ID and verifies without accepting private material. Fakes implement these ports in Task 11; no production port loads `test-only` keys.

Document the exact domain bytes, four-byte big-endian length, JWK fields (`kty=EC`, `crv=P-256`, 32-byte `x/y`, `alg=ES256`, `use=sig`, `kid`), base64url rules and full-wire canonical requirement in `wire-format.md`. For envelopes, the signable value is exactly canonical `{header,payload}` with the `signature` member excluded; `payload_digest` is `base64url(SHA-256(JCS(payload)))`, and the selected message-class/direction domain plus length-prefix wraps that complete signable value.

- [ ] **Step 4: Run focused and property tests**

Run: `npm test -- protocol/test/encoding.test.ts protocol/test/crypto.test.ts && npm run typecheck`

Expected: all canonicalization, mutation and crypto cases pass; typecheck exits 0.

- [ ] **Step 5: Commit the cryptographic profile**

```bash
git add docs/protocol/v1/wire-format.md protocol/profile protocol/schemas/v1/profile.schema.json protocol/src protocol/test protocol/test-only/keys
git commit -m "feat(protocol): freeze canonical wire signatures"
```

### Task 4: Define common types, enrollment and version negotiation

**Files:**
- Create: `docs/protocol/v1/enrollment.md`
- Create: `docs/protocol/v1/versioning-and-migration.md`
- Create: `protocol/schemas/v1/common.schema.json`
- Create: `protocol/schemas/v1/enrollment.schema.json`
- Create: `protocol/schemas/v1/connect.schema.json`
- Create: `protocol/registries/v1/messages.json`
- Create: `protocol/registries/v1/versions.json`
- Create: `protocol/test-only/migration/v0.9/profile.json`
- Create: `protocol/test-only/migration/v0.9/pending-operation.schema.json`
- Create: `protocol/test-only/migration/v0.9/pending-operation.json`
- Create: `protocol/test-only/migration/v0.9/pending-operation-signature.json`
- Create: `protocol/src/schema-validator.ts`
- Create: `protocol/src/enrollment.ts`
- Create: `protocol/src/pairing-transcript.ts`
- Create: `protocol/src/version-negotiation.ts`
- Create: `protocol/src/migration.ts`
- Create: `protocol/test/enrollment.test.ts`
- Create: `protocol/test/version-negotiation.test.ts`
- Create: `protocol/test/migration.test.ts`

**Interfaces:**
- Produces: `validateSchema(schemaId,value)`, `consumeEnrollmentAttempt(input,store,limiter)`, `pairingShortCode(transcript)`, `selectHighestCommonVersion(hello,versionRegistry)`, `verifyWelcome(welcome,hello,versionRegistry)`, `migrateSignedRecord(input,targetVersion)`, `EnrollmentTicketStore`, `EnrollmentAttemptLimiter`.
- Consumes: canonical/hash/signature APIs from Task 3.

- [ ] **Step 1: Write failing enrollment and downgrade tests**

```ts
it("derives ownership only from the consumed server ticket", () => {
  const result = consumeEnrollmentAttempt(
    signedAttempt({ ticket: VALID_TICKET, claimed_tenant_id: "tenant-b" }),
    ticketStoreBoundToTenantA,
    limiter,
  );
  expect(result).toEqual({ ok: false, error: "SCHEMA_INVALID" });
  expect(ticketStoreBoundToTenantA.isConsumed(VALID_TICKET)).toBe(true);
});

it("selects the highest common version and binds the offer digest", () => {
  expect(selectHighestCommonVersion(["0.9", "1.0"], ["1.0"]))
    .toMatchObject({ selected: "1.0" });
  expect(() => verifyWelcome(downgradedWelcomeSelecting09, signedHelloOffering10))
    .toThrowError("VERSION_UNSUPPORTED");
});

it("derives the same human short code only from the complete mutual transcript", () => {
  expect(pairingShortCode(appTranscript)).toBe(pairingShortCode(bridgeTranscript));
  expect(pairingShortCode({ ...appTranscript, bridge_nonce: OTHER_NONCE }))
    .not.toBe(pairingShortCode(bridgeTranscript));
});

it("migrates a locked legacy pending record without rewriting signed facts", () => {
  const migrated = migrateSignedRecord(legacy09PendingOperation, "1.0");
  expect(migrated).toMatchObject({
    operation_id: legacy09PendingOperation.operation_id,
    original_schema: legacy09PendingOperation.message_schema,
    original_digest: legacy09PendingOperation.payload_digest,
    original_signature: legacy09PendingOperation.signature,
  });
  expect(() => migrateSignedRecord(mutatedCriticalFieldFixture, "1.0"))
    .toThrowError("INTEGRITY_FAILED");
});
```

Also test five attempts per principal/IP per rolling ten minutes, success/failure consumption, five-minute expiry, ticket replay, challenge mismatch, bridge fingerprint mismatch and enrollment/control union confusion.

- [ ] **Step 2: Run tests and verify missing schemas/APIs fail**

Run: `npm test -- protocol/test/enrollment.test.ts protocol/test/version-negotiation.test.ts protocol/test/migration.test.ts`

Expected: FAIL because enrollment and negotiation implementations are absent.

- [ ] **Step 3: Implement closed common and enrollment contracts**

`common.schema.json` defines lowercase UUIDv4, opaque server ID, base64url, SHA-256 digest, RFC3339 millisecond timestamp, decimal-u64 and explicit revision snapshot:

```json
{
  "$defs": {
    "decimal_u64": {
      "type": "string",
      "pattern": "^(0|[1-9][0-9]{0,19})$",
      "format": "decimal-u64"
    },
    "sha256_b64u": { "type": "string", "pattern": "^[A-Za-z0-9_-]{43}$" },
    "revision_snapshot": {
      "type": "object",
      "required": ["pairing_generation", "authorization_epoch", "scope_revisions"],
      "properties": {
        "pairing_generation": { "$ref": "#/$defs/decimal_u64" },
        "authorization_epoch": { "$ref": "#/$defs/decimal_u64" },
        "scope_revisions": {
          "type": "object",
          "propertyNames": { "pattern": "^[a-z][a-z0-9._-]{0,127}$" },
          "additionalProperties": { "$ref": "#/$defs/decimal_u64" }
        }
      },
      "unevaluatedProperties": false
    }
  }
}
```

Both Ajv and Kotlin register `decimal-u64` as a mandatory custom format that parses arbitrary precision and accepts only `0..18446744073709551615`; schema startup fails if the format is not registered. Fixed cross-language cases accept `0` and `18446744073709551615`, reject `18446744073709551616`, negative values and leading zeroes, and prove replay/generation arithmetic rejects exhaustion rather than wrapping to zero.

Enrollment is a separate discriminated union: `enrollment_challenge`, `enrollment_response`, `enrollment_complete`, `enrollment_error`. The response may carry only ticket, challenge response, device public JWK, client nonce and supported versions; tenant/human/agent IDs are forbidden. Intake first performs bounded strict JSON parsing sufficient only to extract an opaque ticket, atomically consumes a known unexpired ticket, and then performs full closed-schema/challenge/signature validation; therefore any success or post-ticket failure, including a forbidden identity field, consumes that ticket without trusting any other client field.

Bridge assigns both `device_id` and `pairing_generation`; the App cannot claim either. Re-pairing the same still-installed identity creates a new non-reusable `device_id` and increments the Bridge-held generation before revoking the old binding. Reinstalling creates a new installation identity and new device record; the old record remains revoked and no client-provided name/key can reclaim it.

After both enrollment signatures verify, each side independently canonicalizes `{ ticket_digest, bridge_fingerprint, challenge, client_nonce, bridge_nonce, device_jwk_thumbprint, selected_protocol }`, where `ticket_digest` is SHA-256 of the opaque ticket bytes and `device_jwk_thumbprint` is the RFC 7638 SHA-256 thumbprint. It hashes the `agent-life/v1/pairing-short-code` length-prefixed domain preimage with SHA-256, takes the first 50 bits and renders ten uppercase Crockford Base32 characters as `XXXXX-XXXXX`. No ambiguous `I/L/O/U` characters are used. Enrollment completes only after the user confirms the two displayed codes match; every transcript field mutation has a fixed negative vector.

`connect_hello` and `connect_welcome` are the only canonical names. Protocol versions use canonical `major.minor` decimal components with no leading zeroes and numeric comparison; the server list comes only from the locked production version registry, and welcome must select the numerically highest exact version present in both signed lists. Conformance-only migration fixtures are never negotiable. Welcome repeats both nonces, `client_offer_digest`, selected version, Bridge time, command key set and newly allocated `connection_generation`.

`versions.json` lists exactly production `1.0`; the signed `v0.9` directory is a `negotiable=false` conformance-only legacy line used solely to prove migration behavior before a second production version exists. `versioning-and-migration.md` freezes: old schema files/signatures are immutable; patch changes cannot alter accepted bytes/state semantics; additive negotiated messages require a new minor line; canonicalization, signature, identity, revision, state or replay changes require a major line; migrations create a new record plus signed migration receipt while retaining original schema/digest/signature; and rollback never lowers pairing generation, authorization/scope/grant revisions, revocation or tombstone state. Version selection rejects any line absent from the production registry, including the legacy fixture.

- [ ] **Step 4: Run enrollment/schema tests**

Run: `npm test -- protocol/test/enrollment.test.ts protocol/test/version-negotiation.test.ts protocol/test/migration.test.ts && npm run typecheck`

Expected: all ownership, short-code, rate-limit, expiry, union, downgrade and immutable-migration cases pass.

- [ ] **Step 5: Commit enrollment and negotiation**

```bash
git add docs/protocol/v1/enrollment.md docs/protocol/v1/versioning-and-migration.md protocol/schemas/v1 protocol/registries/v1 protocol/test-only/migration protocol/src protocol/test
git commit -m "feat(protocol): define enrollment and negotiation"
```

### Task 5: Implement control envelopes, replay windows, fencing and key rotation

**Files:**
- Create: `docs/protocol/v1/control-envelope.md`
- Create: `protocol/schemas/v1/control-envelope.schema.json`
- Create: `protocol/schemas/v1/key-rotation.schema.json`
- Create: `protocol/src/control-envelope.ts`
- Create: `protocol/src/replay-window.ts`
- Create: `protocol/src/connection-fence.ts`
- Create: `protocol/src/key-ring.ts`
- Create: `protocol/test/control-envelope.test.ts`
- Create: `protocol/test/replay-window.test.ts`
- Create: `protocol/test/key-ring.test.ts`

**Interfaces:**
- Produces: `encodeSignedEnvelope`, `verifySignedEnvelope`, `verifyTransportFrame`, `ReplayStore`, `acceptSequence`, `fenceConnection`, `applyKeyRotation`, `wrapReceiptReplay`.
- Consumes: Task 3 crypto and Task 4 schema/version primitives.

- [ ] **Step 1: Write failing replay/fencing/rotation tests**

```ts
it("shares a 1024-slot replay window across HTTPS and WSS", () => {
  expect(acceptSequence(state(), message("1024", "https"))).toMatchObject({ kind: "accept" });
  expect(acceptSequence(stateAfter1024, message("1023", "wss"))).toMatchObject({ kind: "accept" });
  expect(acceptSequence(stateAfterBoth, message("1023", "https"))).toMatchObject({ kind: "reject", error: "REPLAY_REJECTED" });
});

it("fences old connections but permits an embedded old receipt on the new connection", () => {
  const fenced = fenceConnection(connectionState(7n), 8n);
  expect(verifyTransportFrame(commandAtGeneration(7n), fenced)).toEqual({ ok: false, error: "CONNECTION_FENCED" });
  expect(verifyTransportFrame(receiptReplayOn8ContainingReceiptFrom7, fenced)).toMatchObject({ ok: true });
});
```

Add tests for exact duplicate returning cached signed receipt, same `message_id` with different digest, too-old sequence, forged future connection generation, direction/key/pairing-generation isolation, forged identity field, payload digest mutation, unknown field, key ACK before activation, 15-minute retirement, revoked key and lost-key re-pair requirement.

- [ ] **Step 2: Run tests and verify missing implementations fail**

Run: `npm test -- protocol/test/control-envelope.test.ts protocol/test/replay-window.test.ts protocol/test/key-ring.test.ts`

Expected: FAIL on missing control/replay/key-ring modules.

- [ ] **Step 3: Implement the control union and durable-state interfaces**

```ts
export type ReplayDecision =
  | { kind: "accept"; next: ReplayWindowState }
  | { kind: "duplicate"; signedReceipt: Uint8Array }
  | { kind: "reject"; error: "REPLAY_REJECTED" | "INTEGRITY_FAILED" };

export interface ReplayWindowState {
  highestSeen: bigint;
  seenBitmap: bigint;
  messageReceipts: ReadonlyMap<string, { digest: string; receipt: Uint8Array }>;
}

export interface ReplayStore {
  compareAndSwap(
    space: { credentialId: string; pairingGeneration: bigint | null; keyId: string; direction: string },
    expected: ReplayWindowState,
    next: ReplayWindowState,
  ): Promise<boolean>;
}
```

The signed control envelope is `{ header, payload, signature }`. Header contains the approved common fields; payload schema is selected strictly by `message_type`. Operation messages carry all principal bindings and explicit revision map; event messages never contain Agent principal/session fields; ping/presence/rotation contain only their registered fields.

Use a 1,024-bit bitmap relative to `highestSeen`. Only after current connection generation, authenticated binding and current authorization/revision all pass may an exact `message_id + digest` duplicate resolve to the byte-identical cached receipt; the receiver returns it inside a newly signed current-generation `receipt_replay`, never as a response on a fenced or stale-revision envelope. A changed digest is `INTEGRITY_FAILED`. Signed receipts remain dedupe-addressable until the later of operation expiry or 30 days after Bridge ACK; after compaction, an otherwise valid old replay returns `REPLAY_REJECTED` and never re-executes. A new connection generation is an atomic monotonic fence: both lower and unallocated future generations are rejected.

Rotation messages select the corresponding `key-rotation/*` signature domain instead of a generic control domain and are signed once by the active old key; they become active only after receiver ACK, keep independent sequence windows per key, and set `retire_at <= activated_at + 15 minutes`. Device installation-key and Bridge command-key activation each advances the affected device `authorization_epoch`, invalidating old approvals/tickets; adapter credential rotation advances only the adapter credential generation and fences old adapter sessions without rewriting device epochs. No unsigned recovery path exists.

- [ ] **Step 4: Run focused and property tests**

Run: `npm test -- protocol/test/control-envelope.test.ts protocol/test/replay-window.test.ts protocol/test/key-ring.test.ts && npm run typecheck`

Expected: all mutation, concurrency, fencing and rotation tests pass.

- [ ] **Step 5: Commit signed control handling**

```bash
git add docs/protocol/v1/control-envelope.md protocol/schemas/v1 protocol/src protocol/test
git commit -m "feat(protocol): add signed control fencing"
```

### Task 6: Implement the two authorization domains and capability manifest

**Files:**
- Create: `docs/protocol/v1/authorization.md`
- Create: `protocol/schemas/v1/authorization.schema.json`
- Create: `protocol/schemas/v1/authorization-revision.schema.json`
- Create: `protocol/schemas/v1/data-query-grant.schema.json`
- Create: `protocol/schemas/v1/capability-manifest.schema.json`
- Create: `protocol/schemas/v1/zero-retention-profile.schema.json`
- Modify: `protocol/registries/v1/messages.json`
- Create: `protocol/registries/v1/capabilities.json`
- Create: `protocol/model/v1/revision-events.json`
- Create: `protocol/src/authorization.ts`
- Create: `protocol/src/authorization-revision.ts`
- Create: `protocol/src/data-query-grant.ts`
- Create: `protocol/src/capability-manifest.ts`
- Create: `protocol/src/risk-policy.ts`
- Create: `protocol/src/egress-policy.ts`
- Create: `protocol/test/authorization.test.ts`
- Create: `protocol/test/authorization-revision.test.ts`
- Create: `protocol/test/data-query-grant.test.ts`
- Create: `protocol/test/capability-manifest.test.ts`
- Create: `protocol/test/risk-policy.test.ts`
- Create: `protocol/test/egress-policy.test.ts`

**Interfaces:**
- Produces: `evaluateDeviceAccess(input)`, `evaluateStoredQuery(input)`, `applyRevisionEvent(state,event)`, `reduceDataQueryGrant(state,event)`, `effectiveCapabilities(manifest,binding)`, `evaluateRiskPolicy(input)`, `evaluateEgressProfile(evidence)`, `DataQueryGrantKey`.
- Consumes: identity/revision primitives and verified authenticated connection context.

- [ ] **Step 1: Write failing authorization-table tests**

```ts
it.each([
  ["inactive pairing", deviceInput({ activePairing: false }), "PAIRING_INACTIVE"],
  ["principal mismatch", deviceInput({ bindingMatches: false }), "BINDING_MISMATCH"],
  ["stale revision", deviceInput({ revisionMatches: false }), "REVISION_MISMATCH"],
  ["backend unavailable", deviceInput({ backendAvailable: false }), "BACKEND_UNAVAILABLE"],
])("denies %s deterministically", (_name, input, reason) => {
  expect(evaluateDeviceAccess(input)).toEqual({ allowed: false, reason });
});

it("stored queries ignore current Android permission and device epoch", () => {
  const input = storedQueryInput({ systemPermission: false, authorizationEpoch: "999" });
  expect(evaluateStoredQuery(input)).toEqual({ allowed: true });
});

it("changes the displayed grant state only after the matching signed Bridge ACK", () => {
  const pending = reduceDataQueryGrant(effectiveGrant, localRevokeRevision8);
  expect(pending.deviceUiState).toBe("revoked_pending_ack");
  expect(pending.bridgeEffectiveState).toBe("effective");
  expect(() => reduceDataQueryGrant(pending, forgedAckRevision8)).toThrowError("AUTH_FAILED");
  expect(() => reduceDataQueryGrant(pending, validBridgeAckRevision7)).toThrowError("INVALID_STATE_TRANSITION");
  expect(reduceDataQueryGrant(pending, validBridgeAckRevision8)).toMatchObject({
    deviceUiState: "revoked",
    bridgeEffectiveState: "revoked",
    grantRevision: 8n,
  });
});

it.each(["pause", "resume", "emergency_stop", "scope_policy_change", "system_permission_change", "backend_credential_change", "bridge_command_key_activated"])(
  "advances authorization epoch for %s",
  (event) => expect(applyRevisionEvent(activeRevision7, revisionEvent(event)).authorizationEpoch).toBe(8n),
);

it("never lowers SMS confirmation or L4 denial", () => {
  expect(evaluateRiskPolicy(policyInput("sms.send", { userOverride: "continuous" })))
    .toMatchObject({ allowed: true, approval: "per_operation_on_device" });
  expect(evaluateRiskPolicy(policyInput("ui.control.typed", { deterministicRisk: "L4", backend: "typed_root" })))
    .toEqual({ allowed: false, reason: "POLICY_BLOCKED" });
});

it.each([undefined, unknownEvidence, driftedEvidence])(
  "fails closed when zero-retention evidence is absent, unknown or drifted",
  (evidence) => expect(evaluateEgressProfile(evidence)).toEqual({ allowed: false, reason: "POLICY_BLOCKED" }),
);
```

Add cross-tenant, same-tenant cross-principal/device/session, admin expansion, forged/late/out-of-order grant ACK, filter hash, lineage, TTL/tombstone, model-supplied `user_id`, network metadata, stale manifest, paused/revoked state and revoke-then-regrant replay of old operation/approval/ticket cases.

- [ ] **Step 2: Run tests and verify missing evaluators fail**

Run: `npm test -- protocol/test/authorization.test.ts protocol/test/authorization-revision.test.ts protocol/test/data-query-grant.test.ts protocol/test/capability-manifest.test.ts protocol/test/risk-policy.test.ts protocol/test/egress-policy.test.ts`

Expected: FAIL on missing authorization modules.

- [ ] **Step 3: Implement explicit deny-first evaluators**

```ts
export interface RevisionSnapshot {
  pairingGeneration: bigint;
  authorizationEpoch: bigint;
  scopeRevisions: ReadonlyMap<string, bigint>;
}

export type AuthorizationDecision =
  | { allowed: true }
  | { allowed: false; reason:
      | "PAIRING_INACTIVE"
      | "BINDING_MISMATCH"
      | "SCOPE_DENIED"
      | "SYSTEM_PERMISSION_MISSING"
      | "BACKEND_UNAVAILABLE"
      | "REVISION_MISMATCH"
      | "GRANT_REVOKED"
      | "LINEAGE_MISMATCH"
      | "RECORD_EXPIRED"
      | "RECORD_DELETED" };
```

`evaluateDeviceAccess` follows the exact conjunction in §7.2. `evaluateStoredQuery` never reads system permission, backend availability or device authorization epoch. `DataQueryGrantKey` is exactly tenant + human principal + device + capability + filter hash, and only human-principal authenticated flows may expand it.

`data_query_grant_update` and `data_query_grant_ack` are signed closed messages. A device-originated change remains `pending_*_ack` in device UI until a matching Bridge signature covers grant key, proposed digest, monotonic `grant_revision` and Bridge effective state. Bridge blocks new queries immediately after durably applying a revoke, then emits the ACK; forged, stale or out-of-order ACKs never change either view. The reducer models device UI and Bridge-effective state separately so offline revocation is represented honestly rather than inferred.

`revision-events.json` is the executable transition table. New pairing advances `pairing_generation` and permanently revokes the old binding. Pause, resume, emergency stop, Android permission change, enhanced-backend session/credential change and Bridge command-key activation each advance `authorization_epoch`; a scope consent/policy revoke or regrant advances both the epoch and that capability's `scope_revision`. Local device state is exactly `active | paused | revoked`, and only an authenticated local-user transition can leave `paused`; `revoked` requires new pairing. Adapter credential reissue is explicitly absent from this device table and advances only adapter generation.

Manifest entries retain separate booleans/state for schema support, backend availability/kind, system permission, local consent, effective Agent scope, offline policy, freshness, constraints and revision tuple, plus model destination and zero-retention evidence revision/status. `effectiveCapabilities()` still re-evaluates authenticated binding and current authorization; a manifest alone can never return allowed.

The capability registry contains every scope/pattern from §11.1 and, for each, freezes risk floor, data sensitivity, offline policy, minimum approval mode, allowed backend classes and special invariant IDs. `evaluateRiskPolicy` takes the maximum requirement across that registry, sensitivity, user override and backend correction; L4 is always `POLICY_BLOCKED`, B1 requires an active enhanced-backend session, and `sms.send` always returns `per_operation_on_device` approval bound to recipient, complete body, SIM, path, operation, expiry and revision.

`zero-retention-profile.schema.json` requires provider destination, account/profile identifier, contract/config evidence revision, verification time, expiry and an exact statement that request, response, attachment and tool payload create no provider log/training/review/cache/backup object. `evaluateEgressProfile` permits body egress only while that signed deployment evidence is current and unchanged; missing, unknown, expired, drifted or provider-object-returned states fail closed. The provider remains a plaintext processing boundary but creates no deletion target.

The capability registry explicitly rejects `shell.root`, `shell.root.*`, generic exec, script and dynamic action names.

- [ ] **Step 4: Run authorization and registry tests**

Run: `npm test -- protocol/test/authorization.test.ts protocol/test/authorization-revision.test.ts protocol/test/data-query-grant.test.ts protocol/test/capability-manifest.test.ts protocol/test/risk-policy.test.ts protocol/test/egress-policy.test.ts && npm run typecheck`

Expected: all deny-table, independence and forbidden-capability cases pass.

- [ ] **Step 5: Commit authorization oracles**

```bash
git add docs/protocol/v1/authorization.md protocol/schemas/v1 protocol/registries/v1 protocol/src protocol/test
git commit -m "feat(protocol): add authorization reference model"
```

### Task 7: Implement operation, error and execution-claim state machines

**Files:**
- Create: `docs/protocol/v1/operations.md`
- Create: `protocol/schemas/v1/operation.schema.json`
- Create: `protocol/schemas/v1/receipt.schema.json`
- Create: `protocol/registries/v1/errors.json`
- Create: `protocol/model/v1/operation-transitions.json`
- Create: `protocol/model/v1/error-precedence.json`
- Create: `protocol/src/operation-machine.ts`
- Create: `protocol/src/execution-ledger.ts`
- Create: `protocol/test/operation-machine.test.ts`
- Create: `protocol/test/execution-ledger.test.ts`

**Interfaces:**
- Produces: `reduceOperation(record,event)`, `toWireOperationState(record)`, `registerOperation`, `claimExecution`, `recordResult`, `reconcile`, `ExecutionLedgerStore`, `classifyProtocolFailure(stage)`.
- Consumes: verified identity/revision/parameter digest and signed receipt primitives.

- [ ] **Step 1: Write failing exhaustive transition and crash-window tests**

```ts
it("never changes result_unknown when reconciliation arrives", () => {
  const unknown = operation({ terminalOutcome: "result_unknown" });
  const reconciled = reduceOperation(unknown, {
    type: "reconcile_evidence",
    outcome: "succeeded",
    evidence_digest: DIGEST_A,
    observed_at: "2026-08-08T12:00:00.000Z",
  });
  expect(reconciled.terminalOutcome).toBe("result_unknown");
  expect(reconciled.reconciliation?.outcome).toBe("succeeded");
});

it("returns the current state when cancel loses to execution claim", async () => {
  await ledger.claim(OPERATION_A, PARAMS_A);
  expect(await ledger.cancelBeforeClaim(OPERATION_A)).toEqual({ cancelled: false, requestStatus: "executing" });
  expect(sideEffectCalls).toBe(0);
});
```

Generate every legal edge and assert every unlisted state/event pair returns `INVALID_STATE_TRANSITION` without changing durable state. Add WAIT_READ versus FAIL_OFFLINE, device locked, same operation/different digest, ledger full, crash before claim, crash after claim/before side effect, crash after side effect/before result, conflicting result, result ACK loss, late receipt and protocol-error-does-not-rewrite-terminal cases. Recovery after any durable claim but before a trustworthy result is always `result_unknown`, even when a test fault hook knows the external call had not started, because production recovery cannot prove that fact.

- [ ] **Step 2: Run tests and verify missing state machine fails**

Run: `npm test -- protocol/test/operation-machine.test.ts protocol/test/execution-ledger.test.ts`

Expected: FAIL on missing reducer/ledger exports.

- [ ] **Step 3: Implement the closed state and error model**

```ts
export type RequestStatus =
  | "created" | "waiting_device" | "dispatching" | "accepted_device"
  | "awaiting_approval" | "approved" | "executing";

export type TerminalOutcome =
  | "succeeded" | "failed" | "denied" | "cancelled" | "expired" | "result_unknown";

export type OperationState =
  | { kind: "pending"; requestStatus: RequestStatus; terminalOutcome: null; operationReason: null }
  | { kind: "terminal"; requestStatus: null; terminalOutcome: TerminalOutcome; operationReason: OperationReason | null };

export interface SignedOperationReceipt {
  operationId: string;
  parametersDigest: string;
  state: OperationState;
  resultDigest: string | null;
  canonicalBytes: Uint8Array;
}

export type LedgerEntry =
  | { kind: "registered"; operationId: string; parametersDigest: string }
  | { kind: "claimed"; operationId: string; parametersDigest: string; claimedAt: string }
  | { kind: "result"; operationId: string; parametersDigest: string; receipt: SignedOperationReceipt };

export interface ExecutionLedgerStore {
  register(operationId: string, parametersDigest: string): Promise<"new" | "same" | "conflict">;
  claim(operationId: string, parametersDigest: string): Promise<"claimed" | "already_claimed" | "unavailable">;
  cancelBeforeClaim(operationId: string): Promise<{ cancelled: true } | { cancelled: false; requestStatus: RequestStatus }>;
  putResult(operationId: string, result: SignedOperationReceipt): Promise<void>;
  get(operationId: string): Promise<LedgerEntry | undefined>;
}
```

Freeze the transition table in both `operations.md` and a data constant consumed by tests. The wire schema is a closed `oneOf`: pending records carry `request_status` only; terminal records carry `terminal_outcome` and an independently typed nullable `operation_reason`; protocol errors are envelope rejection objects and never fields masquerading as operation state. `toWireOperationState` is the unique lossless mapping, and every state vector asserts that TypeScript and Kotlin encode the same three-column representation. `accepted_device` is reachable only after the fake device stores the operation and emits a signed receipt. Only registry entries with `offline_policy=WAIT_READ` may enter `waiting_device`, for at most 900 seconds; all streams and side effects use `FAIL_OFFLINE`. Every external side effect must follow verify → claim CAS → one invocation → durable result → signed receipt → repeated delivery until signed Bridge ACK.

The error registry separately enumerates protocol errors, nonterminal status, terminal outcome, operation failure reason and internal decision reason. It implements the closed total-spec table, including `SCHEMA_INVALID`, `MESSAGE_TOO_LARGE`, `MESSAGE_EXPIRED`, `CONNECTION_FENCED`, `FLOW_CONTROL_VIOLATION`, `INVALID_STATE_TRANSITION` and `RESULT_CONFLICT`; protocol-level failures cannot rewrite an existing operation terminal outcome. `error-precedence.json` fixes evaluation order as size → canonical parse/schema → key/direction/domain/signature → payload digest → expiry → connection fence → authenticated binding → authorization/revision → exact-duplicate/replay/sequence → operation transition. A rejected message never commits replay, approval, ticket, cursor or operation state. Internal reasons such as pending-limit, cooldown or credit exhaustion are never emitted as wire codes; `errors.json` maps them to `RATE_LIMITED` plus `retry_after` or `FLOW_CONTROL_VIOLATION`. `system_ui_handoff` is a typed result whose external side effect status is `not_observed`; it is never encoded as confirmed send success.

- [ ] **Step 4: Run the exhaustive state/property suite**

Run: `npm test -- protocol/test/operation-machine.test.ts protocol/test/execution-ledger.test.ts && npm run typecheck`

Expected: every allowed edge and crash point passes; every illegal transition is rejected.

- [ ] **Step 5: Commit operation semantics**

```bash
git add docs/protocol/v1/operations.md protocol/schemas/v1 protocol/registries/v1/errors.json protocol/model/v1 protocol/src protocol/test
git commit -m "feat(protocol): define operation execution semantics"
```

### Task 8: Bind approvals and enforce anti-harassment limits

**Files:**
- Create: `protocol/schemas/v1/approval.schema.json`
- Create: `protocol/src/approval.ts`
- Create: `protocol/src/request-limiter.ts`
- Create: `protocol/test/approval.test.ts`
- Create: `protocol/test/request-limiter.test.ts`
- Modify: `docs/protocol/v1/operations.md`

**Interfaces:**
- Produces: `issueApprovalCredential`, `claimExecutionWithApproval`, `ApprovalClaimStore`, `RequestLimiter.evaluate`.
- Consumes: signed identity, operation, capability, parameter digest and revision types.

- [ ] **Step 1: Write failing approval mutation and limiter tests**

```ts
it.each(["tenant", "human", "agent", "agent_instance", "workspace", "session_or_job", "device", "operation", "capability", "parameters", "backend", "revision"])(
  "rejects a changed %s binding",
  async (field) => {
    expect(await claimExecutionWithApproval(mutate(validApproval, field), expected, store, clock))
      .toEqual({ ok: false, error: "NOT_AUTHORIZED", reason: "BINDING_MISMATCH" });
  },
);

it("atomically consumes approval and creates the execution claim", async () => {
  store.injectFailure("before_atomic_commit");
  expect(await claimExecutionWithApproval(validApproval, expected, store, clock))
    .toEqual({ ok: false, error: "SECURITY_LEDGER_FULL" });
  expect(store.approvalConsumed(APPROVAL_A)).toBe(false);
  expect(store.executionClaimed(OPERATION_A)).toBe(false);
});

it("enforces 60/minute, three pending and ten-minute rejection cooldown", () => {
  expect(runSixtyRequests(limiter)).toHaveLength(60);
  expect(limiter.evaluate(request61)).toEqual({ allowed: false, wireError: "RATE_LIMITED", decisionReason: "WINDOW_LIMIT", retryAfterSeconds: "60" });
  expect(limiter.openApproval(pending4)).toEqual({ allowed: false, wireError: "RATE_LIMITED", decisionReason: "PENDING_LIMIT", retryAfterSeconds: "1" });
  expect(limiter.evaluate(rejectedEquivalentAtNineMinutes)).toEqual({ allowed: false, wireError: "RATE_LIMITED", decisionReason: "REJECTION_COOLDOWN", retryAfterSeconds: "60" });
});
```

- [ ] **Step 2: Run tests and verify missing approval APIs fail**

Run: `npm test -- protocol/test/approval.test.ts protocol/test/request-limiter.test.ts`

Expected: FAIL on missing approval/limiter modules.

- [ ] **Step 3: Implement one-shot credentials and deterministic limits**

Approval claims contain `approval_id`, all principal/session/device/operation IDs, capability, `parameters_digest`, backend kind, complete revision snapshot, `issued_at`, `expires_at` and one-use nonce. `ApprovalClaimStore.claimExecutionWithApproval()` validates first, then atomically marks `approval_id` consumed and writes the matching `operation_id + parameters_digest` execution claim in one durable transaction. A crash or storage failure cannot leave only one half committed; an exact command retransmission returns the existing claim/receipt, while a different operation or digest cannot reuse the approval. The signature domain is `approval/device`.

`RequestLimiter` uses injected monotonic time and a rolling 60-second request window keyed by agent principal + device. The pending-operation dedupe key is exactly tenant + human + agent + Agent instance + workspace + session-or-job + device + backend + capability + parameters digest + complete revision snapshot, so requests from different principals or sessions never fold together. The maximum is three open approvals per agent/device. Rejection cooldown remains keyed by agent + device + capability + parameters digest for 600 seconds as approved. Equivalent pending requests return the existing operation; every limit response emits registered wire code `RATE_LIMITED`, a closed internal decision reason and deterministic `retry_after`.

- [ ] **Step 4: Run approval and limiter tests**

Run: `npm test -- protocol/test/approval.test.ts protocol/test/request-limiter.test.ts && npm run typecheck`

Expected: all one-shot, mutation, expiry, dedupe and limit cases pass.

- [ ] **Step 5: Commit approval controls**

```bash
git add docs/protocol/v1/operations.md protocol/schemas/v1/approval.schema.json protocol/src protocol/test
git commit -m "feat(protocol): bind approvals and request limits"
```

### Task 9: Freeze the device-event wire identity contract

**Files:**
- Create: `docs/protocol/v1/events.md`
- Create: `protocol/schemas/v1/event.schema.json`
- Create: `protocol/src/event-contract.ts`
- Create: `protocol/test/event-contract.test.ts`

**Interfaces:**
- Produces: `validateDeviceEvent(value,authenticatedDevice)`, `validateEventAck(value,authenticatedBridge)`.
- Consumes: signed device envelope and revision snapshot; no Agent principal/session input.

- [ ] **Step 1: Write failing event identity tests**

```ts
it("does not route a device event using client-supplied Agent identity", () => {
  expect(() => validateDeviceEvent({ ...validEvent, agent_principal_id: "agent-b" }, authenticatedDeviceA))
    .toThrowError("SCHEMA_INVALID");
});

it("binds source epoch, cursor and capture revision to the authenticated device", () => {
  expect(validateDeviceEvent(validEvent, authenticatedDeviceA)).toMatchObject({
    deviceId: "device-a",
    routeByServerSubscriptionOnly: true,
  });
  expect(() => validateDeviceEvent(validEvent, authenticatedDeviceB))
    .toThrowError("AUTH_BINDING_MISMATCH");
});
```

Add same-tenant cross-principal/device, stale capture revision, malformed upsert/delete tombstone/loss marker union, forbidden session/Agent fields and event-ACK direction/key substitution cases.

- [ ] **Step 2: Run tests and verify missing event model fails**

Run: `npm test -- protocol/test/event-contract.test.ts`

Expected: FAIL because event schema and identity validator do not exist.

- [ ] **Step 3: Implement the event contract**

Each event contains `source_epoch`, `occurrence_id`, `record_key`, `record_revision`, `cursor`, `captured_at`, `event_kind`, source capability and capture-time revision snapshot. `event_kind` is exactly `upsert | delete_tombstone | loss_marker`; loss markers also carry closed `lost_from_cursor`, `lost_to_cursor`, `reason` fields.

P0a freezes only these signed fields, direction/domain, device binding and the rule that Bridge routing uses authenticated server-side subscriptions rather than an Agent ID supplied by the device. Durable cursor storage, contiguous-ACK advancement, out-of-order buffering, gap/resync and source collector behavior remain explicitly owned by P0b/P1a and are not implemented or claimed by this plan.

- [ ] **Step 4: Run event tests**

Run: `npm test -- protocol/test/event-contract.test.ts && npm run typecheck`

Expected: all event-union, binding, direction and schema-isolation cases pass.

- [ ] **Step 5: Commit event semantics**

```bash
git add docs/protocol/v1/events.md protocol/schemas/v1/event.schema.json protocol/src/event-contract.ts protocol/test/event-contract.test.ts
git commit -m "feat(protocol): freeze event wire contract"
```

### Task 10: Define artifact and live-stream tickets, PoP and lifecycle

**Files:**
- Create: `docs/protocol/v1/artifacts-streams.md`
- Create: `protocol/schemas/v1/channel-ticket.schema.json`
- Create: `protocol/schemas/v1/artifact.schema.json`
- Create: `protocol/schemas/v1/stream.schema.json`
- Create: `protocol/src/channel-ticket.ts`
- Create: `protocol/src/artifact-machine.ts`
- Create: `protocol/src/stream-machine.ts`
- Create: `protocol/test/channel-ticket.test.ts`
- Create: `protocol/test/artifact-machine.test.ts`
- Create: `protocol/test/stream-machine.test.ts`

**Interfaces:**
- Produces: `redeemTicket`, `verifyChannelPop`, `validateResumeTicket`, `reduceArtifact`, `acceptArtifactChunk`, `commitArtifact`, `reduceStream`, `grantCredit`, `sendStreamChunk`.
- Consumes: Bridge ticket signer, device/adapter verifier, identity/revision and connection generation.

- [ ] **Step 1: Write failing ticket, resume and credit tests**

```ts
it("requires holder-of-key proof and consumes a ticket once", async () => {
  expect(await redeemTicket(ticket, popSignedByWrongDevice, store, clock)).toEqual({ ok: false, error: "AUTH_FAILED" });
  expect(await redeemTicket(ticket, validPop, store, clock)).toMatchObject({ ok: true });
  expect(await redeemTicket(ticket, validPop, store, clock)).toEqual({ ok: false, error: "REPLAY_REJECTED" });
});

it("resumes artifacts only with a new ticket at the confirmed offset", () => {
  expect(validateResumeTicket(oldTicket, confirmedOffset)).toEqual({ ok: false, error: "REPLAY_REJECTED" });
  expect(validateResumeTicket(newTicketAtWrongOffset, confirmedOffset)).toEqual({ ok: false, error: "INTEGRITY_FAILED" });
  expect(validateResumeTicket(newTicketAtConfirmedOffset, confirmedOffset)).toEqual({ ok: true });
});

it("never resumes live streams and never exceeds granted byte credit", () => {
  expect(() => reduceStream(disconnectedStream, { type: "resume" })).toThrowError("INVALID_STATE_TRANSITION");
  expect(sendStreamChunk(streamWith1024Credit, chunkOf1025Bytes)).toEqual({
    ok: false,
    error: "FLOW_CONTROL_VIOLATION",
    decisionReason: "CREDIT_EXCEEDED",
  });
});
```

Add five-minute expiry, cross-tenant/session/device, same-tenant cross-principal download, wrong connection/revision, channel nonce replay, upload-ticket-before-final-hash, digest/length/MIME mutation, exact 4-file/25-MiB/50-MiB boundaries, below-baseline capability declaration, chunk sequence/hash, duplicate commit, message-before-artifact, 24-hour orphan expiry, cancellation and stream ticket revocation cases.

- [ ] **Step 2: Run tests and verify missing channel state fails**

Run: `npm test -- protocol/test/channel-ticket.test.ts protocol/test/artifact-machine.test.ts protocol/test/stream-machine.test.ts`

Expected: FAIL on missing ticket/artifact/stream modules.

- [ ] **Step 3: Implement closed ticket claims and state machines**

The common ticket claim binds ticket ID, tenant/human/agent/workspace/session-or-job/device/operation, direction, capability, current connection generation, explicit revision snapshot, issued/expiry time and limits. It is Bridge-signed with `ticket/bridge`; channel open separately signs `ticket_id + channel_nonce` under the credential named in the ticket.

Artifact tickets additionally bind artifact ID, MIME, final byte length, final SHA-256, `start_offset`, `chunk_size` and byte ceiling. `chunk_size` is a negotiated power of two from 65,536 through 1,048,576 bytes; v1 default is 262,144. The v1 advertised interoperability baseline is at most four artifacts, 26,214,400 bytes per artifact and 52,428,800 bytes per message, with JPEG/PNG/WebP, PDF and `text/plain` MIME values; a deployment may advertise stricter runtime limits but cannot claim the later P3 attachment exit. Resume offset must equal the receiver-confirmed sum of complete chunks. Live-stream tickets bind stream ID/nonce, codec, config digest, chunk/frame/byte/duration/rate ceilings and `max_in_flight_bytes`; they contain neither final digest nor resume offset.

Artifact and stream reducers implement exactly the approved states. Stream credit is measured in payload bytes and every accepted chunk decrements it atomically. Disconnect/revocation/lock/stop closes the stream and releases buffered bytes; no raw frame is persisted.

- [ ] **Step 4: Run channel and lifecycle tests**

Run: `npm test -- protocol/test/channel-ticket.test.ts protocol/test/artifact-machine.test.ts protocol/test/stream-machine.test.ts && npm run typecheck`

Expected: all PoP, isolation, resume, integrity and backpressure cases pass.

- [ ] **Step 5: Commit artifact/stream protocol**

```bash
git add docs/protocol/v1/artifacts-streams.md protocol/schemas/v1 protocol/src protocol/test
git commit -m "feat(protocol): add artifact and stream tickets"
```

### Task 11: Build deterministic fake actors and golden traces

**Files:**
- Create: `protocol/src/testing/fake-clock.ts`
- Create: `protocol/src/testing/fake-stores.ts`
- Create: `protocol/src/testing/fake-bridge.ts`
- Create: `protocol/src/testing/fake-device.ts`
- Create: `protocol/src/testing/fake-adapter.ts`
- Create: `protocol/src/testing/protocol-harness.ts`
- Create: `protocol/src/testing/trace-recorder.ts`
- Create: `protocol/tools/generate-vectors.ts`
- Create: `protocol/vectors/v1/manifest.json`
- Create: `protocol/vectors/v1/public-keys.json`
- Create: `protocol/test/golden-traces.test.ts`
- Modify: `package.json`

**Interfaces:**
- Produces: `FakeClock`, `FakeBridge`, `FakeDevice`, `FakeAdapter`, `ProtocolHarness.run(trace)`, `npm run vectors:generate`, `npm run vectors:check`.
- Consumes: all Task 3–10 pure interfaces; no production network or persistence.

- [ ] **Step 1: Write failing end-to-end fake traces**

```ts
it("retries a lost result ACK without repeating the side effect", async () => {
  const harness = ProtocolHarness.seeded("ack-loss-001");
  const trace = await harness.run("operation/ack-loss");
  expect(trace.sideEffectInvocations).toBe(1);
  expect(trace.adapterOutcome).toBe("succeeded");
  expect(trace.events).toContainEqual(expect.objectContaining({ type: "receipt_replayed" }));
});

it("crashes after the side effect into immutable result_unknown", async () => {
  const harness = ProtocolHarness.seeded("result-unknown-001");
  harness.faults.arm("after_side_effect_before_result");
  const trace = await harness.run("operation/crash-window");
  expect(trace.sideEffectInvocations).toBe(1);
  expect(trace.adapterOutcome).toBe("result_unknown");
  expect(trace.automaticRetryCount).toBe(0);
});
```

Add complete enrollment/connect and short-code tamper, principal injection, stale revision after revoke/regrant, HTTP/WSS reorder, key rotation, old connection fence, approval mutation, event-envelope principal injection, artifact resume, stream disconnect, same-tenant cross-principal plus cross-tenant isolation, error-precedence competition, SMS/L4 policy and zero-retention-profile egress denial traces.

- [ ] **Step 2: Run traces and verify missing fakes fail**

Run: `npm test -- protocol/test/golden-traces.test.ts`

Expected: FAIL because fake actors and harness are absent.

- [ ] **Step 3: Implement fakes and stable trace recording**

```ts
export type FaultPoint =
  | "before_execution_claim"
  | "after_execution_claim_before_side_effect"
  | "after_side_effect_before_result"
  | "after_result_before_bridge_ack"
  | "drop_result_ack"
  | "disconnect_transport";

export class ProtocolHarness {
  static seeded(seed: string): ProtocolHarness;
  readonly clock: FakeClock;
  readonly bridge: FakeBridge;
  readonly device: FakeDevice;
  readonly adapter: FakeAdapter;
  readonly faults: FaultController;
  run(traceId: string): Promise<RecordedTrace>;
}
```

All IDs, nonces, test signatures, timestamps and fault points derive from the named seed. Trace serialization omits private keys and sensitive payload body, records canonical wire bytes as base64, and uses stable enum results. Fake stores expose crash/restart snapshots without claiming production durability.

- [ ] **Step 4: Generate and lock TypeScript vectors**

Add scripts:

```json
{
  "vectors:generate": "tsx protocol/tools/generate-vectors.ts --write",
  "vectors:check": "tsx protocol/tools/generate-vectors.ts --check"
}
```

`manifest.json` groups vectors under `canonical`, `crypto`, `enrollment`, `version`, `control`, `replay`, `rotation`, `authorization`, `operation`, `approval`, `events`, `artifact`, `stream`, `isolation`, `policy` and `egress`. Every vector records `id`, `kind`, `security_property_ids`, canonical `wire_b64`, oracle-only `signing_preimage_b64` when applicable, public key ID, authenticated context, injected clock/nonces, initial durable state, ordered actions and normalized expected results. `public-keys.json` contains distinct device, Bridge command and adapter current/next P-256 public keys; private halves remain under `test-only`, and wrong-role/current-next substitution vectors must fail.

Every ordered action records `state_before`, `state_after` and `state_delta`; side-effect fields include external invocations, claims, results, bytes, replay/key/approval/ticket mutations and audit count/content-sentinel. A rejected action must have `state_delta.external_invocations = 0` and zero non-audit durable delta, even when earlier setup actions legitimately produced effects. Trace totals are separate and never overloaded as per-step deltas. `signing_preimage_b64` is diagnostic oracle output only and is never supplied to either conformance SUT during verification.

The committed set contains at least these twenty-five named families, with field mutations materialized as stable cases rather than generated randomly in CI:

1. `p0a.v1.canonical.profile`
2. `p0a.v1.signature.dev2br`
3. `p0a.v1.signature.br2dev`
4. `p0a.v1.signature.tamper-domain-matrix`
5. `p0a.v1.binding.auth-context-matrix`
6. `p0a.v1.replay.sequence-ledger`
7. `p0a.v1.connection.fence-reconnect`
8. `p0a.v1.key-rotation.lifecycle`
9. `p0a.v1.authorization.revision-lifecycle`
10. `p0a.v1.approval.single-use-atomic-claim`
11. `p0a.v1.operation.transition-matrix`
12. `p0a.v1.operation.idempotency`
13. `p0a.v1.operation.cancel-race`
14. `p0a.v1.operation.crash-cuts`
15. `p0a.v1.operation.reconcile`
16. `p0a.v1.artifact.upload-commit`
17. `p0a.v1.artifact.resume-abuse`
18. `p0a.v1.stream.lifecycle-abuse`
19. `p0a.v1.version.negotiate-downgrade`
20. `p0a.v1.version.schema-preserving-migration`
21. `p0a.v1.event-envelope.binding-matrix`
22. `p0a.v1.error.precedence-matrix`
23. `p0a.v1.policy.sms-send-always-confirms`
24. `p0a.v1.policy.l4-backend-deny-matrix`
25. `p0a.v1.isolation.same-tenant-principal-matrix`

Run: `npm run vectors:generate && npm run vectors:check && npm test`

Expected: generator check reports zero changed vectors and the full TypeScript suite passes.

- [ ] **Step 5: Commit fakes and vectors**

```bash
git add package.json package-lock.json protocol/src/testing protocol/tools protocol/vectors protocol/test/golden-traces.test.ts
git commit -m "test(protocol): add deterministic golden traces"
```

### Task 12: Add an independent Kotlin/JVM conformance consumer

**Files:**
- Create: `conformance/SUT.md`
- Create: `conformance/schemas/sut-request-v1.schema.json`
- Create: `conformance/schemas/sut-response-v1.schema.json`
- Create: `protocol/tools/conformance-cli.ts`
- Create: `protocol/tools/run-conformance.ts`
- Create: `protocol/tools/cross-language-interop.ts`
- Create: `conformance/kotlin/settings.gradle.kts`
- Create: `conformance/kotlin/build.gradle.kts`
- Create: `conformance/kotlin/gradle.properties`
- Create: `conformance/kotlin/gradle/verification-metadata.xml`
- Create: `conformance/kotlin/gradle/wrapper/gradle-wrapper.jar`
- Create: `conformance/kotlin/gradle/wrapper/gradle-wrapper.properties`
- Create: `conformance/kotlin/gradlew`
- Create: `conformance/kotlin/gradlew.bat`
- Create: `conformance/kotlin/run-sut`
- Create: `conformance/kotlin/src/main/kotlin/life/agent/protocol/CanonicalJson.kt`
- Create: `conformance/kotlin/src/main/kotlin/life/agent/protocol/Es256.kt`
- Create: `conformance/kotlin/src/main/kotlin/life/agent/protocol/SchemaValidation.kt`
- Create: `conformance/kotlin/src/main/kotlin/life/agent/protocol/VectorModels.kt`
- Create: `conformance/kotlin/src/main/kotlin/life/agent/protocol/ReferenceReducers.kt`
- Create: `conformance/kotlin/src/main/kotlin/life/agent/protocol/SutMain.kt`
- Create: `conformance/kotlin/src/test/kotlin/life/agent/protocol/GoldenVectorTest.kt`

**Interfaces:**
- Produces: Gradle `test`/`installDist`, stable TypeScript/Kotlin NDJSON launchers, and ABI operations `canonicalize`, `digest`, `encode_sign_envelope`, `verify_envelope`, `validate_schema`, `negotiate_version`, `accept_sequence`, `authorize_device`, `authorize_stored_query`, `evaluate_risk`, `evaluate_egress`, `claim_with_approval`, `transition_operation`, `validate_event_contract`, `redeem_ticket`, `reduce_artifact`, `reduce_stream`, `migrate` and `run_trace`.
- Consumes: frozen vector JSON only; it must not import or execute TypeScript reference code.

- [ ] **Step 1: Configure the verified Gradle wrapper and add the failing Kotlin vector test**

Create the settings/build files with only Maven Central and Gradle Plugin Portal repositories, exact dependency versions, Java toolchain 21 and strict dependency verification. Generate the wrapper from a trusted Gradle 9.6.1 bootstrap distribution, set `distributionSha256Sum=9c0f7faeeb306cb14e4279a3e084ca6b596894089a0638e68a07c945a32c9e14`, and verify `gradle-wrapper.jar` has SHA-256 `497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7`. Then generate and review SHA-256 dependency verification metadata:

```bash
cd conformance/kotlin
gradle wrapper --gradle-version 9.6.1 --distribution-type bin
sha256sum gradle/wrapper/gradle-wrapper.jar
./gradlew --no-daemon --write-verification-metadata sha256 test
```

Expected: wrapper JAR hash matches exactly; before adding `GoldenVectorTest`, the empty test task resolves compile/test artifacts and writes their checksums to `gradle/verification-metadata.xml`; subsequent builds run in strict verification mode.

```kotlin
class GoldenVectorTest {
    @Test
    fun `every v1 vector matches the independent Kotlin implementation`() {
        val manifest = VectorLoader.load("../../protocol/vectors/v1/manifest.json")
        val failures = manifest.vectors.mapNotNull { VectorRunner.run(it) }
        assertEquals(emptyList<String>(), failures)
    }
}
```

Add an interop test that asks Kotlin to encode and sign an unsigned semantic device envelope and TypeScript to verify the resulting raw wire, then asks TypeScript to encode/sign a Bridge command and Kotlin to verify its raw wire. The producer receives no prebuilt canonical bytes or preimage; the consumer receives only `wire_b64`, the public key ring, authenticated context and injected clock, and must independently reconstruct JCS bytes, payload digest, domain and length-prefixed preimage. Include non-canonical raw-wire, one-byte mutation and cross-role key negatives. Assertions compare semantic output and low-S validity, not freshly generated ECDSA signature bytes.

- [ ] **Step 2: Run Gradle and verify missing runner classes fail**

Run: `cd conformance/kotlin && ./gradlew test --tests life.agent.protocol.GoldenVectorTest`

Expected: compilation FAIL because `VectorLoader` and `VectorRunner` are absent.

- [ ] **Step 3: Implement the independent Kotlin conformance path**

Use Gradle wrapper 9.6.1, Kotlin JVM/serialization plugins 2.4.10, Java toolchain 21, `kotlinx-serialization-json:1.11.0`, `io.github.erdtman:java-json-canonicalization:1.1`, `com.networknt:json-schema-validator:3.0.6` and Kotlin/JUnit 5 tests. Dependency verification metadata, distribution checksum and wrapper JAR checksum must remain committed and pass CI validation.

`conformance/SUT.md` and the two closed `sut-abi-v1` schemas define one canonical JSON request and response per NDJSON line. Each request contains `case_id`, operation name, `profile_id`, injected `now`, input bytes/state and authenticated context; each response contains the same `case_id`, `ok`, and either normalized `value` or `{ failure_layer, code, stage }`. `run_trace` receives profile, fixed clock/nonces, authenticated context, initial state and ordered actions, but never the vector's expected result. Stdout is NDJSON only; diagnostics go to stderr and never include payload body.

`CanonicalJson` uses the Java RFC 8785 implementation and compares complete raw canonical bytes. `Es256` parses P-256 JWK coordinates, converts strict DER/P1363 in both directions, normalizes every test-produced JCA signature to low-S, and rejects high-S on verification. `SchemaValidation` loads the committed Draft 2020-12 schemas and mandatory `decimal-u64` format. `ReferenceReducers` independently implements every action required by all 25 families: version/replay/fence/rotation, both authorization domains and revisions, risk/egress, atomic approval claim, operation/crash/reconciliation, event identity, artifact, stream, isolation, error precedence and migration. `SutMain` implements the same NDJSON ABI as `protocol/tools/conformance-cli.ts` without importing TypeScript reference code or expected vector output.

The Kotlin application plugin sets a fixed main class and distribution name `agent-life-kotlin-sut`; `installDist` creates its launcher, while committed executable `conformance/kotlin/run-sut` resolves and `exec`s that launcher without shell evaluation of request data. `protocol/tools/run-conformance.ts` starts each SUT once, validates every request/response against the ABI schemas, sends every committed vector through `run_trace`, and compares the normalized response with the driver's held expected result. This path is mandatory even if language-local unit tests pass. Runners report vector ID plus expected/actual enums only; they never log vector payload bodies.

- [ ] **Step 4: Run cross-language conformance**

Run: `npm run vectors:check && (cd conformance/kotlin && ./gradlew --no-daemon test installDist) && npx tsx protocol/tools/run-conformance.ts && npx tsx protocol/tools/cross-language-interop.ts`

Expected: TypeScript regeneration makes no changes; every Kotlin unit test passes; both NDJSON SUTs independently pass every vector; and Kotlin→TypeScript plus TypeScript→Kotlin canonical producer/consumer directions verify, including mutation negatives.

- [ ] **Step 5: Commit Kotlin conformance**

```bash
git add conformance protocol/tools/conformance-cli.ts protocol/tools/run-conformance.ts protocol/tools/cross-language-interop.ts
git commit -m "test(protocol): verify vectors with Kotlin"
```

### Task 13: Freeze coverage gates and P0a release evidence

**Files:**
- Create: `docs/protocol/v1/p0a-acceptance.md`
- Create: `docs/protocol/v1/README.md`
- Create: `protocol/tools/check-spec-lock.ts`
- Create: `protocol/tools/check-requirement-coverage.ts`
- Create: `protocol/tools/check-forbidden-surfaces.ts`
- Create: `protocol/spec-lock.json`
- Create: `protocol/test/release-gates.test.ts`
- Modify: `conformance/SUT.md`
- Create: `.github/workflows/protocol.yml`
- Modify: `package.json`

**Interfaces:**
- Produces: `npm run schema:check`, `npm run coverage:check`, `npm run forbidden:check`, `npm run check`, immutable semantic hash manifest and CI evidence.
- Consumes: every profile, schema, registry, model, requirement/security property, migration fixture, ABI and vector plus both language test suites.

- [ ] **Step 1: Write failing lock and coverage-gate tests**

```ts
it("never mutates an already locked schema ID", async () => {
  const result = await checkSpecLock({ root: "protocol", lock: "protocol/spec-lock.json" });
  expect(result.changedExistingEntries).toEqual([]);
});

it("maps every P0a requirement and security property to existing evidence", async () => {
  const result = await checkRequirementCoverage();
  expect(result).toMatchObject({
    missingRequirements: [],
    missingSecurityProperties: [],
    outOfScopeEntries: [],
  });
});
```

`check-forbidden-surfaces` semantically enumerates every accepted production schema value, registry entry and exported command/API surface and proves none can represent arbitrary Root Shell, generic exec, script upload/interpreter, dynamic Root action registration or user automation rule DSL. Dedicated negative tests/test-only vectors may contain the forbidden strings as inputs; docs, the checker's deny fixture and rejected vector payloads are excluded from literal scanning, while their expected verdict must be reject.

- [ ] **Step 2: Run the gates and verify missing lock/tools fail**

Run: `npm test -- protocol/test/release-gates.test.ts`

Expected: FAIL because spec lock and check tools are absent.

- [ ] **Step 3: Implement immutable semantic locks and coverage gates**

`spec-lock.json` stores relative path, `$id` where applicable and SHA-256 for the protocol profile, every production and conformance schema, registry/model file, locked migration fixture, `conformance/SUT.md`, vector manifest/public-key registry and every referenced vector file. `check-spec-lock --update` may only add new IDs unless an explicit new version line permitted by Task 4 is being created; changing an existing locked entry fails. `checkRequirementCoverage` resolves every `P0A-001..022` artifact/evidence path and every `SEC-001..016` evidence ID, rejects missing files/IDs, and rejects any registry entry whose declared owner phase is later than P0a.

- [ ] **Step 4: Add the unified CI gate and run it locally**

Add scripts:

```json
{
  "schema:check": "tsx protocol/tools/check-spec-lock.ts --check",
  "coverage:check": "tsx protocol/tools/check-requirement-coverage.ts",
  "forbidden:check": "tsx protocol/tools/check-forbidden-surfaces.ts",
  "check": "npm run typecheck && npm test && npm run vectors:check && npm run schema:check && npm run coverage:check && npm run forbidden:check"
}
```

GitHub Actions runs on Node 24.18.0 and JDK 21, first asserts the committed wrapper JAR SHA-256 and `distributionSha256Sum` equal the Task 12 values, then executes `npm ci`, `npm run check`, `(cd conformance/kotlin && ./gradlew --no-daemon test installDist)` under strict dependency verification, `npx tsx protocol/tools/run-conformance.ts`, and `npx tsx protocol/tools/cross-language-interop.ts`. It uploads only test reports and enum failures; raw vector bodies, fixture private keys and wire payloads are not printed as artifacts.

Run: `npm run check && (cd conformance/kotlin && ./gradlew --no-daemon test installDist) && npx tsx protocol/tools/run-conformance.ts && npx tsx protocol/tools/cross-language-interop.ts`

Expected: Node and Kotlin suites pass, both signing directions interoperate, lock/vector generation changes nothing, requirement coverage is complete, forbidden surfaces are absent and the worktree has no generated drift.

- [ ] **Step 5: Write the P0a acceptance record**

`p0a-acceptance.md` must record:

- approved design commit;
- protocol/schema version and `spec-lock.json` digest;
- exact algorithm/domain/size/version/rotation/replay decisions;
- positive and negative vector counts by manifest group;
- TypeScript, Kotlin and bidirectional TypeScript↔Kotlin interop command results;
- mapping for `P0A-001`–`P0A-022`;
- mapping for `SEC-001`–`SEC-016`;
- known residual risks from the threat model;
- explicit statement that production Bridge, Android persistence/Keystore, real transport and upstream adapters remain P0b–P0f work.

- [ ] **Step 6: Commit the frozen P0a contract**

```bash
git add package.json package-lock.json docs/protocol/v1 protocol .github/workflows/protocol.yml
git commit -m "docs(protocol): freeze P0a contract"
```

## Final Verification

Run these commands from the repository root after Task 13:

```bash
npm ci
npm run check
conformance/kotlin/gradlew --no-daemon -p conformance/kotlin test
npx tsx protocol/tools/cross-language-interop.ts
git diff --check
git status --short
```

Expected:

- Node typecheck, schema validation, unit/property tests, traces, vector regeneration, security coverage and forbidden-surface gates all exit 0.
- Kotlin independently consumes every frozen vector and exits 0; Kotlin→TypeScript and TypeScript→Kotlin signing/verification both pass.
- `git diff --check` reports nothing.
- `git status --short` reports nothing after the final commit.
- P0a may be declared frozen only when all above evidence is recorded in `docs/protocol/v1/p0a-acceptance.md`; otherwise P0b–P3 remain blocked.
