# P0t Gate C/D BLOCKED — 2026-08-17

Run ID: `2026-08-17T11-35-26Z-r5cy32bxv8n`

最终门禁状态：**BLOCKED**。没有矩阵项写成 PASS，也没有任何部分通过声明。

## 已满足的独立前置条件

- 物理设备已连接：`R5CY32BXV8N` / `SM-S9380` / `arm64-v8a` / API 36 / Android 16 / page size 4096。
- 锁定 AAR 存在并通过 verifier：`sha256:a654e487f88cc35a7baa238666151746f83dec12000a3078f6a83508df791e87`，ABI 为 `arm64-v8a,x86_64`。
- `test_transport_boundary.py`、`run-tsnet-go-tests.sh './...'`、`:tailnet-core:check :transport:check`、`mvp:lock:check` 均以 0 退出。
- 清理旧跨路径 Gradle 缓存后，`:tailnet-core:connectedDebugAndroidTest :transport:connectedDebugAndroidTest` 以 0 退出，但两个模块的 `androidTest` 源文件数都是 0。这只是空测试任务的构建成功，不能计为任何 P0t 矩阵覆盖。

## Gate C/D 阻塞项

- `p0t/device` 目录不存在；`run-p0t.sh`、stdin provisioning/framing、collector、validator、secret scanner 均不存在。
- `P0T_DESCRIPTOR`、`P0T_AUTH_KEY_PIPE`、`P0T_CONTROLLER_REVIEW` 均未提供；无法执行控制器一次性 5 分钟 key enrollment，更无法为每个首次 enrollment 提供独立 key 的证据。
- 没有真实 Tailnet/Bridge controller、forced DERP、approval-required policy、validated Wi-Fi/cellular 切换、Doze entry/exit、另一个系统 VPN owner、split/full-tunnel 或 always-on+lockdown 阻断控制器。
- 没有受控网关 egress capture，因此不能证明流量只属于 control/STUN/DERP/ticket-bound Bridge，也没有资源预算数据。
- 本机 OEM Android 16 上 `adb shell dumpsys vpn` 返回 `Can't find service: vpn`。脱敏 `dumpsys connectivity` 基线显示当前 VPN NetworkAgent 数为 0，`com.agentlife.mobile` 没有 `BIND_VPN_SERVICE`；但这只是基线，不能替代每个用例前后的 VPN/route/DNS 审计。
- 新证据目录的秘密候选扫描为 0；但计划要求的 `p0t/device/scan-secrets.py` 不存在，因此不能宣称完整秘密扫描门禁通过。

## 矩阵结论

| Case | 结论 |
| --- | --- |
| DIRECT | BLOCKED |
| DERP | BLOCKED |
| OFFLINE | BLOCKED |
| approval-required | BLOCKED |
| process-death restore | BLOCKED |
| 网络切换 15 秒 + generation 单调递增 | BLOCKED |
| Doze | BLOCKED |
| 另一系统 VPN 共存 | BLOCKED |
| 禁 VPN/TUN | BLOCKED |

Machine-readable inventory: `inventory.json`.
