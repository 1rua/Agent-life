# Agent-Life 当前进度与交接说明

更新时间：2026-08-11
用途：供后续 agent 接手实现、复审和生产化，不作为生产发布批准。

## 1. 当前结论

当前已完成的是一套可复验的 P0a 协议参考实现、Android/Bridge/Agent
MVP source slice 和 SDK-free 合同测试。主 worktree 与协议 worktree 均已
提交且干净，但协议实现仍位于独立分支，尚未合入 `main` 的发布线。

当前状态可以表述为：

> **参考协议与 source/static MVP GREEN；生产持久化、真实 Android provider、
> Tailscale AAR、设备验证、部署与依赖锁仍未完成。**

不要把以下结果写成 APK、真实设备、生产 Bridge 或生产 Agent Gateway 已经
可用。

本文件末尾的“根目录同步快照”覆盖本文早先关于协议仍只存在于独立
worktree 的历史描述；历史段落保留，便于追溯同步前的状态。

## 2. Worktree、提交与验证入口

| 区域 | 路径/分支 | 当前提交 | 状态 |
| --- | --- | --- | --- |
| Android/Bridge/Agent MVP | 主 worktree `main` | `9a69556` | 干净；source/static slice 已提交 |
| P0a protocol | `.worktrees/p0a-protocol-security-model` / `feat/p0a-protocol-security-model` | `c75a96a` + `1b84e60` | 干净；Task 5/6/7 与 Task 9 bounded gate 已提交 |

主分支的 `main` 不包含协议分支的新增提交。需要联调或发布时，必须明确
执行协议分支合并/cherry-pick，并重新跑全量验证，不能只引用另一个 worktree
的测试结果。

已验证命令和结果：

```text
# 主 worktree
e2e/mvp/run-readiness.sh --sdk-free
  16 Vitest files / 98 tests
  Android host-static 48/48
  SDK_FREE_READINESS_PASS / SDK_FREE_GATE_PASS

# 协议 worktree
./tools/run-node24 npm test -- --run
  32 test files / 334 tests
./tools/run-node24 npm run typecheck
  passed
git diff --check
  passed

# TSNET 输入供应链门禁
bash apps/android/tailnet-core/tools/test_verify_tsnet_aar_inputs.sh
  passed（验证缺失 source 会 fail-closed）
```

SDK-free 结果只证明确定性合同和静态门禁；readiness 输出仍保留生产阻断项。

## 3. 已完成任务

### 3.1 P0a Task 1–4：协议基础

已完成并累计进入协议分支：

- Node 24 协议 workspace、版本与可复验 launcher；
- 威胁模型、字段合同和安全不变量 registry；
- canonical JSON/wire bytes、hash、签名、profile/encoding/crypto 基础；
- enrollment、ticket/CAS、pairing transcript、message/schema/version catalog、
  downgrade-resistant negotiation；
- closed schema、unknown-field rejection、public error mapping、冻结/不可变
  结构和异步 CAS 证据。

这些任务不是当前主要缺口，后续变更应避免绕开既有 P0a authority。

### 3.2 Task 5：transport/security reference implementation

已完成并提交：

- signed control envelope、message/header/envelope schema 与累计 registry；
- device/adapter admission、pairing binding、generation fencing；
- replay admission、持久 recovery intent、receipt/intent accounting、safe
  compaction、claim-id uniqueness 与 restart integrity checks；
- opaque device/adapter authority、lease、generation 和 branch-owned backend；
- outbound envelope serialisation、动态 key re-check、rotation prepare/journal/
  ACK/activation/grace、guarded credential-generation seam；
- `resumeJournal` trusted reconciler、journal/outbound/replay/key-ring 全量一致性
  校验与 crash-cut recovery；
- stage precedence、transcript/JWK/thumbprint/direction/type/self-verification
  相关测试。

这是确定性 in-memory/reference backend，不是生产数据库或真实远端 Bridge。

### 3.3 Task 6：capability、authorization、grant、egress

已完成 reference-contract slice：

