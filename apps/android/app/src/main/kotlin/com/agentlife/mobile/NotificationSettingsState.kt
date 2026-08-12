package com.agentlife.mobile

import com.agentlife.core.model.NotificationCollectionPolicyV1
import com.agentlife.core.model.NotificationDeliveryMode
import com.agentlife.core.model.NotificationFieldAccess
import com.agentlife.core.model.NotificationRuleMode
import com.agentlife.core.model.sortNotificationPackageIds
import com.agentlife.policy.NotificationAuthoritySnapshot

/** Pure draft representation used by the local Android settings view. */
data class NotificationSettingsDraft(
    val granted: Boolean = false,
    val deliveryMode: NotificationDeliveryMode = NotificationDeliveryMode.ON_DEMAND,
    val fieldAccess: NotificationFieldAccess = NotificationFieldAccess.METADATA,
    val ruleMode: NotificationRuleMode = NotificationRuleMode.ALLOWLIST,
    val packageIds: List<String> = emptyList(),
) {
    fun commitAgainst(baseline: NotificationAuthoritySnapshot): NotificationSettingsCommit {
        require(!baseline.corrupted) { "notification policy evidence is corrupted" }

        val normalizedPackageIds = normalizeNotificationPackageIds(packageIds)
        val candidatePolicy = NotificationCollectionPolicyV1(
            mode = ruleMode,
            packageIds = normalizedPackageIds,
            fieldAccess = fieldAccess,
            policyRevision = baseline.policy.policyRevision,
        )
        val policyChanged = candidatePolicy != baseline.policy
        val authorizationChanged = policyChanged || granted != baseline.granted ||
            deliveryMode != baseline.deliveryMode
        val authorizationRevision = if (authorizationChanged) {
            nextRevision(baseline.authorizationRevision, baseline.policy.policyRevision)
        } else {
            baseline.authorizationRevision
        }
        val policyRevision = when {
            policyChanged -> nextRevision(
                baseline.policy.policyRevision,
                baseline.authorizationRevision,
            )
            authorizationChanged -> maxOf(
                baseline.policy.policyRevision,
                baseline.authorizationRevision,
            )
            else -> baseline.policy.policyRevision
        }

        return NotificationSettingsCommit(
            policy = candidatePolicy.copy(policyRevision = policyRevision),
            authorizationRevision = authorizationRevision,
            granted = granted,
            deliveryMode = deliveryMode,
        )
    }
}

/** The complete, single mutation submitted by the local settings view. */
data class NotificationSettingsCommit(
    val policy: NotificationCollectionPolicyV1,
    val authorizationRevision: ULong,
    val granted: Boolean,
    val deliveryMode: NotificationDeliveryMode,
)

/** Canonicalize package IDs selected in the local settings UI. */
fun normalizeNotificationPackageIds(packageIds: Iterable<String>): List<String> {
    val normalized = packageIds
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toList()

    require(normalized.all(PACKAGE_ID::matches)) { "package ID is invalid" }
    return try {
        sortNotificationPackageIds(normalized)
    } catch (failure: IllegalArgumentException) {
        throw IllegalArgumentException("package IDs must be unique", failure)
    }
}

/** Compatibility seam for callers that already hold a local settings draft. */
fun commitNotificationSettings(
    baseline: NotificationAuthoritySnapshot,
    draft: NotificationSettingsDraft,
): NotificationSettingsCommit = draft.commitAgainst(baseline)

private fun nextRevision(current: ULong, other: ULong = 0uL): ULong {
    val highest = maxOf(current, other)
    require(highest != ULong.MAX_VALUE) { "notification revision exhausted" }
    return highest + 1uL
}

private val PACKAGE_ID = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")
