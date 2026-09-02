package com.openandroidintelligence.conversation.attachment

import com.openandroidintelligence.conversation.model.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AttachmentSubmissionGate(
    private val onSubmit: suspend (PendingSubmissionIntent) -> Unit,
) {
    private val mutex = Mutex()
    private var currentIntent: PendingSubmissionIntent? = null
    private val verifiedAttachments = mutableSetOf<AttachmentDraftId>()

    suspend fun arm(intent: PendingSubmissionIntent) = mutex.withLock {
        currentIntent = intent
        verifiedAttachments.clear()
        checkAndSubmitLocked()
    }

    suspend fun onAttachmentVerified(intentId: SubmitIntentId, draftId: AttachmentDraftId) = mutex.withLock {
        val intent = currentIntent ?: return@withLock
        if (intent.intentId != intentId) return@withLock

        verifiedAttachments.add(draftId)
        checkAndSubmitLocked()
    }

    suspend fun invalidate(reason: String) = mutex.withLock {
        currentIntent = null
        verifiedAttachments.clear()
    }

    private suspend fun checkAndSubmitLocked() {
        val intent = currentIntent ?: return
        val allRequired = intent.attachments
        if (allRequired.isEmpty() || verifiedAttachments.containsAll(allRequired)) {
            val toSubmit = intent
            currentIntent = null
            verifiedAttachments.clear()
            onSubmit(toSubmit)
        }
    }
}