- closed capability registry 和 `shell.root` 等越权能力拒绝；
- capability/notification package-field closed filter；通知允许按应用和字段
  选择，Unicode code-point 排序、重复/未知/空过滤器 fail-closed；
- authorization revision、pairing generation、scope revision、pause/resume、
  emergency stop、opaque local-user resume；
- data-query grant、filter hash/proposal digest、same-human expansion、pending
  ACK reducer；
- grant state + ACK outbox + branded replay association 的原子 reference store，
  拒绝跨 proposal claim reuse；
- egress zero-retention evidence、provider-object observation fail-closed；
- risk policy floors（含 SMS floor/L4 deny/B1 enhanced session）。

当前 grant/store 仍是 reference/in-memory seam，不能宣称生产授权数据库或
真实设备读取已接通。

### 3.4 Task 7：operation、receipt、migration reference slice

已完成：

- closed operation reducer、合法状态边、`WAIT_READ <= 900s`、terminal immutability、
  monotonic revision；
- execution ledger：idempotent registration、single claim、receipt 校验、first
  result retention、`result_unknown` recovery 且不自动重执行；
- operation/error/migration schemas、catalog/error registry、precedence model、
  v0.9 conformance fixtures；
- quota/accounting model、per-space ledger partition、tombstone charge reference；
- D1–D4 已由用户确认并记录：永久 tombstone/bounded polling、精确 quota、ACK
  retention、terminal `result_unknown` 无自动 retry。

这些是 canonical/reference artifacts；durable replay adapter、数据库迁移、真实
ACK/compaction wiring 尚未完成。

### 3.5 Task 9：device event/ACK bounded pre-replay gate

已完成并提交 `1b84e60`：

- `device_event` lifetime `24h`、`event_ack` lifetime `5min`、replay class
  `task5_default`；
- `createTask9PreReplayIntegrityGate`；基于 authenticated device context 与
  opaque capture/ACK authority resolver；
- device event mismatch matrix：tenant、human、device、source epoch/capability、
  pairing generation、authorization epoch、scope map missing/extra/key/value；
- ACK mismatch matrix：tenant、human、device、source epoch/capability、cursor；
- signature → binding → authorization → Task9 gate → replay 的 precedence；早期
 失败均断言 resolver/replay 不被调用；
- matching device event/ACK allow path、future unknown message passthrough、
  forged ACK authority 的 `INVALID` 与合法 authority mismatch 的区分。

验证：Task9 focused 30/30、event+registry 90/90、协议全量 32/334。

这只完成 bounded pre-replay authority gate，不等于生产 event collector、ACK
database、cursor buffering、subscription router 或跨语言 vectors 已完成。

### 3.6 主 worktree：WP-00–WP-10 source/static MVP

- **WP-00**：依赖 lock 文档、fail-closed validator、lock test；7 行仍是 pending。
- **WP-01**：notification policy/record/API、assistant-chat closed schemas 和
  wire codec；tombstone malformed fields 已拒绝。
- **WP-02/WP-05**：双 APK、无系统 VPN/no-VPN forbidden-surface gate、userspace
  transport seam、persistent generation/reconnect/no-backup state、fake/real-shaped
  transport adapter；不声明 `VpnService`、系统路由/DNS、generic socket。
- **WP-03**：通知 policy evaluator、NotificationListener collector、metadata/content
  过滤、tombstone/loss marker、AES-GCM outbox、ACK/restart recovery、lifecycle
  `NotificationRuntime`；动态 revoke 和 outbox persistence failure 已有静态回归。
- **Capability/provider/control ports**：封闭 capability enum、typed provider
  contracts、on-demand/auto-send authorization、typed writes/screen actions/
  restricted command port；均为 source-only，不是系统 provider/执行器。
- **WP-04/WP-06**：Bridge pairing/fencing、notification query/subscribe/unsubscribe、
  assistant chat、durable-store port、local file-backed store、operation-claim
  dispatcher、BigInt reopen 和 crash pending reclaim；服务仍 process-local/local adapter。
- **WP-07**：Hermes/OpenClaw adapter、唯一 plugin manifest/profile、notification
  query/subscribe/auto-send、ACK/loss marker、assistant text + selected image/file
  metadata、zero-retention fail-closed checks、`android-device-bridge` skill。
