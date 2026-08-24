# AAR 重建提案：wrapper 设置 tsnet.Server.Dir（打通产品 AAR 的 enroll）

状态：**提案，未落码/未重建**。用户批准后才执行。
背景（Run D/E 实证）：Android 进程无可写 HOME；gomobile 在 Go 运行时启动时快照
environ，Java `Os.setenv(HOME/XDG_CONFIG_HOME)` 对 AAR 内 Go 不可见 → tsnet 建
`/.config`（根目录只读）瞬时失败 → AAR 版 enroll 无法在真实 App/测试进程内完成。

## 方案（最小、自包含、不依赖 Java 环境注入）

Tailscale v1.98.10 `tsnet.Server` 有 `Dir`：设置了就不走 `os.UserConfigDir()`。
改 repo 内已跟踪的 wrapper：

`apps/android/tailnet-core/native/tsnetbridge/node.go`：在 build `Server` 处，
Android 上从 `/proc/self/cmdline` 推导进程包名，把 `Dir` 设到
`/data/user/0/<pkg>/files/tsnet-uconfig-<shortName>`；非 Android 退回默认。

精确 diff（待批准后应用）：

```go
// androidWritableVarRoot derives an app-private writable dir from the
// process package name (Android has no usable $HOME; /proc/self/cmdline
// carries the package name, and /data/user/0/<pkg>/files is writable).
func androidWritableVarRoot() string {
	if runtime.GOOS != "android" {
		return ""
	}
	raw, err := os.ReadFile("/proc/self/cmdline")
	if err != nil {
		return ""
	}
	pkg := strings.TrimRight(string(raw), "\x00")
	if pkg == "" || pkg[0] != '/' {
		pkg = strings.TrimPrefix(pkg, "/")
	}
	// cmdline is the process name in argv[0]; on Android it is the package
	// name (possibly with an extra arg after a null). Take the first token.
	first := strings.SplitN(pkg, "\x00", 2)[0]
	if first == "" || strings.Contains(first, "/") {
		return ""
	}
	return "/data/user/0/" + first + "/files/tsnet-uconfig"
}
```
在 create Server 处：
```go
server := &tsnet.Server{
    Hostname:   enroll.Hostname,
    ControlURL: enroll.ControlURL,
    AuthKey:    string(authKey),
    Store:      store,
    Dir:        androidWritableVarRoot(), // 新增：可写、不依赖 HOME
    Ephemeral:  false,
    ...
}
```

说明：`/proc/self/cmdline` 对 Android App 进程可读（自有进程信息）；包名即进程 argv[0]，
`/data/user/0/<pkg>/files/` 为该 app 的可写 files 目录（与 noBackup 语义一致，tsnet
额外文件如 netmap/日志放此）。

## 执行 runbook（获批后）

1. 应用上述 diff（node.go 增加 imports: os, strings, runtime；当前已 import runtime? 核对）。
2. 跑 `tools/build-tsnet-aar.sh`（固定工具链/锁源）→ 生成新 AAR + 新 sha256 (+SBOM/notices
   借此脚本一并更新) → 校验脚本 `verify-tsnet-aar.py` 对新 digest 全绿。
3. 更新 `tsnet-aar.lock.json` 与 `libs/tsnet-android-1.98.10.aar.sha256` 等 sidecar
   为**新锁定工件**（旧 AAR/旧锁原样保留，便于回退；作为新增锁定版本，不算伪造/软链）。
4. 重编 app+tailnet-core 测试，真机跑：
   - 需**第三个一次性 auth key**（前两个均已消耗）；
   - `P0tAppOfflineRealBackendInstrumentedTest`（App 进程 + 新 AAR）冷启动注册 →
     `Path()==OFFLINE` → 无 VPN/路由/DNS 变化（每用例前后审计）；
   - 若通过：矩阵 **OFFLINE= PASS**（真实 AAR 适配器 + 真机 + 真实 backend 信号）。
5. 全仓 `check`/unit 回归后提交（中文 commit message）。

## 影响与回退

- 新 AAR digest 与原锁不同 → 属受控的新锁定版本；`verifyTsnetAar` 校验随之更新。
- 回退：恢复旧 sidecar/旧 AAR 即可（两者都在 repo/工具链内保留）。
- 不触碰：协议/bundle 格式/权限面/现有测试。

## 待确认

- 【需用户批准】执行本重建（供应链/安全相关，按项目规矩先请示）。
