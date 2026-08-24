# Run F — 2026-08-20：AAR 重建落地 + 平台级 SELinux 限制定位

## 一、已执行的批准项：AAR 重建（wrapper 修复）

用户批准重建。对 `apps/android/tailnet-core/native/tsnetbridge/node.go` 的修复（已
构建为**最终锁定工件**并随重建落盘）：

1. `SetUserspaceVarRoot(path)` 导出 API + `userspaceVarRoot` 全局；
2. `tsnet.Server.Dir = varRoot`（优先级：调用方注入 > /proc/self/cmdline 推导）；
3. Android 上 Start 前 `os.Setenv(HOME/XDG_CONFIG_HOME)`（Go 侧 os.Setenv 实时更新
   Go 自身 env，解决 Java setenv 对 Go 不可见的问题）；
4. Kotlin：`TsnetBootstrap.configureUserspaceVarRoot()`（App/测试启动时注入私有
   filesDir 派生目录）。

最终锁定 digest：`sha256=510182647fffb0e1ee050cdcff28694db65fed6070bf189fdf916880fdf919ea`
（arm64-v8a,x86_64, 33337008 B）。同一源码状态两次构建 digest 相同（可复现性数据点）。
旧 AAR 与旧 lock 原样保留；锁文件为源/工具链级（不含 AAR digest），新 digest 已写入
`.sha256`/provenance，`verify-tsnet-aar.py` 全绿。

## 二、真机 App 进程的真实错误（决定性）

用诊断性重建把 wrapper 隐藏的错误暴露出（随后已还原为设计口径）：

```
tsnet: route ip+net: netlinkrib: permission denied
```

即 tsnet v1.98.10 在 `tsnet.Server.Start()` 阶段就调用主机路由编程（netlinkrib 后端
写 CGNAT/peer 路由），而 Android **untrusted_app 域**无 netlink 路由权限 → SELinux
拒绝 → 即使 varRoot 正常，进程内也**无法启动**。

对照：同设备 shell 进程（uid 2000）跑同一 tsnet（独立探针 tspb/tspb2）可 Start +
Running（真实注册）。→ 差异是 **SELinux 进程域**，不是代码/http://varRoot。

## 三、架构含义（需用户决策；不属于可伪造范围）

“嵌入 tsnet userspace、不建 VpnService、普通 app 进程内”的形态，在**非 root 的
主流 Android 上无法通过 v1.98.10 tsnet 启动**（路由编程被拒）。可选方向（另立设计
评估，不在这份证据里擅自替代）：

- 用 VpnService 承载（与该产品“不占系统 VPN 槽位”目标冲突，需重新确认）；
- 特权/系统组件或 root 设备；
- 更换/等待不支持主机路由编程的 userspace 形态，或换控制面/网络实现；
- 维持“设备级独立进程(特权/shell)证明 backend OFFLINE 语义”作为当前最强证据。

## 四、诚实口径与回归

- 矩阵 9 行仍全部 BLOCKED。OFFLINE(AAR 路径) 的阻塞输入更新为**平台 SELinux 限制**
  （非 key/非 varRoot）；DEvice 级真实后端 OFFLINE（standalone，shell）证据保留。
- 最终 AAR 回归：tailnet-core **12/12 PASS**（离线用例 Assume 跳过）、transport 5/5。
  日志：`iterations/05-regress-final-aar-*.log`。
- 未做任何伪装：未把 AAR/设备级证据折算成矩阵 PASS。

## 附：重建过程中的诚实记录

- 首次重建遇 `go mod verify` 报 x/mobile 缓存被 Gradle 写入污染 → 移入回收站目录后
  恢复“all modules verified”；
- 经历了 5 次迭代（Dir-only / Dir+Goenv / +API / +诊断 / 还原诊断），最终锁定
  510182647f；中间 digest 不落库。
