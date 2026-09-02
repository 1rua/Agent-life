package com.openandroidintelligence.conversation.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IdentifierBoundaryTest {
    @Test
    fun blankIdentifiersAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            GatewayId("  ")
        }
        assertFailsWith<IllegalArgumentException> {
            ConversationId("")
        }
        assertFailsWith<IllegalArgumentException> {
            ClientMessageId("")
        }
        assertFailsWith<IllegalArgumentException> {
            AttachmentDraftId(" ")
        }
    }

    @Test
    fun validIdentifiersRetainValue() {
        val gid = GatewayId("gw_123")
        val cid = ConversationId("conv_456")
        assertEquals("gw_123", gid.value)
        assertEquals("conv_456", cid.value)
    }
}
