# Gateway Protocol v2 Task 2 安全纠偏与实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不进入 OpenClaw/Hermes/Android runtime 的前提下，修正 Gateway Protocol v2 的线协议歧义，补齐动态 Schema 失败关闭接口，并完成原迁移计划 Task 2 的签名、纯状态机和六组可执行黄金向量。

**Architecture:** 先修改唯一权威协议和原迁移计划，使 wire ID、HTTP header、canonical target、签名预像、动态 Schema catalog、附件/设备请求状态矩阵都有唯一语言无关语义；再用 TDD 加深 `gateway-contract` 的 Schema 边界；最后实现不依赖数据库、网络、时钟或宿主的纯 TypeScript canonicalizer/reducer，并让六个 JSON 向量全部被测试真实消费。

**Tech Stack:** Node.js `>=24.18.0 <25`、npm `11.16.0`、TypeScript `7.0.2`、Vitest `4.1.10`、Ajv `8.20.0`、`canonicalize` `3.0.0`、SHA-256。

**Spec:** `docs/superpowers/specs/2026-08-24-modular-plugin-architecture.md`

## Global Constraints

- `docs/contracts/gateway-protocol-v2.md` 是网络协议唯一权威来源；实现和向量不得创造契约外语义。
- Gateway Protocol v2 不兼容 Bridge Protocol v1；不得导入 v1 wire bytes、身份、配对密钥、队列或数据库状态。
- TypeScript、Python、Kotlin 后续必须从同一语义输入独立重建结果；Task 2 不建立共享运行二进制。
- 动态 Schema 的来源必须在 validator 构造前已受信，运行期请求不得注入 Schema 或 `ValidateFunction`。
- Android 本地授权仍是设备执行最终权威；Schema 与 Gateway 状态机不能冒充授权裁决。
- 本计划只修改协议文档、迁移计划和 `gateway-contract/`；不得新建或修改 `integrations/openclaw/`、`integrations/hermes/`、Android runtime、Bridge v1 或 Tailscale 路径。
- 使用独立 worktree；保留主工作树和 tsnet/P0t 证据；不得使用 `rm`、`rm -rf`、`unlink` 或任何永久删除命令。
- 所有代码路径与模块引用使用相对路径；所有 Git 提交说明使用中文。

---

### Task 1: 固化 Gateway v2 线协议、动态分派和状态权威

**Files:**
- Create: `docs/superpowers/plans/2026-08-27-gateway-protocol-v2-task2-correction.md`
- Modify: `docs/contracts/gateway-protocol-v2.md`
- Modify: `docs/superpowers/plans/2026-08-24-modular-plugin-architecture-migration.md`

**Interfaces:**
- Produces: wire ID 正则 `^[A-Za-z0-9._~-]{1,128}$`
- Produces: canonical request target 的语言无关逐字节算法
- Produces: 10 个 ASCII 字段、9 个 LF、末尾无 LF 的请求签名预像
- Produces: 构造期不可变动态 Schema catalog 与可信 dispatch 规则
- Produces: 完整 `AttachmentState × AttachmentEvent` 与 `DeviceRequestState × DeviceRequestEvent` 矩阵
- Produces: 六组向量的闭合 `1.0.0` 格式和后续 Task 3/5/6/9 的消费边界

- [ ] **Step 1: 修正 wire ID、header 和身份来源契约**

在协议第 2、5、6 节写明：

```text
wire-id = 1*128(ALPHA / DIGIT / "." / "_" / "~" / "-")
```

所有 ID 仍为不透明值，接收方不得解析业务含义。以下 header 必须从 raw header 列表证明恰好出现一次：

```text
Authorization
X-Agent-Life-Protocol
X-Agent-Life-Account
X-Agent-Life-Device
X-Agent-Life-Session
X-Agent-Life-Request-Id
X-Agent-Life-Timestamp
X-Agent-Life-Nonce
X-Agent-Life-Signature
```

重复、逗号合并、trim 后才合法、CR/LF/NUL/其他控制字符全部拒绝；header 名大小写不敏感，值不做 trim、unfold、first/last 选择。token 验证出的账号、设备、会话必须与 header 精确相等，后续业务只能读取一个 `VerifiedRequestContext`。

- [ ] **Step 2: 固化 method、body、幂等和 SSE cursor 规则**

