package com.agentlife.conversation.model

@JvmInline
value class GatewayId(val value: String) {
    init {
        require(value.isNotBlank()) { "GatewayId cannot be blank" }
    }
}

@JvmInline
value class ConversationId(val value: String) {
    init {
        require(value.isNotBlank()) { "ConversationId cannot be blank" }
    }
}

@JvmInline
value class ClientMessageId(val value: String) {
    init {
        require(value.isNotBlank()) { "ClientMessageId cannot be blank" }
    }
}

@JvmInline
value class AttachmentDraftId(val value: String) {
    init {
        require(value.isNotBlank()) { "AttachmentDraftId cannot be blank" }
    }
}

@JvmInline
value class SubmitIntentId(val value: String) {
    init {
        require(value.isNotBlank()) { "SubmitIntentId cannot be blank" }
    }
}

@JvmInline
value class CatalogVersion(val value: String) {
    init {
        require(value.isNotBlank()) { "CatalogVersion cannot be blank" }
    }
}
