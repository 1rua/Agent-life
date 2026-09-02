package com.openandroidintelligence.mobile.navigation

sealed interface AppDestination {
    data object ConversationHome : AppDestination
    data class ConversationThread(val conversationId: String) : AppDestination
    data object GatewayManagement : AppDestination
    data object AttachmentAndMedia : AppDestination
    data object PlatformSettings : AppDestination
    data object PluginManagement : AppDestination
}
