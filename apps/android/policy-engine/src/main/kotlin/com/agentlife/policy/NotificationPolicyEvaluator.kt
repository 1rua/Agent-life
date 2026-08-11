package com.agentlife.policy

import com.agentlife.core.model.AuthorizationDecision
import com.agentlife.core.model.NotificationAuthorization
import com.agentlife.core.model.NotificationCollectionPolicyV1
import com.agentlife.core.model.NotificationContent
import com.agentlife.core.model.NotificationFieldAccess
import com.agentlife.core.model.NotificationRuleMode
import com.agentlife.core.model.PolicyRevisionRace

data class NotificationPolicyDecision(
    val accepted: Boolean,
    val fieldAccess: NotificationFieldAccess? = null,
    val reason: String? = null,
    /** Always null here; content is added only by the collector normalizer. */
    val content: NotificationContent? = null,
) {
    init {
        require(accepted || !reason.isNullOrBlank()) { "denials need a reason" }
        require(!accepted || reason == null) { "accepted decisions cannot carry a reason" }
    }
}

/**
 * Pure local policy gate. Authorization is called only after local package
 * matching, so denied callbacks have no route into a persistence layer.
 */
class NotificationPolicyEvaluator(
    private val authorization: NotificationAuthorization = NotificationAuthorization { _, _, _ ->
        AuthorizationDecision.deny("NO_AUTHORIZATION")
    },
) {
    @Volatile
    var policy: NotificationCollectionPolicyV1 = NotificationCollectionPolicyV1.default()
        private set

    fun apply(next: NotificationCollectionPolicyV1) {
        if (next.policyRevision < policy.policyRevision) {
            throw PolicyRevisionRace(
                "policy revision ${next.policyRevision} is older than ${policy.policyRevision}",
            )
        }
        if (next.policyRevision == policy.policyRevision && next != policy) {
            throw PolicyRevisionRace("policy contents changed without a revision")
        }
        policy = next.copy(packageIds = next.packageIds.toList())
    }

    fun evaluate(packageName: String): NotificationPolicyDecision {
        if (packageName.isBlank()) return denied("PACKAGE_INVALID")
        val snapshot = policy
        val localMatch = when (snapshot.mode) {
            NotificationRuleMode.ALLOWLIST -> packageName in snapshot.packageIds
            NotificationRuleMode.DENYLIST -> packageName !in snapshot.packageIds
        }
        if (!localMatch) return denied("PACKAGE_NOT_ALLOWED")

        val access = snapshot.fieldAccess
        val decision = authorization.decide(packageName, access, snapshot.policyRevision)
        if (!decision.allowed) return denied(decision.reason ?: "AUTHORIZATION_DENIED")
        return NotificationPolicyDecision(accepted = true, fieldAccess = access)
    }

    fun allows(packageName: String): Boolean = evaluate(packageName).accepted

    private fun denied(reason: String): NotificationPolicyDecision = NotificationPolicyDecision(
        accepted = false,
        reason = reason,
    )
}
