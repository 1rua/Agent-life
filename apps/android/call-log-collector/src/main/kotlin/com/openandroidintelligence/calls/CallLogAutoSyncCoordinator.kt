package com.openandroidintelligence.calls

import com.openandroidintelligence.capability.AuthorizedAutoSendSubscription
import com.openandroidintelligence.capability.MAX_CALL_LOG_BATCH_RECORDS
import com.openandroidintelligence.capability.MobileDataCapability
import com.openandroidintelligence.capability.requireAutoSendScope
import com.openandroidintelligence.core.model.CapabilityDurableEvent
import com.openandroidintelligence.core.model.CapabilityOutbox
import com.openandroidintelligence.core.model.CapabilityOutboxFull
import com.openandroidintelligence.sync.CapabilityDispatchFailure
import com.openandroidintelligence.sync.CapabilityEventEgressGate
import com.openandroidintelligence.sync.CapabilityPendingDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class CallLogSyncFailure {
    SOURCE_DISABLED,
    PERMISSION_REQUIRED,
    PLATFORM_UNSUPPORTED,
    CAPTURE_FAILURE,
    INVALID_PROVIDER_ROW,
    OUTBOX_FULL,
    OUTBOX_CORRUPTED,
    SYNC_STATE_CORRUPTED,
    CURSOR_NOT_DURABLE,
    POLICY_REVOKED,
    PAIRING_UNAVAILABLE,
    UNSUPPORTED_CAPABILITY,
    TRANSPORT_FAILURE,
    ACK_REJECTED,
    CANCELLED,
}

data class CallLogSyncRunResult(
    val captured: Int,
    val enqueued: Int,
    val acknowledged: Int,
    val retained: Int,
    val failure: CallLogSyncFailure?,
)

/**
 * Reconciles the encrypted call outbox with its durable source cursor before
 * collecting another finite provider batch. The same mutex is also the
 * revocation quiescence boundary, so no capture, cursor repair, or dispatch can
 * overlap storage/key cleanup performed by the caller.
 */
