---
status: accepted
date: 2026-08-21
---

# 受保护插件使用 WASM 与声明式界面

受保护模式中的 App 内社区插件使用 WASM 执行逻辑，并通过版本化声明让 Android 宿主渲染配置和简单界面；V1 不开放 WebView JavaScript 桥，也不自行设计 DEX 沙箱。WASM 只能导入当前宿主能力包络内的平台内核能力，宿主必须限制执行时间、内存、存储、网络、并发和后台调度，从而让插件开放性不依赖对插件代码的完全信任；需要包络外 Android 权限或系统组件的插件改用 companion APK。
