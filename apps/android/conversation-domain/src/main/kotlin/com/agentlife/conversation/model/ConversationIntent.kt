package com.agentlife.conversation.model

import com.agentlife.conversation.ports.ConversationScope

sealed interface ConversationIntent {
    data class ActivateScope(val scope: ConversationScope) : ConversationIntent
    data class EditDraft(val text: String) : ConversationIntent
    data object Send : ConversationIntent
    data class CancelGeneration(val requestId: String) : ConversationIntent
    data class AddAttachment(val draftId: AttachmentDraftId) : ConversationIntent
    data class RemoveAttachment(val draftId: AttachmentDraftId) : ConversationIntent
    data class RetryAttachment(val draftId: AttachmentDraftId) : ConversationIntent
}
