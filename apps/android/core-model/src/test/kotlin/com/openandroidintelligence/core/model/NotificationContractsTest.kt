package com.openandroidintelligence.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NotificationContractsTest {
    @Test
    fun default_policy_is_empty_allowlist_metadata_and_revision_zero() {
        val policy = NotificationCollectionPolicyV1.default()
        assertEquals(NotificationRuleMode.ALLOWLIST, policy.mode)
        assertEquals(emptyList<String>(), policy.packageIds)
        assertEquals(NotificationFieldAccess.METADATA, policy.fieldAccess)
        assertEquals(0uL, policy.policyRevision)
    }

    @Test
    fun package_ids_must_be_unique_and_unicode_code_point_sorted() {
        assertThrows(IllegalArgumentException::class.java) {
            NotificationCollectionPolicyV1(
                mode = NotificationRuleMode.ALLOWLIST,
                packageIds = listOf("z.app", "a.app", "a.app"),
                fieldAccess = NotificationFieldAccess.METADATA,
                policyRevision = 1u,
            )
        }
    }

    @Test
    fun record_union_keeps_tombstone_and_loss_shapes_closed() {
        val metadata = NotificationMetadata("pkg", "app", "channel", 10)
        val tombstone = NotificationRecordV1.DeleteTombstone(
            sourceEpoch = 1u,
            occurrenceId = "occ",
            recordKey = "key",
            recordRevision = 2u,
            cursor = 3u,
            capturedAtEpochMs = 10,
            captureRevision = 4u,
            metadata = metadata,
        )
        assertEquals(null, tombstone.content)
        val loss = NotificationRecordV1.LossMarker(
            sourceEpoch = 1u,
            occurrenceId = "loss",
            recordKey = "loss",
            recordRevision = 2u,
            cursor = 3u,
            capturedAtEpochMs = 10,
            captureRevision = 4u,
            loss = NotificationLoss(5u, 6u, "QUEUE_OVERFLOW"),
        )
        assertEquals(null, loss.metadata)
        assertEquals(null, loss.content)
    }
}
