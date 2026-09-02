package com.openandroidintelligence.calls

import com.openandroidintelligence.capability.AuthorizedAutoSendScope
import com.openandroidintelligence.capability.AuthorizedAutoSendSubscription
import com.openandroidintelligence.capability.CallDirection
import com.openandroidintelligence.capability.CallNumberPresentation
import com.openandroidintelligence.capability.CapabilityEvent
import com.openandroidintelligence.capability.CapabilityReadResult
import com.openandroidintelligence.capability.CallsCapabilityProvider
import com.openandroidintelligence.capability.CallsMetadata
import com.openandroidintelligence.capability.CallsPayload
import com.openandroidintelligence.capability.MobileDataCapability
import com.openandroidintelligence.capability.NormalizedContent
import com.openandroidintelligence.core.model.CapabilityDurableEvent
import com.openandroidintelligence.core.model.CapabilityOutbox
import com.openandroidintelligence.core.model.CapabilityOutboxFull
import com.openandroidintelligence.encrypted.store.AesGcmKeyProvider
import com.openandroidintelligence.encrypted.store.InMemoryOutboxPersistence
import com.openandroidintelligence.sync.CapabilityDispatchFailure
import com.openandroidintelligence.sync.CapabilityDispatchResult
import com.openandroidintelligence.sync.CapabilityEventEgressGate
import com.openandroidintelligence.sync.CapabilityPendingDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import javax.crypto.SecretKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CallLogAutoSyncCoordinatorTest {
    private val codec: CallLogEventCodec = DeterministicCallLogEventCodec()

    @Test
    fun `reconciles then enqueues before cursor and dispatches last`() = runBlocking {
        val trace = mutableListOf<String>()
        val outbox = RecordingOutbox(trace = trace)
        val state = RecordingState(trace = trace)
        val dispatcher = RecordingDispatcher(trace = trace)
        val coordinator = coordinator(
            provider = provider(flowOf(event(42, 1_700))),
            outbox = outbox,
            state = state,
            dispatcher = dispatcher,
        )

        val result = coordinator.runOnce(subscription())

        assertEquals(
            listOf(
                "reconcile",
                "enqueue:call:3:42",
                "cursor:3:1700:42",
                "dispatch",
            ),
            trace,
        )
        assertEquals(CallLogSyncRunResult(1, 1, 1, 0, null), result)
    }

    @Test
    fun `repairs state to highest strictly decoded pending cursor before capture`() = runBlocking {
        val trace = mutableListOf<String>()
        val outbox = RecordingOutbox(
            initial = listOf(durable(41, 1_500), durable(42, 1_700)),
            trace = trace,
        )
        val state = RecordingState(
            initial = CallLogSyncState(3u, CallLogCursor(1_000, 1), 7u),
            trace = trace,
        )
        val coordinator = coordinator(
            provider = provider(flow { trace += "capture" }),
            outbox = outbox,
            state = state,
            dispatcher = RecordingDispatcher(trace = trace, result = CapabilityDispatchResult(2, 0, null)),
        )

        val result = coordinator.runOnce(subscription())

        assertEquals(
            listOf("reconcile", "cursor:3:1700:42", "capture", "dispatch"),
            trace,
        )
        assertEquals(CallLogCursor(1_700, 42), state.snapshot()!!.cursor)
        assertEquals(CallLogSyncRunResult(0, 0, 2, 0, null), result)
    }

    @Test
    fun `all reconciliation identity capability epoch revision and cursor violations fail closed`() = runBlocking {
        val valid = durable(42, 1_700)
        val cases = listOf(
            "malformed" to copy(valid, wire = byteArrayOf(0x01)),
            "identity" to copy(valid, eventId = "call:3:99"),
            "record" to copy(valid, recordId = "call:99"),
            "capability" to copy(valid, capability = "sms"),
            "epoch" to durable(42, 1_700, epoch = 4u),
            "revision" to durable(42, 1_700, revision = 8u),
            "cursor" to copy(valid, eventId = "call:3:41"),
        )

        cases.forEach { (label, pending) ->
            var captured = false
            val dispatcher = RecordingDispatcher()
            val result = coordinator(
                provider = provider(flow { captured = true }),
                outbox = RecordingOutbox(initial = listOf(pending)),
                dispatcher = dispatcher,
            ).runOnce(subscription())

            assertEquals(label, CallLogSyncFailure.OUTBOX_CORRUPTED, result.failure)
            assertEquals(label, 1, result.retained)
            assertFalse(label, captured)
            assertEquals(label, 0, dispatcher.calls)
        }
    }

    @Test
    fun `pending policy gate cancellation stays cancelled and skips capture and dispatch`() = runBlocking {
        var captured = false
        val dispatcher = RecordingDispatcher()
        val result = coordinator(
            provider = provider(flow { captured = true }),
            outbox = RecordingOutbox(initial = listOf(durable(42, 1_700))),
            dispatcher = dispatcher,
            gate = CapabilityEventEgressGate { throw CancellationException("pending gate private") },
        ).runOnce(subscription())

        assertEquals(CallLogSyncRunResult(0, 0, 0, 1, CallLogSyncFailure.CANCELLED), result)
        assertFalse(captured)
        assertEquals(0, dispatcher.calls)
    }

    @Test
    fun `pre-enqueue policy gate cancellation stays cancelled without new egress`() = runBlocking {
        val outbox = RecordingOutbox()
        val dispatcher = RecordingDispatcher()
        val result = coordinator(
            provider = provider(flowOf(event(42, 1_700))),
            outbox = outbox,
            dispatcher = dispatcher,
            gate = CapabilityEventEgressGate { throw CancellationException("new gate private") },
        ).runOnce(subscription())

        assertEquals(CallLogSyncRunResult(1, 0, 0, 0, CallLogSyncFailure.CANCELLED), result)
        assertTrue(outbox.entries().isEmpty())
        assertEquals(0, dispatcher.calls)
    }

    @Test
    fun `query before enqueue failure does not advance state and outbox full remains distinct`() = runBlocking {
        listOf(
            IllegalStateException("disk secret") to CallLogSyncFailure.OUTBOX_CORRUPTED,
            CapabilityOutboxFull("capacity secret") to CallLogSyncFailure.OUTBOX_FULL,
        ).forEach { (enqueueFailure, expected) ->
            val state = RecordingState()
            val result = coordinator(
                provider = provider(flowOf(event(42, 1_700))),
                outbox = RecordingOutbox(enqueueFailure = enqueueFailure),
                state = state,
            ).runOnce(subscription())

            assertEquals(expected, result.failure)
            assertEquals(1, result.captured)
            assertEquals(0, result.enqueued)
            assertEquals(null, state.snapshot()!!.cursor)
        }
    }

    @Test
    fun `enqueue before false or throwing state blocks that event and halts later capture`() = runBlocking {
        listOf(false, true).forEach { throws ->
            val outbox = RecordingOutbox()
            val state = RecordingState(advanceReturns = false, advanceThrows = throws)
            val dispatcher = RecordingDispatcher(result = CapabilityDispatchResult(0, 1, null))
            val result = coordinator(
                provider = provider(flowOf(event(42, 1_700), event(43, 1_800))),
                outbox = outbox,
                state = state,
                dispatcher = dispatcher,
            ).runOnce(subscription())

            assertEquals(CallLogSyncFailure.CURSOR_NOT_DURABLE, result.failure)
            assertEquals(1, result.captured)
            assertEquals(1, result.enqueued)
            assertEquals(setOf("call:3:42"), dispatcher.blocked.single())
            assertEquals(listOf("call:3:42"), outbox.entries().map { it.eventId })
        }
    }

    @Test
    fun `state before send and send before ack retain while ack delete completes`() = runBlocking {
        val cases = listOf(
            CapabilityDispatchResult(0, 1, CapabilityDispatchFailure.TRANSPORT_FAILURE) to CallLogSyncFailure.TRANSPORT_FAILURE,
            CapabilityDispatchResult(0, 1, CapabilityDispatchFailure.ACK_REJECTED) to CallLogSyncFailure.ACK_REJECTED,
            CapabilityDispatchResult(1, 0, null) to null,
        )

        cases.forEach { (dispatch, expected) ->
            val state = RecordingState()
            val result = coordinator(
                provider = provider(flowOf(event(42, 1_700))),
                outbox = RecordingOutbox(),
                state = state,
                dispatcher = RecordingDispatcher(result = dispatch),
            ).runOnce(subscription())

            assertEquals(CallLogCursor(1_700, 42), state.snapshot()!!.cursor)
            assertEquals(expected, result.failure)
            assertEquals(dispatch.acknowledged, result.acknowledged)
            assertEquals(dispatch.retained, result.retained)
        }
    }

    @Test
    fun `revoking policy immediately before enqueue retains prior state and skips acceptance`() = runBlocking {
        val outbox = RecordingOutbox()
        val state = RecordingState()
        val dispatcher = RecordingDispatcher(result = CapabilityDispatchResult(0, 0, CapabilityDispatchFailure.POLICY_REVOKED))
        val result = coordinator(
            provider = provider(flowOf(event(42, 1_700))),
            outbox = outbox,
            state = state,
            dispatcher = dispatcher,
            gate = CapabilityEventEgressGate { false },
        ).runOnce(subscription())

        assertEquals(CallLogSyncFailure.POLICY_REVOKED, result.failure)
        assertTrue(outbox.entries().isEmpty())
        assertEquals(null, state.snapshot()!!.cursor)
        assertEquals(1, dispatcher.calls)
    }

    @Test
    fun `generic dispatcher failures map one for one and outbox failure becomes corrupted`() = runBlocking {
        val cases = mapOf(
            CapabilityDispatchFailure.PAIRING_UNAVAILABLE to CallLogSyncFailure.PAIRING_UNAVAILABLE,
            CapabilityDispatchFailure.POLICY_REVOKED to CallLogSyncFailure.POLICY_REVOKED,
            CapabilityDispatchFailure.UNSUPPORTED_CAPABILITY to CallLogSyncFailure.UNSUPPORTED_CAPABILITY,
            CapabilityDispatchFailure.TRANSPORT_FAILURE to CallLogSyncFailure.TRANSPORT_FAILURE,
            CapabilityDispatchFailure.ACK_REJECTED to CallLogSyncFailure.ACK_REJECTED,
            CapabilityDispatchFailure.OUTBOX_FAILURE to CallLogSyncFailure.OUTBOX_CORRUPTED,
            CapabilityDispatchFailure.CANCELLED to CallLogSyncFailure.CANCELLED,
        )

        cases.forEach { (generic, expected) ->
            val result = coordinator(
                provider = provider(emptyFlow()),
                dispatcher = RecordingDispatcher(result = CapabilityDispatchResult(0, 1, generic)),
            ).runOnce(subscription())
            assertEquals(generic.name, expected, result.failure)
        }
    }

    @Test
    fun `dispatcher cancellation and corruption outrank an independent capture failure`() = runBlocking {
        listOf(
            CapabilityDispatchFailure.CANCELLED to CallLogSyncFailure.CANCELLED,
            CapabilityDispatchFailure.OUTBOX_FAILURE to CallLogSyncFailure.OUTBOX_CORRUPTED,
        ).forEach { (dispatchFailure, expected) ->
            val result = coordinator(
                provider = provider(flow { throw CallLogProviderException(CallLogProviderFailure.QUERY_FAILED) }),
                dispatcher = RecordingDispatcher(result = CapabilityDispatchResult(2, 3, dispatchFailure)),
            ).runOnce(subscription())

            assertEquals(dispatchFailure.name, expected, result.failure)
            assertEquals(dispatchFailure.name, 2, result.acknowledged)
            assertEquals(dispatchFailure.name, 3, result.retained)
        }
    }

    @Test
    fun `repair failure keeps durable prefix dispatchable while dispatcher cancellation or corruption wins`() = runBlocking {
        listOf(
            CapabilityDispatchFailure.CANCELLED to CallLogSyncFailure.CANCELLED,
            CapabilityDispatchFailure.OUTBOX_FAILURE to CallLogSyncFailure.OUTBOX_CORRUPTED,
        ).forEach { (dispatchFailure, expected) ->
            val dispatcher = RecordingDispatcher(result = CapabilityDispatchResult(1, 1, dispatchFailure))
            val result = coordinator(
                outbox = RecordingOutbox(initial = listOf(durable(41, 1_400), durable(42, 1_700))),
                state = RecordingState(
                    initial = CallLogSyncState(3u, CallLogCursor(1_500, 1), 7u),
                    advanceReturns = false,
                ),
                dispatcher = dispatcher,
            ).runOnce(subscription())

            assertEquals(dispatchFailure.name, expected, result.failure)
            assertEquals(dispatchFailure.name, 1, result.acknowledged)
            assertEquals(dispatchFailure.name, 1, result.retained)
            assertEquals(dispatchFailure.name, setOf("call:3:42"), dispatcher.blocked.single())
        }
    }

    @Test
    fun `provider failures map exhaustively and cancellation is closed`() = runBlocking {
        val cases = mapOf(
            CallLogProviderFailure.SOURCE_DISABLED to CallLogSyncFailure.SOURCE_DISABLED,
            CallLogProviderFailure.PERMISSION_REQUIRED to CallLogSyncFailure.PERMISSION_REQUIRED,
            CallLogProviderFailure.PLATFORM_UNSUPPORTED to CallLogSyncFailure.PLATFORM_UNSUPPORTED,
            CallLogProviderFailure.INVALID_ROW to CallLogSyncFailure.INVALID_PROVIDER_ROW,
            CallLogProviderFailure.QUERY_FAILED to CallLogSyncFailure.CAPTURE_FAILURE,
            CallLogProviderFailure.POLICY_STALE to CallLogSyncFailure.POLICY_REVOKED,
        )
        cases.forEach { (providerFailure, expected) ->
            val result = coordinator(
                provider = provider(flow { throw CallLogProviderException(providerFailure) }),
            ).runOnce(subscription())
            assertEquals(providerFailure.name, expected, result.failure)
        }

        val cancelled = coordinator(
            provider = provider(flow { throw CancellationException("private cancellation") }),
        ).runOnce(subscription())
        assertEquals(CallLogSyncFailure.CANCELLED, cancelled.failure)
    }

    @Test
    fun `corrupt state and recovery failures are classified without capture or dispatch`() = runBlocking {
        val stateFailure = coordinator(
            provider = provider(flow { error("must not capture") }),
            state = RecordingState(snapshotThrows = true),
        ).runOnce(subscription())
        assertEquals(CallLogSyncFailure.SYNC_STATE_CORRUPTED, stateFailure.failure)

        val recoveryDispatcher = RecordingDispatcher()
        val recoveryFailure = coordinator(
            provider = provider(flow { error("must not capture") }),
            outbox = RecordingOutbox(recoverFailure = IllegalStateException("ciphertext secret")),
            dispatcher = recoveryDispatcher,
        ).runOnce(subscription())
        assertEquals(CallLogSyncFailure.OUTBOX_CORRUPTED, recoveryFailure.failure)
        assertEquals(0, recoveryDispatcher.calls)
    }

    @Test
    fun `real encrypted state key cancellation reaches coordinator as cancelled`() = runBlocking {
        val persistence = InMemoryOutboxPersistence().also { it.write(byteArrayOf(1)) }
        val cancellingKeys = object : AesGcmKeyProvider {
            override fun getOrCreate(): SecretKey = throw CancellationException("key private")
            override fun delete() = Unit
        }
        val dispatcher = RecordingDispatcher()

        val result = coordinator(
            state = EncryptedCallLogSyncStateStore(persistence, cancellingKeys),
            dispatcher = dispatcher,
        ).runOnce(subscription())

        assertEquals(CallLogSyncFailure.CANCELLED, result.failure)
        assertEquals(0, dispatcher.calls)
    }

    @Test
    fun `new encoded wire is strictly decoded and bound before durable acceptance`() = runBlocking {
        val expected = DecodedCallLogRecord("call:42", 3u, CallLogCursor(1_700, 42), 7u, 7u)
        val cases = listOf(
            "malformed" to codecOverride(encodedWire = byteArrayOf(1)),
            "epoch" to codecOverride(decoded = expected.copy(sourceEpoch = 4u)),
            "cursor" to codecOverride(decoded = expected.copy(recordId = "call:43", cursor = CallLogCursor(1_700, 43))),
            "revision" to codecOverride(decoded = expected.copy(captureRevision = 8u, policyRevision = 8u)),
        )

        cases.forEach { (label, injectedCodec) ->
            val outbox = RecordingOutbox()
            val state = RecordingState()
            val dispatcher = RecordingDispatcher(result = CapabilityDispatchResult(0, 0, null))
            val result = coordinator(
                provider = provider(flowOf(event(42, 1_700))),
                outbox = outbox,
                state = state,
                dispatcher = dispatcher,
                eventCodec = injectedCodec,
            ).runOnce(subscription())

            assertEquals(label, CallLogSyncFailure.CAPTURE_FAILURE, result.failure)
            assertEquals(label, 1, result.captured)
            assertEquals(label, 0, result.enqueued)
            assertTrue(label, outbox.entries().isEmpty())
            assertEquals(label, null, state.snapshot()!!.cursor)
        }
    }

    @Test
    fun `new wire verification cancellation stays cancelled before enqueue or dispatch`() = runBlocking {
        val outbox = RecordingOutbox()
        val dispatcher = RecordingDispatcher()
        val cancellingCodec = object : CallLogEventCodec {
            override fun encode(eventId: String, record: CallsPayload, sourceEpoch: ULong, policyRevision: ULong): ByteArray =
                codec.encode(eventId, record, sourceEpoch, policyRevision)

            override fun decode(wire: ByteArray): DecodedCallLogRecord =
                throw CancellationException("codec private")
        }

        val result = coordinator(
            provider = provider(flowOf(event(42, 1_700))),
            outbox = outbox,
            dispatcher = dispatcher,
            eventCodec = cancellingCodec,
        ).runOnce(subscription())

        assertEquals(CallLogSyncFailure.CANCELLED, result.failure)
        assertTrue(outbox.entries().isEmpty())
        assertEquals(0, dispatcher.calls)
    }

    @Test
    fun `failed first event blocks it and every later event from dispatch`() = runBlocking {
        val dispatcher = RecordingDispatcher(result = CapabilityDispatchResult(0, 1, null))
        var laterEventRequested = false
        val result = coordinator(
            provider = provider(flow {
                emit(event(42, 1_700))
                laterEventRequested = true
                emit(event(43, 1_800))
            }),
            state = RecordingState(advanceReturns = false),
            dispatcher = dispatcher,
        ).runOnce(subscription())

        assertEquals(CallLogSyncRunResult(1, 1, 0, 1, CallLogSyncFailure.CURSOR_NOT_DURABLE), result)
        assertEquals(setOf("call:3:42"), dispatcher.blocked.single())
        assertFalse(laterEventRequested)
    }

    @Test
    fun `every terminal result audits allowlisted counts and sink failure cannot change state`() = runBlocking {
        val audit = mutableListOf<CallLogAuditEvent>()
        val state = RecordingState()
        val coordinator = coordinator(
            provider = provider(flowOf(event(42, 1_700))),
            state = state,
            auditSink = CallLogAuditSink { event ->
                audit += event
                throw IllegalStateException("audit sink private message")
            },
            clock = sequenceOf(0L, 100L).iterator()::next,
        )

        val result = coordinator.runOnce(subscription())

        assertEquals(null, result.failure)
        assertEquals(CallLogCursor(1_700, 42), state.snapshot()!!.cursor)
        assertEquals(1, audit.size)
        assertEquals(CallLogAuditResultCode.COMPLETE, audit.single().resultCode)
        assertEquals(1, audit.single().readCount)
        assertEquals(1, audit.single().acceptedCount)
        assertEquals(1, audit.single().acknowledgedCount)
        assertFalse(audit.single().toString().contains("42"))
    }

    @Test
    fun `coordinator audit errors remain fatal`() {
        val coordinator = coordinator(
            auditSink = CallLogAuditSink { throw AssertionError("fatal audit") },
        )

        assertThrows(AssertionError::class.java) {
            runBlocking { coordinator.runOnce(subscription()) }
        }
    }

    @Test
    fun `quiescence and run share one mutex in both directions`() = runBlocking {
        val captureEntered = CompletableDeferred<Unit>()
        val releaseCapture = CompletableDeferred<Unit>()
        val runCoordinator = coordinator(
            provider = provider(flow {
                captureEntered.complete(Unit)
                releaseCapture.await()
            }),
        )
        val running = async { runCoordinator.runOnce(subscription()) }
        captureEntered.await()
        var quiesced = false
        val waitingQuiescence = async { runCoordinator.withSyncQuiesced { quiesced = true } }
        yield()
        assertFalse(quiesced)
        releaseCapture.complete(Unit)
        running.await()
        waitingQuiescence.await()
        assertTrue(quiesced)

        val quiescenceEntered = CompletableDeferred<Unit>()
        val releaseQuiescence = CompletableDeferred<Unit>()
        var captureStarted = false
        val quiescedCoordinator = coordinator(provider = provider(flow { captureStarted = true }))
        val holding = async {
            quiescedCoordinator.withSyncQuiesced {
                quiescenceEntered.complete(Unit)
                releaseQuiescence.await()
            }
        }
        quiescenceEntered.await()
        val waitingRun = async { quiescedCoordinator.runOnce(subscription()) }
        yield()
        assertFalse(captureStarted)
        releaseQuiescence.complete(Unit)
        holding.await()
        waitingRun.await()
        assertTrue(captureStarted)
    }

    private fun coordinator(
        provider: CallsCapabilityProvider = provider(emptyFlow()),
        outbox: RecordingOutbox = RecordingOutbox(),
        state: CallLogSyncStateStore = RecordingState(),
        dispatcher: RecordingDispatcher = RecordingDispatcher(),
        gate: CapabilityEventEgressGate = CapabilityEventEgressGate { true },
        eventCodec: CallLogEventCodec = codec,
        auditSink: CallLogAuditSink = CallLogAuditSink {},
        clock: () -> Long = { 0L },
    ) = CallLogAutoSyncCoordinator(
        provider = provider,
        outbox = outbox,
        stateStore = state,
        codec = eventCodec,
        dispatcher = dispatcher,
        currentPolicyGate = gate,
        auditSink = auditSink,
        elapsedRealtimeMs = clock,
    )

    private fun provider(events: Flow<CapabilityEvent<CallsPayload>>) = object : CallsCapabilityProvider {
        override suspend fun read(scope: com.openandroidintelligence.capability.AuthorizedReadScope): CapabilityReadResult<CallsPayload> =
            error("read is not used")

        override fun observeAutoSend(scope: AuthorizedAutoSendScope): Flow<CapabilityEvent<CallsPayload>> = events
    }

    private fun event(id: Long, startedAt: Long, revision: ULong = 7u): CapabilityEvent<CallsPayload> = CapabilityEvent(
        capability = MobileDataCapability.CALLS,
        eventId = "call:3:$id",
        record = payload(id, startedAt),
        policyRevision = revision,
    )

    private fun payload(id: Long, startedAt: Long) = CallsPayload(
        metadata = CallsMetadata(
            recordId = "call:$id",
            observedAtEpochMs = startedAt,
            direction = CallDirection.INCOMING,
            startedAtEpochMs = startedAt,
            endedAtEpochMs = startedAt + 2_000,
            durationSeconds = 2,
            numberPresentation = CallNumberPresentation.RESTRICTED,
        ),
        counterpartyNumber = NormalizedContent.Withheld,
    )

    private fun durable(
        id: Long,
        startedAt: Long,
        epoch: ULong = 3u,
        revision: ULong = 7u,
    ) = CapabilityDurableEvent(
        eventId = "call:$epoch:$id",
        capability = "calls.metadata",
        recordId = "call:$id",
        policyRevision = revision,
        eventWire = codec.encode("call:$epoch:$id", payload(id, startedAt), epoch, revision),
    )

    private fun copy(
        event: CapabilityDurableEvent,
        eventId: String = event.eventId,
        capability: String = event.capability,
        recordId: String = event.recordId,
        policyRevision: ULong = event.policyRevision,
        wire: ByteArray = event.eventWire,
    ) = CapabilityDurableEvent(eventId, capability, recordId, policyRevision, wire)

    private fun codecOverride(
        encodedWire: ByteArray? = null,
        decoded: DecodedCallLogRecord? = null,
    ) = object : CallLogEventCodec {
        override fun encode(eventId: String, record: CallsPayload, sourceEpoch: ULong, policyRevision: ULong): ByteArray =
            encodedWire?.copyOf() ?: codec.encode(eventId, record, sourceEpoch, policyRevision)

        override fun decode(wire: ByteArray): DecodedCallLogRecord =
            decoded ?: codec.decode(wire)
    }

    private fun subscription(): AuthorizedAutoSendSubscription {
        val policy = CallLogTestFixtures.policy()
        val filter = policy.filter()
        val request = com.openandroidintelligence.capability.AgentDataRequest(
            "call-sync", MobileDataCapability.CALLS, com.openandroidintelligence.capability.DataSyncMode.AUTO_SEND, filter, policy.policyRevision,
        )
        val grant = com.openandroidintelligence.capability.CapabilityGrant(
            MobileDataCapability.CALLS, filter, true, true, true, policy.policyRevision,
        )
        val allowed = com.openandroidintelligence.capability.DefaultAgentRequestAuthorizer().authorize(
            request, grant, com.openandroidintelligence.capability.CapabilityAvailability.READY,
        ) as com.openandroidintelligence.capability.AgentRequestAuthorization.Allowed
        return allowed.access as AuthorizedAutoSendSubscription
    }

    private class RecordingOutbox(
        initial: List<CapabilityDurableEvent> = emptyList(),
        private val trace: MutableList<String> = mutableListOf(),
        private val enqueueFailure: Throwable? = null,
        private val recoverFailure: Throwable? = null,
    ) : CapabilityOutbox {
        private val stored = LinkedHashMap(initial.associateBy { it.eventId })

        override suspend fun enqueueAccepted(event: CapabilityDurableEvent): CapabilityDurableEvent {
            enqueueFailure?.let { throw it }
            trace += "enqueue:${event.eventId}"
            return stored.getOrPut(event.eventId) { event }
        }

        override suspend fun acknowledge(eventId: String, eventAckWire: ByteArray) {
            stored.remove(eventId)
        }

        override suspend fun recoverUnacknowledged(): List<CapabilityDurableEvent> {
            recoverFailure?.let { throw it }
            trace += "reconcile"
            return stored.values.toList()
        }

        override suspend fun clear() = stored.clear()

        fun entries(): List<CapabilityDurableEvent> = stored.values.toList()
    }

    private class RecordingState(
        initial: CallLogSyncState = CallLogSyncState(3u, null, 7u),
        private val trace: MutableList<String> = mutableListOf(),
        private val advanceReturns: Boolean = true,
        private val advanceThrows: Boolean = false,
        private val snapshotThrows: Boolean = false,
    ) : CallLogSyncStateStore {
        private var current = initial

        override fun snapshot(): CallLogSyncState? {
            if (snapshotThrows) throw CallLogSyncStateCorrupted()
            return current
        }

        override fun initialize(state: CallLogSyncState) {
            current = state
        }

        override fun advance(expectedSourceEpoch: ULong, cursor: CallLogCursor, policyRevision: ULong): Boolean {
            if (advanceThrows) throw IllegalStateException("state secret")
            if (!advanceReturns) return false
            current = current.copy(cursor = cursor)
            trace += "cursor:$expectedSourceEpoch:${cursor.startedAtEpochMs}:${cursor.providerId}"
            return true
        }

        override fun resetWithRotatedKey(state: CallLogSyncState) {
            current = state
        }
    }

    private class RecordingDispatcher(
        private val trace: MutableList<String> = mutableListOf(),
        private val result: CapabilityDispatchResult = CapabilityDispatchResult(1, 0, null),
    ) : CapabilityPendingDispatcher {
        var calls = 0
        val blocked = mutableListOf<Set<String>>()

        override suspend fun dispatchPending(blockedEventIds: Set<String>): CapabilityDispatchResult {
            calls += 1
            blocked += blockedEventIds
            trace += "dispatch"
            return result
        }
    }
}
