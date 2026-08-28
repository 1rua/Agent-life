package com.agentlife.gateway.conversations

import com.agentlife.gateway.http.GatewayHttpClient
import com.agentlife.gateway.http.RawHeader
import com.agentlife.gateway.http.SignedGatewayRequest
import com.agentlife.gateway.schema.Json
import com.agentlife.gateway.schema.JsonValue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow

data class ConversationThread(
    val conversationId: String,
    val title: String?,
    val lastMessageAt: String?,
)

/**
 * Conversation listing.
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

    private fun stringValue(source: JsonValue.JObject, name: String): String? =
        (source.fields.firstOrNull { it.first == name }?.second as? JsonValue.JString)?.value
}
