# Legacy Bridge Runtime

## 状态声明

本目录包含已冻结的 v1 Bridge 运行时组件（`legacy/bridge-runtime/`）。

## 使用与维护约束

1. **严禁生产发布**：本运行时仅用于向后兼容读取、安全引导导出（`export-v2-bootstrap`）与历史测试回溯。
2. **严禁新增功能**：禁止在此添加任何新功能或扩展协议特性。
3. **允许安全修复**：仅允许关键安全缺陷与 CVE 的只读合规修复。
4. **统一新架构**：所有设备连接、插件加载与网关交互必须使用模块化插件架构（`docs/superpowers/specs/2026-08-24-modular-plugin-architecture.md`）。
