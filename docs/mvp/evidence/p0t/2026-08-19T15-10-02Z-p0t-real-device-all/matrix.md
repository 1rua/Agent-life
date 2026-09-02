# P0t 矩阵状态（2026-08-19 真机运行后）

口径：只有拿到**真机/真 backend 证据**的项才写 PASS；其余一律 BLOCKED 并写明
缺什么。绝不把“用例构建成功/状态机通过/审计无变化”折算成后端路径 PASS。

| Case | 状态 | 依据 / 阻塞原因 |
| --- | --- | --- |
| DIRECT | BLOCKED | 需要真实 Tailnet 控制器 + 一次性 5 分钟 auth key + P0t Bridge (WSS `connect_hello`/`welcome` + nonce echo) + 设备侧实际 DIRECT 流量证据。目前全部缺失。 |
| DERP | BLOCKED | 需要 forced-DERP 控制器策略并捕获设备侧 RELAY 流量；缺失。 |
| OFFLINE | BLOCKED | 需要 enrolled 节点上报 real backend `Path()==OFFLINE`。本运行仅证明“无控制器时 native CONTROL_UNREACHABLE 且 fail closed”，不等于 backend OFFLINE 信号。 |
| approval-required | BLOCKED | 需要 controller approval 策略产生真实 pending-approval 状态；缺失。 |
| process-death restore | BLOCKED | 完整 warm-restore 需要已 enrolled 的真实节点状态 + 两阶段 process-death harness；缺失。本运行已真机证明持久化/keystore 之外的 generation 与状态文件跨进程恢复、以及 keystore 缺陷（见 README 失败项），属部分证据，不冒充整行 PASS。 |
| 网络切换 15s + generation 单调递增 | BLOCKED | generation 单调递增已在真机证明（transport 3 条 PASS），但“网络切换 15 秒”需真实网络切换控制器 + 真实 backend 流量计时；缺失。 |
| Doze | BLOCKED | 需要受控 Doze 进入/退出与生命周期采集；缺失。 |
| 另一系统 VPN 共存 | BLOCKED | 本运行证明“本产品不创建/不抢占 VPN 槽位”（app+core VPN 表面 PASS、全程 VPN agent=0），但完整的“另一系统 VPN 存在且 allow/block 路径”需要安装第二 VPN 所有者 + 真实 tailnet 流；第二 VPN 与控制器缺失。 |
| 禁 VPN/TUN | BLOCKED | “本产品无 VpnService/无 BIND_VPN_SERVICE/权限面仅 INTERNET”已真机 PASS；但矩阵行所指的“阻断路径真实失败且无公网 fallback”需 always-on/lockdown 或 split/full-tunnel 阻断环境 + 真实 backend 流；缺失。 |

## 补充（Run B，2026-08-20）

- 矩阵行无新增 PASS。**OFFLINE 仍 BLOCKED**：真实 enroll 需该设备 native 成功注册
  后由 backend 报 OFFLINE；本轮走到“束生成→真机尝试→诊断”，尚未成功 enroll。
- tailnet-core 套件在 Keystore 方案②修复后 **12/12 通过**（新增离线用例未提供束时
  Assume 跳过，绝不计 PASS）。修复后新鲜 XML 见 `connected-gradle/`
  `TEST-SM-X710_-_16-_tailnet-core-final.xml`。
- 附加诊断证据：`RUN-B-2026-08-20.md`（权限/网络/解析器排查链，含待跑探针脚本）。

## 补充（Run C，2026-08-20，主机对照）

- 主机侧真实束 Start 60s 超时（CONTROL_UNREACHABLE）→ enroll 未完成的原因定位到
  账户/批准/持续控制连接层（非设备、非 AAR、非束）。详见 `RUN-C-*-host-diag.md`。
- OFFLINE 仍 BLOCKED，阻塞输入更新为：需要 tailnet 侧批准/新 key 以完成 enroll。

## 补充（Run D，2026-08-20，根因锁定）

- 设备 enroll 唯一真障：Android 无可写 HOME → tsnet 建 .config 失败（瞬时）。已修
  （App 启动设 HOME/XDG_CONFIG_HOME；测试 BeforeClass 设 HOME）。
- 当前仅缺**一个新的有效一次性 auth key**（原 key 已被成功注册消耗）。此障碍为
  外部输入，属首次复现，未到 BLOCKED 判定阈值。
- OFFLINE 仍 BLOCKED（待新 key 完成 AAR enroll）。

## 补充（Run E，2026-08-20，设备真实后端 OFFLINE）

- OFFLINE 行（AAR 集成）状态：**BLOCKED**（缺：AAR 进程内可写 varRoot，由 gomobile
  env 快照导致 Java setenv 不生效；需 AAR 重建决策）。
- 但已取得**设备级真实后端证据（非 AAR）**：第二位 key 在真机冷启动注册成功
  （Running），backend 对缺位 pinned Bridge peer 真报 OFFLINE；全程 VPN/route 稳定。
  见 `RUN-E-*.md` 与 `audits/case-device-standalone-tsnet-...`。
- 该证据**不计入矩阵 PASS**（产品 AAR 适配器仍未打通），如实列出而非冒充。

## 补充（回归：2026-08-20，全部源码改动后的 connected 套件）

- 三模块源码改动（Keystore 方案②、AndroidTsnetBinding netdns、OpenAndroidIntelligenceApplication
  HOME、p0t-device 工具）后完整回归：tailnet-core 12/12 PASS + 离线用例 Assume 跳过、
  transport 5/5、app 2/2 + 离线用例跳过。日志：`iterations/04-regress-*.log`。
- 唯一一次红是 **fail-closed 束过期**（`INVALID_BUNDLE: attestation is expired`），
  属测试提供物生命周期问题，非源码回归；已将 failclosed 束有效期改为 +24h 并重新
  提供（新 sha256=09d484b8…）。
- 结论：当前工作区状态 = 全部既有 PASS 保持，OFFLINE(AAR) 仍 BLOCKED，等待 AAR
  重建批复（提案：`proposals/aar-rebuild-dir/PROPOSAL.md`）。

## 补充（Run F，2026-08-20，AAR 重建落地 + SELinux 平台限制）

- AAR 重建（用户批准）已完成并锁定最终 digest 510182647f；varRoot 注入 API 落地，
  构建可复现，回归全绿（tailnet-core 12/12、transport 5/5）。
- 决定性发现：App 进程内 tsnet **无法启动**，真实错误
  `tsnet: route ip+net: netlinkrib: permission denied`（SELinux untrusted_app 无
  netlink 路由权限；同设备 shell 可跑）。→ 矩阵 OFFLINE(AAR 路径) 阻塞输入更新为
  **平台 SELinux 限制**，需架构评估（VpnService/特权/root/换实现等）另立决策。
- 详见 RUN-F-*.md。

## 补充（Run G，2026-08-20，no-host-route 尝试无效 + 收敛）
- 方案 A 最小补丁尝试被证实不生效（补丁编译进库但路由编程仍在补丁点之外执行）；
  已回退补丁机制，锁定 AAR 回到干净可复现件 **510182647f**（varRoot 修复）。
- 平台定性定稿：锁定 tsnet v1.98.10 在普通 Android app 内无法启动
  （netlink 路由编程被 SELinux 拒，无代码级可拦截点）；shell/特权进程可完整
  注册+backend OFFLINE（Run E）。出路见 `NEXT-DECISION.md`（产品层决策）。
- 矩阵 9 行保持 BLOCKED；回归：tailnet-core 12/12 PASS、transport 5/5（离线用例
  Assume 跳过）。详见 `RUN-G-*.md`。