- **WP-08**：assistant-holder/default-assistant manifest、voice interaction seam、
  bounded text/opaque-grant handoff、PHOTO_PICKER/SAF attachment selection contract、
  default-deny gate；没有真实 SAF upload/IPC production implementation。
- **WP-09**：SDK-free smoke/readiness launcher和生产 fail-closed release gate。
- **WP-10/M1.1**：artifact ticket、digest/PoP、MIME/size、commit/delete/reclaim
  contract及 Android granted-selection/encrypted-copy/delete source seam；不包含
  真实对象存储、scanner 或设备上传。

## 4. 未完成的代码/协议/产品工作（不把环境缺失混入）

### P0：必须先完成才能称为生产 MVP

1. **协议分支与主发布线合并**
   - 将 `c75a96a`、`1b84e60` 合入主发布线；解决主 worktree 与 protocol worktree
     的目录/测试入口差异。
   - 合并后重新跑协议全量、main smoke、typecheck、diff-check，并更新 lock/CI。

2. **Bridge 生产持久化与认证 ingress**
   - 当前 `bridge-runtime` 只有 local `fs/promises` adapter、operation claim
     dispatcher 与 source ports。
   - 仍缺：生产 SQLite/数据库 driver、migration execution、backup/restore drill、
     multi-process transaction/lease、authenticated Tailscale ingress、health/
     readiness deployment、pairing/notification/subscription durable stores。
   - 需要将 notification query/subscribe、assistant chat、operation claim、ACK 和
     replay association 接入同一可恢复事务边界，不能只持久化 `operation.claims`。

3. **Android NotificationRuntime 的真实 composition**
   - 当前 listener lifecycle 默认 factory 是 `NO_AUTHORIZATION` 且 `outbox=null`；
     只有外部注入 collector/outbox 才会产生自动发送。
   - 需要安装真实 local policy/authorization revision、encrypted outbox、Bridge
     dispatcher、ACK/retry/loss-marker/backpressure，并通过设备测试证明动态撤权
     和进程死亡恢复。

4. **Android 真实数据 provider**
   - SMS、电话、联系人、剪贴板、位置、Health Connect、传感器、日历、闹钟、
     当前窗口（Accessibility）、当前屏幕（MediaProjection）目前只有 typed
     contracts/normalizers 或 capability ports，没有 Android API、权限生命周期、
     store、同步和设备验证。
   - 通知已具备 policy/collector/outbox contract，但仍需真实 app install、权限、
     encrypted persistence 和 Bridge egress wiring。

5. **Agent 主动请求与设备控制执行器**
   - capability authorization seam 已存在，但 agent→device HTTP/tsnet request
     routing、grant persistence、per-operation approval、audit、revoke/replay
     recovery 尚未贯通。
   - typed control port 尚未接 Accessibility service、MediaProjection、后台受限
     command executor。按已确认约束，不能实现 root shell、generic shell、任意 exec、
     Shizuku/Device Owner 绕过或隐式 automation DSL。

6. **Assistant 生产化**
   - assistant-holder 的 manifest/contracts/attachment handoff 已有，但默认助手
     UI、真实 VoiceInteractionService/RoleManager 流程、主 APK 与 holder 的受控
     IPC/授权实现、真实图片/文件读取、digest/加密/上传尚未完成。

### P1：协议与 Agent 端剩余工作

1. **Task 7 pending artifacts**（以
   `protocol/test-only/task7-independent-artifacts-v1.json` 为准）：
   - `intent-metadata-device-v1.json`；
   - `intent-metadata-adapter-v1.json`；
   - `intent-metadata-adapter-empty-scope-v1.json`；
   - `operation/v1/receipt-replay-vectors.json`；
   - `protocol/registries/v1/messages.json` 的 Task7 13 rows；
   - replay intent/receipt replay rows 与 durable ACK/compaction integration。

