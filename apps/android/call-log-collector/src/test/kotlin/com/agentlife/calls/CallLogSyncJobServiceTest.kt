package com.agentlife.calls

import com.agentlife.capability.AuthorizedAutoSendSubscription
import com.agentlife.capability.CallCounterpartyAccess
import com.agentlife.capability.CallDirection
import com.agentlife.capability.CallHistoryPolicy
import com.agentlife.capability.CallLogSyncInterval
import com.agentlife.capability.CapabilityAvailability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CallLogSyncJobServiceTest {
    @Test
    fun `each scheduled invocation re reads authority availability and state`() = runBlocking {
        val authority = PersistentCallLogSettingsAuthority(InMemoryCallLogSettingsPersistence())
        var availability = CapabilityAvailability.DISABLED
        var availabilityReads = 0
        val state = RecordingStateStore(null)
        val runner = RecordingRunner()
        val runtime = CallLogRuntime(
            authority,
            CallLogAvailabilitySource { availabilityReads += 1; availability },
            state,
            runner,
        )

        assertEquals(CallLogScheduledRunResult(ran = false, retryPending = false), runtime.runScheduled())
        enable(authority, policy(revision = 7u), authorizationRevision = 1u)
        availability = CapabilityAvailability.READY
        state.current = CallLogSyncState(sourceEpoch = 1u, cursor = null, policyRevision = 7u)
        assertEquals(CallLogScheduledRunResult(ran = true, retryPending = false), runtime.runScheduled())

        assertEquals(2, availabilityReads)
        assertEquals(2, state.snapshotCalls)
        assertEquals(1, runner.calls)
    }

    @Test
    fun `disabled revoking corrupt and exhausted authorities never run`() = runBlocking {
        val disabled = PersistentCallLogSettingsAuthority(InMemoryCallLogSettingsPersistence())
        val revoking = PersistentCallLogSettingsAuthority(InMemoryCallLogSettingsPersistence()).also {
            it.beginRevocation(1u, 7u, policy(revision = 7u), authorizationRevision = 1u)
        }
        val corrupted = PersistentCallLogSettingsAuthority(InMemoryCallLogSettingsPersistence(byteArrayOf(1, 2, 3)))
        val exhausted = PersistentCallLogSettingsAuthority(InMemoryCallLogSettingsPersistence()).also {
            it.beginRevocation(1u, 7u, policy(revision = 7u), authorizationRevision = 1u, epochExhausted = true)
            it.commitRevocationTarget()
        }

        for (authority in listOf(disabled, revoking, corrupted, exhausted)) {
            val runner = RecordingRunner()
            val result = runtime(authority, runner = runner).runScheduled()
            assertEquals(CallLogScheduledRunResult(ran = false, retryPending = false), result)
            assertEquals(0, runner.calls)
        }
    }

    @Test
    fun `manual disabled auto send and non ready availability never run`() = runBlocking {
        val policies = listOf(
            policy(revision = 7u, interval = CallLogSyncInterval.MANUAL),
            policy(revision = 7u, autoSendEnabled = false),
        )
        for (currentPolicy in policies) {
            val runner = RecordingRunner()
            val result = runtime(enabledAuthority(currentPolicy), runner = runner).runScheduled()
            assertFalse(result.ran)
            assertFalse(result.retryPending)
            assertEquals(0, runner.calls)
        }
        for (currentAvailability in listOf(
            CapabilityAvailability.PERMISSION_REQUIRED,
            CapabilityAvailability.PLATFORM_UNSUPPORTED,
            CapabilityAvailability.DISABLED,
        )) {
            val runner = RecordingRunner()
            val result = runtime(
                enabledAuthority(policy(revision = 7u)),
                availability = currentAvailability,
                runner = runner,
            ).runScheduled()
            assertFalse(result.ran)
            assertFalse(result.retryPending)
            assertEquals(0, runner.calls)
        }
    }

    @Test
    fun `missing corrupt or stale sync state never runs`() = runBlocking {
        val authority = enabledAuthority(policy(revision = 7u))
        val states = listOf<CallLogSyncState?>(
            null,
            CallLogSyncState(sourceEpoch = 1u, cursor = null, policyRevision = 6u),
        )
        for (currentState in states) {
            val runner = RecordingRunner()
            val result = CallLogRuntime(
                authority,
                CallLogAvailabilitySource { CapabilityAvailability.READY },
                RecordingStateStore(currentState),
                runner,
            ).runScheduled()
            assertEquals(CallLogScheduledRunResult(ran = false, retryPending = false), result)
            assertEquals(0, runner.calls)
        }

        val runner = RecordingRunner()
        val corruptState = object : CallLogSyncStateStore by RecordingStateStore(null) {
            override fun snapshot(): CallLogSyncState? = throw CallLogSyncStateCorrupted()
        }
        val result = CallLogRuntime(
            authority,
            CallLogAvailabilitySource { CapabilityAvailability.READY },
            corruptState,
            runner,
        ).runScheduled()
        assertEquals(CallLogScheduledRunResult(ran = false, retryPending = false), result)
        assertEquals(0, runner.calls)
    }

    @Test
    fun `local auto send runs even when agent requests are disabled`() = runBlocking {
        val runner = RecordingRunner()
        val authority = enabledAuthority(policy(revision = 9u, agentMayRequest = false))

        val result = runtime(authority, policyRevision = 9u, runner = runner).runScheduled()

        assertEquals(CallLogScheduledRunResult(ran = true, retryPending = false), result)
        assertEquals(1, runner.calls)
        assertEquals(9uL, runner.lastSubscription?.request?.policyRevision)
    }

    @Test
    fun `missing runner requests retry only for an otherwise healthy run`() = runBlocking {
        val result = runtime(enabledAuthority(policy(revision = 7u)), runner = null).runScheduled()

        assertEquals(CallLogScheduledRunResult(ran = false, retryPending = true), result)
    }

    @Test
    fun `retained events and terminal failures request retry`() = runBlocking {
        for (runResult in listOf(
            CallLogSyncRunResult(0, 0, 0, retained = 1, failure = null),
            CallLogSyncRunResult(0, 0, 0, retained = 0, failure = CallLogSyncFailure.TRANSPORT_FAILURE),
        )) {
            val result = runtime(
                enabledAuthority(policy(revision = 7u)),
                runner = RecordingRunner(result = runResult),
            ).runScheduled()
            assertTrue(result.ran)
            assertTrue(result.retryPending)
        }
    }

    @Test
    fun `runner cancellation propagates without becoming retry`() {
        val runtime = runtime(
            enabledAuthority(policy(revision = 7u)),
            runner = RecordingRunner(failure = CancellationException("cancelled")),
        )

        assertThrows(CancellationException::class.java) {
            runBlocking { runtime.runScheduled() }
        }
    }

    @Test
    fun `retry after stop re reads all sources and requires a healthy runner`() {
        val authority = enabledAuthority(policy(revision = 7u))
        var availability = CapabilityAvailability.READY
        var availabilityReads = 0
        val state = RecordingStateStore(CallLogSyncState(1u, null, 7u))
        val runtime = CallLogRuntime(
            authority,
            CallLogAvailabilitySource { availabilityReads += 1; availability },
            state,
            RecordingRunner(),
        )

        assertTrue(runtime.retryAfterStop())
        availability = CapabilityAvailability.PERMISSION_REQUIRED
        assertFalse(runtime.retryAfterStop())
        assertEquals(2, availabilityReads)
        assertEquals(2, state.snapshotCalls)

        var missingRunnerAvailabilityReads = 0
        val missingRunnerState = RecordingStateStore(CallLogSyncState(1u, null, 7u))
        val missingRunnerRuntime = CallLogRuntime(
            enabledAuthority(policy(revision = 7u)),
            CallLogAvailabilitySource { missingRunnerAvailabilityReads += 1; CapabilityAvailability.READY },
            missingRunnerState,
            runner = null,
        )
        assertFalse(missingRunnerRuntime.retryAfterStop())
        assertEquals(1, missingRunnerAvailabilityReads)
        assertEquals(1, missingRunnerState.snapshotCalls)
    }

    @Test
    fun `job execution finishes once even if run is invoked again`() = runBlocking {
        val completed = mutableListOf<Boolean>()
        val execution = CallLogJobExecution(finish = completed::add)

        execution.run { false }
        execution.run { true }

        assertEquals(listOf(false), completed)
    }

    @Test
    fun `stopping execution suppresses a late finish`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val completed = mutableListOf<Boolean>()
        val execution = CallLogJobExecution(finish = completed::add)
        val job = launch {
            execution.run {
                started.complete(Unit)
                awaitCancellation()
            }
        }

        started.await()
        execution.stop()
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertTrue(completed.isEmpty())
    }

    private fun runtime(
        authority: PersistentCallLogSettingsAuthority,
        availability: CapabilityAvailability = CapabilityAvailability.READY,
        policyRevision: ULong = 7u,
        runner: CallLogAutoSyncRunner?,
    ) = CallLogRuntime(
        authority,
        CallLogAvailabilitySource { availability },
        RecordingStateStore(CallLogSyncState(sourceEpoch = 1u, cursor = null, policyRevision = policyRevision)),
        runner,
    )

    private fun enabledAuthority(currentPolicy: CallLogLocalPolicy): PersistentCallLogSettingsAuthority =
        PersistentCallLogSettingsAuthority(InMemoryCallLogSettingsPersistence()).also {
            enable(it, currentPolicy, authorizationRevision = 1u)
        }

    private fun enable(
        authority: PersistentCallLogSettingsAuthority,
        currentPolicy: CallLogLocalPolicy,
        authorizationRevision: ULong,
    ) {
        authority.beginRevocation(1u, currentPolicy.policyRevision, currentPolicy, authorizationRevision)
        authority.commitRevocationTarget()
    }

    private fun policy(
        revision: ULong,
        interval: CallLogSyncInterval = CallLogSyncInterval.MINUTES_30,
        autoSendEnabled: Boolean = true,
        agentMayRequest: Boolean = true,
    ) = CallLogLocalPolicy(
        historyPolicy = CallHistoryPolicy(fromEpochMs = 100L, maxRecords = 10),
        directions = CallDirection.entries.toSet(),
        counterpartyAccess = CallCounterpartyAccess.WITHHELD,
        syncInterval = interval,
        onDemandEnabled = true,
        autoSendEnabled = autoSendEnabled,
        agentMayRequest = agentMayRequest,
        policyRevision = revision,
    )

    private class RecordingRunner(
        private val result: CallLogSyncRunResult = CallLogSyncRunResult(0, 0, 0, 0, null),
        private val failure: CancellationException? = null,
    ) : CallLogAutoSyncRunner {
        var calls = 0
        var lastSubscription: AuthorizedAutoSendSubscription? = null

        override suspend fun runOnce(subscription: AuthorizedAutoSendSubscription): CallLogSyncRunResult {
            calls += 1
            lastSubscription = subscription
            failure?.let { throw it }
            return result
        }
    }

    private class RecordingStateStore(var current: CallLogSyncState?) : CallLogSyncStateStore {
        var snapshotCalls = 0

        override fun snapshot(): CallLogSyncState? = current.also { snapshotCalls += 1 }
        override fun initialize(state: CallLogSyncState) { current = state }
        override fun advance(expectedSourceEpoch: ULong, cursor: CallLogCursor, policyRevision: ULong): Boolean = false
        override fun resetWithRotatedKey(state: CallLogSyncState) { current = state }
    }
}
