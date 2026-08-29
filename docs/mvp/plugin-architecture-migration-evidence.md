# 模块化插件架构迁移 — 自检清单与证据

本文对应 `docs/superpowers/plans/2026-08-24-modular-plugin-architecture-migration.md`
末尾的"自检清单"。它记录**已经实际跑过什么命令、看到什么结果**，以及哪些条目
还没有证据。

状态约定：

- `VERIFIED` — 本次实际运行命令并得到通过结果；
- `PENDING` — 尚未运行，或属于尚未完成的 Task；
- `FINDING` — 运行时发现需要处理的问题。

## 运行环境

系统 Node 是 v26.7.0，超出仓库 `engines`（`>=24.18.0 <25`）。**所有 Node 命令
必须通过 `./tools/run-node24`（v24.18.0）执行**；在 v26 下 `bridge-runtime` 的
5 个 sqlite 测试会失败，那是与改动无关的误报。

Hermes 侧使用 `python3 -m venv` 自建的虚拟环境（系统无 pip，用
`python3 -m ensurepip` 引导后 `pip install pytest`）。

## 清单

| # | 条目 | 状态 | 证据 |
|---|---|---|---|
| 1 | 总规格每条验收标准都有测试或人工证据路径 | PENDING | 需 Task 13/14/15 收尾后统一核对 |
| 2 | 没有通过 v1 网络兼容、密钥复制或队列复制绕过重新配对 | VERIFIED | 见下方"导出不携带身份" |
| 3 | App 可在零插件、零 Companion 状态完成登录、对话和附件 | PENDING | Android 门禁（`connectedDebugAndroidTest`）未运行 |
| 4 | 三个参考插件与第三方插件走相同签名、安装和授权路径 | PENDING | 属 Task 13/14 |
| 5 | Tailscale Companion 无 Gateway 凭据，主 App 终止 TLS | PENDING | 属 Task 14 |
| 6 | 两个 Gateway 实现使用同一向量且结果哈希一致 | VERIFIED | `npm run gateway:v2:conformance` → 3 passed |
| 7 | 每个账号的数据库、密钥、附件、队列和审计路径可证明互异 | PENDING | 宿主运行时行为，属 Task 13/15 |
| 8 | `rg -n "fixture\|mobile-bridge-v1\|latest-stable"` 在生产路径无命中 | FINDING | 见下方"扫描结果" |
| 9 | 计划文档无 `TODO`/`TBD`/占位文字残留 | VERIFIED | 见下方"计划文档扫描" |

## Task 16：v1 配置导出与旧 Bridge 冻结

工具链：全部通过 `./tools/run-node24`（v24.18.0）执行。

| 命令 | 结果 |
|---|---|
| `npm --prefix bridge-runtime test -- export-v2-bootstrap.test.ts` | Step 2 RED：`Cannot find module '../tools/export-v2-bootstrap.js'` |
| 同上（实现后） | Step 4 GREEN：1 file / 26 tests passed |
| `npm --prefix bridge-runtime run typecheck:tools` | 退出码 0 |
| `npx vitest run`（根套件） | 81 files / 782 tests passed（含新增 26 项） |
| `npm run typecheck`（根） | 退出码 0 |
| `npm run gateway:v2:conformance` | 1 file / 3 tests passed，退出码 0 |
| `npm --prefix bridge-runtime test` | 16 files / 87 tests passed |
| `python -m pytest integrations/hermes/tests -q` | 91 passed |
| `cd apps/android && ./gradlew check connectedDebugAndroidTest` | **未运行** — 需要 UTF-8 locale、Android SDK 与已连接设备，属 Task 13/14/15 门禁 |

### 红灯确实来自缺失的实现，而不是测试写错

实现前只存在测试文件，vitest 报 `Cannot find module '../tools/export-v2-bootstrap.js'`。

### 负向验证（证明门禁不是空转）

对实现做临时变异后重跑测试，确认每条防线都真的拦得住：

