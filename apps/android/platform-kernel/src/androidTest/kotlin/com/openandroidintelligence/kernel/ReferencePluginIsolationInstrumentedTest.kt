package com.openandroidintelligence.kernel

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openandroidintelligence.plugin.pkg.PluginIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReferencePluginIsolationInstrumentedTest {

    private val officialSmsIdentity = PluginIdentity(
        pluginId = "org.openandroidintelligence.sms",
        version = "1.0.0",
        authorKeyFingerprint = "35f3b7bf83777bc35fb080ee0e94abef8e945cbb6aa82414fa9cfcb7eaee699f",
    )

    private val officialNotificationsIdentity = PluginIdentity(
        pluginId = "org.openandroidintelligence.notifications",
        version = "1.0.0",
        authorKeyFingerprint = "35f3b7bf83777bc35fb080ee0e94abef8e945cbb6aa82414fa9cfcb7eaee699f",
    )

    private val officialCallLogIdentity = PluginIdentity(
        pluginId = "org.openandroidintelligence.call-log",
        version = "1.0.0",
        authorKeyFingerprint = "35f3b7bf83777bc35fb080ee0e94abef8e945cbb6aa82414fa9cfcb7eaee699f",
    )

    private val budget = ResourceBudget(
        maxInvocationMillis = 5_000L,
        maxMemoryBytes = 16 * 1024 * 1024L,
        maxOutputBytes = 1024 * 1024L,
        maxConcurrentInvocations = 2,
        maxDailyNetworkBytes = 0L,
    )

    private fun session(correlationId: String = "corr-test") = SessionConstraints(
        primitives = setOf(
            "org.openandroidintelligence.sms.query@1.0.0",
            "org.openandroidintelligence.notifications.query@1.0.0",
            "org.openandroidintelligence.call-log.query@1.0.0",
        ),
        background = false,
        correlationId = correlationId,
    )

    @Test
    fun referencePluginsHaveNoPrivilegeByAuthor() {
        val grantsMap = mutableMapOf<String, PairingGrant>()
        val providerSelector = CapabilityProviderSelector(
            phoneDefaults = mapOf(
                "org.openandroidintelligence.sms.query@1.0.0" to officialSmsIdentity,
            ),
        )

        val fakeAudit = AndroidAuditStore(InMemoryAuditSink())

        val fakeRuntime = object : PluginRuntime {
            override fun invoke(
                identity: PluginIdentity,
                budget: ResourceBudget,
                input: ByteArray,
            ): ByteArray = input
        }

        val trustMode = DeveloperTrustMode()
        val kernel = PluginKernel(
            hostEnvelope = HostEnvelope(
                setOf(
                    "org.openandroidintelligence.sms.query@1.0.0",
                    "org.openandroidintelligence.notifications.query@1.0.0",
                    "org.openandroidintelligence.call-log.query@1.0.0",
                ),
            ),
            phoneLimits = PhoneLimits(
                setOf(
                    "org.openandroidintelligence.sms.query@1.0.0",
                    "org.openandroidintelligence.notifications.query@1.0.0",
                    "org.openandroidintelligence.call-log.query@1.0.0",
                ),
            ),
            runtimes = mapOf(PluginKernel.RUNTIME_PROTECTED_WASM to fakeRuntime),
            audit = fakeAudit,
            trustMode = trustMode,
            nativeLoader = NativePluginLoader(trustMode),
            providerSelector = providerSelector,
            grants = { pairingId -> grantsMap[pairingId] },
        )

        // 注册官方 SMS 插件但未显式启用/未授权
        kernel.register(
            PluginRegistration(
                identity = officialSmsIdentity,
                runtimeType = PluginKernel.RUNTIME_PROTECTED_WASM,
                declaredPrimitives = setOf("org.openandroidintelligence.sms.query@1.0.0"),
                budget = budget,
            ),
        )

        // 1. 默认停用状态下调用必须拒绝
        try {
            kernel.invoke(
                identity = officialSmsIdentity,
                accountId = "account-a",
                pairingId = "pairing-a",
                capability = "org.openandroidintelligence.sms.query@1.0.0",
                input = ByteArray(0),
                session = session(),
            )
            fail("未启用状态下官方参考插件必须被拒绝")
        } catch (e: CapabilityDenied) {
            assertEquals("org.openandroidintelligence.sms.query@1.0.0", e.primitive)
        }

        // 启用插件但未给 pairing-a 授权
        kernel.enable(officialSmsIdentity.pluginId)
        try {
            kernel.invoke(
                identity = officialSmsIdentity,
                accountId = "account-a",
                pairingId = "pairing-a",
                capability = "org.openandroidintelligence.sms.query@1.0.0",
                input = ByteArray(0),
                session = session(),
            )
            fail("无配对授权时官方参考插件必须被拒绝")
        } catch (e: CapabilityDenied) {
            assertEquals("org.openandroidintelligence.sms.query@1.0.0", e.primitive)
        }

        // 2. 为 pairing-a 授予权限后调用成功
        grantsMap["pairing-a"] = PairingGrant(
            pairingId = "pairing-a",
            granted = setOf("org.openandroidintelligence.sms.query@1.0.0"),
            revision = 1L,
        )

        val result = kernel.invoke(
            identity = officialSmsIdentity,
            accountId = "account-a",
            pairingId = "pairing-a",
            capability = "org.openandroidintelligence.sms.query@1.0.0",
            input = "test".toByteArray(),
            session = session(),
        )
        assertEquals("test", String(result.output))

        // 3. 跨账号/跨配对隔离：pairing-b 仍然无权访问
        try {
            kernel.invoke(
                identity = officialSmsIdentity,
                accountId = "account-b",
                pairingId = "pairing-b",
                capability = "org.openandroidintelligence.sms.query@1.0.0",
                input = ByteArray(0),
                session = session(),
            )
            fail("未授权的 pairing-b 必须被拒绝")
        } catch (e: CapabilityDenied) {
            assertEquals("org.openandroidintelligence.sms.query@1.0.0", e.primitive)
        }
    }
}