```text
method    = "GET" / "POST" / "PUT" / "DELETE"
timestamp = RFC3339 UTC with exactly milliseconds
nonce     = canonical unpadded base64url of exactly 16 bytes
signature = canonical unpadded base64url of exactly 64 Ed25519 bytes
```

method 在线上区分大小写，不得通过 `toUpperCase()` 接受别名。body digest 覆盖 HTTP transfer framing 解码后的 exact entity-body bytes，并在 JSON decode、字符转换、自动解压或重序列化之前计算；JSON 请求禁止 `Content-Encoding`。零字节 body 的摘要固定为：

```text
e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
```

副作用请求的 `Idempotency-Key` 必须精确等于已签名的 `X-Agent-Life-Request-Id`；重试可以更换 timestamp/nonce，但保持 request ID。认证 SSE 恢复以 canonical query 的 `cursor` 为权威，`Last-Event-ID` 若存在必须与 query 精确相等。

- [ ] **Step 3: 固化 canonical request target 算法**

输入只允许 `/agent-life/v2` 下的 HTTP origin-form。规范算法按以下顺序执行：

1. 拒绝 absolute-form、authority-form、`*`、fragment、空白和控制字符。
2. 只在第一个字面量 `?` 分离 path/query；query 内字面量 `?` 非规范。
3. path 用字面量 `/` 分段；完整 `%HH` 解码为 byte。
4. `[A-Za-z0-9._~-]` byte 原样输出，其他 byte 输出大写 `%HH`。
5. 拒绝解码后为 `.`、`..` 的 segment；拒绝解码后 `/` 或 `\\`；拒绝 `//` 和非根尾随 `/`。
6. query 用字面量 `&` 分 pair，空 pair 拒绝；在第一个字面量 `=` 分 name/value，空 name 拒绝，canonical 输出始终包含 `=`。
7. name/value 使用同一 byte 编码；`+` 规范为 `%2B`，不解释成空格。
8. 按 canonical encoded name 的 ASCII bytes 排序，再按 value 排序；完全重复 pair 保留数量。
9. 无 query 不输出 `?`，空 query 标记拒绝。
10. 客户端发送的 raw target 与 Gateway 验签读取的 raw target 都必须等于 canonical target；反向代理不能靠未认证 `X-Original-URI` 恢复被重写路径。

- [ ] **Step 4: 固化请求签名预像和独立 oracle**

预像严格为：

```text
ASCII("AGENT-LIFE-REQUEST-V2") LF
ASCII(method) LF
ASCII(canonicalTarget) LF
ASCII(accountId) LF
ASCII(deviceId) LF
ASCII(sessionId) LF
ASCII(requestId) LF
ASCII(timestamp) LF
ASCII(nonce) LF
ASCII(lowercaseHex(SHA-256(exactBodyBytes)))
```

总共 10 个字段、9 个 `0x0A`，最后无 LF、CR、NUL、空格或 BOM。Ed25519 直接签完整预像，不使用 Ed25519ph。

协议写入以下独立向量：

```json
{
  "method": "GET",
  "target": "/agent-life/v2/events?cursor=evt_1&z=last",
  "accountId": "acct_1",
  "deviceId": "dev_1",
  "sessionId": "sess_1",
  "requestId": "req_1",
  "timestamp": "2026-08-27T00:00:00.000Z",
  "nonce": "AAAAAAAAAAAAAAAAAAAAAA",
  "bodyHex": ""
}
```

canonical target：

```text
/agent-life/v2/events?cursor=evt_1&z=last
```

expected preimage hex：

```text
4147454e542d4c4946452d524551554553542d56320a4745540a2f6167656e742d6c6966652f76322f6576656e74733f637572736f723d6576745f31267a3d6c6173740a616363745f310a6465765f310a736573735f310a7265715f310a323032362d30382d32375430303a30303a30302e3030305a0a414141414141414141414141414141414141414141410a65336230633434323938666331633134396166626634633839393666623932343237616534316534363439623933346361343935393931623738353262383535
```

- [ ] **Step 5: 固化动态 Schema catalog 契约**

保留 `schemaFor` / `validateGatewayValue` 的 outer-only 兼容语义。新增原子 dispatched validator：构造时接收并冻结 catalog，运行时按可信 dispatch 完成外壳验证、key 解析、catalog lookup、子值验证。catalog entry 的 Schema identity 为 `sha256:` 加 RFC 8785 JCS UTF-8 bytes 的小写 SHA-256。

四类 key：

