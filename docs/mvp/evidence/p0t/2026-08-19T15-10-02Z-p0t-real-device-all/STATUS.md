# STATUS（2026-08-20）：阻塞点与恢复清单

## 完成且可复现（证据均已落档本 run-id）
- tailnet-core connected: 12/12 PASS + 离线真实后端用例 Assume 跳过
- transport 5/5、app 2/2；前后脱敏审计 18/18 稳定；全程 VPN agent=0
- Keystore 方案②生产修复（真机验证）
- OpenAndroidIntelligenceApplication HOME/XDG_CONFIG_HOME 生产修复（真机必需）
- 设备级真实后端 OFFLINE 证据（第二 key，非 AAR 独立 tsnet）
- 根因链 RUN-A/B/C/D/E 全部存档

## 阻塞（需用户行动；已连续多轮）
1. 【批准】执行 AAR 重建提案（proposals/aar-rebuild-dir/PROPOSAL.md：wrapper 设
   tsnet.Server.Dir）——供应链/安全敏感，按项目规矩需用户确认。
2. 【提供】第三个一次性 Tailscale auth key（前两个已被真机注册消耗）。
这两项到位后：重建 AAR→锁新 digest→跑 P0tAppOfflineRealBackendInstrumentedTest
→OFLINE 行写 PASS（附每用例审计）；其余 DIRECT/DERP/approval/Doze/网络切换/
第二 VPN 行需控制器+网桥 harness+（按 A/B 路径）后续搭建。

## 干净工作区说明
所有改动均未提交（git 工作区保留，含用户原有未提交内容）；删除一律走临时目录，
无危险删除操作。恢复本目标时直接续接即可。

## 恢复后更新（2026-08-20，Run F）
- 用户批准 AAR 重建 → 已执行并锁定新 digest 510182647f（varRoot 注入 API + Dir +
  Go-env 修复；同源两次构建一致；回归全绿）。
- new关键发现：App 进程内 tsnet 无法启动——SELinux 禁 netlink 路由编程
  （`netlinkrib: permission denied`；shell 进程可跑）。→ 产品“无 VpnService”
  形态在主流 Android 上被平台限制阻断，需架构评估（VpnService / 特权 / root /
  换实现）另行决策；矩阵各行仍 BLOCKED，设备级真实后端 OFFLINE(standalone) 证据保留。
- 下一步恢复入口：架构决策（怎么让 tsnet 在真机 App 内启动，或接受现状）；
  若走特权/root 再补 AAR 真机 enroll 证据。

## 恢复后更新（Run F+1）
- 平台限制已彻底定性：tsnet v1.98.10 userspace 路由器在 Linux/Android 必然写主机
  路由（netlink），普通 app 被 SELinux 拒；varRoot 修复(已重建锁定)之外无代码级
  开关。→ 出路与选型见 `NEXT-DECISION.md`（推荐 A：no-host-route 补丁的 fork）。
- 当前状态：AAR 锁定 510182647f、tailnet-core 12/12、transport 5/5、设备级真实后端
  OFFLINE(独立进程)证据保留；矩阵 9 行 BLOCKED。

## Run G 收敛
- 委托的“方案 A（no-host-route 补丁）”尝试证实不生效并已回退；锁定 AAR 回到干净
  可复现件 510182647f（varRoot 修复）。技术定性定稿（RUN-G）：普通 Android app 内
  锁定 tsnet 无法启动（SELinux netlink；无代码级可拦截点）；设备级(特权/shell)真实
  backend OFFLINE 证据保留（Run E）。
- 恢复入口：产品层决策（VpnService 承载 / 特权或 root / 换不碰主机路由的 userspace /
  上游 tstun-nil 研究），见 NEXT-DECISION.md；矩阵 9 行保持 BLOCKED（诚实）。
