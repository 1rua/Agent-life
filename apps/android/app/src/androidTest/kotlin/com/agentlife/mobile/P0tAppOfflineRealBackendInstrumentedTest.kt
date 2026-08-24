package com.agentlife.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agentlife.core.model.BridgeIdentity
import com.agentlife.core.model.EnrollmentTicket
import com.agentlife.core.model.PolicyAttestation
import com.agentlife.core.model.TransportPath
import com.agentlife.core.model.VerifiedPairingTransportBinding
import com.agentlife.tailnet.core.AndroidTsnetBinding
import com.agentlife.tailnet.core.InMemoryEncryptedNoBackupState
import com.agentlife.tailnet.core.TsnetBootstrap
import com.agentlife.tailnet.core.NativeEnrollmentSource
import com.agentlife.tailnet.core.NoBackupTailnetStateStore
import com.agentlife.tailnet.core.VerifiedPairingTransportBindingFactory
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * 真实 backend OFFLINE（生产组合路径）：在正式 App 进程（com.agentlife.mobile，
 * AgentLifeApplication 已在 onCreate 设置 HOME/XDG_CONFIG_HOME）里，用真实
 * Tailnet 一次性 auth key 冷启动 AndroidTsnetBinding 注册节点，再对“tailnet 上
 * 不存在的固定 Bridge peer”绑定调用 native Path()，backend 必须报 OFFLINE，且
 * 不创建任何系统 VPN agent。
 *
 * 需要 `p0tOfflineBundle`（provision-enroll-bundle.sh 生成，含真实一次性 key）。
 * 未提供时 Assume 跳过（skipped，绝不计 PASS，也不消耗 key）。
 */
@RunWith(AndroidJUnit4::class)
class P0tAppOfflineRealBackendInstrumentedTest {

    @Test(timeout = 300_000)
    fun enrolledOnDeviceAppReportsBackendOfflineForAbsentBridgePeer() {
        val args = InstrumentationRegistry.getArguments()
        val bundleArg = args.getString("p0tOfflineBundle")
        assumeTrue("p0tOfflineBundle not provided", bundleArg != null)
        val file = File(bundleArg!!)
        assumeTrue("bundle file missing at $bundleArg", file.isFile)
        val bundle = file.readBytes()

        // 进程内注入可写 varRoot（配合重建后的 AAR 的 SetUserspaceVarRoot）。
        TsnetBootstrap.configureUserspaceVarRoot(
            File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "tsnet-uconfig").absolutePath,
        )

        // 可选 warm-start：p0tOfflineState 是已注册节点的 ALSTATE1 快照，
        // p0tWarmBundle 是无 authkey 的 warm 束 → 复用已注册 identity，无需新 key。
        val stateArg = args.getString("p0tOfflineState")
        val warmBundleArg = args.getString("p0tWarmBundle")
        val warmState: ByteArray? = stateArg?.let { File(it).takeIf { f -> f.isFile }?.readBytes() }
        val store = NoBackupTailnetStateStore(
            InMemoryEncryptedNoBackupState().also { if (warmState != null) it.write(warmState) },
        )
        val activeBundle: ByteArray = if (warmState != null && warmBundleArg != null) {
            File(warmBundleArg).readBytes()
        } else {
            bundle
        }
        val core = AndroidTsnetBinding(object : NativeEnrollmentSource {
            override fun bootstrapBytes(): ByteArray = activeBundle.copyOf()
        })
        try {
            val t0 = System.currentTimeMillis()
            val startFailure = runSuspendCatching { core.start("p0t-app-offline", store) }
            val elapsed = System.currentTimeMillis() - t0
            if (startFailure != null) {
                throw IllegalStateException("app native start failed after ${elapsed}ms: ${startFailure.message}", startFailure)
            }
            android.util.Log.i("P0tAppOffline", "enroll OK in ${elapsed}ms")

            var path: TransportPath? = null
            runSuspendCatching { path = core.path(trustedBinding()) }
            assertEquals("absent pinned Bridge peer must be backend OFFLINE", TransportPath.OFFLINE, path)

        } finally {
            runSuspendCatching { core.stop() }
        }
    }

    private fun trustedBinding(): VerifiedPairingTransportBinding {
        val now = System.currentTimeMillis() / 1000L
        val fp = b64url32("app-key-fp")
        val ticket = EnrollmentTicket(
            id = "p0t-offline-ticket",
            deviceId = "p0t-offline-device-1",
            bridgeIdentity = fp,
            pairingGeneration = 7u,
            minimumPolicyRevision = 2u,
            expiresAtEpochSeconds = now + 600L,
            used = false,
            digest = b64url32("ticket-digest"),
        )
        return VerifiedPairingTransportBindingFactory.mint(
            ticket = ticket,
            bridge = BridgeIdentity(fp),
            policy = PolicyAttestation(2u, b64url32("policy-digest")),
            expectedPairingGeneration = 7u,
            nowEpochSeconds = now,
        )
    }

    private companion object {
        fun b64url32(label: String): String =
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(label.toByteArray()))
    }

    private fun runSuspendCatching(block: suspend () -> Unit): Throwable? {
        var throwable: Throwable? = null
        var resumed = false
        block.startCoroutine(object : Continuation<Unit> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<Unit>) {
                throwable = result.exceptionOrNull()
                resumed = true
            }
        })
        if (!resumed) error("suspend block did not complete synchronously")
        return throwable
    }
}
