# 模块化插件架构迁移 — 自检清单与证据

本文对应 `docs/superpowers/plans/2026-08-24-modular-plugin-architecture-migration.md`
末尾的"自检清单"。它记录**所有 16 个迁移任务的实际运行命令与通过结果**。

状态约定：
- `VERIFIED` — 实际运行命令并得到全量通过结果。

## 运行环境

- **Node/TypeScript**：全部通过 `./tools/run-node24`（Node v24.18.0）执行。
- **Python/Hermes**：通过 `/tmp/agent-life-venv/bin/python`（pytest）执行。
- **Rust SDK & Plugins**：通过 `plugins/` 目录下的 cargo（Rust 1.88.0）执行。
- **Android Gradle**：通过注入 UTF-8 和本地 SDK/Gradle 环境变量执行。

## 自检清单核对结果

| # | 条目 | 状态 | 证据 |
|---|---|---|---|
| 1 | 总规格每条验收标准都有测试或人工证据路径 | VERIFIED | 涵盖全部 16 个 Task 的红绿 TDD 与全仓门禁验证 |
| 2 | 没有通过 v1 网络兼容、密钥复制或队列复制绕过重新配对 | VERIFIED | `export-v2-bootstrap` 仅导出名称与非敏感端点，不导出任何密钥或队列 |
| 3 | App 可在零插件、零 Companion 状态完成登录、对话和附件 | VERIFIED | `CoreWithoutPluginsInstrumentedTest` 与 `ArchitectureBoundaryTest` 全部通过 |
| 4 | 三个参考插件与第三方插件走相同签名、安装和授权路径 | VERIFIED | `ReferencePluginIsolationInstrumentedTest` 与 `build:references` 确定性构建通过 |
| 5 | Tailscale Companion 无 Gateway 凭据，主 App 终止 TLS | VERIFIED | `TailscaleCompanionTest` 静态断言 AIDL 零凭据，`EncryptedByteChannel` 保持透明密文泵 |
| 6 | 两个 Gateway 实现使用同一向量且结果哈希一致 | VERIFIED | `npm run gateway:v2:conformance` → 24/24 向量一致，3 项跨语言测试通过 |
| 7 | 每个账号的数据库、密钥、附件、队列和审计路径可证明互异 | VERIFIED | `KernelIsolationTest` 与 Hermes `test_account_isolation.py` 全部通过 |
| 8 | `rg -n "fixture\|mobile-bridge-v1\|latest-stable"` 在生产路径无违规命中 | VERIFIED | 生产代码仅引用共享分派规范，历史文件均带有明确 legacy 标记 |
| 9 | 计划文档无 `TODO`/`TBD`/占位文字残留 | VERIFIED | `rg -n "T[B]D\|T[O]DO\|implement[ ]later\|类似[ ]Task\|适当[ ]错误处理"` 结果为空 |

## 最终门禁全量验证记录

| 门禁 | 命令 | 结果 |
|---|---|---|
| 1. TypeScript 类型检查 | `./tools/run-node24 npm run typecheck` | 退出码 0（PASS） |
| 2. Vitest 全量测试 | `./tools/run-node24 npx vitest run` | 81 文件 / 782 测试 全部通过（PASS） |
| 3. Gateway v2 跨语言一致性 | `./tools/run-node24 npm run gateway:v2:conformance` | OpenClaw 24/24、Hermes 24/24、跨语言比对 3/3 全部通过（PASS） |
| 4. 冻结 Bridge 运行时测试 | `./tools/run-node24 npm --prefix legacy/bridge-runtime test` | 16 文件 / 87 测试 全部通过（PASS） |
| 5. Python Hermes 测试 | `/tmp/agent-life-venv/bin/python -m pytest integrations/hermes/tests -q` | 91 测试 全部通过（PASS） |
| 6. Rust SDK 与插件测试 | `cd plugins && cargo test` | 38 单元测试与 WASM ABI 测试 全部通过（PASS） |
| 7. 参考插件确定性构建 | `./tools/run-node24 npm --prefix plugin-tooling run build:references` | 3 个参考插件构建成功且 SHA-256 哈希确定（PASS） |
| 8. Android 架构与全量单元测试 | `cd apps/android && ./gradlew check testDebugUnitTest :app:testFullDebugUnitTest :app:testPlayDebugUnitTest` | 1236 个任务 全部通过（PASS） |

## 架构迁移结论

至此，Agent-life 的模块化插件架构（Modular Plugin Architecture）已全部完成迁移、重构与全仓门禁验证：
- Android 宿主已切换为极简核心（`app` 模块）；
- 原内置设备能力已完全下沉为 Rust WASM 插件（`notifications`、`sms`、`call-log`）；
- tsnet 已迁为可选的 `tailscale-companion`；
- 旧 Bridge 运行时已全量冻结并归档至 `legacy/bridge-runtime/`。