```ts
type GatewaySubschemaKey =
  | { kind: "event"; eventType: string; schemaSha256: string }
  | { kind: "device.request"; pluginId: string; authorKeyId: string;
      capabilityId: string; capabilityVersion: string; schemaSha256: string }
  | { kind: "response.success"; operation: string; status: number; schemaSha256: string }
  | { kind: "response.failure"; errorCode: string; schemaSha256: string };
```

事件 type 来自可信 SSE `event:` 行；device key 的 provider/capability 从已经通过外壳验证的 value 提取，只有 schema digest 从协商会话上下文传入；success operation/status 来自本地请求上下文；failure code 从已验证 error envelope 提取。请求不得传入 Schema、resolver 或 `ValidateFunction`。重复 key、digest 不符、无法 strict 编译、未解析外部 `$ref` 在构造时失败；运行期未知/缺失 key 返回冻结的 validation errors。

- [ ] **Step 6: 固化附件矩阵**

状态和事件：

```ts
type AttachmentState =
  | "created" | "uploading" | "verified" | "delivered"
  | "acknowledged" | "failed" | "expired" | "deleted";

type AttachmentEvent =
  | "begin_upload" | "verify" | "deliver" | "acknowledge"
  | "fail" | "expire" | "cleanup";
```

合法转移：

| 当前状态 | 事件 | 下一状态 |
|---|---|---|
| `created` | `begin_upload` | `uploading` |
| `created` | `fail` | `failed` |
| `created` | `expire` | `expired` |
| `uploading` | `verify` | `verified` |
| `uploading` | `fail` | `failed` |
| `uploading` | `expire` | `expired` |
| `verified` | `deliver` | `delivered` |
| `verified` | `fail` | `failed` |
| `verified` | `expire` | `expired` |
| `delivered` | `acknowledge` | `acknowledged` |
| `delivered` | `expire` | `expired` |
| `acknowledged` | `cleanup` | `deleted` |
| `failed` | `cleanup` | `deleted` |
| `expired` | `cleanup` | `deleted` |

全部其他组合抛 `INVALID_STATE_TRANSITION`。精确 HTTP 重试由 reducer 前的幂等账本处理，不给普通事件增加自循环。解除配对和删除账号属于 Task 3 的资源级事务，不伪装成逐附件事件。

- [ ] **Step 7: 固化设备请求 claim 与状态矩阵**

状态和事件：

```ts
type DeviceRequestState =
  | "pending" | "claimed" | "cancel_requested"
  | "succeeded" | "failed" | "denied" | "cancelled"
  | "expired" | "outcome_unknown";

type DeviceRequestEvent =
  | "claim" | "cancel" | "expire"
  | "result_succeeded" | "result_failed" | "result_denied"
  | "result_cancelled" | "result_outcome_unknown"
  | "recover_outcome_unknown";
```

合法转移：

| 当前状态 | 事件 | 下一状态 |
|---|---|---|
| `pending` | `claim` | `claimed` |
| `pending` | `cancel` | `cancelled` |
| `pending` | `expire` | `expired` |
| `claimed` | `cancel` | `cancel_requested` |
| `claimed` | `expire` | `outcome_unknown` |
| `claimed` | `result_succeeded` | `succeeded` |
| `claimed` | `result_failed` | `failed` |
| `claimed` | `result_denied` | `denied` |
| `claimed` | `result_cancelled` | `cancelled` |
| `claimed` | `result_outcome_unknown` | `outcome_unknown` |
| `claimed` | `recover_outcome_unknown` | `outcome_unknown` |
| `cancel_requested` | `expire` | `outcome_unknown` |
| `cancel_requested` | `result_succeeded` | `succeeded` |
| `cancel_requested` | `result_failed` | `failed` |
| `cancel_requested` | `result_denied` | `denied` |
| `cancel_requested` | `result_cancelled` | `cancelled` |
| `cancel_requested` | `result_outcome_unknown` | `outcome_unknown` |
| `cancel_requested` | `recover_outcome_unknown` | `outcome_unknown` |

全部其他组合抛 `INVALID_STATE_TRANSITION`，`outcome_unknown` 是不可被普通结果覆盖的终态。协议增加幂等 claim 操作；Android 必须在任何副作用前 claim。claim/result 的 SQLite、CAS、HTTP route 留给 Task 3/9。

- [ ] **Step 8: 固化向量格式与后续边界**

六个 JSON 顶层统一为：

