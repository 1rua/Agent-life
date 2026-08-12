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

        assertEquals("", draft.packageIdsText)
        assertEquals(NotificationFieldAccess.METADATA, draft.fieldAccess)
        assertEquals(NotificationDeliveryMode.ON_DEMAND, draft.deliveryMode)
        assertFalse(draft.granted)
    }

    @Test
    fun package_text_is_trimmed_blanks_removed_and_code_point_sorted() {
        val normalized = normalizeNotificationPackageIds(
            "  z.app  \n\na.a\n a.app \n",
        )

        assertEquals(listOf("a.a", "a.app", "z.app"), normalized)
    }

    @Test
    fun duplicate_package_ids_after_trimming_are_rejected() {
        assertThrows(IllegalArgumentException::class.java) {
            normalizeNotificationPackageIds("com.example.mail\n com.example.mail ")
        }
    }

    @Test
    fun invalid_package_ids_are_rejected() {
        assertThrows(IllegalArgumentException::class.java) {
            normalizeNotificationPackageIds("com.example.mail\nnot-a-package")
        }
    }

    @Test
    fun changed_grant_and_delivery_mode_commit_with_a_monotonic_revision() {
        val base = NotificationAuthoritySnapshot(
            policy = NotificationCollectionPolicyV1.default(),
            authorizationRevision = 9u,
            granted = false,
            deliveryMode = NotificationDeliveryMode.ON_DEMAND,
        )

        val commit = commitNotificationSettings(
            base,
            NotificationSettingsDraft(
                packageIdsText = "com.example.mail",
                fieldAccess = NotificationFieldAccess.CONTENT,
                deliveryMode = NotificationDeliveryMode.AUTO_SEND,
                granted = true,
            ),
        )

        assertEquals(10uL, commit.authorizationRevision)
        assertEquals(10uL, commit.policy.policyRevision)
        assertTrue(commit.granted)
        assertEquals(NotificationDeliveryMode.AUTO_SEND, commit.deliveryMode)
        assertEquals(listOf("com.example.mail"), commit.policy.packageIds)
        assertEquals(NotificationFieldAccess.CONTENT, commit.policy.fieldAccess)
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
                packageIdsText = " com.example.mail ",
                fieldAccess = NotificationFieldAccess.METADATA,
                deliveryMode = NotificationDeliveryMode.AUTO_SEND,
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
        assertEquals(21uL, commit.policy.policyRevision)
    }
}
