package com.agentlife.mobile

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 极简 Android 宿主主界面。
 *
 * 核心三屏导航：
 * 1. Gateway 状态与连接 (GatewayScreen)
 * 2. 对话交互 (ConversationScreen)
 * 3. 附件管理 (AttachmentPicker)
 * 4. 平台设置 (PlatformSettingsScreen)
 */
data class CoreNavigation(
    val gateway: GatewayDestination,
    val conversations: ConversationDestination,
    val attachments: AttachmentDestination,
)

class MainActivity : Activity() {

    private lateinit var application: AgentLifeApplication
    private val gatewayPresenter = GatewayPresenter()
    private val conversationPresenter = ConversationPresenter()
    private val attachmentPresenter = AttachmentPresenter()
    private lateinit var settingsPresenter: PlatformSettingsPresenter

    private var currentTab: String = "gateway"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        application = getApplication() as AgentLifeApplication
        val allowTrust = BuildConfig.ALLOW_DEVELOPER_TRUST_MODE
        val allowPlugins = BuildConfig.ALLOW_RUNTIME_PLUGINS
        settingsPresenter = PlatformSettingsPresenter(
            DistributionPolicy(
                allowRuntimePlugins = allowPlugins,
                allowDeveloperTrustMode = allowTrust,
            ),
        )
        renderCurrentTab()
    }

    fun navigateTo(tab: String) {
        currentTab = tab
        renderCurrentTab()
    }

    fun currentNavigation(): CoreNavigation = CoreNavigation(
        gateway = GatewayDestination(baseUrl = gatewayPresenter.currentState().endpointUrl),
        conversations = ConversationDestination(),
        attachments = AttachmentDestination(uploadedCount = attachmentPresenter.currentState().attachments.size),
    )

    private fun renderCurrentTab() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        // 导航栏
        val navBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        navBar.addView(Button(this).apply {
            text = "Gateway"
            setOnClickListener { navigateTo("gateway") }
        })
        navBar.addView(Button(this).apply {
            text = "Chat"
            setOnClickListener { navigateTo("chat") }
        })
        navBar.addView(Button(this).apply {
            text = "Files"
            setOnClickListener { navigateTo("files") }
        })
        navBar.addView(Button(this).apply {
            text = "Settings"
            setOnClickListener { navigateTo("settings") }
        })
        root.addView(navBar)

        val content = when (currentTab) {
            "gateway" -> renderGatewayTab()
            "chat" -> renderChatTab()
            "files" -> renderFilesTab()
            "settings" -> renderSettingsTab()
            else -> renderGatewayTab()
        }
        root.addView(content)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun renderGatewayTab(): View {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val state = gatewayPresenter.currentState()
        layout.addView(TextView(this).apply {
            text = "Gateway Connection"
            textSize = 20f
        })
        val urlInput = EditText(this).apply {
            setText(state.endpointUrl)
        }
        layout.addView(urlInput)
        val statusText = TextView(this).apply {
            text = if (state.isOnline) "Connected to Gateway" else "Disconnected"
        }
        layout.addView(statusText)
        layout.addView(Button(this).apply {
            text = if (state.isOnline) "Disconnect" else "Connect"
            setOnClickListener {
                if (state.isOnline) {
                    gatewayPresenter.disconnect()
                } else {
                    gatewayPresenter.connect(urlInput.text.toString())
                }
                renderCurrentTab()
            }
        })
        return layout
    }

    private fun renderChatTab(): View {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(TextView(this).apply {
            text = "Conversation"
            textSize = 20f
        })
        val state = conversationPresenter.currentState()
        for (msg in state.messages) {
            layout.addView(TextView(this).apply {
                text = "${msg.sender}: ${msg.text}"
            })
        }
        val input = EditText(this).apply { hint = "Type a message..." }
        layout.addView(input)
        layout.addView(Button(this).apply {
            text = "Send"
            setOnClickListener {
                val txt = input.text.toString()
                if (txt.isNotBlank()) {
                    conversationPresenter.sendMessage(txt)
                    input.setText("")
                    renderCurrentTab()
                }
            }
        })
        return layout
    }

    private fun renderFilesTab(): View {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(TextView(this).apply {
            text = "Attachments"
            textSize = 20f
        })
        val state = attachmentPresenter.currentState()
        layout.addView(TextView(this).apply {
            text = "Uploaded count: ${state.attachments.size}"
        })
        for (att in state.attachments) {
            layout.addView(TextView(this).apply {
                text = "${att.name} (${att.sizeBytes} bytes)"
            })
        }
        return layout
    }

    private fun renderSettingsTab(): View {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(TextView(this).apply {
            text = "Platform Settings"
            textSize = 20f
        })
        val state = settingsPresenter.currentState()
        layout.addView(TextView(this).apply {
            text = "Developer Trust Mode: ${state.developerTrustModeEnabled}"
        })
        layout.addView(Button(this).apply {
            text = "Toggle Trust Mode"
            setOnClickListener {
                settingsPresenter.toggleDeveloperTrustMode(!state.developerTrustModeEnabled)
                renderCurrentTab()
            }
        })
        return layout
    }
}
