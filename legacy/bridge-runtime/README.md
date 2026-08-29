# bridge-runtime（WP-06）

<!-- 本文件描述的本地验证/镜像构建结果，not evidence of production readiness，不替代物理 Tailnet enrollment 与 Android/Bridge 物理 E2E。 -->

`bridge-runtime` 现在包含两个明确分开的层次：

1. **确定性开发 seam**：`file-backed-store.ts`、原 ingress/fake replay
   seam 与既有 contract tests，用于说明和回归测试，不作为生产证据。
2. **单主机生产栈**：Node `24.18.0` 内置 `node:sqlite`/SQLite `3.53.1`、
   同库多进程 lease/fencing、本地只读 Ed25519 pairing verifier、Unix socket
   runtime，以及锁定 Tailscale `v1.98.10` 源码构建的 Go tsnet ingress
   sidecar。

## 生产持久化

`src/node-sqlite-adapter.ts` 实现 `SqliteBridgeAdapterPort`：

- schema v1 固化 namespace/value/lease/transaction metadata；
- `BEGIN IMMEDIATE` 提供原子事务与跨连接写竞争串行化；
- value 使用闭合 JSON + bigint tag，非普通 JSON 值 fail closed；
- `backup()` 使用 SQLite backup API 并生成 SHA-256；
- `restore()` 先复制到隔离文件、quick check、再原子发布，并避免旧 WAL
  sidecar 被误用于新数据库；
- `recover()` 执行 SQLite quick/foreign-key recovery 检查；
- `createLeaseCoordinator()` 在同一 SQLite DB 中维护 scope 级单调
  fencing token，`transact()` 在同一事务内验证 lease 后才执行业务回调。

`src/local-pairing-ticket-verifier.ts` 只接受权限受控的 Ed25519 SPKI 公钥
文件与固定 envelope；签发私钥永不进入 runtime。ticket 的单次消费、
generation 与 binding 一致性仍由 durable pairing repository 在同一事务中
裁决。

`src/main.ts` 启动真实 runtime：打开/恢复 SQLite、加载 verifier、取得
lease、只监听 Unix socket，并提供 `/health/live` 与 `/health/ready`。
`/v1/control` 目前执行已验证 pairing ticket 与 tsnet peer fingerprint 的
绑定检查；notification/subscription/operation 的完整对外协议路由仍不是
本切片的生产 API 声明。

## 认证 ingress

`bridge-runtime/ingress/` 是 Go sidecar：

- 依赖锁定 `tailscale.com v1.98.10`；
- 只创建私有 tsnet TLS listener，不声明 host port/Funnel/public wildcard；
- 每个连接通过 LocalClient `WhoIs` 认证；
- 将 peer node key 的 SHA-256 fingerprint 写入仅由 runtime 信任的 sidecar
  header；
- 通过 Unix socket 反向代理到 Node runtime；
- hostname 默认 `agent-life-bridge`；
- control 服务器默认官方 `https://controlplane.tailscale.com`；
- auth key 可选：不提供时会在日志里打印官方登录网址，由操作员交互式登录。

`src/runtime-http.ts` 对直接进入 runtime 的请求强制要求单一合法 fingerprint
header。真实网络身份仍以 Go sidecar 的 `WhoIs` 为准；外部客户端伪造的
header 会被 sidecar 覆盖。

## 验证

从仓库根执行：

```bash
./tools/run-node24 npx --no-install vitest --root . run \
  bridge-contract/test bridge-runtime/test
npm --prefix bridge-runtime run typecheck
npm --prefix bridge-runtime run build
npm --prefix bridge-runtime run drill -- \
  --output "$PWD/docs/mvp/evidence/bridge/2026-08-18-node-sqlite.json"
(cd bridge-runtime/ingress && GOPROXY=off go mod verify && GOPROXY=off go test ./...)
bridge-runtime/deploy/verify-systemd.sh
bridge-runtime/deploy/verify-deployment-static.sh
```

完整生产命令是：

```bash
./bridge-runtime/deploy/verify-production.sh
```

该命令会执行 contract/runtime tests、typecheck/build、真实 SQLite drill、
Go module verify/test/build、systemd template verify、静态部署检查，以及
Docker Compose config 和 `build --pull`。当 Docker 不可用时输出
`BRIDGE_PRODUCTION_VERIFY_BLOCKED`，不会伪造镜像构建通过；Docker 可用时
输出 `BRIDGE_PRODUCTION_VERIFY_PASS`。

## 部署模板

`deploy/Dockerfile` 与 `deploy/Dockerfile.ingress` 使用固定 digest：

- Node：`node:24.18.0-bookworm-slim@sha256:6f7b03f7c2c8e2e784dcf9295400527b9b1270fd37b7e9a7285cf83b6951452d`
- Go builder：`golang:1.26.5-bookworm@sha256:53eeac89074db483fdf0ab3be1df32bf6e47562263d2d0d6baa7f26acb4957dd`

Compose 中 ingress 与 runtime 共享同一个 private network namespace，没有
`ports:`；数据库、tsnet state 与 Unix socket 分离，secret 均为 read-only
bind mount。systemd 模板同样没有 socket activation 或 host listener，并
使用 `ProtectSystem=strict`。

## 边界

> **边界声明**：本文件描述的本地验证/镜像构建结果，not evidence of production readiness，
> 不替代物理 Tailnet enrollment 与 Android/Bridge 物理 E2E。

- 首版只支持单主机、单 SQLite 文件；
- 不支持跨主机 lease/failover；
- 不启用公网 ingress/Funnel；
- 不引入远程 KMS/HSM；
- 本地 Go sidecar 尚未连接真实 Tailnet enrollment，也未获得物理
  DIRECT/DERP/offline 矩阵证据；
- Docker 镜像构建已执行（deploy-bridge / deploy-ingress），证据见 `docs/mvp/evidence/bridge/2026-08-18-docker-build.json`；
- source seam/fake tests 不提升为生产证据。
The deterministic file-backed store and fake port tests are not evidence of production readiness.
