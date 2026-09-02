package com.openandroidintelligence.mobile

/**
 * The three visible core destinations of the Android host.
 *
 * The architecture spec fixes the main navigation to exactly these surfaces:
 * gateway/account, conversation, and attachments. Plugin, permission and audit
 * management live under the platform settings screen, never as top-level
 * destinations — [ArchitectureBoundaryTest] holds this boundary.
 */
data class GatewayDestination(
    val baseUrl: String,
    val isConnected: Boolean = false,
    val activeAccountId: String? = null,
)

data class ConversationDestination(
    val activeSessionId: String? = null,
)

data class AttachmentDestination(
    val uploadedCount: Int = 0,
)

data class CoreNavigation(
    val gateway: GatewayDestination,
    val conversations: ConversationDestination,
    val attachments: AttachmentDestination,
)
