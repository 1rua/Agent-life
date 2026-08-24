---
status: accepted
date: 2026-08-24
---

# 使用 SQLite 与显式 Gateway 密钥管理

Hermes 与 OpenClaw 的 Gateway Core V1 都以 SQLite 实现同一套协议存储语义、事务边界、约束、清理规则、迁移和一致性测试，不要求外部数据库。活动密钥、密码验证材料和附件数据使用 Gateway 主密钥加密；主密钥优先来自宿主 Secret Store，没有时由用户提供权限受限的专用密钥文件或一次性解锁来源，禁止与数据库一同导出、写入普通配置、日志、备份或诊断。密钥来源缺失或文件权限不安全时 Gateway 拒绝启动。
