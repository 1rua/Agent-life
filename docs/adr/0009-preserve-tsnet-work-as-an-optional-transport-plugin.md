---
status: accepted
date: 2026-08-21
---

# 保全 tsnet 成果并迁移为可选连接插件

现有 Tailscale AAR、补丁、真机测试和证据继续保留，但暂停把它们接入默认 Android 组合根，也不先按旧架构完成 Tailscale 默认链路。Gateway Protocol v2、核心 HTTPS 连接和连接插件接口稳定后，再把这些成果迁移为官方 Tailscale 可选连接插件；迁移不得削弱现有 no-VPN、窄接口、来源验证和真实设备证据要求。