```json
{
  "formatVersion": "1.0.0",
  "protocolVersion": "2.0",
  "vectorSet": "request-signatures",
  "cases": []
}
```

顶层和 case 均拒绝未知字段；case 只含 `id`、`operation`、`input`、`expected`；ID 在六文件中全局唯一；每文件至少一个 `expected.outcome = "value"` 和一个 `"error"`；二进制用 lowercase hex；时间固定毫秒 UTC；不能出现 `skipped`、`futureOnly` 或无人消费 case。

原迁移计划 Task 2 增加 `golden-vectors.test.ts` 与 dispatched validator 依赖；Task 9 的 `DeviceRequestClient` 增加 claim 接口。Task 3/5 保留数据库、CAS、幂等账本、TTL、SSE store、附件 staging、crash recovery；Task 6 保留双宿主 runner/resultHash；Task 8/9 保留 Keystore、HTTP/SSE parser、真机 TLS 和 claim/result transport。

- [ ] **Step 9: 自审文档并提交**

Run:

```bash
rg -n "T[B]D|T[O]DO|implement[ ]later|类似[ ]Task|适当[ ]错误处理" \
  docs/contracts/gateway-protocol-v2.md \
  docs/superpowers/plans/2026-08-24-modular-plugin-architecture-migration.md \
  docs/superpowers/plans/2026-08-27-gateway-protocol-v2-task2-correction.md
git diff --check
```

Expected: `rg` 无命中，`git diff --check` 退出码 0。

Commit:

```bash
git add docs/contracts/gateway-protocol-v2.md \
  docs/superpowers/plans/2026-08-24-modular-plugin-architecture-migration.md \
  docs/superpowers/plans/2026-08-27-gateway-protocol-v2-task2-correction.md
git commit -m "文档: 固化 Gateway v2 签名与状态契约"
```

### Task 2: 完成 wire ID 与动态 Schema 二阶段验证

**Files:**
- Modify: `gateway-contract/package.json`
- Modify: `gateway-contract/schemas/envelope.schema.json`
- Modify: `gateway-contract/src/schema-registry.ts`
- Create: `gateway-contract/src/dispatched-schema-validator.ts`
- Modify: `gateway-contract/test/schema-registry.test.ts`
- Create: `gateway-contract/test/dispatched-schema-validator.test.ts`

**Interfaces:**
- Produces: `GatewaySchemaName` 新增 `response.success` / `response.failure`
- Produces: `gatewaySubschemaSha256(schema: object): string`
- Produces: `createGatewayDispatchedValidator(entries): GatewayDispatchedValidator`
- Preserves: `schemaFor(name)` / `validateGatewayValue(name, value)` outer-only 行为

- [ ] **Step 1: 写 wire ID 和公开 response 外壳失败测试**

把原测试改为接受：

```text
A-Z a-z 0-9 . _ ~ -
```

并拒绝 space、HTAB、LF、CR、NUL、DEL、逗号、斜杠、百分号和 Unicode。保留长度 0/1/128/129 边界。为 `response.success` 和 `response.failure` 加合法外壳、未知顶层字段和错误字段测试。

- [ ] **Step 2: 写不可变 catalog 与原子验证失败测试**

测试至少覆盖：

- event：可信 event type + digest + 合法 payload 通过；未知 type、digest 不符、payload 未知字段拒绝；payload 自报 type 不参与分派。
- device：外壳通过后从 provider/capability 提取 key；author/plugin/capability/version/digest 任一不匹配拒绝；合法嵌套 parameters 通过。
- success：可信 operation/status/digest 选择 data Schema；错误 operation/status 拒绝。
- failure：从已验证 `error.code` 选择 details Schema；修改 message 不改变分派；未知 code 拒绝。
- catalog：重复 key、digest 不符、非 closed root、无法 strict 编译、未解析外部 `$ref` 构造失败。
- 防御性：构造后修改输入 Schema 或 getter 返回值不影响 validator；错误数组稳定、排序、冻结。
- 顺序：外壳失败时返回外壳错误，不进入动态 key 提取。

- [ ] **Step 3: 运行 RED**

Run:

```bash
PATH="../../.toolchains/node-v24.18.0-linux-x64/bin:../../node_modules/.bin:$PATH" \
  npm --prefix gateway-contract test -- schema-registry.test.ts dispatched-schema-validator.test.ts
```

Expected: FAIL，原因是 ID Schema 仍接受控制字符、response 名称不存在、`dispatched-schema-validator.ts` 不存在。

