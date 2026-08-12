package com.agentlife.mobile

import com.agentlife.core.model.NotificationCollectionPolicyV1
import com.agentlife.core.model.NotificationDeliveryMode
import com.agentlife.core.model.NotificationFieldAccess
import com.agentlife.core.model.NotificationRuleMode
import com.agentlife.core.model.sortNotificationPackageIds
import com.agentlife.policy.NotificationAuthoritySnapshot

/** Pure draft representation used by the local Android settings view. */
data class NotificationSettingsDraft(
    val packageIdsText: String = "",
    val fieldAccess: NotificationFieldAccess = NotificationFieldAccess.METADATA,
    val deliveryMode: NotificationDeliveryMode = NotificationDeliveryMode.ON_DEMAND,
    val granted: Boolean = false,
)

/** The complete, single mutation submitted by the local settings view. */
data class NotificationSettingsCommit(
    val policy: NotificationCollectionPolicyV1,
    val authorizationRevision: ULong,
    val granted: Boolean,
    val deliveryMode: NotificationDeliveryMode,
)

/**
 * Canonicalize the newline-separated local package allowlist.
 *
 * Package IDs follow the same closed syntax as the query boundary.  The
 * resulting list is unique and sorted by Unicode code point through the
 * shared core-model comparator.
 */
fun normalizeNotificationPackageIds(packageIdsText: String): List<String> {
    val packageIds = packageIdsText
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toList()

    require(packageIds.all(PACKAGE_ID::matches)) { "package ID is invalid" }
    return try {
        sortNotificationPackageIds(packageIds)
    } catch (failure: IllegalArgumentException) {
        throw IllegalArgumentException("package IDs must be unique", failure)
    }
}

/**
 * Convert a local draft into a monotonic commit.  Any policy, grant, or
 * delivery-mode change advances authorization and policy revisions together,
 * while an unchanged draft preserves the current snapshot revisions.
 */
fun commitNotificationSettings(
    baseline: NotificationAuthoritySnapshot,
    draft: NotificationSettingsDraft,
): NotificationSettingsCommit {
    require(!baseline.corrupted) { "notification policy evidence is corrupted" }

    val packageIds = normalizeNotificationPackageIds(draft.packageIdsText)
    val candidatePolicy = NotificationCollectionPolicyV1(
        mode = NotificationRuleMode.ALLOWLIST,
        packageIds = packageIds,
        fieldAccess = draft.fieldAccess,
        policyRevision = baseline.policy.policyRevision,
    )
    val policyChanged = candidatePolicy.copy(policyRevision = baseline.policy.policyRevision) != baseline.policy
    val changed = policyChanged || draft.granted != baseline.granted ||
        draft.deliveryMode != baseline.deliveryMode
    val authorizationRevision = if (changed) nextRevision(baseline) else baseline.authorizationRevision
    val policyRevision = if (changed) authorizationRevision else baseline.policy.policyRevision

    return NotificationSettingsCommit(
        policy = candidatePolicy.copy(policyRevision = policyRevision),
        authorizationRevision = authorizationRevision,
        granted = draft.granted,
        deliveryMode = draft.deliveryMode,
    )
}

private fun nextRevision(baseline: NotificationAuthoritySnapshot): ULong {
    val current = maxOf(baseline.authorizationRevision, baseline.policy.policyRevision)
    require(current != ULong.MAX_VALUE) { "notification revision exhausted" }
    return current + 1uL
}

private val PACKAGE_ID = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")