class CallLogAutoSyncCoordinator(
    private val provider: com.openandroidintelligence.capability.CallsCapabilityProvider,
    private val outbox: CapabilityOutbox,
    private val stateStore: CallLogSyncStateStore,
    private val codec: CallLogEventCodec,
    private val dispatcher: CapabilityPendingDispatcher,
    private val currentPolicyGate: CapabilityEventEgressGate,
    private val auditSink: CallLogAuditSink = CallLogAuditSink {},
    private val elapsedRealtimeMs: () -> Long =
        { System.nanoTime() / 1_000_000L },
) {
    private val runMutex = Mutex()

    suspend fun runOnce(
        subscription: AuthorizedAutoSendSubscription,
    ): CallLogSyncRunResult = runMutex.withLock {
        val started = elapsedRealtimeMs()
        val revision = subscription.request.policyRevision
        var captured = 0
        var enqueued = 0

        fun terminal(
            acknowledged: Int,
            retained: Int,
            failure: CallLogSyncFailure?,
        ): CallLogSyncRunResult = finish(
            revision = revision,
            started = started,
            captured = captured,
            enqueued = enqueued,
            acknowledged = acknowledged,
            retained = retained,
            failure = failure,
        )

        val scope = try {
            subscription.requireAutoSendScope(MobileDataCapability.CALLS)
        } catch (_: CancellationException) {
            return@withLock terminal(0, 0, CallLogSyncFailure.CANCELLED)
        } catch (_: Exception) {
            return@withLock terminal(0, 0, CallLogSyncFailure.POLICY_REVOKED)
        }
        val state = try {
            stateStore.snapshot()
        } catch (_: CancellationException) {
            return@withLock terminal(0, 0, CallLogSyncFailure.CANCELLED)
        } catch (_: Exception) {
            return@withLock terminal(0, 0, CallLogSyncFailure.SYNC_STATE_CORRUPTED)
        } ?: return@withLock terminal(0, 0, CallLogSyncFailure.SYNC_STATE_CORRUPTED)
        if (state.policyRevision != scope.policyRevision) {
            return@withLock terminal(0, 0, CallLogSyncFailure.POLICY_REVOKED)
        }

        val pending = try {
            outbox.recoverUnacknowledged()
        } catch (_: CancellationException) {
            return@withLock terminal(0, 0, CallLogSyncFailure.CANCELLED)
        } catch (_: Exception) {
            return@withLock terminal(0, 0, CallLogSyncFailure.OUTBOX_CORRUPTED)
        }
        val decodedPending = ArrayList<Pair<CapabilityDurableEvent, DecodedCallLogRecord>>(pending.size)
        var previousCursor: CallLogCursor? = null
        for (durable in pending) {
            val decoded = try {
                codec.decode(durable.eventWire)
            } catch (_: CancellationException) {
                return@withLock terminal(0, pending.size, CallLogSyncFailure.CANCELLED)
            } catch (_: Exception) {
                return@withLock terminal(0, pending.size, CallLogSyncFailure.OUTBOX_CORRUPTED)
            }
            val outerMatchesWire =
                durable.capability == CALL_LOG_CAPABILITY &&
                    durable.eventId == "call:${decoded.sourceEpoch}:${decoded.cursor.providerId}" &&
                    durable.recordId == decoded.recordId &&
                    durable.recordId == "call:${decoded.cursor.providerId}" &&
                    durable.policyRevision == decoded.policyRevision &&
                    decoded.captureRevision == decoded.policyRevision
            val wireMatchesCurrentAuthority =
                decoded.sourceEpoch == state.sourceEpoch &&
                    decoded.policyRevision == state.policyRevision &&
                    decoded.policyRevision == scope.policyRevision
            val insertionOrderIsValid = previousCursor == null || decoded.cursor > previousCursor
            if (!outerMatchesWire || !wireMatchesCurrentAuthority || !insertionOrderIsValid) {
                return@withLock terminal(0, pending.size, CallLogSyncFailure.OUTBOX_CORRUPTED)
            }
            val allowed = try {
                currentPolicyGate.allows(durable)
            } catch (_: CancellationException) {
                return@withLock terminal(0, pending.size, CallLogSyncFailure.CANCELLED)
            } catch (_: Exception) {
                false
            }
            if (!allowed) {
                return@withLock terminal(0, pending.size, CallLogSyncFailure.POLICY_REVOKED)
            }
            decodedPending += durable to decoded
            previousCursor = decoded.cursor
        }

        val maximumPendingCursor = decodedPending.lastOrNull()?.second?.cursor
        if (maximumPendingCursor != null && (state.cursor == null || maximumPendingCursor > state.cursor)) {
            val repaired = try {
                stateStore.advance(state.sourceEpoch, maximumPendingCursor, scope.policyRevision)
            } catch (_: CancellationException) {
                return@withLock terminal(0, pending.size, CallLogSyncFailure.CANCELLED)
            } catch (_: Exception) {
                false
            }
            if (!repaired) {
                val blocked = decodedPending
                    .filter { (_, decoded) -> state.cursor == null || decoded.cursor > state.cursor }
                    .mapTo(linkedSetOf()) { (durable) -> durable.eventId }
                val dispatch = try {
                    dispatcher.dispatchPending(blocked)
                } catch (_: CancellationException) {
                    return@withLock terminal(0, pending.size, CallLogSyncFailure.CANCELLED)
                } catch (_: Exception) {
                    return@withLock terminal(0, pending.size, CallLogSyncFailure.CURSOR_NOT_DURABLE)
                }
                return@withLock terminal(
                    dispatch.acknowledged,
                    dispatch.retained,
                    selectTerminalFailure(CallLogSyncFailure.CURSOR_NOT_DURABLE, dispatch.failure),
                )
            }
        }

        var collectionFailure: CallLogSyncFailure? = null
        val blockedEventIds = linkedSetOf<String>()
        try {
            provider.observeAutoSend(scope).take(MAX_CALL_LOG_BATCH_RECORDS).collect { providerEvent ->
                captured += 1
                val durable = try {
                    require(providerEvent.capability == MobileDataCapability.CALLS)
                    require(providerEvent.policyRevision == scope.policyRevision)
                    val cursor = providerEvent.record.metadata.toCallLogCursor()
                    val eventId = "call:${state.sourceEpoch}:${cursor.providerId}"
                    require(providerEvent.eventId == eventId)
                    require(providerEvent.record.metadata.recordId == "call:${cursor.providerId}")
                    CapabilityDurableEvent(
                        eventId = eventId,
                        capability = CALL_LOG_CAPABILITY,
                        recordId = providerEvent.record.metadata.recordId,
                        policyRevision = scope.policyRevision,
                        eventWire = codec.encode(
                            eventId,
                            providerEvent.record,
                            state.sourceEpoch,
                            scope.policyRevision,
                        ),
                    ).also { encoded ->
                        val decoded = codec.decode(encoded.eventWire)
                        require(encoded.capability == CALL_LOG_CAPABILITY)
                        require(encoded.eventId == eventId)
                        require(encoded.recordId == decoded.recordId)
                        require(decoded.recordId == "call:${cursor.providerId}")
                        require(decoded.cursor == cursor)
                        require(decoded.sourceEpoch == state.sourceEpoch)
                        require(decoded.captureRevision == scope.policyRevision)
                        require(decoded.policyRevision == scope.policyRevision)
                        require(encoded.policyRevision == decoded.policyRevision)
                    }
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: Exception) {
                    collectionFailure = CallLogSyncFailure.CAPTURE_FAILURE
                    throw CallLogCollectionHalted()
                }

                val allowed = try {
                    currentPolicyGate.allows(durable)
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: Exception) {
                    false
                }
                if (!allowed) {
                    collectionFailure = CallLogSyncFailure.POLICY_REVOKED
                    throw CallLogCollectionHalted()
                }
                try {
                    val accepted = outbox.enqueueAccepted(durable)
                    if (accepted != durable) {
                        blockedEventIds += durable.eventId
                        collectionFailure = CallLogSyncFailure.OUTBOX_CORRUPTED
                        throw CallLogCollectionHalted()
                    }
                    enqueued += 1
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: CapabilityOutboxFull) {
                    collectionFailure = CallLogSyncFailure.OUTBOX_FULL
                    throw CallLogCollectionHalted()
                } catch (halted: CallLogCollectionHalted) {
                    throw halted
                } catch (_: Exception) {
                    blockedEventIds += durable.eventId
                    collectionFailure = CallLogSyncFailure.OUTBOX_CORRUPTED
                    throw CallLogCollectionHalted()
                }

                val cursor = providerEvent.record.metadata.toCallLogCursor()
                val advanced = try {
                    stateStore.advance(state.sourceEpoch, cursor, scope.policyRevision)
                } catch (_: CancellationException) {
                    blockedEventIds += durable.eventId
                    throw CancellationException()
                } catch (_: Exception) {
                    false
                }
                if (!advanced) {
                    blockedEventIds += durable.eventId
                    collectionFailure = CallLogSyncFailure.CURSOR_NOT_DURABLE
                    throw CallLogCollectionHalted()
                }
            }
        } catch (_: CancellationException) {
            return@withLock terminal(0, pending.size + enqueued, CallLogSyncFailure.CANCELLED)
        } catch (_: CallLogCollectionHalted) {
            // The closed failure was selected at the exact capture cut.
        } catch (failure: CallLogProviderException) {
            collectionFailure = failure.failure.toSyncFailure()
        } catch (_: Exception) {
            collectionFailure = CallLogSyncFailure.CAPTURE_FAILURE
        }

        val dispatch = try {
            dispatcher.dispatchPending(blockedEventIds)
        } catch (_: CancellationException) {
            return@withLock terminal(0, pending.size + enqueued, CallLogSyncFailure.CANCELLED)
        } catch (_: Exception) {
            return@withLock terminal(0, pending.size + enqueued, CallLogSyncFailure.TRANSPORT_FAILURE)
        }
        terminal(
            acknowledged = dispatch.acknowledged,
            retained = dispatch.retained,
            failure = selectTerminalFailure(collectionFailure, dispatch.failure),
        )
    }

    suspend fun withSyncQuiesced(block: suspend () -> Unit) {
        runMutex.withLock { block() }
    }

    private fun finish(
        revision: ULong,
        started: Long,
        captured: Int,
        enqueued: Int,
        acknowledged: Int,
        retained: Int,
        failure: CallLogSyncFailure?,
    ): CallLogSyncRunResult {
        val result = CallLogSyncRunResult(captured, enqueued, acknowledged, retained, failure)
        try {
            auditSink.record(
                CallLogAuditEvent(
                    policyRevision = revision,
                    resultCode = failure.toAuditResult(),
                    readCount = captured,
                    acceptedCount = enqueued,
                    acknowledgedCount = acknowledged,
                    latencyBucket = callLogLatencyBucket((elapsedRealtimeMs() - started).coerceAtLeast(0L)),
                ),
            )
        } catch (_: Exception) {
            // The fixed-field audit record is best effort and cannot change durable state.
        }
        return result
    }
}

