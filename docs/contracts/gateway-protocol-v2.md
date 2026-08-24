---
status: accepted
date: 2026-08-24
contract: agent-life-gateway-protocol
version: 2.0.0
---

# Agent-life Gateway Protocol v2 契约

## 1. 适用范围

本契约定义 Android 宿主与一个账号所属逻辑 Gateway 之间的应用层协议。它不定义 Hermes/OpenClaw 内部 API、插件 WASM ABI 或 Companion IPC。

V2 是新协议，不兼容 Bridge Protocol v1。所有端点必须使用 HTTPS；SSE 是唯一 V1 流式事件通道，不提供等价 WebSocket 通道。

## 2. 基础约定

默认基路径：

```text
/agent-life/v2
```

所有 JSON：

- UTF-8 编码；
- `Content-Type: application/json`；
- 对象拒绝重复键；
- 协议 Schema 默认拒绝未知字段；
- 时间使用 RFC 3339 UTC，精确到毫秒；
- 摘要使用小写十六进制 SHA-256；
- ID 是服务端或客户端生成的不透明 ASCII 字符串，长度 1–128，接收方不得解析业务含义。

所有成功响应包含：

```json
{
  "requestId": "req_01...",
  "correlationId": "cor_01...",
  "protocol": "2.0",
  "data": {}
}
```

所有失败响应包含：

```json
{
  "requestId": "req_01...",
  "correlationId": "cor_01...",
  "protocol": "2.0",
  "error": {
    "code": "AUTHENTICATION_REQUIRED",
    "message": "Human-readable localizable fallback",
    "retryable": false,
    "retryAfterSeconds": null,
    "details": {}
  }
}
```

`message` 不能作为客户端逻辑分支；客户端只使用 `code`、`retryable` 和经过 Schema 验证的 `details`。

## 3. TLS

正式连接只能使用：

- 系统信任的公网证书；或
- 用户首次确认后固定的私有 CA、证书或 SPKI SHA-256 指纹。

固定身份变化需要旧身份签署的轮换证明或用户重新确认。连接插件和 Tailscale 不豁免 TLS 验证。

真正忽略证书错误的开发连接必须满足全部条件：目标是 loopback、RFC1918/ULA 或用户明确标记的测试地址；界面持续显示警告；禁用密码、刷新、附件、后台同步、设备插件和敏感正文。该模式不能创建可在正式连接复用的账号或配对状态。

## 4. 协议协商

`POST /negotiate` 在认证前执行，请求不含秘密：

```json
{
  "negotiationId": "neg_01...",
  "protocol": { "major": 2, "minor": 0 },
  "client": {
    "installationId": "install_01...",
    "appVersion": "2.0.0",
    "platform": "android",
    "platformApi": 35
  },
  "features": {
    "auth": ["password", "account-invitation", "refresh", "device-key"],
    "messages": ["chat-v1"],
    "attachments": ["staged-sha256-v1"],
    "events": ["sse-cursor-v1"],
    "deviceRequests": ["risk-queue-v1"]
  },
  "schemaHashes": {
    "core": "sha256:..."
  }
}
```

响应固定当前连接可用交集和有限值：

```json
{
  "protocol": { "major": 2, "minor": 0 },
  "features": {
    "auth": ["password", "account-invitation", "refresh", "device-key"],
    "messages": "chat-v1",
    "attachments": "staged-sha256-v1",
    "events": "sse-cursor-v1",
    "deviceRequests": "risk-queue-v1"
  },
  "limits": {
    "maxSingleAttachmentBytes": 26214400,
    "maxMessageAttachmentBytes": 52428800,
    "allowedMediaTypes": ["image/jpeg", "image/png", "image/webp", "application/pdf", "text/plain", "audio/mp4"],
    "attachmentTtlSeconds": 3600,
    "eventRetentionSeconds": 86400,
    "maxClockSkewSeconds": 120
  },
  "gatewayIdentity": {
    "deploymentId": "deploy_01...",
    "tlsSpkiSha256": "sha256:..."
  }
}
```

协议主版本不同、核心 Schema 不兼容、未知安全字段或未知高风险能力时返回 `PROTOCOL_INCOMPATIBLE`。次版本差异只启用双方声明的交集。协商结果由 `negotiationId` 绑定后续认证会话，客户端不能在单次请求中自行扩大功能。

