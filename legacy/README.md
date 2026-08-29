# legacy — 被取代的设计与冻结代码

本目录只保存**已被 accepted 规格/ADR 取代**的历史产物。进入这里的代码不再是
产品的一部分，也不参与任何生产门禁的可信链。

## 冻结政策

依据 [ADR 0037](../docs/adr/0037-supersede-standalone-bridge-design-and-freeze-legacy-runtime.md)：

- 2026-08-08 总设计、2026-08-11 Tailscale 默认链路决定、2026-08-17 Bridge
  生产部署设计由模块化插件架构取代；
- 独立 Bridge 设计被取代，不再发布或扩展 Docker/systemd 独立服务；
- 旧 `bridge-runtime` 在 Gateway Protocol v2 与两个宿主适配器通过一致性套件前
  只作为**冻结的迁移来源**；可复用的安全语义、存储逻辑与测试被提取后迁入本目录。

因此对本目录的任何改动只允许两类：

1. **安全修复**——修复仍会影响迁移读取路径的安全缺陷；
2. **迁移读取**——为 v1 → v2 迁移提供只读导出能力。

明确禁止：

- 新增功能、新增协议版本、新增部署形态（Docker/systemd/ingress）；
- 恢复或扩展 v1 网络线协议兼容层（ADR 0038：v1 → v2 必须重新配对）；
- 把本目录代码当作生产依赖或生产证据引用。

## 当前内容

| 路径 | 说明 |
|---|---|
| `integrations/hermes-v1/adapter.ts` | v1 时期 Hermes 适配器的冻结副本，仅作历史参考。 |

## 待办：`bridge-runtime/` 尚未迁入 `legacy/`

计划 `docs/superpowers/plans/2026-08-24-modular-plugin-architecture-migration.md`
的 Task 16 把 `bridge-runtime/` 移到 `legacy/bridge-runtime/` 标记为
**"Move after all gates pass"**。截至本次提交**尚未执行该移动**，原因：

1. Task 13/14/15 尚未完成，计划 Step 4 要求的 Android 门禁
   （`cd apps/android && ./gradlew check connectedDebugAndroidTest`）未运行，
   门禁未全绿；
2. `bridge-runtime` 目前仍是根测试套件的组成部分（16 个测试文件 / 87 个测试，
   含 `test/export-v2-bootstrap.test.ts`）。此时移动会直接让这些测试脱离门禁，
   也会让后续 Task 的回归比对失去基线；
3. 移动还必须同步更新根脚本与 `vitest` 的扫描范围，属于"再次运行根测试验证"
   的收尾动作，应在 Android 门禁通过之后一次性完成。

**执行条件**：Task 13/14/15 完成且下列命令全部通过后，才可移动：

```bash
./tools/run-node24 npx vitest run              # 根套件 PASS
./tools/run-node24 npm run typecheck           # PASS
./tools/run-node24 npm run gateway:v2:conformance  # PASS
cd apps/android && ./gradlew check connectedDebugAndroidTest  # PASS
```

## v1 → v2 迁移读取工具

`bridge-runtime/tools/export-v2-bootstrap.ts` 是 ADR 0037 允许的第 2 类改动
（迁移读取）。它提供：

```bash
node --import tsx bridge-runtime/tools/export-v2-bootstrap.ts \
  --data-dir <v1 数据目录> --out <输出 JSON>
```

- 只读端口只打开 `<data-dir>/gateways.json` 与 `<data-dir>/plugins.json`
  两个索引文件，数据库、密钥、队列、附件与正文文件永远不会被打开；
- 只导出 Gateway 显示名、非秘密地址、证书指纹、插件 ID/作者 key ID；
- 排除密码、refresh、配对密钥、队列、未确认操作、正文、附件、数据库文件与主密钥；
- 产物必须通过 `gateway-contract/schemas/v1-bootstrap-export.schema.json`
  校验与秘密字样扫描后才落盘；
- 输出拒绝符号链接与已存在文件，权限固定为 owner-only（`0600`）；
- 导入侧只得到本地 profile 与插件发现清单，并显示"需要重新配对/授权"。