private fun CallLogProviderFailure.toSyncFailure(): CallLogSyncFailure = when (this) {
    CallLogProviderFailure.SOURCE_DISABLED -> CallLogSyncFailure.SOURCE_DISABLED
    CallLogProviderFailure.PERMISSION_REQUIRED -> CallLogSyncFailure.PERMISSION_REQUIRED
    CallLogProviderFailure.PLATFORM_UNSUPPORTED -> CallLogSyncFailure.PLATFORM_UNSUPPORTED
    CallLogProviderFailure.INVALID_ROW -> CallLogSyncFailure.INVALID_PROVIDER_ROW
    CallLogProviderFailure.QUERY_FAILED -> CallLogSyncFailure.CAPTURE_FAILURE
    CallLogProviderFailure.POLICY_STALE -> CallLogSyncFailure.POLICY_REVOKED
}

private fun CapabilityDispatchFailure.toCallLogSyncFailure(): CallLogSyncFailure = when (this) {
    CapabilityDispatchFailure.PAIRING_UNAVAILABLE -> CallLogSyncFailure.PAIRING_UNAVAILABLE
    CapabilityDispatchFailure.POLICY_REVOKED -> CallLogSyncFailure.POLICY_REVOKED
    CapabilityDispatchFailure.UNSUPPORTED_CAPABILITY -> CallLogSyncFailure.UNSUPPORTED_CAPABILITY
    CapabilityDispatchFailure.ACK_REJECTED -> CallLogSyncFailure.ACK_REJECTED
    CapabilityDispatchFailure.OUTBOX_FAILURE -> CallLogSyncFailure.OUTBOX_CORRUPTED
    CapabilityDispatchFailure.TRANSPORT_FAILURE -> CallLogSyncFailure.TRANSPORT_FAILURE
    CapabilityDispatchFailure.CANCELLED -> CallLogSyncFailure.CANCELLED
}