## 5. 账号与设备

### 5.1 标识

- `accountId`：Gateway 生成的稳定隔离主键；
- `username`：可变登录名，不作为数据隔离键；
- `installationId`：Android 首次安装生成并由 Keystore 相关状态保护的本地标识；
- `deviceId`：逻辑 Gateway 为该安装实例创建的账号内设备标识；
- `pairingId`：账号与设备之间的信任关系；
- `sessionId`：短期认证会话。

不同账号中的相同 `installationId` 不共享 device ID、配对、授权或审计。Gateway 必须在打开账号数据库前完成账号解析，不能先连接共享业务数据库再依赖行过滤。

### 5.2 密码登录

`POST /sessions/password` 只允许在正式验证的 TLS 上调用：

```json
{
  "negotiationId": "neg_01...",
  "username": "alice",
  "password": "user-entered-secret",
  "installation": {
    "installationId": "install_01...",
    "displayName": "Alice's phone",
    "devicePublicKey": "base64url-ed25519-public-key"
  }
}
```

成功后 Gateway 创建或确认该账号下的独立设备记录，返回短期 access token、一次性展示的 refresh credential 和配对摘要。账号密码不由 Android 保存。新设备的核心对话与附件可用，但设备插件授权集合为空。

### 5.3 账号邀请配对

二维码或短码由目标账号生成，内容包含 Gateway 地址、账号提示、邀请 ID、一次性秘密、期限和 Gateway 身份指纹。用户必须填写或确认账号名。

`POST /pairings/exchange` 提交邀请与新的设备公钥。邀请只能使用一次、不能跨账号、默认 5 分钟失效。成功响应与密码登录相同，但审计认证方式为 `account-invitation`。

### 5.4 刷新与设备密钥会话

`POST /sessions/refresh` 使用绑定 `accountId + installationId + deviceId` 的刷新凭据轮换短期 access token，并同时轮换 refresh credential。旧 refresh credential 在成功使用后立即失效；检测到重复使用时撤销该设备全部 refresh credential 并产生安全审计。

`POST /sessions/device` 使用已配对设备私钥签名挑战建立短期会话。请求仍必须携带账号名或账号 ID；设备密钥不能在账号之间探测或复用。

密码重置撤销账号的全部 refresh credential。既有配对密钥是否保留由操作员选择；疑似泄露流程撤销全部会话和配对。

### 5.5 会话终止

`DELETE /sessions/current` 结束当前 access session；请求 `revokeRefresh=true` 时同时实现“退出登录”。解除配对和删除账号使用独立管理端点，不能由普通会话删除隐式替代。

## 6. 已认证请求

短期 access token 使用：

```http
Authorization: Bearer <opaque-access-token>
X-Agent-Life-Protocol: 2.0
X-Agent-Life-Account: <account-id>
X-Agent-Life-Device: <device-id>
X-Agent-Life-Session: <session-id>
X-Agent-Life-Request-Id: <request-id>
X-Agent-Life-Timestamp: <RFC3339 UTC>
X-Agent-Life-Nonce: <base64url 16 random bytes>
X-Agent-Life-Signature: <base64url Ed25519 signature>
```

设备签名输入：

```text
AGENT-LIFE-REQUEST-V2\n
<UPPERCASE METHOD>\n
<normalized path and sorted percent-encoded query>\n
<account-id>\n
<device-id>\n
<session-id>\n
<request-id>\n
<timestamp>\n
<nonce>\n
<lowercase sha256 of exact body bytes>
```

Gateway 校验 token、账号、设备、配对 generation、时间窗、nonce、防重放和签名。认证身份只能来自验证结果；请求 JSON 中出现 `accountId`、`deviceId`、`principalId` 等试图覆盖认证身份的字段时返回 `IDENTITY_OVERRIDE_REJECTED`。

读请求重复可以安全重试。所有创建、副作用和附件提交请求必须携带 `Idempotency-Key`；同一账号、设备和键在保留期内返回同一终态，不重复执行。

## 7. 对话

端点：

```text
GET  /conversations
POST /conversations
GET  /conversations/{conversationId}
POST /conversations/{conversationId}/messages
```

创建对话请求包含客户端生成的 `clientConversationId` 和可选显示标题。发送消息请求：

```json
{
  "clientMessageId": "msg_client_01...",
  "text": "Summarize the attached document",
  "attachments": [
    { "attachmentId": "att_01..." }
  ]
}
```

