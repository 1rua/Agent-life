package com.openandroidintelligence.core.model

/**
 * Closed, SDK-independent handoff data shared by the assistant holder and the
 * main APK.  A handoff contains only user-selected text and opaque grant
 * metadata; provider locations and bytes never cross this boundary.
 */
const val MAX_ASSISTANT_HANDOFF_ATTACHMENTS: Int = 4
const val MAX_ASSISTANT_TEXT_CHARS: Int = 50_000

enum class AssistantHandoffSource { PHOTO_PICKER, SAF }

enum class AssistantHandoffMediaType { JPEG, PNG, WEBP, PDF, TEXT_PLAIN }

enum class AssistantHandoffOrigin {
    USER_INITIATED,
    AGENT_REQUEST,
    SYSTEM_RESTORE,
}

data class AssistantHandoffAttachment(
    val selectionId: String,
    val source: AssistantHandoffSource,
    val readGrantId: String,
    val mediaTypeHint: AssistantHandoffMediaType?,
) {
    init {
        require(selectionId.isNotBlank()) { "selection ID must not be blank" }
        require(readGrantId.isNotBlank()) { "read grant ID must not be blank" }
    }
}

data class AssistantHandoffRequest(
    val text: String?,
    val attachments: List<AssistantHandoffAttachment>,
    val origin: AssistantHandoffOrigin,
) {
    init {
        require(text == null || text.length <= MAX_ASSISTANT_TEXT_CHARS) {
            "assistant text exceeds the closed limit"
        }
        require(attachments.size <= MAX_ASSISTANT_HANDOFF_ATTACHMENTS) {
            "too many assistant attachments"
        }
        require(attachments.map { it.selectionId }.distinct().size == attachments.size) {
            "assistant attachment selection IDs must be unique"
        }
    }
}

enum class AssistantHandoffDenialReason {
    DEFAULT_DENY,
    NOT_USER_INITIATED,
}

sealed interface AssistantHandoffDecision {
    data class Accepted internal constructor(
        val request: AssistantHandoffRequest,
    ) : AssistantHandoffDecision

    data class Denied(
        val reason: AssistantHandoffDenialReason,
    ) : AssistantHandoffDecision
}

/**
 * Local gate used by the main APK. Implementations must make the explicit
 * user opt-in decision; an Agent request or an implicit IPC cannot mint one.
 */
fun interface AssistantHandoffGate {
    fun evaluate(request: AssistantHandoffRequest): AssistantHandoffDecision
}

/** Safe default for a fresh install and for test/device configurations. */
class DefaultAssistantHandoffGate(
    private val userHandoffEnabled: Boolean = false,
) : AssistantHandoffGate {
    override fun evaluate(request: AssistantHandoffRequest): AssistantHandoffDecision {
        if (!userHandoffEnabled) {
            return AssistantHandoffDecision.Denied(AssistantHandoffDenialReason.DEFAULT_DENY)
        }
        if (request.origin != AssistantHandoffOrigin.USER_INITIATED) {
            return AssistantHandoffDecision.Denied(AssistantHandoffDenialReason.NOT_USER_INITIATED)
        }
        return AssistantHandoffDecision.Accepted(request)
    }
}
