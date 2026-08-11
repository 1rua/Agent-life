package com.agentlife.policy

import com.agentlife.core.model.AuthorizationDecision
import com.agentlife.core.model.NotificationCollectionPolicyV1
import com.agentlife.core.model.NotificationFieldAccess
import com.agentlife.core.model.NotificationRuleMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPolicyEvaluatorTest {
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
