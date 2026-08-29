package com.agentlife.mobile

data class AttachmentDestination(
    val uploadedCount: Int = 0,
)

data class AttachmentItem(
    val id: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
)

data class AttachmentState(
    val attachments: List<AttachmentItem> = emptyList(),
    val isUploading: Boolean = false,
)

class AttachmentPresenter(
    private var state: AttachmentState = AttachmentState(),
) {
    fun currentState(): AttachmentState = state

    fun addAttachment(name: String, mimeType: String, sizeBytes: Long, sha256: String): AttachmentItem {
        val item = AttachmentItem(
            id = "att-${System.currentTimeMillis()}",
            name = name,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            sha256 = sha256,
        )
        state = state.copy(attachments = state.attachments + item)
        return item
    }
}

