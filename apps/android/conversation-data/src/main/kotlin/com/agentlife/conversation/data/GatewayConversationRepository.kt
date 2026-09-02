package com.agentlife.conversation.data

import com.agentlife.conversation.model.CatalogVersion
import com.agentlife.conversation.model.ConversationId
import com.agentlife.conversation.ports.AgentCommandCatalog
import com.agentlife.conversation.ports.AgentCommandCatalogRepository
import com.agentlife.conversation.ports.BatchAcceptance
import com.agentlife.conversation.ports.Conversation
import com.agentlife.conversation.ports.ConversationPage
import com.agentlife.conversation.ports.ConversationRepository
import com.agentlife.conversation.ports.ConversationScope
import com.agentlife.conversation.ports.ConversationSummary
import com.agentlife.conversation.ports.MessageAcceptance
import com.agentlife.conversation.ports.MessageBatch
import com.agentlife.conversation.ports.OutgoingMessage
import com.agentlife.conversation.ports.PageRequest
import com.agentlife.conversation.ports.TimelinePage
import com.agentlife.conversation.ports.VerifiedConversationEvent
import com.agentlife.gateway.commands.CommandCatalogClient
import com.agentlife.gateway.conversations.BatchAcceptance as WireBatchAcceptance
import com.agentlife.gateway.conversations.ConversationClient
import com.agentlife.gateway.conversations.MessageBatchRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull

/**
 * The domain's conversation port, backed by the real Gateway Protocol v2 client.
 *
 * Nothing here manufactures a conversation, a message or an outcome. Every
 * result is whatever the Gateway returned, and a failure keeps its code so the
 * UI can tell "empty" from "we could not ask".
 */
