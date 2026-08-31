package com.agentlife.conversation.ports

import com.agentlife.conversation.model.*
import kotlinx.coroutines.flow.Flow

data class ConversationScope(val accountId: String, val gatewayId: String)
data class PageRequest(val cursor: String? = null, val limit: Int = 50)
data class ConversationPage(val conversations: List<ConversationSummary>, val nextCursor: String?)
data class ConversationSummary(val id: ConversationId, val title: String, val updatedAt: Long)
data class Conversation(val id: ConversationId, val title: String, val createdAt: Long)
data class TimelinePage(val messages: List<TimelineMessage>, val nextCursor: String?)
data class TimelineMessage(
    val id: String,
    val sender: String,
    val parts: List<MessagePart>,
    val timestamp: Long,
    val state: String = "CONFIRMED",
)

data class MessageBatch(
    val batchId: String,
    val messages: List<OutgoingMessage>,
    val clientConversationId: String? = null,
)

data class OutgoingMessage(
    val clientMessageId: ClientMessageId,
    val text: String,
    val attachmentIds: List<String> = emptyList(),
    val command: MessagePart.Command? = null,
)

data class BatchAcceptance(val batchId: String, val acceptedMessageIds: List<String>)
data class MessageAcceptance(val messageId: String, val correlationId: String)

data class CancelGenerationResult(val success: Boolean, val message: String? = null)
data class CancelSubmissionResult(val success: Boolean)
data class PendingSubmissionIntent(val intentId: SubmitIntentId, val revision: Long)
data class LocalAttachmentSelection(val filename: String, val mediaType: String, val bytes: ByteArray)
data class AttachmentDraftState(val draftId: AttachmentDraftId, val state: AttachmentState, val progress: Float = 0f)

data class AgentCommandCatalog(val version: CatalogVersion, val commands: List<String>)
data class VerifiedConversationEvent(val eventType: String, val payload: Map<String, Any?>)

data class MirrorScope(val accountId: String)
interface MirrorSession

interface ConversationRepository {
    suspend fun listConversations(scope: ConversationScope, page: PageRequest): ConversationPage
    suspend fun createConversation(scope: ConversationScope, clientConversationId: String): Conversation
    suspend fun timeline(conversationId: String, page: PageRequest): TimelinePage
    suspend fun submitBatch(batch: MessageBatch): BatchAcceptance
    suspend fun submitMessage(message: OutgoingMessage): MessageAcceptance
    fun observeEvents(scope: ConversationScope): Flow<VerifiedConversationEvent>
    suspend fun cancelGeneration(generationId: String, requestId: String): CancelGenerationResult
}

interface AgentCommandCatalogRepository {
    suspend fun get(gatewayId: String, languageCode: String): AgentCommandCatalog
}

interface AttachmentDraftCoordinator {
    suspend fun prepare(selection: LocalAttachmentSelection): AttachmentDraft
    suspend fun armSubmission(draftId: String, revision: Long): PendingSubmissionIntent
    suspend fun cancelSubmission(intentId: String): CancelSubmissionResult
    fun observe(draftId: String): Flow<AttachmentDraftState>
}

interface ConversationMirrorStore {
    suspend fun open(scope: MirrorScope): MirrorSession
    suspend fun lock(scope: MirrorScope)
    suspend fun wipeForUnpairing(scope: MirrorScope)
    suspend fun wipeForLocalAccountRemoval(scope: MirrorScope)
}
