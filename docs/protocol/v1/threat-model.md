# P0a 威胁模型与安全不变量

本文件冻结协议版本 `1.0` 的 P0a 安全基线。规范性需求及其后续证据 ID 在 `protocol/registries/v1/p0a-requirements.json`，安全属性在 `protocol/registries/v1/security-properties.json`，审计字段契约在 `protocol/registries/v1/audit-fields.json`。这些登记项是实现和验证的边界，不表示所列后续工件已经在 P0a 中实现。

## 资产

- 人类、Agent、租户、设备、会话和工作空间之间的服务端权威绑定，以及配对、授权 epoch、scope revision 和 stored-query grant revision。
- 设备安装密钥、Bridge command-signing key、adapter credential、enrollment ticket、approval 凭据、channel ticket 和不可重放的序列空间。
- 手机正文、附件、屏幕/传感器流、操作参数和结果；它们不得进入审计正文。
- 执行账本、operation receipt、tombstone、零保留 profile/attestation 证据和最小审计元数据。
- 类型化 capability、风险级别及 Android/后端权限状态。

## 参与者与攻击者

- 目标 human principal、已认证 Agent principal、Bridge、Android App 和已安装的类型化 backend 是正常参与者。
- 恶意或被提示注入的模型输出、伪造/过期的客户端 envelope、重放者、跨租户 Agent、被盗 credential 的调用者和恶意附件/通知/UI 文本都是不可信输入。
- Tailscale coordination/DERP 和公网网络可能观察网络元数据；网络身份不具有人类、Agent 或设备授权权力。
- 应用管理员只能缩小 ceiling 或撤销配对；不能读取正文、扩大 scope 或代替用户授权。Agent 主机 OS root 是受信基础设施风险，而非由协议消除的攻击者。

## 信任边界

1. Android OS 权限与 App 沙箱；Android 进程与内嵌 `tailnet-core`；App 与 Shizuku/类型化 Root broker。
2. Tailscale core、coordination/DERP 和公网网络与应用层：后者仍必须做 TLS、签名、身份绑定和授权。
3. Device Bridge 与 Agent backend，以及模型输出与确定性命令策略。
4. 租户、用户、设备、session/job、artifact 和 stream 的隔离边界。
5. 本地模型或符合零保留契约的远程瞬时推理提供方：它是明文处理边界，不是持久存储边界。

## 入口点

- ticket-owned enrollment、human short code 及配对生命周期。
- HTTPS、WSS 控制 envelope、key rotation、connection reconnect、event/event-ACK、approval 和 cancel。
- Agent adapter 的类型化 operation 创建/查询/等待/取消/核对入口。
- artifact/stream 的短时 ticket、PoP、分片、resume、credit 和 close。
- capability manifest、授权/撤销修订、stored-query grant、零保留 profile 和审计事件。

## 假设

- Bridge 从已认证连接和 credential 取得 principal、设备和 transport context；客户端声明、IP、MagicDNS、Tailnet node ID/tag/header 或 listener profile 只是可校验的一致性声明或审计元数据，不能产生授权。
- 一个安装实例只接受当前有效 pairing 和 connection generation；服务端时间是远程 TTL 的权威时钟。
- 实时设备访问与查询 Bridge 已存副本是独立授权域。前者检查实时 pairing/consent/system permission/backend/revision；后者检查独立 grant、lineage、TTL 和 tombstone。
- 部署提供的模型 profile 满足零保留契约。缺失、未知、签名不合格或 revision/digest 漂移时，手机正文外发必须停止。
- 能提供类型化 Root/restricted backend 并不授予任意执行权限；L4 在所有此类 backend 上无条件拒绝。

## 滥用情形与控制

| 滥用情形 | 控制与安全属性 |
|---|---|
| 以网络元数据、文本 `user_id` 或 manifest 冒充身份 | 服务端权威 principal/device binding；adapter 无可信 principal 时启动失败；manifest 不是 token（SEC-001、SEC-002、SEC-013、SEC-014）。 |
| 撤销后重放旧 pairing、grant、approval、ticket 或 scope | pairing/epoch/scope/grant revision 精确匹配；旧对象失效（SEC-004）。 |
| 用 stored-query 权限取得实时设备访问，或反向混用 | 两个明确、独立且拒绝优先的授权判定域（SEC-003）。 |
| transport、adapter 重试或 ACK 丢失造成重复副作用 | 先持久化 execution claim，账本失败即拒绝；operation ID/参数摘要幂等，结果未知不自动重做（SEC-005、SEC-006）。 |
| 批准后篡改正文、收件人、设备、身份或 revision | 单次、原子消费的 approval 绑定完整参数及所有身份/revision（SEC-007）。 |
| 跨 tenant/user/device/session/artifact/stream 复用对象 | 服务端绑定和分区隔离；所有对象只在其绑定域可用（SEC-008）。 |
| 新旧 WSS 并存时用旧连接继续推进 | 新 connection generation fence 旧连接（SEC-009）。 |
| 将正文、秘密或屏幕写进 audit | 审计只允许字段注册表，未注册/禁止字段拒绝（SEC-010）。 |
| 在供应商保留状态未知时外发正文 | 零保留 profile/evidence fail closed（SEC-011）。 |
| 通过 Root Shell、generic exec、脚本或自动化 DSL 获得任意执行 | schema、registry、fake 和 accepted surfaces 均不接受这些表面；只存在类型化 action（SEC-012）。 |
| 绕过短信确认或在 L4 上选择更强 backend | 每个正式 `sms.send` 在手机逐条确认；所有类型化/restricted backend 无条件拒绝 L4（SEC-015、SEC-016）。 |

## 审计控制

审计记录只接受 `audit-fields.json` 中严格有序的 allowlist，并拒绝同文件 forbidlist 和所有未注册字段。每个允许字段都有封闭的 primitive/map kind、格式或稳定 enum reference、最大编码长度；不允许自由文本。

`tailnet_node_id`、`tailnet_tag`、`policy_attestation_revision` 和 `policy_attestation_digest` 仅可审计，绝不参与授权。digest 仅接受隔离 verifier 产生的 `b64u(SHA-256(JCS_UTF8(full signed attestation {header,payload,signature})))`；P0a 不生成 attestation，也不得由 digest 推导 principal 或 scope。Task 11 才实现 sanitizer 与 SEC-010 的可执行证据。

## 残余风险

- 完全失陷的设备、Root 或 Agent 主机管理员仍可能调用可用密钥或读取进程内解密数据。
- Tailscale control plane/DERP 的正常工作仍会处理节点和连通性元数据；网络隔离不能约束已获授权 Agent 对明文的后续使用。
- enrollment secret、issuer credential、lifecycle credential 和 Android/OEM 权限路径存在供应链与操作风险；短时、分离、轮换、限速和审计只能降低风险。
- 零保留是部署契约与配置假设，不能由本协议黑盒证明提供方内部行为。
- 未受平台保护的屏幕像素可能含敏感内容；包 denylist 和本地分类不能成为绝对保证。

## 范围外的妥协

- P0a 不提供 arbitrary Root Shell、generic exec、脚本、解释器或通用自动化 DSL；也不以“受限 shell”例外绕过 L4、短信确认、审计或紧急停止。
- P0a 不让 Tailscale/network metadata 充当任何授权 token，不把 capability manifest 当 token，也不以 App listener、自动公网降级或网络路径切换扩大授权。
- P0a 不解决防止 Agent 主机 root 读取数据的问题；这需要可信执行环境或端到端隐私计算。
- event cursor persistence/gap recovery 是 P0b/P1a 事项，故不在 P0a requirement registry 中。
