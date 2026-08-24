---
status: accepted
date: 2026-08-24
---

# Gateway Core 只短期暂存设备正文

Gateway Core 长期保存配对、游标、去重、授权版本、回执和最小审计，但通知、短信、联系人、传感器及其他设备正文只在传输、排队和交付 Agent 宿主期间暂存。Agent 宿主确认接收或暂存期限到期后删除正文；长期搜索、索引、记忆和自动化由 Hermes 或 OpenClaw 管理，Gateway 及其适配器不得自行延长正文保留期而重新形成隐藏 Device Bridge 数据仓库。
