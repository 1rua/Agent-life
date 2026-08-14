package com.agentlife.calls

import com.agentlife.capability.AuthorizedAutoSendScope
import com.agentlife.capability.AuthorizedReadScope
import com.agentlife.capability.CallDirection
import com.agentlife.capability.CallNumberPresentation
import com.agentlife.capability.CapabilityAvailability
import com.agentlife.capability.CapabilityEvent
import com.agentlife.capability.CapabilityFilter
import com.agentlife.capability.CapabilityReadResult
import com.agentlife.capability.CapabilityReadStatus
import com.agentlife.capability.CallsCapabilityProvider
import com.agentlife.capability.CallsMetadata
import com.agentlife.capability.CallsPayload
import com.agentlife.capability.MobileDataCapability
import com.agentlife.capability.normalizeCallCounterpartyNumber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

enum class CallLogProviderFailure(val code: String) {
    SOURCE_DISABLED("CALL_LOG_DISABLED"),
    PERMISSION_REQUIRED("CALL_LOG_PERMISSION_REQUIRED"),
    PLATFORM_UNSUPPORTED("CALL_LOG_PLATFORM_UNSUPPORTED"),
    QUERY_FAILED("CALL_LOG_QUERY_FAILED"),
    INVALID_ROW("CALL_LOG_INVALID_ROW"),
    POLICY_STALE("CALL_LOG_POLICY_STALE"),
}

class CallLogProviderException(val failure: CallLogProviderFailure) :
    IllegalStateException(failure.code)

