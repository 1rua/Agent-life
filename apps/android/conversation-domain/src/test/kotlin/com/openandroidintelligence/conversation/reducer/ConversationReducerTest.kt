package com.openandroidintelligence.conversation.reducer

import com.openandroidintelligence.conversation.model.*
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConversationReducerTest {
    @Test
    fun acceptedComposerCannotReturnToWaitingAttachments() {
        val state = ConversationSessionState(
            surface = SurfaceState.HIDDEN,
            generation = GenerationState.IDLE,
            composer = ComposerState.ACCEPTED,
            attachments = emptyList(),
        )

        assertFailsWith<InvalidConversationTransition> {
            ConversationReducer.reduce(state, ConversationEvent.AttachmentsPending)
        }
    }
}
