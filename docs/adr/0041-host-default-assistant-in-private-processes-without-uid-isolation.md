---
status: accepted
date: 2026-08-29
---

# 将默认助理角色并入 Android 宿主并使用私有进程

默认助理角色作为 Android 宿主的受限系统入口，与主 App 使用同一 package 和 UID；轻量 `VoiceInteractionService`、`VoiceInteractionSessionService` 与主界面分别运行在私有进程和主进程中，以隔离生命周期、内存、崩溃和 ANR。私有进程不构成权限、私有文件或 Keystore 隔离，所有助理输入仍须经过平台内核的默认拒绝入口并由本机用户确认后才能绑定活动 Gateway 的对话或附件，系统角色、Assist 数据、截图和插件状态都不能自行扩大授权。现有独立 `assistant-holder` 只有在进程感知初始化、外部输入、会话死亡、撤权和临时内容清理获得同等或更严格的失败关闭证据后才迁入主 App。