Gateway 返回 `accepted` 及服务端 message ID；Agent 回复通过 SSE 发送 `conversation.message.delta` 和 `conversation.message.completed`。每个 conversation 只属于当前逻辑 Gateway，服务端拒绝跨账号、跨 Gateway 或跨 conversation 附件引用。

宿主拥有长期对话与 Agent 记忆。Gateway 只保存完成可靠交付所需映射、幂等结果和短期暂存，不复制长期对话正文。

## 8. 附件

上传分三步：

1. `POST /attachments` 创建暂存记录；
2. `PUT /attachments/{attachmentId}/content` 上传字节；
3. `POST /attachments/{attachmentId}/commit` 校验并提交。

创建请求：

```json
{
  "clientAttachmentId": "att_client_01...",
  "filename": "report.pdf",
  "mediaType": "application/pdf",
  "sizeBytes": 102400,
  "sha256": "64-lowercase-hex-chars"
}
```

Gateway 在读取内容前检查协商限制和本账号配额。content 请求必须携带 `Content-Length` 与 `Digest: sha-256=<base64>`，不允许未协商的压缩或媒体类型。commit 只有在字节数和摘要完全匹配时成功。

附件状态：

```text
created -> uploading -> verified -> delivered -> acknowledged -> deleted
created|uploading|verified -> expired|failed -> deleted
```

只有 `verified` 附件可被消息引用。宿主确认接收后 Gateway 删除暂存字节并保留无正文终态；配对解除、账号删除或 TTL 到期立即删除。

## 9. SSE 事件流

`GET /events?cursor=<opaque>` 使用 `Accept: text/event-stream`。客户端也可以发送 `Last-Event-ID`；两者同时存在且不一致时返回 `CURSOR_CONFLICT`。

事件格式：

```text
id: evt_01...
event: conversation.message.completed
data: {"correlationId":"cor_01...","occurredAt":"2026-08-24T12:00:00.000Z","payload":{}}

```

V2 事件类型：

- `conversation.message.delta`
- `conversation.message.completed`
- `device.requested`
- `device.request.cancelled`
- `pairing.grant.changed`
- `session.revoked`
- `attachment.acknowledged`
- `gateway.notice`

事件 ID 同时是恢复游标。Gateway 保留时间不得短于协商值；游标过旧返回 `CURSOR_EXPIRED` 以及可安全重建的资源类型，不静默跳到最新事件。客户端先重建资源快照，再使用响应给出的新游标恢复流。

心跳使用 SSE 注释行，不创建事件 ID。Android 以指数退避重连，不假定流永久在线。

## 10. 设备插件请求

Agent 宿主通过 Gateway Core 创建结构化设备请求；模型不能提供账号、设备、会话、授权版本或风险级别。Gateway 从已验证能力 Schema 和宿主认证上下文补齐这些字段。

`device.requested` 事件载荷：

```json
{
  "requestId": "device_req_01...",
  "capability": {
    "id": "org.agentlife.sms.query",
    "version": "1.0.0"
  },
  "provider": {
    "pluginId": "org.agentlife.sms",
    "authorKeyId": "sha256:..."
  },
  "parameters": {},
  "risk": "read",
  "grantRevision": 7,
  "createdAt": "2026-08-24T12:00:00.000Z",
  "expiresAt": "2026-08-25T12:00:00.000Z",
  "requiresForegroundConfirmation": false
}
```

Android 执行前重新验证插件身份、当前提供者、能力版本、授权 revision、本地权限、资源状态和期限。结果提交到 `POST /device-requests/{requestId}/result`，必须幂等并包含执行时授权 revision。

队列上限：

| 风险 | 默认最长等待 | 恢复后处理 |
|---|---:|---|
| read / sync | 24 小时 | 重新验证后执行 |
| write | 15 分钟 | 重新验证，必要时再次确认 |
| high-privilege ephemeral | 0 | 离线即失败，不排队 |

Gateway 可以声明更短期限，不能超过上表。Android 可以按本地策略进一步缩短或拒绝。

## 11. 配对授权

每个配对的授权以单调递增 `grantRevision` 表示，键为插件身份和能力版本范围。安装或启用插件不会创建授权；新配对授权为空。

