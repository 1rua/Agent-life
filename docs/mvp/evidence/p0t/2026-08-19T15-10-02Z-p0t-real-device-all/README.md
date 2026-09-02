# P0t 真机验证运行记录 — 2026-08-19

Run ID: `2026-08-19T15-10-02Z-p0t-real-device-all`

## 一句话结论

在真实 API 36 / Android 16 / arm64 设备上，把 `tailnet-core`/`transport` 的
androidTest 从 0 个用例推进到 **18 条真机用例（17 PASS / 1 FAIL）**，并首次拿到
**每用例前后、脱敏、稳定的系统 VPN/路由/DNS 审计证据**（全程系统 VPN agent 恒为 0）。
1 条失败是**真机首次暴露的生产代码缺陷**：`KeystoreEncryptedNoBackupState` 在真机
Android Keystore 上加密被拒（详见下文“失败项”）。DIRECT/DERP 等需要真实 Tailnet
控制器和网桥 harness 的矩阵行，仍然如实保持 BLOCKED，没有用任何替代证据冒充 PASS。

> 本节保留首轮运行结论。后续已修复 Keystore 问题并完成回归：
> `tailnet-core` 12 PASS / 1 SKIP、`transport` 5/5、`app` 2/2。最终状态见
> `STATUS.md` 与 `RUN-G-2026-08-20-no-host-route-attempt-ineffective.md`。

## 设备与运行方式

| 项 | 值 |
| --- | --- |
| 型号 | SM-X710（Samsung，Wi-Fi 平板） |
| 序列号 | `<redacted-device-serial>` |
| API / Release | 36 / Android 16 |
| ABI | arm64-v8a |
| page size | 4096 |
| adb 状态 | `device`（已授权） |

做法：
1. 以固定 Go 工具链 + 固定 `cmd/enrollment-bundle` 生成**权威 ALTSNET1 fail-closed 束**
   （controlUrl=`https://127.0.0.1`，仅用于证明“无控制器时 fail closed”），推到
   `/data/local/tmp/openandroidintelligence-p0t/failclosed.bundle`（sha256 见 `provisioning/`）。
2. 执行目标命令
   `./gradlew --no-daemon --console=plain :tailnet-core:connectedDebugAndroidTest :transport:connectedDebugAndroidTest`
   （+ 补充 `:app:connectedDebugAndroidTest`），结果 XML 见 `connected-gradle/`。
3. 用 `p0t-device/run-p0t-smallstep.sh` 对每条用例做：
   **before 审计 → `am instrument` 真跑 → after 审计**。审计内容：脱敏
   `dumpsys connectivity`、`dumpsys vpn`、`/proc/net/route`、`ipv6_route`、
   `getprop`、`dumpsys package`（见 `audits/`，共 252 个文件）。

## 用例结果（18 条，`am instrument` 独立真跑）

| 模块 | 用例 | 结果 |
| --- | --- | --- |
| tailnet-core | 设备环境：API34+ / ABI / 真实 AAR 可加载 | PASS |
| tailnet-core | 设备环境：P0t 设备契约（含 page size 采集） | PASS |
| tailnet-core | fail-closed：无 enrollment 源直接拒绝 | PASS |
| tailnet-core | fail-closed：畸形束被 native 拒绝（INVALID_BUNDLE） | PASS |
| tailnet-core | fail-closed：无控制器时 CONTROL_UNREACHABLE、无 VPN、无状态 | PASS |
| tailnet-core | VPN 表面：无 VpnService 组件 / 无 BIND_VPN_SERVICE | PASS |
| tailnet-core | VPN 表面：生命周期不新增系统 VPN agent | PASS |
| tailnet-core | 进程恢复：generation 文件跨实例往返 | PASS |
| tailnet-core | 进程恢复：generation 单调递增（8 次进程重启模拟） | PASS |
| tailnet-core | 进程恢复：损坏 generation 文件拒绝（fail closed） | PASS |
| tailnet-core | 进程恢复：**Keystore 加密节点状态跨实例往返** | **FAIL（生产缺陷）** |
| transport | 进程恢复：generation 跨 10 次进程死亡仅递增 | PASS |
| transport | 进程恢复：断网→重连 generation 递增 + 陈旧 generation 被 fencing | PASS |
| transport | 进程恢复：generation 读/写分离持久化 | PASS |
| transport | fail-closed：OFFLINE 路径拒绝开放会话 | PASS |
| transport | fail-closed：enrollment 不可用时协调器拒开 | PASS |
| app | 正式 app：无 VpnService / 无 BIND_VPN_SERVICE | PASS |
| app | 正式 app：权限面仅为 INTERNET（无 VPN/网络接管权限） | PASS |

`gradle connected` 结果与本表一致：
`tailnet-core` 12 条（11 过 1 败）、`transport` 5 条（全过）、`app` 2 条（全过）。
过早迭代的 7 条失败（缺 `ACCESS_NETWORK_STATE`、`41u/5u` 字面量、Keystore）均保留在
`iterations/` 做透明记录。

## 失败项（必须向用户报告的真实缺陷）

`KeystoreEncryptedNoBackupState.write(...)`（`tailnet-core/src/main/...`）在真机
Android Keystore 上抛：

