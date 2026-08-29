package com.agentlife.mobile

import android.app.Application
import com.agentlife.kernel.AndroidAuditStore
import com.agentlife.kernel.CapabilityProviderSelector
import com.agentlife.kernel.DeveloperTrustMode
import com.agentlife.kernel.HostEnvelope
import com.agentlife.kernel.NativePluginLoader
import com.agentlife.kernel.PhoneLimits
import com.agentlife.kernel.PluginKernel

/**
 * 极简 Android 宿主组合根。
 *
 * 核心架构：
 * 1. 彻底解耦内建 Collector 与旧 Bridge 依赖；
 * 2. 零插件状态下仍具备完整的 Gateway v2 连接、多账号与对话附件支持；
 * 3. 平台内核（PluginKernel）独立初始化并管理受保护插件与审计。
 */
class AgentLifeApplication : Application() {

    lateinit var kernel: PluginKernel
        private set

    lateinit var trustMode: DeveloperTrustMode
        private set

    lateinit var auditStore: AndroidAuditStore
        private set

    override fun onCreate() {
        super.onCreate()
        trustMode = DeveloperTrustMode()
        auditStore = AndroidAuditStore()

        val providerSelector = CapabilityProviderSelector(phoneDefaults = emptyMap())
        kernel = PluginKernel(
            hostEnvelope = HostEnvelope(
                primitives = setOf(
                    "org.agentlife.notifications.query@1.0.0",
                    "org.agentlife.sms.query@1.0.0",
                    "org.agentlife.call-log.query@1.0.0",
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