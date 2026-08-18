# MVP-DEP-TSNET 锁行与 NDK provisional digest 审计（2026-08-17）

审计时点：本地 2026-08-17（UTC 时间处于前一日期）。对象：`docs/mvp/mvp-dependency-lock.md` 的 `MVP-DEP-TSNET` 行、`apps/android/tailnet-core/native/tsnetbridge/tsnet-aar.lock.json` 的 `toolchain.androidNdk`。

## 锁行更新

`MVP-DEP-TSNET` 已改为锁定的真实 artifact：

- release：`tailscale-v1.98.10`
- tag object：`0ee734d3089846b27bc6ebcddd3d6ee5ec13e04d`
- commit：`36550d57f4a4055246ef7412f4e650a012a465f1`
- AAR `sha256:a654e487f88cc35a7baa238666151746f83dec12000a3078f6a83508df791e87`
- ABIs：arm64-v8a + x86_64

`tools/compute-lock-hashes.ts` 中的 TSNET 行同步更新，重算 integrity 为 `sha256:c0022aac5dda0ee560810fe055223d86f19eff8bf8ada314a3a40e503ae7f134`（仅 TSNET 行变化，其余行不变）。

验证：

```text
$ python3 apps/android/tailnet-core/tools/verify-tsnet-aar.py ... 
TSNET_AAR_VERIFIED sha256=a654e487f88cc35a7baa238666151746f83dec12000a3078f6a83508df791e87 abis=arm64-v8a,x86_64 size=33331528

$ npm run mvp:lock:check
MVP dependency lock: PASS (7 rows)
```

## NDK provisional digest 审计结论

`tsnet-aar.lock.json` 当前值：

```json
{
  "archive": "android-ndk-r27c-linux.zip",
  "revision": "27.2.12479018",
  "sha256": "59c2f6dc96743b5daf5d1626684640b20a6bd2b1d85b13156b90333741bad5cc",
  "provisionalDigest": true
}
```

已核实的部分：

- 本地 `.toolchains/downloads/android-ndk-r27c-linux.zip`（663,987,688 字节）的实际 SHA-256 = `59c2f6dc…bad5cc`，与锁中 `sha256` 一致。
- 已安装 NDK `27.2.12479018` 的 `source.properties` 报告 `Pkg.Revision = 27.2.12479018`、`Pkg.ReleaseName = r27c`，目录名与 revision 一致。

未能完成“取消 provisional”的部分：

- `verify-tsnet-toolchain.sh` / `bootstrap-tsnet-toolchain.sh` 要求存在完整 `resolvedDigests`（go / gomobile / androidNdk / androidSdk / jdk 全部）后才能把 `provisionalDigest` 置 `false`。
- 本地 `go1.26.5.linux-amd64.tar.gz` 摘要匹配，但 `.toolchains/go-1.26.5` 的 Go 源码树出现 `internal/abi/map.go` 与 `map_swiss.go` 重复声明，无法用 `go version -m` 稳定重建锁定版本的 gomobile（`v0.0.0-20240806205939-81131f6468ab`）；现有 gomobile 均为其他版本。
- 因此当前不能诚实地把 NDK 标记为 fully-resolved。**维持 `provisionalDigest=true`、`resolvedDigests` 缺失**，这是 fail-closed 的正确状态；本次不改动 tsnet-aar.lock.json。

## 后续解除 provisional 的前置条件

1. 修复/重装 stock `go1.26.5`（消除 `internal/abi` 重复声明），并单独记录其 binary 与 directory manifest。
2. 用锁定 `golang.org/x/mobile v0.0.0-20240806205939-81131f6468ab` 重建 gomobile/gobind 到 `.toolchains/gomobile-<locked-version>`，记录 build-info。
3. 运行 `bash apps/android/tailnet-core/tools/bootstrap-tsnet-toolchain.sh --download`（或等价离线 verifier），生成并校验 `resolvedDigests`。
4. 校验通过后，锁行中的 `toolchain digest remains provisional` 标注可移除。
