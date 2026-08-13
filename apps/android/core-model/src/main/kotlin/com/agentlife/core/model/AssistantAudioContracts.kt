package com.agentlife.core.model

const val MAX_ASSISTANT_AUDIO_BYTES: Long = 10L * 1024L * 1024L
const val MAX_ASSISTANT_AUDIO_DURATION_MS: Long = 120_000L

data class AssistantAudioAttachment(
    val artifactId: String,
    val displayName: String,
    val byteSize: Long,
    val sha256Hex: String,
    val durationMs: Long,
) {
    init {
        require(artifactId.matches(Regex("^[A-Za-z0-9._~-]{1,128}$")))
        require(displayName.isNotBlank() && !displayName.contains('/') && !displayName.contains('\\'))
        require(byteSize in 0L..MAX_ASSISTANT_AUDIO_BYTES)
        require(sha256Hex.matches(Regex("^[A-Fa-f0-9]{64}$")))
        require(durationMs in 1L..MAX_ASSISTANT_AUDIO_DURATION_MS)
    }
}

enum class AssistantReplyEventKind { DELTA, COMPLETE, FAILED }

data class AssistantReplyEvent(
    val operationId: String,
    val messageId: String,
    val sequence: ULong,
    val kind: AssistantReplyEventKind,
    val text: String,
    val errorCode: String? = null,
) {
    init {
        require(operationId.isNotBlank() && messageId.isNotBlank())
        require(sequence > 0uL)
        require(text.length <= 50_000)
        if (kind == AssistantReplyEventKind.FAILED) require(!errorCode.isNullOrBlank())
        else require(errorCode == null)
    }
}