private fun selectTerminalFailure(
    collectionFailure: CallLogSyncFailure?,
    dispatchFailure: CapabilityDispatchFailure?,
): CallLogSyncFailure? {
    val mappedDispatch = dispatchFailure?.toCallLogSyncFailure()
    return when (mappedDispatch) {
        CallLogSyncFailure.CANCELLED -> CallLogSyncFailure.CANCELLED
        CallLogSyncFailure.OUTBOX_CORRUPTED -> CallLogSyncFailure.OUTBOX_CORRUPTED
        else -> collectionFailure ?: mappedDispatch
    }
}

private fun CallLogSyncFailure?.toAuditResult(): CallLogAuditResultCode = when (this) {
    null -> CallLogAuditResultCode.COMPLETE
    CallLogSyncFailure.SOURCE_DISABLED -> CallLogAuditResultCode.CALL_LOG_DISABLED
    CallLogSyncFailure.PERMISSION_REQUIRED -> CallLogAuditResultCode.CALL_LOG_PERMISSION_REQUIRED
    CallLogSyncFailure.PLATFORM_UNSUPPORTED -> CallLogAuditResultCode.CALL_LOG_PLATFORM_UNSUPPORTED
    CallLogSyncFailure.CAPTURE_FAILURE -> CallLogAuditResultCode.CALL_LOG_QUERY_FAILED
    CallLogSyncFailure.INVALID_PROVIDER_ROW -> CallLogAuditResultCode.CALL_LOG_INVALID_ROW
    CallLogSyncFailure.OUTBOX_FULL -> CallLogAuditResultCode.CALL_LOG_OUTBOX_FULL
    CallLogSyncFailure.OUTBOX_CORRUPTED -> CallLogAuditResultCode.CALL_LOG_OUTBOX_CORRUPTED
    CallLogSyncFailure.SYNC_STATE_CORRUPTED -> CallLogAuditResultCode.CALL_LOG_SYNC_STATE_CORRUPTED
    CallLogSyncFailure.CURSOR_NOT_DURABLE -> CallLogAuditResultCode.CALL_LOG_CURSOR_NOT_DURABLE
    CallLogSyncFailure.POLICY_REVOKED -> CallLogAuditResultCode.CALL_LOG_POLICY_REVOKED
    CallLogSyncFailure.PAIRING_UNAVAILABLE -> CallLogAuditResultCode.CALL_LOG_PAIRING_UNAVAILABLE
    CallLogSyncFailure.UNSUPPORTED_CAPABILITY -> CallLogAuditResultCode.CALL_LOG_UNSUPPORTED_CAPABILITY
    CallLogSyncFailure.TRANSPORT_FAILURE -> CallLogAuditResultCode.CALL_LOG_TRANSPORT_FAILURE
    CallLogSyncFailure.ACK_REJECTED -> CallLogAuditResultCode.CALL_LOG_ACK_REJECTED
    CallLogSyncFailure.CANCELLED -> CallLogAuditResultCode.CALL_LOG_CANCELLED
}

private const val CALL_LOG_CAPABILITY = "calls.metadata"

private class CallLogCollectionHalted : RuntimeException(null, null, false, false)
