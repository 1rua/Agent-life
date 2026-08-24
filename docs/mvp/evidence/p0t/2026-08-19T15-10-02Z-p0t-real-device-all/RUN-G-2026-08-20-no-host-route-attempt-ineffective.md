# Run G — 2026-08-20：no-host-route 补丁尝试（无效）与最终收敛

## 尝试（用户委托“由我决定如何解决，直到任务完成”）
针对 App 进程内 `route ip+net: netlinkrib: permission denied`，按方案 A 尝试最小
补丁：在固定源 `wgengine/router/osrouter/router_linux.go` 的 `newUserspaceRouter`
上，`runtime.GOOS=="android"` 时返回 no-op 路由器（不写主机路由/规则/nft）。

## 结果：不生效（已如实回退）
- 补丁编译进运行库（`strings` 命中 `noop`，app merged lib 与补丁 AAR 一致），
  但真机 App 进程内 **仍报同一错误** `route ip+net: netlinkrib: permission denied`。
- 结论：tsnet v1.98.10 的 userspace 路由编程发生在补丁点之外/不可由该 hook 拦截
  的路径（`cidrDiff("route",…) → linuxRouter.addRoute` 仍被调用），继续深挖远超
  P0t 取证范围。受控补丁机制（lock source.patches/校验器）已回退，**锁定 AAR 回到
  干净可复现件 510182647f**（varRoot 修复，无补丁）；补丁文件保留于
  `proposals/no-host-route-attempt/` 供参考，不被任何门禁引用。

## 最终技术定性（多次交叉验证）
- 普通 Android app（untrusted_app）内，锁定 tsnet v1.98.10 userspace **无法启动**：
  它必然向主机路由表编程（netlink），SELinux 拒绝；无代码级开关/可拦截点。
- 同设备 **shell/特权进程** 可完整 Start+Running+backend OFFLINE（Run E，真实注册）。
- 因此“无 VpnService、普通 app 进程内跑嵌入式 tsnet”这一设计目标，在出厂 Android 上
  无法用当前锁定 tsnet 达成；出路需产品层决策（VpnService 承载 / 特权或 root /
  换用不碰主机路由的 userspace 实现/上游研究 tstun-nil），见 NEXT-DECISION.md。

## 收敛后的最终状态（诚实）
- 锁定 AAR：510182647f（可复现，verify 全绿）。
- tailnet-core connected：**13 tests / 12 PASS / 0 fail / 1 skip**（离线真实后端用例
  Assume 跳过）；transport 5/5；app 2/2（Run F 回归）。
- 设备级真实后端 OFFLINE（Run E，shell）与全部根因链 RUN-A→G、18/18 前后审计、
  无 VPN 槽位/权限面证据均保留。
- 矩阵 9 行：仍全 BLOCKED（各写明缺什么；OFFLINE(AAR)=平台 SELinux 限制）。
- 未在任何环节伪造；未把设备级(非 AAR)/源码级证据折算成矩阵 PASS。
