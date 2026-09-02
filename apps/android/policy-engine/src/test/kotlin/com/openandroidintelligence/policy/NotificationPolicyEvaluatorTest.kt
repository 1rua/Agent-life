package com.openandroidintelligence.policy

import com.openandroidintelligence.core.model.AuthorizationDecision
import com.openandroidintelligence.core.model.NotificationCollectionPolicyV1
import com.openandroidintelligence.core.model.NotificationFieldAccess
import com.openandroidintelligence.core.model.NotificationRuleMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPolicyEvaluatorTest {
    @Test
    fun persistent_local_authority_is_default_deny_and_restores_only_a_local_grant() {
        val persistence = InMemoryNotificationPolicyPersistence()
        val fresh = PersistentNotificationPolicyAuthority(persistence)
        assertFalse(fresh.decide("mail", NotificationFieldAccess.CONTENT, 0u).allowed)

        fresh.localController().apply(
            policy = NotificationCollectionPolicyV1(
                NotificationRuleMode.ALLOWLIST,
                listOf("mail"),
                NotificationFieldAccess.CONTENT,
                3u,
            ),
            authorizationRevision = 7u,
            granted = true,
        )

        val restored = PersistentNotificationPolicyAuthority(persistence)
        assertEquals(3uL, restored.snapshot().policy.policyRevision)
        assertEquals(7uL, restored.snapshot().authorizationRevision)
        assertTrue(restored.decide("mail", NotificationFieldAccess.CONTENT, 3u).allowed)
        assertFalse(restored.decide("mail", NotificationFieldAccess.CONTENT, 2u).allowed)
    }

    @Test
    fun local_authority_rejects_revision_rollback_and_same_revision_mutation() {
        val authority = PersistentNotificationPolicyAuthority(InMemoryNotificationPolicyPersistence())
        val controller = authority.localController()
        val policy = NotificationCollectionPolicyV1(
            NotificationRuleMode.ALLOWLIST,
            listOf("mail"),
            NotificationFieldAccess.METADATA,
            4u,
        )
        controller.apply(policy, authorizationRevision = 9u, granted = true)

        assertThrows(com.openandroidintelligence.core.model.PolicyRevisionRace::class.java) {
            controller.apply(policy.copy(policyRevision = 3u), authorizationRevision = 10u, granted = true)
        }
        assertThrows(com.openandroidintelligence.core.model.PolicyRevisionRace::class.java) {
            controller.apply(policy, authorizationRevision = 9u, granted = false)
        }
    }

    @Test
    fun revoke_is_immediate_persistent_and_requires_a_new_authorization_revision() {
        val persistence = InMemoryNotificationPolicyPersistence()
        val authority = PersistentNotificationPolicyAuthority(persistence)
        val controller = authority.localController()
        val policy = NotificationCollectionPolicyV1(
            NotificationRuleMode.ALLOWLIST,
            listOf("mail"),
            NotificationFieldAccess.CONTENT,
            1u,
        )
        controller.apply(policy, authorizationRevision = 1u, granted = true)
        controller.revoke(authorizationRevision = 2u)

        assertFalse(authority.decide("mail", NotificationFieldAccess.CONTENT, 1u).allowed)
        assertFalse(
            PersistentNotificationPolicyAuthority(persistence)
                .decide("mail", NotificationFieldAccess.CONTENT, 1u)
                .allowed,
        )
        assertThrows(com.openandroidintelligence.core.model.PolicyRevisionRace::class.java) {
            controller.apply(policy, authorizationRevision = 1u, granted = true)
        }
    }

    @Test
    fun corrupted_persistent_authority_fails_closed_without_overwriting_evidence() {
        val persistence = object : NotificationPolicyPersistence {
            var bytes = "not-a-policy".toByteArray()
            override fun read(): ByteArray = bytes.copyOf()
            override fun write(value: ByteArray) { bytes = value.copyOf() }
        }
        val authority = PersistentNotificationPolicyAuthority(persistence)

        assertTrue(authority.snapshot().corrupted)
        assertFalse(authority.decide("mail", NotificationFieldAccess.CONTENT, 0u).allowed)
        assertThrows(PolicyStateCorrupted::class.java) {
            authority.localController().apply(
                NotificationCollectionPolicyV1.default(),
                authorizationRevision = 1u,
                granted = true,
            )
        }
        assertEquals("not-a-policy", String(persistence.bytes))
    }

    @Test
    fun empty_allowlist_denies_every_package_without_authorization_call() {
        var calls = 0
        val evaluator = NotificationPolicyEvaluator(
            authorization = { _, _, _ -> calls += 1; AuthorizationDecision.allow() },
        )
        evaluator.apply(NotificationCollectionPolicyV1.default())
        val result = evaluator.evaluate("com.example.mail")
        assertFalse(result.accepted)
        assertEquals(0, calls)
    }

    @Test
    fun allowlist_and_denylist_match_packages_and_metadata_strips_content() {
        val allow = NotificationPolicyEvaluator { _, _, _ -> AuthorizationDecision.allow() }
        allow.apply(NotificationCollectionPolicyV1(NotificationRuleMode.ALLOWLIST, listOf("mail"), NotificationFieldAccess.METADATA, 3u))
        assertTrue(allow.evaluate("mail").accepted)
        assertFalse(allow.evaluate("chat").accepted)
        assertEquals(null, allow.evaluate("mail").content)

        val deny = NotificationPolicyEvaluator { _, _, _ -> AuthorizationDecision.allow() }
        deny.apply(NotificationCollectionPolicyV1(NotificationRuleMode.DENYLIST, listOf("chat"), NotificationFieldAccess.CONTENT, 4u))
        assertTrue(deny.evaluate("mail").accepted)
        assertFalse(deny.evaluate("chat").accepted)
    }

    @Test
    fun task6_denial_wins_after_local_policy_allow() {
        val evaluator = NotificationPolicyEvaluator { _, _, _ -> AuthorizationDecision.deny("GRANT_REQUIRED") }
        evaluator.apply(NotificationCollectionPolicyV1(NotificationRuleMode.ALLOWLIST, listOf("mail"), NotificationFieldAccess.CONTENT, 5u))
        val result = evaluator.evaluate("mail")
        assertFalse(result.accepted)
        assertEquals("GRANT_REQUIRED", result.reason)
    }
}
