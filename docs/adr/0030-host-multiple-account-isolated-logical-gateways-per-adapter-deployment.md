---
status: accepted
date: 2026-08-24
---

# 一个适配器部署承载多个账号隔离的逻辑 Gateway

一个 Hermes 或 OpenClaw Gateway 适配器部署可以服务多个账号，但每个账号对应一个只属于该用户的逻辑 Gateway。每个逻辑 Gateway 使用独立稳定账号 ID、SQLite 数据库、Gateway 主密钥、附件目录、队列、设备、授权和审计；账号之间只共享适配器代码，不依赖共享数据库中的行过滤实现隔离。V1 账号由 Gateway 部署所有者在本机创建或通过一次性邀请发放，不开放匿名自注册。
