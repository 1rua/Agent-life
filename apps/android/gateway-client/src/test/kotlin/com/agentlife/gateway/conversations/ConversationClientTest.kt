package com.agentlife.gateway.conversations

import com.agentlife.gateway.events.EventCursorStore
import com.agentlife.gateway.events.GatewayEvent
import com.agentlife.gateway.http.GatewayByteTransport
import com.agentlife.gateway.http.GatewayHttpClient
import com.agentlife.gateway.http.GatewayProfile
import com.agentlife.gateway.http.RawHeader
import com.agentlife.gateway.http.WireRequest
import com.agentlife.gateway.http.WireResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationClientTest {

    private class RecordingTransport : GatewayByteTransport {
        var lastRequest: WireRequest? = null
        var responseToReturn = WireResponse(
            status = 200,
            headers = listOf(RawHeader("content-type", "application/json")),
            body = """{"conversationId":"conv_123","status":"received"}""".toByteArray(Charsets.UTF_8),
        )

        override suspend fun execute(request: WireRequest): WireResponse {
            lastRequest = request
            return responseToReturn
        }

        override fun eventStream(request: WireRequest): Flow<ByteArray> = emptyFlow()
    }

    private class MemoryCursorStore : EventCursorStore {
        private val map = mutableMapOf<String, String>()
        override fun load(accountId: String): String? = map[accountId]
        override fun save(accountId: String, cursor: String) { map[accountId] = cursor }
        override fun clear(accountId: String) { map.remove(accountId) }
    }

    @Test
    fun testSendMessageBatchSuccess() = runBlocking {
        val transport = RecordingTransport()
        val profile = GatewayProfile("acc_test", "dev_test", "sess_test", "https://gateway.example.com")
        val http = GatewayHttpClient(profile, transport, { ByteArray(64) }, MemoryCursorStore())
        val client = ConversationClient(http)

        val batch = OutgoingMessageBatch(
            clientConversationId = "cconv_01",
            correlationId = "corr_01",
            messages = listOf(
                OutgoingMessage(
                    role = "user",
                    parts = listOf(
                        MessagePart.Text("Hello assistant"),
                        MessagePart.AttachmentRef(
                            attachmentId = "att_01",
                            visualContext = VisualContext(
                                bounds = NormalizedRect(0.1, 0.1, 0.9, 0.9),
                                displayMetrics = DisplayMetrics(1080, 2400, 480),
                                uiHierarchySummary = "TextView: Hello",
                            ),
                        ),
                    ),
                ),
            ),
        )

        val response = client.sendMessageBatch("conv_123", batch)
        assertEquals("conv_123", response.conversationId)
        val recorded = transport.lastRequest
        assertNotNull(recorded)
        assertEquals("POST", recorded?.method)
        assertEquals("/agent-life/v2/conversations/conv_123/messages", recorded?.target)
        val bodyStr = String(recorded!!.body, Charsets.UTF_8)
        assertTrue(bodyStr.contains("Hello assistant"))
        assertTrue(bodyStr.contains("visualContext"))
        assertTrue(bodyStr.contains("uiHierarchySummary"))
    }
}
