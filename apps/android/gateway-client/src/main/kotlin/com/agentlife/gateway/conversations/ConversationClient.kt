package com.agentlife.gateway.conversations

import com.agentlife.gateway.http.GatewayHttpClient
import com.agentlife.gateway.http.RawHeader
import com.agentlife.gateway.http.SignedGatewayRequest
import com.agentlife.gateway.schema.Json
import com.agentlife.gateway.schema.JsonValue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow

data class NormalizedRect(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
)

data class DisplayMetrics(
    val widthPixels: Int,
    val heightPixels: Int,
    val densityDpi: Int,
)

data class VisualContext(
    val bounds: NormalizedRect,
    val displayMetrics: DisplayMetrics,
    val uiHierarchySummary: String? = null,
)

sealed class MessagePart {
    data class Text(val text: String) : MessagePart()
    data class AttachmentRef(
        val attachmentId: String,
        val visualContext: VisualContext? = null,
    ) : MessagePart()
}

data class OutgoingMessage(
    val role: String,
    val parts: List<MessagePart>,
    val timestamp: Long? = null,
)

data class OutgoingMessageBatch(
    val clientConversationId: String,
    val correlationId: String,
    val messages: List<OutgoingMessage>,
)

data class SendMessageBatchResponse(
    val conversationId: String,
    val status: String,
)

data class ConversationThread(
    val conversationId: String,
    val title: String?,
    val lastMessageAt: String?,
)

/**
 * Conversation listing and message batch sending.
 *
 * A conversation belongs to exactly one account and one Gateway; the client
 * never supplies an account id in the request body, so a cross-account
 * reference cannot be constructed here even by mistake.
 */
class ConversationClient(private val http: GatewayHttpClient) {

    /** Emits the current threads, then a fresh list whenever the server says one changed. */
    fun threads(accountId: String): Flow<List<ConversationThread>> = flow {
        emit(readThreads())
        http.events()
            .filter { it.event == "conversation.updated" }
            .collect { emit(readThreads()) }
    }

    suspend fun readThreads(): List<ConversationThread> {
        val response = http.execute(
            SignedGatewayRequest(
                method = "GET",
                target = "/agent-life/v2/conversations",
                headers = listOf(RawHeader("Accept", "application/json")),
            ),
        )
        if (response.status != 200) {
            throw IllegalStateException("CONVERSATIONS_FAILED:${response.status}")
        }
        val body = Json.parse(String(response.body, Charsets.UTF_8)) as? JsonValue.JObject
            ?: throw IllegalStateException("CONVERSATIONS_FAILED:malformed")
        val threads = (body.fields.firstOrNull { it.first == "threads" }?.second as? JsonValue.JArray)
            ?: return emptyList()
        return threads.items.mapNotNull { item ->
            val thread = item as? JsonValue.JObject ?: return@mapNotNull null
            val conversationId = stringValue(thread, "conversationId") ?: return@mapNotNull null
            ConversationThread(
                conversationId = conversationId,
                title = stringValue(thread, "title"),
                lastMessageAt = stringValue(thread, "lastMessageAt"),
            )
        }
    }

    suspend fun sendMessageBatch(
        conversationId: String,
        batch: OutgoingMessageBatch,
    ): SendMessageBatchResponse {
        val payloadMap = mapOf(
            "clientConversationId" to batch.clientConversationId,
            "correlationId" to batch.correlationId,
            "messages" to batch.messages.map { msg ->
                val msgMap = mutableMapOf<String, Any?>(
                    "role" to msg.role,
                    "parts" to msg.parts.map { part ->
                        when (part) {
                            is MessagePart.Text -> mapOf("type" to "text", "text" to part.text)
                            is MessagePart.AttachmentRef -> {
                                val attMap = mutableMapOf<String, Any?>(
                                    "type" to "attachment_ref",
                                    "attachmentId" to part.attachmentId,
                                )
                                part.visualContext?.let { vc ->
                                    val vcMap = mutableMapOf<String, Any?>(
                                        "bounds" to mapOf(
                                            "left" to vc.bounds.left,
                                            "top" to vc.bounds.top,
                                            "right" to vc.bounds.right,
                                            "bottom" to vc.bounds.bottom,
                                        ),
                                        "displayMetrics" to mapOf(
                                            "widthPixels" to vc.displayMetrics.widthPixels,
                                            "heightPixels" to vc.displayMetrics.heightPixels,
                                            "densityDpi" to vc.displayMetrics.densityDpi,
                                        ),
                                    )
                                    vc.uiHierarchySummary?.let { summary ->
                                        vcMap["uiHierarchySummary"] = summary
                                    }
                                    attMap["visualContext"] = vcMap
                                }
                                attMap
                            }
                        }
                    },
                )
                msg.timestamp?.let { msgMap["timestamp"] = it }
                msgMap
            },
        )

        val bodyJson = Json.canonical(Json.of(payloadMap))
        val response = http.execute(
            SignedGatewayRequest(
                method = "POST",
                target = "/agent-life/v2/conversations/$conversationId/messages",
                headers = listOf(
                    RawHeader("Content-Type", "application/json"),
                    RawHeader("Accept", "application/json"),
                ),
                body = bodyJson.toByteArray(Charsets.UTF_8),
            ),
        )

        if (response.status !in 200..299) {
            throw IllegalStateException("SEND_MESSAGE_BATCH_FAILED:${response.status}")
        }

        val resBody = Json.parse(String(response.body, Charsets.UTF_8)) as? JsonValue.JObject
            ?: return SendMessageBatchResponse(conversationId, "received")

        val resConvId = stringValue(resBody, "conversationId") ?: conversationId
        val resStatus = stringValue(resBody, "status") ?: "received"
        return SendMessageBatchResponse(resConvId, resStatus)
    }

    private fun stringValue(source: JsonValue.JObject, name: String): String? =
        (source.fields.firstOrNull { it.first == name }?.second as? JsonValue.JString)?.value
}
