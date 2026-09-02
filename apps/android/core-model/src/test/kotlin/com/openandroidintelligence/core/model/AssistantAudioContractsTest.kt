package com.openandroidintelligence.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AssistantAudioContractsTest {
    @Test
    fun audio_attachment_accepts_the_closed_limits() {
        val attachment = AssistantAudioAttachment(
            artifactId = "artifact-audio",
            displayName = "voice.m4a",
            byteSize = 10L * 1024L * 1024L,
            sha256Hex = "a".repeat(64),
            durationMs = 120_000L,
        )

        assertEquals(120_000L, attachment.durationMs)
    }

    @Test
    fun audio_attachment_rejects_invalid_opaque_metadata() {
        assertThrows(IllegalArgumentException::class.java) {
            audioAttachment(artifactId = "audio/path")
        }
        assertThrows(IllegalArgumentException::class.java) {
            audioAttachment(displayName = "voice\\clip.m4a")
        }
        assertThrows(IllegalArgumentException::class.java) {
            audioAttachment(byteSize = 10L * 1024L * 1024L + 1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            audioAttachment(sha256Hex = "not-a-digest")
        }
        assertThrows(IllegalArgumentException::class.java) {
            audioAttachment(durationMs = 120_001L)
        }
    }

    @Test
    fun reply_events_require_ordered_identifiers_and_kind_specific_errors() {
        assertThrows(IllegalArgumentException::class.java) {
            replyEvent(sequence = 0u)
        }
        assertThrows(IllegalArgumentException::class.java) {
            replyEvent(kind = AssistantReplyEventKind.DELTA, errorCode = "UNEXPECTED")
        }
        assertThrows(IllegalArgumentException::class.java) {
            replyEvent(kind = AssistantReplyEventKind.FAILED)
        }

        val failed = replyEvent(
            kind = AssistantReplyEventKind.FAILED,
            errorCode = "CONNECTION_FENCED",
        )
        assertEquals("CONNECTION_FENCED", failed.errorCode)
    }

    private fun audioAttachment(
        artifactId: String = "artifact-audio",
        displayName: String = "voice.m4a",
        byteSize: Long = 1L,
        sha256Hex: String = "a".repeat(64),
        durationMs: Long = 1L,
    ) = AssistantAudioAttachment(artifactId, displayName, byteSize, sha256Hex, durationMs)

    private fun replyEvent(
        sequence: ULong = 1u,
        kind: AssistantReplyEventKind = AssistantReplyEventKind.DELTA,
        errorCode: String? = null,
    ) = AssistantReplyEvent(
        operationId = "operation-audio",
        messageId = "message-audio",
        sequence = sequence,
        kind = kind,
        text = "reply",
        errorCode = errorCode,
    )
}
