# OFFLINE 行推进预案（已写好、未执行，等待路径批复）

目的：把矩阵 OFFLINE 行用“真实 backend Path()==OFFLINE + 真机审计”从 BLOCKED
推到 PASS。**只有在用户批准“路径 B（自建 headscale+隧道）或 A（外部凭据）”后才执行。**

前提事实（本轮已实测）：手机↔主机 LAN 互通（16.6ms）；主机有 docker、可访问
github；产品 ALTSNET1 强制 https 控制面，Go 客户端只信公网 CA → 用
cloudflared quick tunnel（无账号、有效公网证书）解决 TLS 信任，无需给手机装 CA。

## 一、待新增测试（P0tOfflineRealBackendInstrumentedTest.kt，放 tailnet-core androidTest）

```kotlin
package com.agentlife.tailnet.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.agentlife.core.model.TransportPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 真实 backend OFFLINE：节点成功 enroll 一台只含本节点的 tailnet，并对一个
 * 不存在 Bridge peer 的绑定调用 native Path()，backend 必须真报 OFFLINE。
 * 需要 p0tOfflineBundle 指向已推送到设备的 ALTSNET1（由 provision-offline-bundle.sh
 * 生成，controlUrl=已就绪的 headscale+tunnel）。缺少该束时本用例明确失败（不冒充 PASS）。
 */
@RunWith(AndroidJUnit4::class)
class P0tOfflineRealBackendInstrumentedTest {

    @Test(timeout = 180_000)
    fun enrolledNodeReportsBackendOfflineForAbsentBridgePeer() {
        val path = InstrumentationRegistry.getArguments().getString("p0tOfflineBundle")
        val bundle: ByteArray = requireNotNull(path, { "p0tOfflineBundle missing; run provision-offline-bundle.sh" })
            .let { File(it) }
            .takeIf { it.isFile }?.readBytes()
            ?: error("p0tOfflineBundle file missing at $path")
        val before = P0tVpnAudit.vpnNetworkCount()
        val store = NoBackupTailnetStateStore(InMemoryEncryptedNoBackupState())
        val core = AndroidTsnetBinding(object : NativeEnrollmentSource {
            override fun bootstrapBytes(): ByteArray = bundle.copyOf()
        })
        runSuspend { core.start("p0t-offline-device", store) }
        val binding = P0tOfflineTestBinding.matching(bundle) // 与本束 enrollment 一致的绑定
        val pathResult = runRunCatching { core.path(binding) }
        assertTrue("native start/enroll must succeed against real control", pathResult.isSuccess)
        assertEquals(
            "absent pinned Bridge peer must be reported OFFLINE by backend",
            TransportPath.OFFLINE, pathResult.getOrThrow(),
        )
        // 必须 fail closed：没有该 peer 时也不建 VPN/不改路由
        assertEquals(before, P0tVpnAudit.vpnNetworkCount())
        runSuspend { core.stop() }
    }
}
```

说明：`P0tOfflineTestBinding.matching(bundle)` 需按 provisioning 脚本写入的具体字段
（deviceId、generation=7、policyRevision=2、appKeyFP/ticketDigest/policyDigest、
pinned IPv4）用 `VerifiedPairingTransportBindingFactory.mint` 构造与 enrollment
匹配的绑定。束里 pinned IPv4 填一个 tailnet 上不存在的 peer 地址即可让
`Path()` 走 OFFLINE 分支（wrapper: 匹配 binding 后无该 peer -> "OFFLINE"）。
`runRunCatching`/`InstrumentationRegistry` 需在本文件内补 import 与辅助函数。

## 二、provision-offline-bundle.sh（待新增，仿 fail-closed 版）

参数：`--control-url <https://...>`、`--auth-key <headscale-preauth>`；
默认字段：hostname=p0t-offline-device、pinnedIpv4=100.96.0.1（不存在 peer）、
generation=7、policyRevision=2、expiry=now+600、digests=确定性 sha256/base64url。
复用 `cmd/enrollment-bundle`（固定 Go 工具链），输出推送到
`/data/local/tmp/agentlife-p0t/offline.bundle`，校验设备端 sha 与主机一致。

## 三、headscale + 隧道 runbook（仅获批后执行）

```bash
# 1) 拉 headscale（docker 固定镜像版本）并起在 127.0.0.1:8080
docker run -d --name agentlife-p0t-headscale -p 127.0.0.1:8080:8080 \
  headscale/headscale:v0.23.0 rc  # 版本以 httpS(github releases) 实际为准

# 2) 建用户与一次性 preauth 密钥（5 分钟级）
headscale users create p0t
headscale apikeys create --expiration 1h
headscale preauthkeys create --user p0t --reusable=false --expiration 10m

# 3) 公网 https（cloudflared quick tunnel 无需账号、证书来自公有 CA）
cloudflared tunnel --url http://127.0.0.1:8080   # 打印 https://<rand>.trycloudflare.com

# 4) headscale 需处理 X-Forwarded（配置 trusted_proxies 或 metrics 之外的代理设置）

# 5) 用 <rand>.trycloudflare.com 做 controlUrl 生成 offline.bundle 并推送
# 6) 安装测试 APK 后：
#    am instrument -w -r -e p0tOfflineBundle /data/local/tmp/agentlife-p0t/offline.bundle \
#      -e class com.agentlife.tailnet.core.P0tOfflineRealBackendInstrumentedTest \
#      com.agentlife.tailnet.core.test/androidx.test.runner.AndroidJUnitRunner
# 7) 用 run-p0t-smallstep.sh 思路做 before/after 脱敏审计（VPN/route/DNS）
```

## 四、明确边界（诚实口径）

- OFFLINE 一旦通过：只把该行写成 PASS，证据=真机 enrolled + backend Path()==OFFLINE
  + 前后审计无 VPN/路由/DNS 变化。
- DIRECT/DERP 仍需 Bridge peer + WSS harness + 出口采集；approval/网络切换/Doze/
  第二 VPN 需各自控制器。本预案不把它们“顺带”写成 PASS。
- 全部命令都等“路径 B 获批”才执行；未获批前任何网络暴露动作一律不做。
