package com.agentlife.mobile

import com.agentlife.core.model.NotificationCollectionPolicyV1
import com.agentlife.core.model.NotificationDeliveryMode
import com.agentlife.core.model.NotificationFieldAccess
import com.agentlife.core.model.NotificationRuleMode
import com.agentlife.policy.NotificationAuthoritySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationSettingsStateTest {
    @Test
    fun draft_defaults_to_deny_first_notification_settings() {
        val draft = NotificationSettingsDraft()

        assertEquals(emptyList<String>(), draft.packageIds)
        assertEquals(NotificationFieldAccess.METADATA, draft.fieldAccess)
        assertEquals(NotificationDeliveryMode.ON_DEMAND, draft.deliveryMode)
        assertEquals(NotificationRuleMode.ALLOWLIST, draft.ruleMode)
        assertFalse(draft.granted)
    }

    @Test
    fun package_ids_are_trimmed_blanks_removed_and_code_point_sorted() {
        val normalized = normalizeNotificationPackageIds(
            listOf("  z.app  ", "", "a.a", " a.app "),
        )

        assertEquals(listOf("a.a", "a.app", "z.app"), normalized)
    }

    @Test
    fun duplicate_package_ids_after_trimming_are_rejected() {
        assertThrows(IllegalArgumentException::class.java) {
            normalizeNotificationPackageIds(listOf("com.example.mail", " com.example.mail "))
        }
    }

    @Test
    fun invalid_package_ids_are_rejected() {
        assertThrows(IllegalArgumentException::class.java) {
            normalizeNotificationPackageIds(listOf("com.example.mail", "not-a-package"))
        }
    }

    @Test
    fun changed_grant_delivery_mode_policy_and_rule_mode_commit_together() {
        val base = NotificationAuthoritySnapshot(
            policy = NotificationCollectionPolicyV1.default(),
            authorizationRevision = 9u,
            granted = false,
            deliveryMode = NotificationDeliveryMode.ON_DEMAND,
        )

        val commit = commitNotificationSettings(
            base,
            NotificationSettingsDraft(
                packageIds = listOf("com.example.mail"),
                fieldAccess = NotificationFieldAccess.CONTENT,
                deliveryMode = NotificationDeliveryMode.AUTO_SEND,
                ruleMode = NotificationRuleMode.DENYLIST,
                granted = true,
            ),
        )

        assertEquals(10uL, commit.authorizationRevision)
        assertEquals(10uL, commit.policy.policyRevision)
        assertTrue(commit.granted)
        assertEquals(NotificationDeliveryMode.AUTO_SEND, commit.deliveryMode)
        assertEquals(listOf("com.example.mail"), commit.policy.packageIds)
        assertEquals(NotificationFieldAccess.CONTENT, commit.policy.fieldAccess)
        assertEquals(NotificationRuleMode.DENYLIST, commit.policy.mode)
    }

    @Test
    fun only_policy_content_change_increments_both_revisions() {
        val base = NotificationAuthoritySnapshot(
            policy = NotificationCollectionPolicyV1(
                mode = NotificationRuleMode.ALLOWLIST,
                packageIds = listOf("com.example.mail"),
                fieldAccess = NotificationFieldAccess.METADATA,
                policyRevision = 12u,
            ),
            authorizationRevision = 12u,
            granted = true,
            deliveryMode = NotificationDeliveryMode.ON_DEMAND,
        )

        val commit = NotificationSettingsDraft(
            packageIds = listOf("com.example.calendar"),
            fieldAccess = NotificationFieldAccess.METADATA,
            deliveryMode = NotificationDeliveryMode.ON_DEMAND,
            ruleMode = NotificationRuleMode.ALLOWLIST,
            granted = true,
        ).commitAgainst(base)

        assertEquals(13uL, commit.authorizationRevision)
        assertEquals(13uL, commit.policy.policyRevision)
    }

    @Test
    fun only_delivery_mode_change_increments_authorization_without_rolling_policy_revision() {
        val base = NotificationAuthoritySnapshot(
            policy = NotificationCollectionPolicyV1(
                mode = NotificationRuleMode.ALLOWLIST,
                packageIds = listOf("com.example.mail"),
                fieldAccess = NotificationFieldAccess.METADATA,
                policyRevision = 12u,
            ),
            authorizationRevision = 12u,
            granted = true,
            deliveryMode = NotificationDeliveryMode.ON_DEMAND,
        )

        val commit = NotificationSettingsDraft(
            packageIds = listOf("com.example.mail"),
            fieldAccess = NotificationFieldAccess.METADATA,
            deliveryMode = NotificationDeliveryMode.AUTO_SEND,
            ruleMode = NotificationRuleMode.ALLOWLIST,
            granted = true,
        ).commitAgainst(base)

        assertEquals(13uL, commit.authorizationRevision)
        assertEquals(12uL, commit.policy.policyRevision)
    }

    @Test
    fun unchanged_draft_keeps_the_snapshot_revision_and_mode() {
        val base = NotificationAuthoritySnapshot(
            policy = NotificationCollectionPolicyV1(
                mode = NotificationRuleMode.ALLOWLIST,
                packageIds = listOf("com.example.mail"),
                fieldAccess = NotificationFieldAccess.METADATA,
                policyRevision = 12u,
            ),
            authorizationRevision = 12u,
            granted = true,
            deliveryMode = NotificationDeliveryMode.AUTO_SEND,
        )

        val commit = commitNotificationSettings(
            base,
            NotificationSettingsDraft(
                packageIds = listOf(" com.example.mail "),
                fieldAccess = NotificationFieldAccess.METADATA,
                deliveryMode = NotificationDeliveryMode.AUTO_SEND,
                ruleMode = NotificationRuleMode.ALLOWLIST,
                granted = true,
            ),
        )

        assertEquals(12uL, commit.authorizationRevision)
        assertEquals(12uL, commit.policy.policyRevision)
        assertEquals(NotificationDeliveryMode.AUTO_SEND, commit.deliveryMode)
    }

    @Test
    fun commit_never_rolls_back_a_higher_authorization_revision() {
        val base = NotificationAuthoritySnapshot(
            policy = NotificationCollectionPolicyV1.default(),
            authorizationRevision = 20u,
            granted = false,
            deliveryMode = NotificationDeliveryMode.ON_DEMAND,
        )

        val commit = commitNotificationSettings(base, NotificationSettingsDraft(granted = true))

        assertEquals(21uL, commit.authorizationRevision)
        assertEquals(20uL, commit.policy.policyRevision)
    }
}
