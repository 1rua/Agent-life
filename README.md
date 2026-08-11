# Agent Life

Agent Life 让你的 Android 手机安全连接到自托管 AI Agent，让 Agent 在授权范围内读取通知、对话交流、执行操作。通过 app 内嵌的 Tailscale userspace 节点建立加密连接，不占用系统 VPN 通道。

## 核心功能

### 安全连接

通过 app 内嵌的 Tailscale userspace 节点，仅为本 app 到私有 Bridge 的流量提供加密通道。不占用 Android 系统 VPN 槽位，允许其他 VPN 应用继续正常工作。连接必须通过 enrollment ticket 配对，不存在通用网络 API。

### 通知同步

- 策略驱动的通知采集，支持 allowlist/denylist 模式，按包名和字段（metadata / content）精细控制
- Agent 可查询、订阅和取消订阅通知，支持 on-demand 按需查询和 auto-send 自动推送
- 加密 outbox 缓存，不留明文通知于日志或磁盘重试队列
- 默认策略为空 allowlist + metadata 级别，拒绝的通知不采集、不持久化、不发送

### 助手对话

- 注册为系统默认数字助手，通过系统手势或按键唤起
- 支持文字输入、按住说话、流式语音回复、图片和文件对话
- 主 APK 与 assistant-holder 双 APK 隔离，默认 deny 的 handoff gate，需用户显式启用

### 分层能力授权

所有数据采集和设备操作采用风险分级、逐能力授权：

| 能力 | 授权策略 |
| --- | --- |
| 通话记录 | 元数据只读，不含通话控制、录音或转写 |
| 短信 | 可读；发送须逐条人工确认 |
| 联系人 | 只读 |
| 日历与闹钟 | 可读；创建/修改须确认 |
| 屏幕内容 | 无障碍语义树与像素画面分开授权 |
| 传感器 | 快照、低频聚合或限时连续流 |
| 后台命令 | 类型化动作优先，受限 shell 逐次确认 |
| 图片/文件 | 仅限用户通过系统选择器明确选择 |

> 安全边界：任意 Root Shell、脚本、通用命令解释器不会暴露给 Agent；Agent 不能远程启用 Root、Shizuku、无障碍、通知访问等权限。

### 多用户隔离

一个 Bridge 管理多位用户的多台手机，设备、数据、会话、长期记忆和密钥按 tenant 严格隔离。

### 操作安全

- 持久化操作 tombstone + 有界轮询，防止重放
- 精确逻辑配额（9,663,676,416 bytes），超限拒绝而非静默驱逐
- terminal `result_unknown` 机制，无法证明副作用时不自动重试
- 所有安全事件和设备事件有明确的生命周期和 ACK 窗口

### Agent 后端集成

通过服务端适配器无缝接入 Hermes 和 OpenClaw Agent，统一工具契约、事件和会话系统。

### 附件管理（M1.1）

- 图片/文件通过系统 Photo Picker/SAF 选择，拒绝任意路径和 URL
- digest → proof-of-possession → ticket → 提交的完整生命周期
- 24 小时未提交孤儿自动回收
- 限制：每消息 4 个文件，单文件 25 MiB，总计 50 MiB

## 目标与非目标

### 目标

- 为 Android 手机和自托管 Agent 提供统一、可版本化的双向能力协议
- 用户逐项控制数据源、字段、同步模式、主动读取、写入和屏幕控制权限
- 多用户隔离：设备、数据、会话、长期记忆、密钥和审计记录
- 标准 Android API 为首选，Device Owner / Shizuku / 类型化 Root 为可选增强后端
- 不占用系统 VPN 通道，允许其他 VPN 继续正常工作
- 可立即生效的暂停、撤销和紧急停止能力

### 非目标

- 不保证被强制停止、关机、Doze 或断网时即时响应
- 不实现持续监听的自定义语音唤醒词
- 不录制通话音频、不远程接听/挂断/拨号
- 不提取密码、OTP、支付凭据或生物识别内容
- 不暴露任意 Root Shell、脚本或通用命令解释器
- 不提供 VPN、出口节点、子网路由或通用 Tailnet 拨号服务
- 不让 Agent 静默浏览文件系统

## 架构概览

