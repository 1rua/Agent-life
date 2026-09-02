package com.agentlife.conversation.model

private val WIRE_ID_REGEX = Regex("^[A-Za-z0-9._~-]+$")

private fun requireWireId(value: String, name: String): String {
    require(value.isNotBlank()) { "$name cannot be blank" }
    require(WIRE_ID_REGEX.matches(value)) { "$name contains invalid characters: $value" }
    return value
}

@JvmInline
value class GatewayId(val value: String) {
    init {
        requireWireId(value, "GatewayId")
    }
}

@JvmInline
value class ConversationId(val value: String) {
    init {
        requireWireId(value, "ConversationId")
    }
}

@JvmInline
value class ClientMessageId(val value: String) {
    init {
        requireWireId(value, "ClientMessageId")
    }
}

@JvmInline
value class AttachmentDraftId(val value: String) {
    init {
        requireWireId(value, "AttachmentDraftId")
    }
}

@JvmInline
value class SubmitIntentId(val value: String) {
    init {
        requireWireId(value, "SubmitIntentId")
    }
}

@JvmInline
value class CatalogVersion(val value: String) {
    init {
        requireWireId(value, "CatalogVersion")
    }
}
