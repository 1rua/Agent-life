# Run E — 2026-08-20：设备真实后端 OFFLINE 证据（非 AAR 适配器）

## 一句话结论

在真机（SM-X710 / API36 / arm64）上用**第二个一次性 auth key** 冷启动真实
Tailscale userspace（同一 Go+tsnet 代码、同一设备、真实官方控制面），节点成功注册
进入 **Running**，随后按 wrapper `Path()` 相同的语义，对“tailnet 上不存在的固定
pinned Bridge peer（100.96.0.1）”查询真实 backend 状态：

```
DIRECT_START_OK after=46ms
DIRECT_STATE=Running after=6.173s
BACKEND_PEER_OFFLINE=true (pinned Bridge peer absent on real backend)
```

warm 复跑：`Running(49ms)` + `BACKEND_PEER_OFFLINE=true`。

## 意义与诚实口径

- **已证（设备级真实后端信号）**：设备可真实 enroll（新 key 有效）、节点 Running、
  backend 对缺位 Bridge peer 真报 OFFLINE；全程 VPN agent 0→0、路由 1→1（每用例
  前后脱敏审计）。
- **仍未证（产品 AAR 适配器）**：以上通过“设备独立 tsnet 二进制”复现，**不是**
  项目 `AndroidTsnetBinding`/AAR 的 connected test。AAR 在进程内仍因 gomobile 在
  Go 运行时启动时快照 environ、Java 侧 `Os.setenv(HOME/XDG_CONFIG_HOME)` 不生效而
  无法创建可写 varRoot（config “/.config: read-only file system”）。
- 因此 **矩阵 OFFLINE 行仍标 BLOCKED（AAR 集成路径）**：缺“AAR 进程内获得可写
  varRoot”。真正落地需**重建 AAR**（在 wrapper 里给 `tsnet.Server` 设可写
  Dir/或用启动环境注入），或换一台可经 shell 预置 HOME 的可 root 设备/不同注入方式
  ——这是留给用户的产品级决策。

## 新 key 使用说明

- 用户提供的新 key `<redacted-consumed-auth-key>` 已被本次成功注册消耗；
  设备 tailnet 新增节点 `p0t-offline-device`（Running），管理台可见，可删除。
- 后续如需跑 AAR 版 enroll，需要再一个新的 key。

## 审计证据

`audits/case-device-standalone-tsnet-real-backend-offline-{before,after}.*`：
VPN agent 0→0、IPv4 路由 1→1、本 OEM `dumpsys vpn` 服务不可用（connectivity 核验）。
