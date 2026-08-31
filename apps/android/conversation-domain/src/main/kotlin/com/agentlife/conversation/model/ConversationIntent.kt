package com.agentlife.conversation.model

sealed interface ConversationEvent {
    data object OpenSurface : ConversationEvent
    data object CloseSurface : ConversationEvent
    data object DockSurface : ConversationEvent
    data object ExpandSurface : ConversationEvent
    data object StartScreenSelection : ConversationEvent
    data object PreviewCrop : ConversationEvent
    data object TerminateSurface : ConversationEvent

    data class EditInput(val text: String) : ConversationEvent
    data object AttachmentsPending : ConversationEvent
    data object AllAttachmentsReady : ConversationEvent
    data class PrepareSubmit(val intentId: SubmitIntentId) : ConversationEvent
    data object SubmitStarted : ConversationEvent
    data object SubmitAccepted : ConversationEvent
    data class SubmitFailed(val reason: String) : ConversationEvent

    data object GenerationQueued : ConversationEvent
    data object GenerationStarted : ConversationEvent
    data object RequestCancelGeneration : ConversationEvent
    data object GenerationCancelled : ConversationEvent
    data object GenerationCompleted : ConversationEvent
    data class GenerationFailed(val reason: String) : ConversationEvent
    data object GenerationUnsupported : ConversationEvent
    data object GenerationOutcomeUnknown : ConversationEvent

    data class SyncStateChanged(val syncState: MirrorSyncState) : ConversationEvent
}