- [ ] **Step 4: 最小实现 wire ID 与 response 外壳**

`opaqueId` 使用：

```json
{
  "type": "string",
  "minLength": 1,
  "maxLength": 128,
  "pattern": "^[A-Za-z0-9._~-]+$"
}
```

`definitions` 增加：

```ts
"response.success": [envelopeDocument, "success"],
"response.failure": [envelopeDocument, "failure"],
```

旧接口继续只做外壳验证。

- [ ] **Step 5: 最小实现 dispatched validator**

`gatewaySubschemaSha256` 对 self-contained Schema 做 RFC 8785 JCS、UTF-8、SHA-256，返回 `sha256:<64 lowercase hex>`。catalog entry 结构：

```ts
type GatewaySubschemaCatalogEntry = Readonly<{
  key: GatewaySubschemaKey;
  schema: object;
}>;
```

构造器 defensive-clone entry，核对 key digest，strict Ajv 编译并存入不可变内部 map。运行期 API 不接受 Schema/resolver，只接受 `TrustedGatewayDispatch` 与 value；完整验证一次性返回 `ValidationResult`。

- [ ] **Step 6: 运行 GREEN、回归和类型检查**

Run:

```bash
PATH="../../.toolchains/node-v24.18.0-linux-x64/bin:../../node_modules/.bin:$PATH" \
  npm --prefix gateway-contract test -- schema-registry.test.ts dispatched-schema-validator.test.ts
PATH="../../.toolchains/node-v24.18.0-linux-x64/bin:../../node_modules/.bin:$PATH" \
  npm --prefix gateway-contract test
PATH="../../.toolchains/node-v24.18.0-linux-x64/bin:../../node_modules/.bin:$PATH" \
  npm --prefix gateway-contract run typecheck
```

Expected: 全部 PASS，输出无 warning/error。

- [ ] **Step 7: 自审并提交**

检查所有 `validateGatewayValue` 调用方仍只有测试，catalog 无请求级 Schema 输入，`git diff --check` 通过。

Commit:

```bash
git add gateway-contract/package.json \
  gateway-contract/schemas/envelope.schema.json \
  gateway-contract/src/schema-registry.ts \
  gateway-contract/src/dispatched-schema-validator.ts \
  gateway-contract/test/schema-registry.test.ts \
  gateway-contract/test/dispatched-schema-validator.test.ts
git commit -m "修复: 完善 Gateway v2 动态 Schema 验证"
```

### Task 3: 实现原迁移计划 Task 2 的签名、状态机与六组向量

**Files:**
- Create: `gateway-contract/src/request-signature.ts`
- Create: `gateway-contract/src/state-machines.ts`
- Create: `gateway-contract/vectors/request-signatures.json`
- Create: `gateway-contract/vectors/protocol-negotiation.json`
- Create: `gateway-contract/vectors/auth-sessions.json`
- Create: `gateway-contract/vectors/attachments.json`
- Create: `gateway-contract/vectors/sse-events.json`
- Create: `gateway-contract/vectors/device-requests.json`
- Create: `gateway-contract/test/request-signature.test.ts`
- Create: `gateway-contract/test/state-machines.test.ts`
- Create: `gateway-contract/test/golden-vectors.test.ts`

**Interfaces:**
- Produces: `canonicalRequestTarget(target: string): string`
- Produces: `canonicalRequestSignatureInput(input: SignedRequestInput): Uint8Array`
- Produces: `nextAttachmentState(current, event): AttachmentState`
- Produces: `nextDeviceRequestState(current, event): DeviceRequestState`
- Produces: `maximumDeviceRequestQueueSeconds(risk): 86400 | 900 | 0`

- [ ] **Step 1: 写逐字节签名和 target RED 测试**

测试加载 `request-signatures.json`，从 `bodyHex` 独立解码 bytes，断言 canonical target、完整 expectedHex、末尾无 LF。加入：method/ID/timestamp/nonce/body 任一变化改变预像；小写 method、非法 ID、非 canonical nonce/timestamp 拒绝；absolute target、fragment、dot segment、`//`、尾随 `/`、编码 slash/backslash、畸形 `%`、空 query 拒绝；query percent 编码、重复 pair 与排序按契约执行。

- [ ] **Step 2: 运行签名 RED**

Run:

```bash
PATH="../../.toolchains/node-v24.18.0-linux-x64/bin:../../node_modules/.bin:$PATH" \
  npm --prefix gateway-contract test -- request-signature.test.ts
```

