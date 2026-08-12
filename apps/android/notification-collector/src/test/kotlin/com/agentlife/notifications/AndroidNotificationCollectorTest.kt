package com.agentlife.notifications

import com.agentlife.core.model.AuthorizationDecision
import com.agentlife.core.model.NotificationCollectionPolicyV1
import com.agentlife.core.model.NotificationFieldAccess
import com.agentlife.core.model.NotificationRuleMode
import com.agentlife.core.model.OnDemandNotificationRead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AndroidNotificationCollectorTest {
    @Test
    fun metadata_capture_contains_no_title_or_body() {
        val collector = AndroidNotificationCollector(
            authorization = { _, _, _ -> AuthorizationDecision.allow() },
        )
        collector.applyPolicyBlocking(NotificationCollectionPolicyV1(NotificationRuleMode.ALLOWLIST, listOf("mail"), NotificationFieldAccess.METADATA, 1u))
        collector.onPosted(RawNotification("mail", "key", "Mail", "Secret title", "Secret body", "channel", 100))
        val result = collector.captureOnDemandBlocking(OnDemandNotificationRead("op", 1u, 10))
        val record = result.records.single() as com.agentlife.core.model.NotificationRecordV1.Upsert
        assertEquals(null, record.content)
    }

    @Test
    fun content_is_purged_when_policy_downgrades_to_metadata_and_cannot_be_restored() {
        val collector = AndroidNotificationCollector(
            authorization = { _, _, _ -> AuthorizationDecision.allow() },
        )
        collector.applyPolicyBlocking(NotificationCollectionPolicyV1(NotificationRuleMode.ALLOWLIST, listOf("mail"), NotificationFieldAccess.CONTENT, 1u))
        collector.onPosted(RawNotification("mail", "key", "Mail", "Secret title", "Secret body", "channel", 100))

        val contentBeforeDowngrade = collector.captureOnDemandBlocking(OnDemandNotificationRead("op-1", 1u, 10))
            .records.single() as com.agentlife.core.model.NotificationRecordV1.Upsert
        assertEquals("Secret title", contentBeforeDowngrade.content?.title)
        assertEquals("Secret body", contentBeforeDowngrade.content?.body)

        collector.applyPolicyBlocking(NotificationCollectionPolicyV1(NotificationRuleMode.ALLOWLIST, listOf("mail"), NotificationFieldAccess.METADATA, 2u))
        val metadataAfterDowngrade = collector.captureOnDemandBlocking(OnDemandNotificationRead("op-2", 2u, 10))
            .records.single() as com.agentlife.core.model.NotificationRecordV1.Upsert
        assertEquals(null, metadataAfterDowngrade.content)

        collector.applyPolicyBlocking(NotificationCollectionPolicyV1(NotificationRuleMode.ALLOWLIST, listOf("mail"), NotificationFieldAccess.CONTENT, 3u))
        val contentAfterUpgrade = collector.captureOnDemandBlocking(OnDemandNotificationRead("op-3", 3u, 10))
            .records.single() as com.agentlife.core.model.NotificationRecordV1.Upsert
        assertEquals(null, contentAfterUpgrade.content?.title)
        assertEquals(null, contentAfterUpgrade.content?.body)
    }

    @Test
    fun stale_policy_revision_is_rejected_before_capture() {
        val collector = AndroidNotificationCollector(
            authorization = { _, _, _ -> AuthorizationDecision.allow() },
        )
        collector.applyPolicyBlocking(NotificationCollectionPolicyV1.default())
        assertThrows(PolicyRevisionRace::class.java) {
            collector.captureOnDemandBlocking(OnDemandNotificationRead("op", 9u, 10))
        }
    }
}
