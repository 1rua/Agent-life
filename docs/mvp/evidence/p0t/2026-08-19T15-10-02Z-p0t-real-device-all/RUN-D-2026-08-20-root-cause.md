# Run D — 2026-08-20：根因彻底定位（HOME）与 key 状态

## 一句话结论

设备 enroll 的**唯一真实障碍**是 **Android 进程没有可写 HOME** 导致嵌入式 tsnet 无法
创建 `$HOME/.config`（“mkdir /.config: read-only file system”）而瞬时失败。DNS/
网络/证书/AAR 解析器等此前怀疑项全部排除。设置 HOME 后可写目录后，设备真实 tsnet
冷启动成功连到官方控制面并在 ~3s 收到服务器的显式响应：

```
DIRECT_UP_ERR="tsnet.Up: backend: invalid key: API key <redacted-consumed-key> not valid" after=3.177s
```

即：设备侧已完全打通，当前仅剩**一次性 auth key 已被消耗**（被诊断中首次成功的注册
用掉），需一个新的一次性 key 即可完成 AAR 版 enroll → Path()==OFFLINE。

## 实证链（全部存档/可复现）

1. AAR 版（instrumentation）失败耗时 19ms→134ms：与“纯 Go 探针 4ms 失败”“设备无
   /etc/resolv.conf”一度指向解析器，但改 `netdns=cgo` 无效；
2. 主机对照：同束 Start 60s 超时（当时误判为账户/批准）；
3. **设备独立 cgo 版真实 tsnet 探针（非 gomobile）**：
   - HOME 未设：`tsnet: mkdir /.config: read-only file system`（0s）← 真因；
   - `HOME=/data/local/tmp`：`DIRECT_START_OK`，且已有注册态下 `DIRECT_STATE=Running`
     45ms；
   - 干净冷启动（新状态目录）：连上官方控制面 → 服务器返回
     `invalid key: API key <redacted-consumed-key> not valid`（3.177s）；
4. 结论：设备网络可连控制面（3s 往返）、注册机制正常、key 已一次性消耗。

## 生产修复（已应用，编译通过）

- `app/src/main/kotlin/com/agentlife/mobile/AgentLifeApplication.onCreate()`：
  启动时 `Os.setenv("HOME", noBackupFilesDir) `与 `XDG_CONFIG_HOME` → tsnet varRoot
  可写且不备份。不加此修复，正式 App 在任何 Android 上都无法 enroll。
- 测试侧：`P0tOfflineRealBackendInstrumentedTest.@BeforeClass` 同样先设 HOME
  （测试进程 filesDir）。

## 剩余输入（需用户提供）

- **一个新的临时一次性 Tailscale auth key**（原 key 已被注册节点 `p0t-offline-device`
  一次性消耗；该节点现存在于您的 tailnet，可从管理台删除，不影响后续）。
- 拿到后我即可跑 AAR 版 OFFLINE：设 HOME 已内建 → 冷启动注册 → 对不存在的 pinned
  Bridge peer 调 `Path()` → 若 backend 报 OFFLINE 且无 VPN，把矩阵 OFFLINE 行写为
  PASS（附每用例 before/after 审计）。
