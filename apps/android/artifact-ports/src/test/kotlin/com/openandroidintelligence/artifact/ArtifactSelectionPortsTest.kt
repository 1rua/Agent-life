package com.openandroidintelligence.artifact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ArtifactSelectionPortsTest {
    @Test
    fun audio_summary_accepts_the_closed_limits() {
        val summary = ArtifactSummary(
            selection = selection(),
            mediaType = ArtifactMediaType.AUDIO_MP4,
            digest = ArtifactDigest("a".repeat(64), 10L * 1024L * 1024L),
            durationMs = 120_000L,
        )

        assertEquals(120_000L, summary.durationMs)
    }

    @Test
    fun summary_rejects_audio_outside_its_size_and_duration_limits() {
        assertThrows(IllegalArgumentException::class.java) {
            ArtifactSummary(
                selection = selection(),
                mediaType = ArtifactMediaType.AUDIO_MP4,
                digest = ArtifactDigest("a".repeat(64), 10L * 1024L * 1024L + 1L),
                durationMs = 1L,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArtifactSummary(
                selection = selection(),
                mediaType = ArtifactMediaType.AUDIO_MP4,
                digest = ArtifactDigest("a".repeat(64), 1L),
                durationMs = 120_001L,
            )
        }
    }

    @Test
    fun summary_requires_duration_only_for_audio() {
        assertThrows(IllegalArgumentException::class.java) {
            ArtifactSummary(
                selection = selection(),
                mediaType = ArtifactMediaType.AUDIO_MP4,
                digest = ArtifactDigest("a".repeat(64), 1L),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArtifactSummary(
                selection = selection(),
                mediaType = ArtifactMediaType.PNG,
                digest = ArtifactDigest("a".repeat(64), 1L),
                durationMs = 1L,
            )
        }
    }

    @Test
    fun artifact_id_is_present_only_on_a_committed_ticket() {
        assertThrows(IllegalArgumentException::class.java) {
            ticket(ArtifactTicketStatus.ISSUED, artifactId = "artifact-audio")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ticket(ArtifactTicketStatus.MESSAGE_COMMITTED)
        }

        assertEquals(
            "ticket-audio",
            ticket(ArtifactTicketStatus.MESSAGE_COMMITTED, artifactId = "ticket-audio").artifactId,
        )
    }

    @Test
    fun committed_ticket_rejects_an_artifact_id_that_does_not_match_its_ticket_id() {
        assertThrows(IllegalArgumentException::class.java) {
            ticket(ArtifactTicketStatus.MESSAGE_COMMITTED, artifactId = "other-ticket")
        }
    }

    private fun selection() = GrantedArtifactSelection(
        selectionId = "selection-audio",
        source = ArtifactSelectionSource.SAF,
        readGrant = ArtifactReadGrant("grant-audio"),
    )

    private fun ticket(
        status: ArtifactTicketStatus,
        artifactId: String? = null,
    ) = ArtifactTicket(
        ticketId = "ticket-audio",
        selectionId = "selection-audio",
        mediaType = ArtifactMediaType.AUDIO_MP4,
        digest = ArtifactDigest("a".repeat(64), 1L),
        durationMs = 1L,
        revision = ArtifactAuthorizationRevision(1u, 1u, 1u),
        issuedAtEpochMs = 1L,
        status = status,
        artifactId = artifactId,
    )
}
