package com.agentlife.mobile

data class ConversationDestination(
    val activeSessionId: String? = null,
)

data class ChatMessage(
    val id: String,
    val sender: String,
    val text: String,
    val timestampMs: Long,
)

data class ConversationState(
    val messages: List<ChatMessage> = emptyList(),
    val isTyping: Boolean = false,
)

class ConversationPresenter(
    private var state: ConversationState = ConversationState(),
) {
    fun currentState(): ConversationState = state

    fun sendMessage(text: String, sender: String = "User"): ChatMessage {
        val msg = ChatMessage(
            id = "msg-${System.currentTimeMillis()}",
            sender = sender,
            text = text,
            timestampMs = System.currentTimeMillis(),
        )
        state = state.copy(messages = state.messages + msg)
        return msg
    }

    fun receiveReply(text: String): ChatMessage {
        val reply = ChatMessage(
            id = "reply-${System.currentTimeMillis()}",
            sender = "Agent",
            text = text,
            timestampMs = System.currentTimeMillis(),
        )
        state = state.copy(messages = state.messages + reply)
        return reply
    }
}

