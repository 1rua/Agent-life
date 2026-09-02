package com.openandroidintelligence.artifact

/** Closed attachment limits shared by the Android picker and Bridge packet. */
const val MAX_ARTIFACT_FILES: Int = 4
const val MAX_SINGLE_ARTIFACT_BYTES: Long = 25L * 1024L * 1024L
const val MAX_MESSAGE_ARTIFACT_BYTES: Long = 50L * 1024L * 1024L
const val MAX_AUDIO_ARTIFACT_BYTES: Long = 10L * 1024L * 1024L
const val MAX_AUDIO_DURATION_MS: Long = 120_000L
const val ORPHAN_RECLAIM_AFTER_MS: Long = 24L * 60L * 60L * 1000L

enum class ArtifactSelectionSource { PHOTO_PICKER, SAF }

/** MIME values are closed; adapters cannot smuggle an executable payload. */
enum class ArtifactMediaType(val mimeType: String) {
    JPEG("image/jpeg"),
    PNG("image/png"),
    WEBP("image/webp"),
    PDF("application/pdf"),
    TEXT_PLAIN("text/plain"),
    AUDIO_MP4("audio/mp4"),
}

/** Opaque proof that the user granted this particular selection for reading. */
data class ArtifactReadGrant internal constructor(
    val grantId: String,
) {
    init { require(grantId.isNotBlank()) { "read grant must not be blank" } }
}

/** No provider location is exposed here; only a provider-issued opaque ID. */
data class GrantedArtifactSelection internal constructor(
    val selectionId: String,
    val source: ArtifactSelectionSource,
    val readGrant: ArtifactReadGrant,
) {
    init { require(selectionId.isNotBlank()) { "selection ID must not be blank" } }
}

data class ArtifactSelectionRequest(
    val maxFiles: Int = MAX_ARTIFACT_FILES,
) {
    init {
        require(maxFiles in 1..MAX_ARTIFACT_FILES) { "selection count exceeds the closed limit" }
    }
}

/** Digest and size are produced before a ticket is issued. */
data class ArtifactDigest(
    val sha256Hex: String,
    val byteSize: Long,
) {
    init {
        require(sha256Hex.matches(Regex("^[A-Fa-f0-9]{64}$"))) { "SHA-256 digest is required" }
        require(byteSize in 0..MAX_SINGLE_ARTIFACT_BYTES) { "single artifact is too large" }
    }
}

data class ArtifactSummary(
    val selection: GrantedArtifactSelection,
    val mediaType: ArtifactMediaType,
    val digest: ArtifactDigest,
    val durationMs: Long? = null,
) {
    init {
        validateAudioArtifactMetadata(mediaType, digest.byteSize, durationMs)
    }
}

/** The future provider must calculate a digest while it still holds the grant. */
interface ArtifactDigestPort {
    suspend fun summarize(
        selection: GrantedArtifactSelection,
        mediaType: ArtifactMediaType,
    ): ArtifactSummary
}

data class ArtifactAuthorizationRevision(
    val pairingGeneration: ULong,
    val connectionGeneration: ULong,
    val policyRevision: ULong,
)

enum class ArtifactTicketStatus {
    ISSUED,
    PROOF_VERIFIED,
    UPLOADING,
    UPLOAD_INTERRUPTED,
    MESSAGE_COMMITTED,
    ORPHAN_RECLAIMED,
}

data class ArtifactRemoteProof internal constructor(
    val proofId: String,
    val digest: ArtifactDigest,
) {
    init { require(proofId.isNotBlank()) { "remote proof must not be blank" } }
}

/** Ticket state is opaque and always carries the authorization fence. */
data class ArtifactTicket internal constructor(
    val ticketId: String,
    val selectionId: String,
    val mediaType: ArtifactMediaType,
    val digest: ArtifactDigest,
    val durationMs: Long? = null,
    val revision: ArtifactAuthorizationRevision,
    val issuedAtEpochMs: Long,
    val status: ArtifactTicketStatus,
    val artifactId: String? = null,
    val remoteProof: ArtifactRemoteProof? = null,
    val localCopyDeletionAllowed: Boolean = false,
    val requiresFreshTicket: Boolean = false,
) {
    init {
        require(ticketId.isNotBlank())
        require(selectionId.isNotBlank())
        require(issuedAtEpochMs >= 0L)
        validateAudioArtifactMetadata(mediaType, digest.byteSize, durationMs)
        require((status == ArtifactTicketStatus.MESSAGE_COMMITTED) == (artifactId != null)) {
            "artifact ID is available only after message commit"
        }
        if (artifactId != null) {
            require(artifactId.matches(Regex("^[A-Za-z0-9._~-]{1,128}$"))) {
                "artifact ID must be an opaque identifier"
            }
            require(artifactId == ticketId) {
                "committed artifact ID must match ticket ID"
            }
        }
    }
}

