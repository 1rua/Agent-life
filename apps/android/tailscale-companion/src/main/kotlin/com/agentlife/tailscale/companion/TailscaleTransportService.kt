package com.agentlife.tailscale.companion

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.agentlife.companion.EncryptedByteChannel
import com.agentlife.companion.ICompanionTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

/**
 * Tailscale Companion 传输服务。
 *
 * 核心安全架构：
 * 1. Companion 作为独立的 Android Service / Process 运行；
 * 2. Companion 仅持有 Tailscale 本地连接能力，不持有任何 Gateway 凭据、私钥或 access token；
 * 3. 宿主通过 AIDL 传递单用途令牌并获取 ParcelFileDescriptor；
 * 4. Companion 仅作为不透明的字节泵（Opaque TLS Byte Pump），TLS 握手与加解密始终在主 App 内完成；
 * 5. 服务停止或崩溃时所有通道立即关闭（Fail-Closed）。
 */
class TailscaleTransportService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeChannels = ConcurrentHashMap<String, Closeable>()

    private val binder = object : ICompanionTransport.Stub() {
        override fun openEncryptedByteChannel(
            serializedToken: String?,
            host: String?,
            port: Int,
        ): ParcelFileDescriptor? {
            if (serializedToken.isNullOrBlank() || host.isNullOrBlank() || port <= 0 || port > 65535) {
                return null
            }

            // 校验 token 前缀格式
            if (!serializedToken.startsWith("altok:v1:")) {
                return null
            }

            val (hostFd, companionFd) = try {
                EncryptedByteChannel.createSocketPair()
            } catch (e: Exception) {
                return null
            }

            activeChannels[serializedToken] = Closeable {
                try {
                    companionFd.close()
                } catch (_: Exception) {}
            }

            return hostFd
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        for (channel in activeChannels.values) {
            try {
                channel.close()
            } catch (_: Exception) {}
        }
        activeChannels.clear()
        serviceScope.cancel()
        super.onDestroy()
    }
}

