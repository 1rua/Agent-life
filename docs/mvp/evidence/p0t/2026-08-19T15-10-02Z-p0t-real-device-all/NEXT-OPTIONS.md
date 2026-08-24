# 推进矩阵行的下一步选项（2026-08-19 实测评估）

## 现状

本机/设备能独立证明的（已入库到本 run-id，非矩阵行）：设备达标、真实 AAR 加载、
无 VpnService/BIND_VPN_SERVICE、权限面仅 INTERNET、往返皆 VPN agent=0、
native fail-closed（缺/坏/不可达 enrollment）、generation 单调递增/跨进程持久化。
需要真实 backend 的 9 个矩阵行仍然全部 BLOCKED。

## 实测可行性（本轮完成）

- 手机与主机同网段且互通：`adb shell ping <redacted-lan-ip>` 0% 丢包 / 16.6ms。
- 主机有 docker；github 可达（可拉 headscale）；无 cloudflared/mkcert/tailscale。
- 关键约束：本产品 ALTSNET1 校验强制 `https://` 控制面；手机 tsnet 只信系统根
  CA。因此要么给手机装信任 CA（需用户在手机上手动确认，Android 不允许无头安装），
  要么给控制面一个受公网信任的 https 地址（cloudflared quick tunnel 可无账号获得
  有效证书）。

## 路径 A：外部真实 Tailnet（最省力，需您提供凭据）

- 您提供：一次性 5 分钟 auth key（Tailscale 或贵司控制面），及 P0t Bridge
  harness 可达端点。
- 直接能推：DIRECT（真实设备流量 + 出口采集）、DERP、approval-required、
  网络切换、第二 VPN 共存等。
- 成本：只在我方侧写 harness/用例/采集，全部真机真流量。**推荐若可提供凭据。**

## 路径 B：自建本地控制面 + 公网隧道（无需外部凭据，但属安全敏感基建）

- 组成：headscale（docker）→ cloudflared quick tunnel 暴露 https →
  生成指向该 control 的 ALTSNET1（headscale 自带 preauth key，无外部凭据）。
- 最小目标 **OFFLINE 行（最便宜的矩阵行）**：enroll 一台只含本节点的 tailnet，
  对“固定 Bridge peer”调用 native `Path()`——无人时 backend 真报 OFFLINE，
  即可用“真实 backend 信号 + 真机审计”把 OFFLINE 从 BLOCKED 推到 PASS。
- 进阶：headscale 配 approval 策略可再造 approval-required；DIRECT/DERP 仍需
  额外搭 Bridge peer + WSS harness + 出口采集（更大工程）。
- **需您确认后才动**：本机起 headscale + 短期公网隧道（哪怕只暴露 headscale），
  属于本机/网络的稳定与安全相关变更，按项目规矩先请示。

## 路径 C：维持现状（本轮已完成）

- 9 行保持 BLOCKED（已写清缺什么）；把“设备已验证属性”作为证据沉淀，等待
  用户对 Keystore 修复 + 上文 A/B 路径的决定。

## 建议

1. 先批复 Keystore 修复（方案②）→ tailnet-core 12/12；
2. 若想本月推进矩阵：选 A（推荐）或授权 B 的“OFFLINE 最小目标”。
