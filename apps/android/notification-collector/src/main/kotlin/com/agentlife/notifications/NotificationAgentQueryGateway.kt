package com.agentlife.notifications

import com.agentlife.core.model.NotificationCaptureResult
import com.agentlife.core.model.NotificationCaptureStatus
import com.agentlife.core.model.NotificationCollector
import com.agentlife.core.model.NotificationFieldAccess
import com.agentlife.core.model.NotificationRecordV1
import com.agentlife.core.model.OnDemandNotificationRead
import com.agentlife.core.model.compareNotificationPackageIds
import com.agentlife.policy.PersistentNotificationPolicyAuthority
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class NotificationQueryFilter(
    val packageIds: List<String> = emptyList(),
    val fieldAccess: NotificationFieldAccess = NotificationFieldAccess.METADATA,
) {
    init {
        if (packageIds.any { !PACKAGE_NAME.matches(it) }) {
            throw NotificationQueryRejected("PACKAGE_ID_INVALID")
        }
        if (packageIds.size != packageIds.toSet().size) {
            throw NotificationQueryRejected("PACKAGE_IDS_DUPLICATE")
        }
        if (packageIds.zipWithNext().any { (left, right) -> compareNotificationPackageIds(left, right) >= 0 }) {
            throw NotificationQueryRejected("PACKAGE_IDS_UNSORTED")
        }
    }

    private companion object {
        val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")
    }
}

data class NotificationAgentQueryRequest(
    val operationId: String,
    val policyRevision: ULong,
    val limit: Int,
    val filter: NotificationQueryFilter = NotificationQueryFilter(),
) {
    init {
        if (operationId.isBlank()) throw NotificationQueryRejected("OPERATION_ID_INVALID")
        if (limit !in 1..100) throw NotificationQueryRejected("LIMIT_INVALID")
    }
}

class NotificationQueryRejected(val code: String) : IllegalArgumentException(code)

class NotificationAgentQueryGateway(
    private val collector: NotificationCollector,
    private val authority: PersistentNotificationPolicyAuthority,
) {
    private val operationLock = Mutex()
    private val completedOperations = mutableMapOf<String, CachedOperation>()

    suspend fun query(request: NotificationAgentQueryRequest): NotificationCaptureResult = operationLock.withLock {
        val snapshot = authority.snapshot()
        authorizationFailure(request, snapshot)?.let { reason ->
            return@withLock failed(reason, snapshot.policy.policyRevision).also { result ->
                completedOperations[request.operationId] = CachedOperation(request, result)
            }
        }

        completedOperations[request.operationId]?.let { cached ->
            if (cached.request != request) {
                throw NotificationQueryRejected("OPERATION_IDENTITY_MISMATCH")
            }
            return@withLock cached.result
        }

        val captured = collector.captureOnDemand(
            OnDemandNotificationRead(request.operationId, request.policyRevision, request.limit),
        )
        val current = authority.snapshot()
        val result = authorizationFailure(request, current)?.let { reason ->
            failed(reason, current.policy.policyRevision)
        } ?: filterCaptured(request, captured)

        completedOperations[request.operationId] = CachedOperation(request, result)
        result
    }

    private fun authorizationFailure(
        request: NotificationAgentQueryRequest,
        snapshot: com.agentlife.policy.NotificationAuthoritySnapshot,
    ): String? {
        return when {
            request.policyRevision != snapshot.policy.policyRevision -> "AUTHORIZATION_REVISION_STALE"
            snapshot.corrupted -> "LOCAL_POLICY_CORRUPTED"
            !snapshot.granted -> "LOCAL_GRANT_REQUIRED"
            request.filter.fieldAccess == NotificationFieldAccess.CONTENT &&
                snapshot.policy.fieldAccess != NotificationFieldAccess.CONTENT -> "FIELD_ACCESS_NOT_GRANTED"
            request.filter.packageIds.isNotEmpty() &&
                request.filter.packageIds.any { !matches(snapshot.policy.mode, snapshot.policy.packageIds, it) } ->
                "PACKAGE_NOT_ALLOWED"
            else -> null
        }
    }

    private fun filterCaptured(
        request: NotificationAgentQueryRequest,
        captured: NotificationCaptureResult,
    ): NotificationCaptureResult {
        return captured.copy(records = captured.records.mapNotNull { record ->
            when (record) {
                is NotificationRecordV1.Upsert -> {
                    if (request.filter.packageIds.isNotEmpty() && record.metadata.packageName !in request.filter.packageIds) {
                        null
                    } else if (request.filter.fieldAccess == NotificationFieldAccess.METADATA) {
                        record.copy(content = null)
                    } else {
                        record
                    }
                }
                is NotificationRecordV1.DeleteTombstone -> {
                    if (request.filter.packageIds.isEmpty() || record.metadata.packageName in request.filter.packageIds) record else null
                }
                is NotificationRecordV1.LossMarker -> {
                    if (request.filter.packageIds.isEmpty()) record else null
                }
            }
        })
    }

    private fun matches(
        mode: com.agentlife.core.model.NotificationRuleMode,
        packageIds: List<String>,
        packageName: String,
    ): Boolean = when (mode) {
        com.agentlife.core.model.NotificationRuleMode.ALLOWLIST -> packageName in packageIds
        com.agentlife.core.model.NotificationRuleMode.DENYLIST -> packageName !in packageIds
    }

    private fun failed(reason: String, revision: ULong): NotificationCaptureResult = NotificationCaptureResult(
        records = emptyList(),
        status = NotificationCaptureStatus.FAILED,
        failureReason = reason,
        policyRevision = revision,
    )

    private data class CachedOperation(
        val request: NotificationAgentQueryRequest,
        val result: NotificationCaptureResult,
    )
}