授权变更必须由 Android 本地确认。Gateway 保存签名授权摘要并发送 `pairing.grant.changed`，但不能扩大 Android 本地权威记录。请求携带旧 revision 时返回 `GRANT_STALE`，Android 不按旧授权执行。

能力提供者切换产生新 revision 并要求重新授权，不把旧插件授权转移给新插件。

## 12. 离线、幂等与 fencing

每次重新配对、密钥轮换或恢复后生成更高 `pairingGeneration`。所有设备请求、结果、附件和授权都绑定 generation；旧 generation 请求返回 `PAIRING_GENERATION_STALE`。

Gateway 对副作用保存幂等终态直到请求最大 TTL 与审计窗口两者中的较长者。崩溃恢复后，相同 idempotency key 返回原终态或明确 `OUTCOME_UNKNOWN`，不能猜测重试高风险动作。

## 13. 数据保留与删除

Gateway 长期保存协议元数据，不长期保存设备正文。通知、短信、通话记录和附件正文只在排队、交付和确认期间加密暂存；宿主 ACK 或 TTL 到期后删除。

- 退出登录：撤销目标 refresh credential，不删除配对；
- 移除本地账号：是 Android 本地动作，Gateway 只看到退出；
- 解除配对：撤销设备密钥、refresh credential、授权、队列和未确认附件；
- 删除账号：删除该逻辑 Gateway 全部数据库、密钥、设备、队列、附件和审计，允许先导出可迁移备份。

最小审计默认保留 30 天，不含正文；用户可以清除。清除审计不能复活已删除正文或凭据。

## 14. 错误代码

V2 至少实现：

```text
PROTOCOL_INCOMPATIBLE
SCHEMA_INVALID
TLS_IDENTITY_REQUIRED
ACCOUNT_NOT_FOUND
AUTHENTICATION_REQUIRED
AUTHENTICATION_FAILED
REFRESH_REUSED
SESSION_EXPIRED
SESSION_REVOKED
IDENTITY_OVERRIDE_REJECTED
SIGNATURE_INVALID
REQUEST_REPLAYED
CLOCK_SKEWED
PAIRING_REQUIRED
PAIRING_INVITATION_EXPIRED
PAIRING_GENERATION_STALE
GRANT_REQUIRED
GRANT_STALE
CAPABILITY_UNAVAILABLE
PROVIDER_CHANGED
IDEMPOTENCY_CONFLICT
OUTCOME_UNKNOWN
ATTACHMENT_LIMIT_EXCEEDED
ATTACHMENT_DIGEST_MISMATCH
ATTACHMENT_EXPIRED
CURSOR_CONFLICT
CURSOR_EXPIRED
RATE_LIMITED
HOST_INCOMPATIBLE
ACCOUNT_DELETING
INTERNAL_ERROR
```

认证失败不泄露账号是否存在。安全、身份、摘要、授权和 generation 错误默认不可自动重试；网络中断和明确 `retryable: true` 的限流错误按退避策略处理。

## 15. Gateway 暴露与宿主兼容

Gateway 可以使用宿主已有路由、loopback 加反向代理或显式证书直接 TLS 监听。三种模式必须运行相同路由、认证、限制、审计和一致性测试。

适配器声明经过验证的宿主 API 最小与最大版本。宿主版本超出范围时 Gateway 进入 `host-incompatible`：管理入口只读、数据不迁移、外部端点返回 `HOST_INCOMPATIBLE`。

## 16. 一致性套件

Android、Hermes 和 OpenClaw 实现必须共同通过语言无关向量：

- 协商成功、次版本交集、主版本拒绝和未知风险拒绝；
- 密码、邀请、刷新、设备密钥四种账号绑定流程；
- refresh 轮换与重复使用撤销；
- 请求签名、防重放、时钟偏差、身份覆盖拒绝和 generation fencing；
- 对话严格账号隔离与多线程路由；
- 附件长度/摘要、TTL、ACK 和崩溃清理；
- SSE 有序游标、断线恢复、过期重建和重复事件幂等；
- 三档离线队列期限与执行前重新授权；
- 提供者切换、授权 revision 和 Companion 故障关闭；
- 多账号文件级隔离、备份不含活动身份、账号删除；
- Android/Gateway correlation ID 对应且审计不含正文。

任一实现只有在同一向量版本全部通过后才能声明 `Gateway Protocol 2.0` 兼容。
