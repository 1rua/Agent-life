# 下一步决策：让 tsnet 在真机 App 进程内可用（平台限制的出路）

## 已被证据固定的平台事实（RUN-D/E/F）
1. Android 进程无 HOME → tsnet 建 `/.config` 失败（已用 varRoot 注入修复，AAR 已重建
   锁定 510182647f）。
2. 即便 varRoot 正常，App 进程（untrusted_app）内 tsnet `s.Start()` 仍立即失败：
   `tsnet: route ip+net: netlinkrib: permission denied`（SELinux 禁 netlink 路由）；
   同设备 shell 进程可跑（真实注册+backend OFFLINE）。
3. tsnet v1.98.10 的 userspace 路由器在 Linux/Android 上**必然**写主机路由
   （`osrouter → addRoute/netlinkrib`），没有关闭开关；`router.HookNewUserspaceRouter`
   只能 Set 一次且 SetForTest 仅测试构建（均不可在 AAR 内覆盖）。

## 出路（各自命中不同约束，需您选型；按项目规矩先定设计再落码）

### A. 打补丁的上游 fork（推荐，最贴合“不写系统路由”目标）
- 在第三方源上 fork 一份“userspace 路由器 no-op 补丁”：Android 上 `router.Set` 不再
  写主机路由（产品本就只经 netstack 拨号、不应接管系统路由）。
- 影响：新的锁定源 + 锁定 AAR（等同一次受控 source 升级，供应链步骤走既有 verify/
  lock 流程）；不改协议/接口。
- 风险：需维护 fork；若未来上游提供开关可回退。

### B. VpnService 承载（再确认是否接受）
- Android 唯一惯常给 app 的“建路由”权力即 VpnService；用它运行 userspace 则与
  “不占系统 VPN 槽位”目标相悖 → 需要您对这一条目标的取舍确认。

### C. 特权/root 设备
- root 后 netlink 权限足够，可继续用原锁定 tsnet 完成 enroll 证据；检验的是特权域，
  不是普通 app 出货形态。适合先取证，不适合代表产品形态。

### D. 接受现状（诚实收口）
- 矩阵 9 行全 BLOCKED；保留：tailnet-core 12/12（Keystore 修复）、无 VPN 槽位/权限面
  证据、每用例前后审计稳定、**设备级(独立进程/shell)真实后端 OFFLINE 证据**（RUN-E）、
  完整诊断链 RUN-A→F、重建后锁定 AAR（varRoot 修复）。

## 建议
选 A（fork+no-host-route 补丁），与产品安全目标一致；我来出补丁设计→您确认→重建
新锁定源+AAR→真机 enroll→把 OFFLINE 行写 PASS（届时若需新 key 再提供，或复用已注册
节点做 warm-start 复用，可免新 key）。