class AndroidCallLogCapabilityProvider(
    private val reader: CallLogReader,
    private val settings: PersistentCallLogSettingsAuthority,
    private val availability: CallLogAvailabilitySource,
    private val syncState: CallLogSyncStateStore,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val elapsedRealtimeMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val auditSink: CallLogAuditSink = CallLogAuditSink {},
) : CallsCapabilityProvider {
    override suspend fun read(scope: AuthorizedReadScope): CapabilityReadResult<CallsPayload> {
        val started = elapsedRealtimeMs()
        var readCount = 0
        var acceptedCount = 0
        return try {
            val fence = requireCurrentPolicy(scope)
            requireReady()
            val rows = reader.query(CallLogQuery(fence.policy.historyPolicy, fence.policy.directions))
                .take(fence.policy.historyPolicy.maxRecords)
            readCount = rows.size
            val records = rows.map { it.toPayload(scope, fence.policy.directions) }
            acceptedCount = records.size
            requireUnchanged(fence, scope)
            complete(scope.policyRevision, records, started, readCount, acceptedCount)
        } catch (cancelled: CancellationException) {
            audit(scope.policyRevision, CallLogAuditResultCode.CALL_LOG_CANCELLED, readCount, acceptedCount, 0, started)
            throw cancelled
        } catch (failure: CallLogProviderException) {
            failed(scope.policyRevision, failure.failure, started, readCount, acceptedCount)
        } catch (failure: CallLogPermissionRequiredException) {
            failed(scope.policyRevision, CallLogProviderFailure.PERMISSION_REQUIRED, started, readCount, acceptedCount)
        } catch (failure: SecurityException) {
            failed(scope.policyRevision, CallLogProviderFailure.PERMISSION_REQUIRED, started, readCount, acceptedCount)
        } catch (failure: CallLogInvalidRowException) {
            failed(scope.policyRevision, CallLogProviderFailure.INVALID_ROW, started, readCount, acceptedCount)
        } catch (failure: CallLogQueryException) {
            failed(scope.policyRevision, CallLogProviderFailure.QUERY_FAILED, started, readCount, acceptedCount)
        } catch (_: Exception) {
            failed(scope.policyRevision, CallLogProviderFailure.QUERY_FAILED, started, readCount, acceptedCount)
        }
    }

    override fun observeAutoSend(scope: AuthorizedAutoSendScope): Flow<CapabilityEvent<CallsPayload>> = flow {
        val started = elapsedRealtimeMs()
        var readCount = 0
        var acceptedCount = 0
        try {
            val fence = requireCurrentPolicy(scope)
            requireReady()
            val state = syncState.snapshot() ?: throw CallLogSyncStateCorrupted()
            if (state.policyRevision != scope.policyRevision) {
                throw CallLogProviderException(CallLogProviderFailure.POLICY_STALE)
            }
            val rows = reader.query(CallLogQuery(fence.policy.historyPolicy, fence.policy.directions, state.cursor))
                .take(fence.policy.historyPolicy.maxRecords)
            readCount = rows.size
            val events = rows.map { row ->
                CapabilityEvent(
                    capability = MobileDataCapability.CALLS,
                    eventId = "call:${state.sourceEpoch}:${row.providerId}",
                    record = row.toPayload(scope, fence.policy.directions),
                    policyRevision = scope.policyRevision,
                )
            }.sortedWith(compareBy<CapabilityEvent<CallsPayload>> { it.record.metadata.startedAtEpochMs }
                .thenBy { it.record.metadata.recordId.removePrefix("call:").toLong() })
            acceptedCount = events.size
            requireUnchanged(fence, scope)
            events.forEach { event -> emit(event) }
            audit(scope.policyRevision, CallLogAuditResultCode.COMPLETE, readCount, acceptedCount, 0, started)
        } catch (cancelled: CancellationException) {
            audit(scope.policyRevision, CallLogAuditResultCode.CALL_LOG_CANCELLED, readCount, acceptedCount, 0, started)
            throw cancelled
        } catch (failure: CallLogProviderException) {
            audit(scope.policyRevision, failure.failure.auditCode(), readCount, acceptedCount, 0, started)
            throw failure
        } catch (failure: CallLogPermissionRequiredException) {
            throwProvider(scope, CallLogProviderFailure.PERMISSION_REQUIRED, started, readCount, acceptedCount)
        } catch (failure: SecurityException) {
            throwProvider(scope, CallLogProviderFailure.PERMISSION_REQUIRED, started, readCount, acceptedCount)
        } catch (failure: CallLogInvalidRowException) {
            throwProvider(scope, CallLogProviderFailure.INVALID_ROW, started, readCount, acceptedCount)
        } catch (failure: CallLogQueryException) {
            throwProvider(scope, CallLogProviderFailure.QUERY_FAILED, started, readCount, acceptedCount)
        } catch (failure: CallLogSyncStateCorrupted) {
            audit(scope.policyRevision, CallLogAuditResultCode.CALL_LOG_SYNC_STATE_CORRUPTED, readCount, acceptedCount, 0, started)
            throw CallLogProviderException(CallLogProviderFailure.QUERY_FAILED)
        } catch (_: Exception) {
            throwProvider(scope, CallLogProviderFailure.QUERY_FAILED, started, readCount, acceptedCount)
        }
    }

    private fun requireCurrentPolicy(scope: AuthorizedReadScope): PolicyFence = policyFence(scope.filter, scope.policyRevision)

    private fun requireCurrentPolicy(scope: AuthorizedAutoSendScope): PolicyFence = policyFence(scope.filter, scope.policyRevision)

    private fun policyFence(filter: CapabilityFilter, revision: ULong): PolicyFence {
        val snapshot = settings.snapshot()
        val enabled = snapshot.phase as? CallLogSettingsPhase.Enabled
            ?: throw CallLogProviderException(CallLogProviderFailure.SOURCE_DISABLED)
        if (snapshot.corrupted || snapshot.epochExhausted) {
            throw CallLogProviderException(CallLogProviderFailure.SOURCE_DISABLED)
        }
        if (enabled.policy.policyRevision != revision || enabled.policy.filter() != filter) {
            throw CallLogProviderException(CallLogProviderFailure.POLICY_STALE)
        }
        return PolicyFence(enabled.policy, snapshot.authorizationRevision)
    }

    private fun requireReady() = when (availability.current()) {
        CapabilityAvailability.READY -> Unit
        CapabilityAvailability.DISABLED -> throw CallLogProviderException(CallLogProviderFailure.SOURCE_DISABLED)
        CapabilityAvailability.PERMISSION_REQUIRED -> throw CallLogProviderException(CallLogProviderFailure.PERMISSION_REQUIRED)
        CapabilityAvailability.PLATFORM_UNSUPPORTED -> throw CallLogProviderException(CallLogProviderFailure.PLATFORM_UNSUPPORTED)
    }

    private fun requireUnchanged(fence: PolicyFence, scope: AuthorizedReadScope) = requireUnchanged(fence, scope.filter, scope.policyRevision)

    private fun requireUnchanged(fence: PolicyFence, scope: AuthorizedAutoSendScope) = requireUnchanged(fence, scope.filter, scope.policyRevision)

    private fun requireUnchanged(fence: PolicyFence, filter: CapabilityFilter, revision: ULong) {
        val current = settings.snapshot()
        val enabled = current.phase as? CallLogSettingsPhase.Enabled
        if (
            current.corrupted || current.epochExhausted ||
            current.authorizationRevision != fence.authorizationRevision ||
            enabled == null || enabled.policy.policyRevision != revision ||
            enabled.policy.filter() != filter
        ) {
            throw CallLogProviderException(CallLogProviderFailure.POLICY_STALE)
        }
    }

    private fun CallLogRow.toPayload(scope: AuthorizedReadScope, directions: Set<CallDirection>): CallsPayload =
        CallsPayload(metadata(), normalizeCallCounterpartyNumber(number, numberPresentation, scope)).also {
            if (direction !in directions) throw CallLogInvalidRowException()
        }

    private fun CallLogRow.toPayload(scope: AuthorizedAutoSendScope, directions: Set<CallDirection>): CallsPayload =
        CallsPayload(metadata(), normalizeCallCounterpartyNumber(number, numberPresentation, scope)).also {
            if (direction !in directions) throw CallLogInvalidRowException()
        }

    private fun CallLogRow.metadata(): CallsMetadata = try {
        CallsMetadata(
            recordId = "call:$providerId",
            observedAtEpochMs = nowEpochMs(),
            direction = direction,
            startedAtEpochMs = startedAtEpochMs,
            endedAtEpochMs = endedAtEpochMs,
            durationSeconds = durationSeconds,
            numberPresentation = numberPresentation,
        )
    } catch (_: IllegalArgumentException) {
        throw CallLogInvalidRowException()
    } catch (_: ArithmeticException) {
        throw CallLogInvalidRowException()
    }

    private fun complete(
        revision: ULong,
        records: List<CallsPayload>,
        started: Long,
        readCount: Int,
        acceptedCount: Int,
    ): CapabilityReadResult<CallsPayload> {
        audit(revision, CallLogAuditResultCode.COMPLETE, readCount, acceptedCount, 0, started)
        return CapabilityReadResult(records, CapabilityReadStatus.COMPLETE, revision)
    }

    private fun failed(
        revision: ULong,
        failure: CallLogProviderFailure,
        started: Long,
        readCount: Int,
        acceptedCount: Int,
    ): CapabilityReadResult<CallsPayload> {
        audit(revision, failure.auditCode(), readCount, acceptedCount, 0, started)
        return CapabilityReadResult(emptyList(), CapabilityReadStatus.FAILED, revision, failure.code)
    }

    private fun throwProvider(
        scope: AuthorizedAutoSendScope,
        failure: CallLogProviderFailure,
        started: Long,
        readCount: Int,
        acceptedCount: Int,
    ): Nothing {
        audit(scope.policyRevision, failure.auditCode(), readCount, acceptedCount, 0, started)
        throw CallLogProviderException(failure)
    }

    private fun audit(
        revision: ULong,
        result: CallLogAuditResultCode,
        readCount: Int,
        acceptedCount: Int,
        acknowledgedCount: Int,
        started: Long,
    ) {
        auditSink.record(
            CallLogAuditEvent(
                policyRevision = revision,
                resultCode = result,
                readCount = readCount,
                acceptedCount = acceptedCount,
                acknowledgedCount = acknowledgedCount,
                latencyBucket = callLogLatencyBucket((elapsedRealtimeMs() - started).coerceAtLeast(0L)),
            ),
        )
    }

    private data class PolicyFence(
        val policy: CallLogLocalPolicy,
        val authorizationRevision: ULong,
    )
}

fun CallsMetadata.toCallLogCursor(): CallLogCursor = CallLogCursor(
    startedAtEpochMs = startedAtEpochMs,
    providerId = recordId.removePrefix("call:").toLong(),
)

private fun CallLogProviderFailure.auditCode(): CallLogAuditResultCode = when (this) {
    CallLogProviderFailure.SOURCE_DISABLED -> CallLogAuditResultCode.CALL_LOG_DISABLED
    CallLogProviderFailure.PERMISSION_REQUIRED -> CallLogAuditResultCode.CALL_LOG_PERMISSION_REQUIRED
    CallLogProviderFailure.PLATFORM_UNSUPPORTED -> CallLogAuditResultCode.CALL_LOG_PLATFORM_UNSUPPORTED
    CallLogProviderFailure.QUERY_FAILED -> CallLogAuditResultCode.CALL_LOG_QUERY_FAILED
    CallLogProviderFailure.INVALID_ROW -> CallLogAuditResultCode.CALL_LOG_INVALID_ROW
    CallLogProviderFailure.POLICY_STALE -> CallLogAuditResultCode.CALL_LOG_POLICY_STALE
}
