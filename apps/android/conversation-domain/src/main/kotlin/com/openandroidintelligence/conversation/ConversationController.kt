package com.openandroidintelligence.conversation

import com.openandroidintelligence.conversation.model.*
import kotlinx.coroutines.flow.StateFlow

sealed interface ConversationIntentResult {
    data object Success : ConversationIntentResult
    data class Failure(val reason: String) : ConversationIntentResult
}

interface ConversationController {
    val state: StateFlow<ConversationSessionState>
    suspend fun dispatch(intent: ConversationIntent): ConversationIntentResult
}
