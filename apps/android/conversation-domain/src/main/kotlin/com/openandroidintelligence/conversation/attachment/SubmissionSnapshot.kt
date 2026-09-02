package com.openandroidintelligence.conversation.attachment

import com.openandroidintelligence.conversation.model.AttachmentDraftId
import com.openandroidintelligence.conversation.model.SubmitIntentId

data class PendingSubmissionIntent(
    val intentId: SubmitIntentId,
    val revision: Long,
    val text: String,
    val attachments: List<AttachmentDraftId> = emptyList(),
)