| 变异 | 失败项 |
|---|---|
| 移除投影层与写入层的 Schema 校验 + 秘密扫描 | 3 项失败：秘密字样失败关闭、夹带凭据地址被拒、指纹形状被拒 |
| 移除输出路径的符号链接判定（一律报 `OUTPUT_EXISTS`） | 2 项失败：写到符号链接被拒、不跟随已存在符号链接目标 |
| 上述变异均已还原，重跑 26/26 通过。 |

### 导出不携带身份（清单第 2 条）

`bridge-runtime/tools/export-v2-bootstrap.ts` 的只读端口只打开
`<data-dir>/gateways.json` 与 `<data-dir>/plugins.json`；数据库、密钥、队列、
附件与正文文件按构造就不可能被打开。

白名单之外的一切都在投影阶段被丢弃：

- 导出：`displayName`、`baseUrl`、`tlsSpkiSha256`、`id`、`authorKeyId`；
- 排除：密码、refresh、配对密钥、队列、未确认操作、正文、附件、数据库文件、主密钥。

测试用一个哨兵值验证"未读取"不是巧合：把 `SENTINEL-CANARY-VALUE` 写进数据目录
里的 `credentials.json`、`bridge.sqlite`、`master.key`，导出产物中不含该值。

产物落盘前必须同时通过
`gateway-contract/schemas/v1-bootstrap-export.schema.json` 校验与秘密字样扫描，
因此连"白名单字段的值里夹带秘密"也会失败关闭。落盘拒绝符号链接与已存在文件，
权限固定 `0600`（实测 `stat -c '%a'` 为 `600`）。

导入侧 `describeV2BootstrapImport` 只产出本地 profile 与插件发现清单，并固定带
`需要重新配对/授权` 提示——没有任何可以绕过重新配对的凭据被带走。

### CLI 冒烟

```
$ ./tools/run-node24 npm --prefix bridge-runtime run export:v2-bootstrap -- \
    --data-dir <v1 目录> --out <输出>
V1_BOOTSTRAP_EXPORT_OK out=<输出> gateways=1 plugins=1 schema=1.0
需要重新配对/授权：本次导出不含凭据、刷新凭据、配对私钥、队列、未确认操作、正文、附件、数据库与主密钥，导入端必须重新配对并重新授权。

$ 再次执行同一命令
V1_BOOTSTRAP_EXPORT_FAIL code=V1_BOOTSTRAP_OUTPUT_EXISTS
```

## 计划文档扫描（清单第 9 条）

```
$ rg -n "T[B]D|T[O]DO|implement[ ]later|类似[ ]Task|适当[ ]错误处理" \
    docs/superpowers/plans/2026-08-24-modular-plugin-architecture-migration.md
（无命中）
```

## 扫描结果（清单第 8 条，FINDING）

```
$ rg -n "mobile-bridge-v1|latest-stable" integrations gateway-contract apps/android
integrations/hermes/plugin-manifest.json:3:  "upstream": { "release": "latest-stable", ... }
integrations/hermes/plugin-manifest.json:4:  "protocolVersion": "mobile-bridge-v1",
```

- `apps/android` 无任何 `mobile-bridge-v1` / `latest-stable` 命中。
- `fixture` 在生产源码（`apps/android/gateway-client/src/main/...`、
  `integrations/openclaw/src/core/shared-vectors.ts`、
  `integrations/hermes/agent_life_gateway/core.py`）里的命中全部指向**共享分派
  Schema/fixture registry**（`gateway-core-fixtures-v1`），这是跨语言一致性门禁
  有意使用的契约校验机制，不是拿 mock 冒充生产实现，不计为违规。
- **待处理**：`integrations/hermes/plugin-manifest.json` 仍写着
  `protocolVersion: "mobile-bridge-v1"` 与 `release: "latest-stable"`，且该文件
  不在 `legacy/` 下，没有"历史产物"标记。清单要求"历史/legacy 命中有明确标记"，
  因此它需要二选一：迁入 `legacy/` 并标注，或更新为 v2 协议版本与锁定来源。
  这一项超出 Task 16 的文件范围，交由 integrations 侧处理。