2. **Task 9 production contracts**：
   - production capture-authority read port/authority database；
   - durable ACK store、cursor buffer、recovery ordering 和 server subscription/router；
   - cross-language event/ACK canonical vectors（当前 gate 是 TypeScript reference
     test）；
   - event collector/Bridge route/agent subscription 的真实端到端实现。

3. **Task 5/6 durable adapters**：
   - protocol 目前仍依赖 trusted injected reconciler、key-ring、replay store、grant
     store 和 egress evidence ports；需要生产数据库事务和 crash/restart evidence。
   - adapter remote rotation、Task6 grant/ACK/replay 与 Bridge state 的跨进程原子
     事务仍需真实 adapter/vector。

4. **Agent server production profile**：
   - Hermes/OpenClaw 当前是 adapter/plugin/skill seam；仍需接入真实官方版本/profile、
     authenticated binding、workspace/session/job lifecycle、reconnect fencing 和
     production zero-retention provider evidence。
   - 用户自定义触发规则应继续由 agent 定义；手机端只负责授权、同步、执行已授权
     typed command，不要在 app 中新增第二套规则 DSL。

## 5. 外部环境/依赖缺失（单独列出，不要误写成代码已完成）

这些不是本次 source slice 的代码修复项，但不满足时不能宣称生产：

- `docs/mvp/mvp-dependency-lock.md` 的 7 行全部仍为 `pending`：ANDROID、TSNET、
  BRIDGE、HERMES、OPENCLAW、MODEL、ARTIFACT；
- 本机已有 JDK 17、Android SDK platform/build-tools、Go/gomobile 和 `adb`，但没有
  可用的锁定 Gradle 8.9 distribution、完整 NDK、reviewed Tailscale source checkout
  或 `tsnet-android` AAR；
- 当前没有 API 34+ connected physical/emulator device；
- 没有生产 Bridge database/secret store/deployment、认证 ingress、对象存储/scanner、
  官方 Hermes/OpenClaw release lock 或模型 provider zero-retention deployment evidence。

TSNET 供应链输入门禁位于：
`apps/android/tailnet-core/tools/verify-tsnet-aar-inputs.sh`。缺 source、commit、
module、SDK/NDK 或 clean Git checkout 时必须 fail-closed，不能下载/猜测依赖。

## 6. 后续 agent 接手顺序

建议不要同时修改同一层，按以下顺序拆 agent：

| 顺序 | 责任 | 首个入口 | 完成判据 |
| --- | --- | --- | --- |
| 1 | 协议合并/发布线 | 两个 worktree、`c75a96a`、`1b84e60` | 合并后 protocol/main smoke 全绿，提交关系清晰 |
| 2 | Task7 pending artifacts | `task7-independent-artifacts-v1.json` | 5 个 pending artifacts 真实落地并有 RED/GREEN/vector review |
| 3 | Task9 durable event path | `event-contract.ts`、`replay-window.ts`、Bridge stores | authority/ACK/cursor/router durable transaction + cross-language vectors |
| 4 | Bridge production adapter | `bridge-runtime/src/composition.ts`、`durable-operation-dispatcher.ts` | DB/migrations/ingress/health/backup/restart evidence |
| 5 | Android notification runtime | `NotificationRuntime.kt`、`AndroidNotificationCollector.kt`、outbox | real policy/grant/outbox/Bridge wiring + device revoke/restart test |
| 6 | Android providers/control | `capability-ports`、`CapabilityProviderContracts.kt`、`control-ports` | per-capability permission/provider and accessibility/screen control tests |
| 7 | TSNET and device gate | `tailnet-core`、TSNET verifier、P0t evidence | locked AAR, no-VPN connected tests, process-death/network-switch/Doze evidence |
| 8 | Agent release integration | `integrations/*`、skill、dependency lock | official profiles, authenticated event/query/ACK flow, zero-retention evidence |

## 7. 交接注意事项

- 不要把 `SDK_FREE_PASS`、Vitest 通过或 source-only Kotlin static tests写成生产
  readiness；必须保留 release blockers。
- 不要在主分支重写 P0a protocol authority；先合并协议分支并以 protocol tests
  作为唯一安全语义来源。
