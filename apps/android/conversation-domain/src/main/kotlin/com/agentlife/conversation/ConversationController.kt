package com.agentlife.conversation

import com.agentlife.conversation.model.*
import kotlinx.coroutines.flow.StateFlow

sealed interface ConversationIntentResult {
    data object Success : ConversationIntentResult
    data class Failure(val reason: String) : ConversationIntentResult
}

interface ConversationController {
    val state: StateFlow<ConversationSessionState>
    suspend fun dispatch(intent: ConversationIntent): ConversationIntentResult
}
