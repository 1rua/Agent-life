package com.agentlife.conversation.attachment

import com.agentlife.conversation.model.AttachmentDraftId
import com.agentlife.conversation.model.SubmitIntentId

data class PendingSubmissionIntent(
    val intentId: SubmitIntentId,
    val revision: Long,
    val text: String,
    val attachments: List<AttachmentDraftId> = emptyList(),
)
