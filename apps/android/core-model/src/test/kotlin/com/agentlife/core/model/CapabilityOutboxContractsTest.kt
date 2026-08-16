package com.agentlife.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityOutboxContractsTest {
    @Test
    fun durable_event_diagnostics_retain_routing_context_without_identity_or_wire() {
        val event = CapabilityDurableEvent(
            eventId = "calls:event-private",
            capability = "calls",
            recordId = "calls:record-private",
            policyRevision = 9u,
            eventWire = "private wire".encodeToByteArray(),
        )

        val diagnostic = event.toString()
        assertTrue(diagnostic.contains("capability=calls"))
        assertTrue(diagnostic.contains("policyRevision=9"))
        assertFalse(diagnostic.contains("event-private"))
        assertFalse(diagnostic.contains("record-private"))
        assertFalse(diagnostic.contains("private wire"))
    }
}