class GatewayConversationRepository(
    private val client: ConversationClient,
    private val decoder: GatewayEventDecoder = GatewayEventDecoder,
    /** The thread cancellation and event scope act on; owned by the screen holder. */
    private val activeConversationId: () -> String? = { null },
) : ConversationRepository, com.agentlife.conversation.ports.GenerationTracker {

    private val _generationId = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    override val generationId: kotlinx.coroutines.flow.StateFlow<String?> = _generationId


    override suspend fun listConversations(
        scope: ConversationScope,
        page: PageRequest,
    ): ConversationPage {
        val threads = client.readThreads(cursor = page.cursor, limit = page.limit)
        return ConversationPage(
            conversations = threads.map { thread ->
                ConversationSummary(
                    id = ConversationId(thread.conversationId),
                    title = thread.title?.takeIf { it.isNotBlank() } ?: "新对话",
                    updatedAt = parseMillis(thread.lastMessageAt),
                )
            },
            // The v2 list endpoint is not cursor-paginated yet; a single page is
            // the honest answer instead of an invented next cursor.
            nextCursor = null,
        )
    }

    override suspend fun createConversation(
        scope: ConversationScope,
        clientConversationId: String,
    ): Conversation {
        val detail = client.createConversation(clientConversationId)
        return Conversation(
            id = ConversationId(detail.conversationId),
            title = detail.title ?: "新对话",
            createdAt = parseMillis(detail.createdAt),
        )
    }

    override suspend fun timeline(conversationId: String, page: PageRequest): TimelinePage {
        val result = client.readTimeline(
            conversationId = conversationId,
            cursor = page.cursor,
            limit = page.limit,
        )
        return TimelinePage(
            messages = result.messages.map { message ->
                com.agentlife.conversation.ports.TimelineMessage(
                    id = message.messageId,
                    sender = message.sender,
                    parts = message.parts.map { part ->
                        when (part) {
                            is com.agentlife.gateway.conversations.MessagePart.Text ->
                                com.agentlife.conversation.model.MessagePart.Text(part.text)
                            is com.agentlife.gateway.conversations.MessagePart.AttachmentRef ->
                                com.agentlife.conversation.model.MessagePart.Attachment(
                                    com.agentlife.conversation.model.AttachmentDraftId(part.attachmentId),
                                )
                        }
                    },
                    timestamp = message.timestamp ?: 0L,
                    state = message.state,
                )
            },
            nextCursor = result.nextCursor,
        )
    }

    override suspend fun submitBatch(batch: MessageBatch): BatchAcceptance {
        val conversationId = batch.clientConversationId
            ?: activeConversationId()
            ?: throw IllegalStateException("SUBMIT_BATCH_FAILED:no-conversation")
        val acceptance: WireBatchAcceptance = client.submitBatch(
            conversationId = conversationId,
            batch = MessageBatchRequest(
                clientBatchId = batch.batchId,
                clientConversationId = conversationId,
                members = batch.messages.map { message ->
                    MessageBatchRequest.BatchMember(
                        clientMessageId = message.clientMessageId.value,
                        text = message.text,
                        attachmentIds = message.attachmentIds,
                    )
                },
            ),
        )
        return BatchAcceptance(
            batchId = acceptance.batchId,
            acceptedMessageIds = acceptance.memberIds.values.toList(),
        )
    }

    override suspend fun submitMessage(message: OutgoingMessage): MessageAcceptance {
        val conversationId = activeConversationId()
            ?: throw IllegalStateException("SUBMIT_MESSAGE_FAILED:no-conversation")
        val response = client.sendMessageBatch(
            conversationId = conversationId,
            batch = com.agentlife.gateway.conversations.OutgoingMessageBatch(
                clientConversationId = conversationId,
                correlationId = message.clientMessageId.value,
                messages = listOf(
                    com.agentlife.gateway.conversations.OutgoingMessage(
                        role = "user",
                        parts = buildList {
                            if (message.text.isNotEmpty()) {
                                add(com.agentlife.gateway.conversations.MessagePart.Text(message.text))
                            }
                            message.attachmentIds.forEach { id ->
                                add(com.agentlife.gateway.conversations.MessagePart.AttachmentRef(id))
                            }
                        },
                        timestamp = System.currentTimeMillis(),
                    ),
                ),
            ),
        )
        return MessageAcceptance(
            messageId = response.conversationId,
            correlationId = message.clientMessageId.value,
        )
    }

    /**
     * Cancellation results stay a closed set.
     *
     * A 404 means the generation already finished, which is a different fact
     * from "we asked and it stopped"; the UI shows them differently.
     */
    override suspend fun cancelGeneration(
        generationId: String,
        requestId: String,
    ): com.agentlife.conversation.ports.CancelGenerationResult {
        val conversationId = activeConversationId()
            ?: return com.agentlife.conversation.ports.CancelGenerationResult(
                outcome = com.agentlife.conversation.ports.CancelGenerationOutcome.UNSUPPORTED,
                message = "NO_ACTIVE_CONVERSATION",
            )
        val outcome = client.cancelGeneration(conversationId, generationId, requestId)
        return com.agentlife.conversation.ports.CancelGenerationResult(
            outcome = when (outcome) {
                "CANCELLED" -> com.agentlife.conversation.ports.CancelGenerationOutcome.CANCELLED
                "ALREADY_COMPLETED" -> com.agentlife.conversation.ports.CancelGenerationOutcome.ALREADY_COMPLETED
                "UNSUPPORTED" -> com.agentlife.conversation.ports.CancelGenerationOutcome.UNSUPPORTED
                else -> com.agentlife.conversation.ports.CancelGenerationOutcome.OUTCOME_UNKNOWN
            },
            message = outcome,
        )
    }

    override fun observeEvents(scope: ConversationScope): Flow<VerifiedConversationEvent> =
        client.rawEvents().mapNotNull { event ->
            decoder.generationIdOf(event)?.let { _generationId.value = it }
            decoder.decode(event)
        }

    private fun parseMillis(value: String?): Long =
        value?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L
}

/**
 * The Agent command catalog, read from the Gateway.
 *
 * The version is sanitised into a wire-safe value: a catalog version is an
 * opaque token to the phone, but a malformed one must not crash the composer.
 */
class GatewayCommandCatalogRepository(
    private val client: CommandCatalogClient,
) : AgentCommandCatalogRepository {

    override suspend fun get(gatewayId: String, languageCode: String): AgentCommandCatalog {
        val catalog = client.get(languageCode)
        val version = catalog.catalogVersion.takeIf { WIRE_ID.matches(it) } ?: "unknown"
        return AgentCommandCatalog(
            version = CatalogVersion(version),
            commands = catalog.commands.map { entry ->
                com.agentlife.conversation.ports.AgentCommand(
                    command = entry.invocation,
                    description = entry.description.ifBlank { entry.title },
                    argumentHint = entry.title.takeIf { entry.acceptsArguments },
                )
            },
        )
    }

    private companion object {
        val WIRE_ID = Regex("^[A-Za-z0-9._~-]+$")
    }
}
