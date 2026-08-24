---
status: accepted
date: 2026-08-21
---

# 用 Agent 宿主内 Gateway 取代独立 Device Bridge

Android 宿主默认通过出站 HTTPS 直接连接安装在用户 Agent 宿主中的 Gateway，不再要求项目提供独立 Docker Bridge 或中央 Relay；Tailscale 降为可选连接插件，且不承担应用层身份或授权。Gateway 采用共享 Gateway Core 加 Hermes、OpenClaw 两个独立宿主适配器：一台手机可以保存多个 Gateway，一个 Gateway 可以配对多台手机，但每个 Gateway 实例只属于一个用户。该决定取代旧规格中的 Tailscale 默认、独立 Bridge 部署及多租户 Bridge 拓扑。