```
java.security.InvalidAlgorithmParameterException: Caller-provided IV not permitted
```

原因：密钥策略 `setRandomizedEncryptionRequired(true)`（默认），而代码在加密时
`Cipher.init(ENCRYPT_MODE, key, GCMParameterSpec(...))` 显式传入调用方 IV，Android
Keystore 拒绝。**这是生产代码路径（encrypted node state）在真实设备上无法落盘**
——Robolectric 单测与先前的源码级检查没有覆盖到这一硬件行为。

提议的最小修复（修改 `KeystoreEncryptedNoBackupState`，不改协议/AAR）：
在 `KeyGenParameterSpec.Builder` 上 `setRandomizedEncryptionRequired(false)`，
保留每写一次 `SecureRandom` 生成新 IV + GCM 128-bit tag 的现有设计，或改为
`init(ENCRYPT_MODE, key)` 由 Keystore 自生成 IV 并回写信封。两者二选一，需用户
“安全敏感变更”确认后再落代码。

## 审计证据摘要（`audits/`，每用例 before/after）

- 18/18 用例前后：系统 VPN network-agent 数**恒为 0**，且前后不变；
- 18/18 用例前后：IPv4 路由表条目数不变（无新路由）；
- DNS 与 route 明细取自同一份脱敏 `dumpsys connectivity` 每网络 LinkProperties；
- 本 OEM Android 16 无 `dumpsys vpn` 服务（`Can't find service: vpn`），与历史
  BLOCKED 记录一致；但对系统 VPN 槽位的核验改由 connectivity 中
  `TRANSPORT_VPN` / `NOT_VPN` 能力与 in-app `ConnectivityManager` 断言共同完成；
- 默认网络能力含 `NOT_VPN`，未见任何产品 VPN、TUN、全局代理或公网降级迹象。

脱敏规则：MAC、IPv4/IPv6、SSID/UTF-8、serial、16+ 位 hex 全部替换为 `<redacted-*>`。

## 秘密扫描

提交前对本运行的文本、JSON 与工具脚本做了仓库级候选扫描；设备
序列号、局域网 IP、密钥指纹和已消耗 auth key 尾部标识均已脱敏。
证据中仅保留占位符、非秘密 fail-closed 测试值与说明性文本；未发现
私钥、认证头或可用的 Tailscale auth key。

## 矩阵状态与阻塞项

见 `matrix.md` 与 `inventory.json`。诚实口径：能过（有真机证据）的才写 PASS，
其余一律 BLOCKED 并写明缺什么；不把本记录的 native/状态机/审计结果折算成
DIRECT/DERP/approval/Doze/另一 VPN 共存等行。

## 下一步（供决策）

1. **确认 Keystore 修复方案**（上面二选一）后再重跑该条用例；
2. 若要在矩阵推进 DIRECT/DERP/OFFLINE(backend)/approval/网络切换/Doze/另一 VPN，
   需要：真实 Tailnet 控制器（或本地可用的 headscale 类控制面）、一次性 5 分钟
   auth key、P0t Bridge harness（WSS `connect_hello`/`welcome` + nonce echo）、
   forced-DERP 与 approval-required 策略、第二系统 VPN 所有者与
   always-on/lockdown 阻断环境、以及受控 egress 出口采集。

## 延续轮次补充（2026-08-19 第二轮）

- **可复现性**：三模块连接测试干净重跑结果一致（tailnet-core 11/12、
  transport 5/5、app 2/2），复现日志见 `iterations/03-reproduce-*.log`。
- **fail-closed 直证**：对
  `unreachableControlFailsClosedWithoutVpnOrPublicFallback` 单独做了一次
  logcat 包裹的 `am instrument`（`OK (1 test)`），logcat 显示测试进程
  `com.openandroidintelligence.tailnet.core.test` 真实启动并快速得到 fail-closed 结果，
  全程无任何 VPN/VpnService 事件；脱敏 logcat 见
  `audits/*unreachableControl*.logcat.sanitized.txt`。
  说明：控制目标为本机回环 `https://127.0.0.1:443`（拒连），native
  `CONTROL_UNREACHABLE` 在无控制器场景下数毫秒返回——这是期望的快速关闭，
  不是慢超时兜底。
- **Keystore 修复**：两版候补方案见 `proposals/keystore-fix/PROPOSAL.md`
  （推荐方案②：加密时由 Keystore 自生成 IV），**未应用源码，待用户批复**。

## 最终收敛（2026-08-20）

- Keystore 方案②已应用并真机验证；最终 XML 见
  `connected-gradle/TEST-SM-X710_-_16-_tailnet-core-final.xml`，首轮失败 XML 以
  `*-initial-failure.xml` 保留根因证据。
- AAR 重建后锁定 SHA-256 `510182647f…f919ea`，供应链元数据与锁文件
  已同步。
- no-host-route 尝试已证实无效并回退；失败补丁只归档在
  `proposals/no-host-route-attempt/`，不在生产构建中应用。
- 原始迭代 `.log` 含环境细节，由全局 `*.log` 规则保持本地忽略；
  入库的是脱敏审计、XML、矩阵、摘要与根因记录。