/** A scratch handle contains no cleartext and cannot be used as a location. */
data class EncryptedArtifactCopy internal constructor(
    val copyId: String,
    val ticketId: String,
    val encryptionVersion: String,
    val createdAtEpochMs: Long,
) {
    init {
        require(copyId.isNotBlank())
        require(ticketId.isNotBlank())
        require(encryptionVersion.isNotBlank())
        require(createdAtEpochMs >= 0L)
    }
}

data class ArtifactUploadInterrupted internal constructor(
    val invalidatedTicketId: String,
    val interruptedAtEpochMs: Long,
    val requiresFreshTicket: Boolean = true,
) {
    init {
        require(invalidatedTicketId.isNotBlank())
        require(interruptedAtEpochMs >= 0L)
    }
}

data class ArtifactCommitReceipt internal constructor(
    val messageId: String,
    val committedTicketIds: List<String>,
    val committedAtEpochMs: Long,
) {
    init {
        require(messageId.isNotBlank())
        require(committedTicketIds.isNotEmpty())
        require(committedTicketIds.size <= MAX_ARTIFACT_FILES)
        require(committedTicketIds.distinct().size == committedTicketIds.size)
        require(committedAtEpochMs >= 0L)
    }
}

/** User-facing picker boundary; implementations must return only granted records. */
interface ArtifactSelectionPort {
    suspend fun select(request: ArtifactSelectionRequest): List<GrantedArtifactSelection>
}

/**
 * Bridge ticket lifecycle.  A digest summary is mandatory input to issuance;
 * an interrupted ticket cannot be committed and must be replaced.
 */
interface ArtifactTicketPort {
    suspend fun issueTicket(
        summary: ArtifactSummary,
        revision: ArtifactAuthorizationRevision,
        nowEpochMs: Long,
    ): ArtifactTicket

    suspend fun verifyRemoteProof(
        ticket: ArtifactTicket,
        remoteProof: ArtifactRemoteProof,
    ): ArtifactTicket

    suspend fun commitMessage(
        messageId: String,
        tickets: List<ArtifactTicket>,
        nowEpochMs: Long,
    ): ArtifactCommitReceipt

    suspend fun markMessageCommitted(
        ticket: ArtifactTicket,
        receipt: ArtifactCommitReceipt,
    ): ArtifactTicket

    suspend fun interrupt(ticket: ArtifactTicket, nowEpochMs: Long): ArtifactUploadInterrupted
}

/**
 * The storage adapter owns encryption and deletion.  The only normal success
 * deletion takes a commit receipt; interrupted copies use a separate flow.
 */
interface EncryptedArtifactScratchStore {
    suspend fun stageEncryptedCopy(
        ticket: ArtifactTicket,
        selection: GrantedArtifactSelection,
        nowEpochMs: Long,
    ): EncryptedArtifactCopy

    suspend fun deleteAfterCommit(
        copy: EncryptedArtifactCopy,
        receipt: ArtifactCommitReceipt,
    )

    suspend fun discardInterruptedCopy(
        copy: EncryptedArtifactCopy,
        interruption: ArtifactUploadInterrupted,
    )
}

/**
 * Validation used by adapters before issuing a Bridge packet.  This is a
 * contract helper only; it never reads provider data or performs encryption.
 */
fun validateArtifactSummaries(summaries: List<ArtifactSummary>) {
    require(summaries.isNotEmpty()) { "at least one artifact is required" }
    require(summaries.size <= MAX_ARTIFACT_FILES) { "too many artifacts" }
    summaries.forEach { summary ->
        validateAudioArtifactMetadata(summary.mediaType, summary.digest.byteSize, summary.durationMs)
    }
    val totalBytes = summaries.sumOf { it.digest.byteSize }
    require(totalBytes <= MAX_MESSAGE_ARTIFACT_BYTES) { "message artifacts are too large" }
}

private fun validateAudioArtifactMetadata(
    mediaType: ArtifactMediaType,
    byteSize: Long,
    durationMs: Long?,
) {
    if (mediaType == ArtifactMediaType.AUDIO_MP4) {
        require(byteSize <= MAX_AUDIO_ARTIFACT_BYTES) { "audio artifact is too large" }
        require(durationMs in 1L..MAX_AUDIO_DURATION_MS) { "audio duration is invalid" }
    } else {
        require(durationMs == null) { "duration is valid only for audio artifacts" }
    }
}