```
多位用户的 Android 14+ 手机
  ├─ 助手/对话界面
  ├─ 本机授权与风险策略引擎
  ├─ 数据采集器与能力适配层
  │    ├─ 标准 Android API
  │    ├─ Device Owner
  │    ├─ Shizuku/ADB
  │    └─ 类型化 Root actions
  ├─ 加密缓冲、临时附件、审计
  └─ 出站 transport
       ├─ 内嵌 Tailscale userspace core
       │    └─ 仅 App→Bridge 的私有通道；DIRECT 或 DERP/RELAY
       └─ 用户手动启用的公网 HTTPS 备用
             │
       Agent Device Bridge
  ├─ 租户/principal/设备注册与密钥
  ├─ 消息路由、事件、幂等、审计
  ├─ 按用户隔离的数据存储与保留
  └─ Agent 适配器
       ├─ Hermes：plugin / platform adapter / MCP
       └─ OpenClaw：plugin / Gateway adapter
             │
        本地或远程模型提供方（零保留推理）
```

## 项目结构

| 目录 | 说明 | 技术栈 |
| --- | --- | --- |
| `apps/android/` | Android 双 APK（11 个 Gradle 模块） | Kotlin, AGP 8.7.3, SDK 35 |
| `bridge-contract/` | Bridge 服务契约（配对/通知/订阅/操作/对话） | TypeScript |
| `bridge-runtime/` | Bridge 运行时适配器与部署模板 | TypeScript, Docker, systemd |
| `artifact-contract/` | 附件 ticket/PoP/提交契约 | TypeScript |
| `mvp-contract/` | 闭合 JSON Schema v1 与 wire codec | TypeScript, JSON Schema |
| `integrations/` | Hermes / OpenClaw 适配器与 skill | TypeScript |
| `e2e/` | 端到端 smoke 与 readiness 检查 | Shell, Python |
| `docs/` | 设计规格、实施计划、准备度报告与门禁决策 | Markdown |

## 快速开始

### SDK-free 检查（无需 Android SDK）

```bash
e2e/mvp/run-smoke.sh --sdk-free
e2e/mvp/run-readiness.sh --sdk-free
npm run mvp:lock:check
```

### Bridge 契约测试

```bash
npx vitest --root . run bridge-contract/test/
npx vitest --root . run bridge-runtime/test/
npx vitest --root . run artifact-contract/test/
npx tsc --noEmit --strict bridge-contract/src/*.ts
npx tsc --noEmit --strict bridge-runtime/src/*.ts
```

### Android 构建

需要 Android SDK 35+、JDK 17、Kotlin 2.0.21：

```bash
cd apps/android
./gradlew --no-daemon check
```

## 当前状态

| 门禁 | 状态 |
| --- | --- |
| 协议契约测试 | ✅ 32 files / 334 tests, typecheck GREEN |
| Bridge/集成/MVP 联调 | ✅ 16 files / 98 tests |
| Android SDK-free 静态门禁 | ✅ 48 tests |
| 依赖锁（7 行） | ⏳ PENDING |
| Android 生产构建 | ⏳ PENDING |
| Tailscale AAR 集成 | ⏳ PENDING |
| Bridge 生产部署 | ⏳ PENDING |

> 详细准备度报告见 [docs/mvp/mvp-readiness-report.md](docs/mvp/mvp-readiness-report.md)

## 关键设计决策

| 决策 | 说明 |
| --- | --- |
| 无系统 VPN | 内嵌 Tailscale userspace，不占用系统 VPN 槽位 |
| Ticket-bound 传输 | 连接必须通过 enrollment ticket 配对，不暴露通用网络 API |
| Fail-closed 安全模型 | 所有边界默认拒绝；通知、控制、附件均需显式授权 |
| 闭合 Schema | 所有 API 使用 JSON Schema 2020-12，拒绝未知字段 |
| 操作幂等 | 持久化 tombstone + 有界轮询，terminal `result_unknown` |
| 零保留推理 | 模型 API 采用零保留配置，不形成提供商侧持久副本 |
| 双 APK 隔离 | 主 APK 与 assistant-holder 分离，默认 deny 的 handoff gate |

详见 [docs/mvp/p0a-gate-decisions.md](docs/mvp/p0a-gate-decisions.md) 和 [设计规格](docs/superpowers/specs/2026-08-08-agent-bridge-android-design.md)。

## 许可证

MIT