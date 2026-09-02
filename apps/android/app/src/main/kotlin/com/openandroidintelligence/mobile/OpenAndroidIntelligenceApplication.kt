package com.openandroidintelligence.mobile

import android.app.Application
import com.openandroidintelligence.kernel.AndroidAuditStore
import com.openandroidintelligence.kernel.CapabilityProviderSelector
import com.openandroidintelligence.kernel.DeveloperTrustMode
import com.openandroidintelligence.kernel.HostEnvelope
import com.openandroidintelligence.kernel.InMemoryAuditSink
import com.openandroidintelligence.kernel.NativePluginLoader
import com.openandroidintelligence.kernel.PhoneLimits
import com.openandroidintelligence.kernel.PluginKernel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 极简 Android 宿主组合根。
 *
 * 核心架构：
 * 1. 彻底解耦内建 Collector 与旧 Bridge 依赖；
 * 2. 零插件状态下仍具备完整的 Gateway v2 连接、多账号与对话附件支持；
 * 3. 平台内核（PluginKernel）独立初始化并管理受保护插件与审计。
 */
class OpenAndroidIntelligenceApplication : Application() {

    lateinit var kernel: PluginKernel
        private set

    lateinit var trustMode: DeveloperTrustMode
        private set

    lateinit var auditStore: AndroidAuditStore
        private set

    private lateinit var auditSink: InMemoryAuditSink

    /** 进程级连接运行时：登录、协商、会话建立与工作台装配。 */
    val gatewayRuntime: GatewayRuntime by lazy {
        GatewayRuntime(this, CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }

    fun platformSettingsEnvironment(): PlatformSettingsEnvironment = PlatformSettingsEnvironment(
        trustMode = trustMode,
        audit = auditStore,
        auditSink = auditSink,
        allowDeveloperTrustMode = BuildConfig.ALLOW_DEVELOPER_TRUST_MODE,
    )

    override fun onCreate() {
        super.onCreate()
        trustMode = DeveloperTrustMode()
        auditSink = InMemoryAuditSink()
        auditStore = AndroidAuditStore(sink = auditSink)

        val providerSelector = CapabilityProviderSelector(phoneDefaults = emptyMap())
        kernel = PluginKernel(
            hostEnvelope = HostEnvelope(
                primitives = setOf(
                    "org.openandroidintelligence.notifications.query@1.0.0",
                    "org.openandroidintelligence.sms.query@1.0.0",
                    "org.openandroidintelligence.call-log.query@1.0.0",
                ),
            ),
            phoneLimits = PhoneLimits(primitives = emptySet()),
            runtimes = emptyMap(),
            audit = auditStore,
            trustMode = trustMode,
            nativeLoader = NativePluginLoader(trustMode),
            providerSelector = providerSelector,
            grants = { null },
        )
    }
}
