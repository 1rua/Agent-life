# Run C — 2026-08-20：主机侧对照诊断（最终根因分层）

## 结论（证据充分，不再猜测）

在 DNS 正常、网络可达、`http.Get("https://controlplane.tailscale.com/")==200` 的
**主机**上，用与设备完全相同的真实束调用同一个原生 `tsnetbridge.Start`：

- `HOST_START err=CONTROL_UNREACHABLE after=1m0.005s`（60s 全超时）

束本身字节级正确（`DecodeBundle`：authKey 指纹=用户提供 key、controlURL 正确、
expiry 在未来、warm=false）。因此 **enroll/注册没有完成的原因不在设备/不在 AAR
解析器/不在束编码**，而在 **enroll-账户或持续控制连接层**：
- 最可能：tailnet 开启“设备批准”(device approval required)，新节点停在
  `pending` 永不 `Running` → tsnet `Up()` 撞 60s 超时 → CONTROL_UNREACHABLE；
- 或一次性 key 已被消耗/失效/未带 tag（无法从我方账号侧读取判断）。

设备侧（`<redacted-device-serial>`）因叠加的解析问题（/etc/resolv.conf 缺失→AAR Go 快速失败，
`GODEBUG=netdns=cgo` 在本进程未生效）还有一层 19ms 快速失败，但那不是本轮阻塞根因。

## 已排除

- 束/编码：DecodeBundle 校验通过（authKey 指纹与提供 key 一致；指纹已脱敏）。
- 设备网络/证书/时钟：curl 严格 TLS 到 controlplane 302、baidu 200、时钟正确。
- 主机端 Go TLS/DNS：`http.Get` 200。
- 设备 Go 解析器探针：pure-Go 失败（`[::1]:53 refused`），cgo(bionic) 成功
  （`https status=200`）→ 锁定 AAR 在设备上需走 cgo 解析（netdns 机制待进一步落地）。

## 待用户账户侧动作（我方无法代做）

1. 登录 login.tailscale.com 管理台，查是否有节点 `p0t-offline-device`：
   - 若 pending/待批准 → 批准它，或确认 tailnet 是否开启了设备批准；
   - 若节点已用掉该一次性 key → 请提供**新的**一次性 auth key。
2. 若批准后仍失败，提供新 key，我方将按序重试：主机 Start 确认注册 → 设备 enroll
   → 若设备通过则把 **OFFLINE** 行基于真实 backend 信号 + 真机审计写为 PASS。

## 诚实口径

- 矩阵行仍未新增 PASS；OFFLINE 保持 BLOCKED（缺“完成 enroll 并 backend 报
  OFFLINE”的真实证据）。
- 设备侧解析器修复（AAR 运行时强制 cgo DNS）是独立开放项：涉及锁 AAR/改生产代码
  或换设备，需用户一并决策。