Expected: FAIL，原因是 `request-signature.ts` 与向量不存在。

- [ ] **Step 3: 实现 canonical target 与签名预像**

`SignedRequestInput.body` 必须是 `Uint8Array`；不 parse/stringify body。method 是封闭大写 union；ID、timestamp、nonce 逐项 runtime 校验。使用 `node:crypto` 对 exact bytes 做 SHA-256。`canonicalRequestTarget` 把可规范化输入变为 Task 1 契约的 ASCII origin-form；`canonicalRequestSignatureInput` 必须先确认 `input.target === canonicalRequestTarget(input.target)`，非 canonical target 抛 `NON_CANONICAL_TARGET`，再使用固定 10 行 framing。这样客户端先构造并发送 canonical target，Gateway 从 raw request-target 验证同一字符串。

- [ ] **Step 4: 写状态机 RED 测试**

测试完整遍历 Task 1 的状态/事件笛卡尔积：合法表得到精确下一状态；全部其他组合抛 `INVALID_STATE_TRANSITION`；输入字符串无可变状态。测试 `maximumDeviceRequestQueueSeconds`：read/sync `86400`、write `900`、high-privilege-ephemeral `0`。

- [ ] **Step 5: 运行状态机 RED**

Run:

```bash
PATH="../../.toolchains/node-v24.18.0-linux-x64/bin:../../node_modules/.bin:$PATH" \
  npm --prefix gateway-contract test -- state-machines.test.ts
```

Expected: FAIL，原因是 `state-machines.ts` 不存在。

- [ ] **Step 6: 实现纯 reducer**

使用只读 transition table；函数不接受 clock、数据库、重试次数、账号目录或授权对象。未知 runtime cast 的 state/event 也抛 `INVALID_STATE_TRANSITION`。`outcome_unknown` 和其他终态没有普通出边。

- [ ] **Step 7: 创建六组闭合向量并写消费门禁**

每个文件使用 Task 1 的 `1.0.0` 顶层结构。operation 至少覆盖：

```text
request.target
request.signature
schema.validate
schema.validate_dispatched
attachment.transition
device.transition
device.maximum_queue_seconds
```

`golden-vectors.test.ts` 验证六文件存在、顶层/case 闭合、vectorSet 与文件名一致、case ID 全局唯一、每文件有 value/error、每个 operation 有明确消费者；不得仅 grep 文本。`protocol-negotiation` 和 `auth-sessions` 的 Schema cases 调用 `validateGatewayValue`；SSE/device 动态 cases 调用 `GatewayDispatchedValidator`；状态与签名 cases 分别调用生产函数。

- [ ] **Step 8: 运行 Task 2 完整验证**

Run:

```bash
PATH="../../.toolchains/node-v24.18.0-linux-x64/bin:../../node_modules/.bin:$PATH" \
  npm --prefix gateway-contract test
PATH="../../.toolchains/node-v24.18.0-linux-x64/bin:../../node_modules/.bin:$PATH" \
  npm --prefix gateway-contract run typecheck
PATH="../../.toolchains/node-v24.18.0-linux-x64/bin:../../node_modules/.bin:$PATH" \
  npx vitest run --exclude '.worktrees/**'
PATH="../../.toolchains/node-v24.18.0-linux-x64/bin:../../node_modules/.bin:$PATH" \
  npx tsc --noEmit
git diff --check
```

Expected: 全部 PASS；根测试不得扫描其他 worktree；不把 TypeScript 本地向量宣称为 Python/Kotlin/Android 一致性或真机证据。

- [ ] **Step 9: 自审并提交**

确认只改 Task 2 列出的 `gateway-contract` 新文件，未进入 `integrations/openclaw`、Hermes、Android、Bridge 或 Tailscale。

Commit:

```bash
git add gateway-contract/src/request-signature.ts \
  gateway-contract/src/state-machines.ts \
  gateway-contract/vectors \
  gateway-contract/test/request-signature.test.ts \
  gateway-contract/test/state-machines.test.ts \
  gateway-contract/test/golden-vectors.test.ts
git commit -m "新增: 固化 v2 签名与状态机向量"
```

## 本计划完成边界

完成三个 Task、逐 Task 独立审查和最终范围审查后停止。不得创建 `integrations/openclaw/` 文件，不得声称 Task 3、双宿主一致性、Android HTTP/SSE、真机 TLS 或生产部署已经完成。