- 不要引入系统 VPN、`VpnService`、TUN、系统 route/DNS、public HTTPS fallback、
  root shell 或 generic execution。
- 不要把 ignored `.superpowers/sdd/...` 报告当作 Git 交付物；它们是审计证据。
  生产交接应以提交、测试命令和本文件为准。
- Task9 旧的 `task9-readiness-gap-inventory-v1.json` 仍保留历史 blocked-contract
  inventory；当前 bounded pre-replay 实现以 `1b84e60` 和
  `task-9-pre-replay-report.md` 为准，旧 inventory 不应覆盖新提交的 G7 gate 结果。

## 8. 最小复核命令

```bash
# main
e2e/mvp/run-readiness.sh --sdk-free
python3 -m unittest discover -s apps/android/tools -p 'test_*.py'

# protocol
cd .worktrees/p0a-protocol-security-model
./tools/run-node24 npm test -- --run
./tools/run-node24 npm run typecheck
git diff --check
```

任何后续 agent 在宣称“完成”前，应同时更新本文件对应表项、追加测试证据，
并记录仍未通过的生产 gate。

## 9. 根目录同步快照（2026-08-11）

本次已将 worktree 中“已有提交、路径不覆盖根目录用户未提交改动”的进度
同步到根目录 `main`。同步提交链为：

| 域 | 根目录提交 | 同步内容 | 当前证据/边界 |
| --- | --- | --- | --- |
| P0a 协议整合 | `5ddfbe6`（来源 `08ac7eb`） | 保留 P0a 双亲历史、Node 24 launcher、协议 schema/registry/tests、clean-clone inventory 修复 | Task 1 review clean；需在根目录重新跑协议全量与 readiness |
| Task 7 artifacts | `b2372b6`（来源 `23fec97`） | 5 个 canonical artifacts、13 条 registry rows、replay classification/vector tests | focused 93/93、typecheck 为 agent 证据；Node 22 engine warning，需根目录 fresh verify |
| Bridge durable slice | `b4132a4`（来源 `ac6d38b`） | pairing/notification/subscription/replay durable repositories、fenced composition、backup/restore drill | agent 证据 runtime 39/39、Bridge+contract 69/69；SQLite/secret-store/lease/部署仍 pending |
| Android notification | `b7a7b40..5c8d9d7`（来源 `4a3107e..4cfb124`） | 修复 Kotlin baseline、local authority persistence、AES-GCM outbox、ACK/retry dispatcher、Application/Keystore composition | 已提交但尚无独立 review report；真实设备/Android release gate 未宣称 |

根目录仍保留用户原有的 4 个未提交编译环境改动：

- `apps/android/gradlew`；
- `apps/android/core-model/src/main/kotlin/com/agentlife/core/model/TransportContracts.kt`；
- `apps/android/tailnet-core/src/main/kotlin/com/agentlife/tailnet/core/TailscaleUserspaceCore.kt`；
- `apps/android/tools/test_transport_boundary.py`。

以下进度仍只存在于 worktree，未同步到根目录：

1. **Task 9 durable event/ACK/cursor/router**：`agent/task9-durable` 已有
   `f48c6e5`、`1014b4d`，但当前 worktree 存在未解决的 `composition.ts`、
   `index.ts`、`run-smoke.sh` 冲突；不得 cherry-pick 这条未收敛链。
2. **Android providers/control**：`agent/android-provider-control` 仍有
   未提交的 `apps/android/platform-adapters/`、settings/manifest/ports/static
   gate 改动；provider agent 因额度限制中断，尚无可同步 commit 或完成报告。

同步后的根目录状态仍不是生产发布批准：

- `MVP-DEP-ANDROID/TSNET/BRIDGE/HERMES/OPENCLAW/MODEL/ARTIFACT` 7 行仍为
  `pending`；
- 无 API 34+ connected device、无锁定 tsnet AAR、无真实 Bridge DB/secret-store/
  authenticated ingress/deployment evidence；
- Task 9 durable path、Android provider/control、物理 P0t 与 Agent/Assistant
  production integration 仍未完成。
