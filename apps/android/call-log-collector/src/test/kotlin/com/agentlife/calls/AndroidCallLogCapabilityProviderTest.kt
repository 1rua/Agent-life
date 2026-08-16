package com.agentlife.calls

import com.agentlife.capability.CallCounterpartyAccess
import com.agentlife.capability.CallDirection
import com.agentlife.capability.CapabilityAvailability
import com.agentlife.capability.CapabilityReadStatus
import com.agentlife.capability.NormalizedContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidCallLogCapabilityProviderTest {
    @Test
    fun `on demand maps descending rows without a cursor and keeps unavailable numbers withheld`() = runBlocking {
        val policy = CallLogTestFixtures.policy(counterpartyAccess = CallCounterpartyAccess.NUMBER)
        var query: CallLogQuery? = null
        val provider = provider(policy, reader = CallLogTestFixtures.reader { request ->
            query = request
            listOf(
                CallLogTestFixtures.row(9, 900),
                CallLogTestFixtures.row(8, 800, presentation = com.agentlife.capability.CallNumberPresentation.RESTRICTED),
            )
        })

        val result = provider.read(CallLogTestFixtures.readScope(policy))

        assertEquals(CapabilityReadStatus.COMPLETE, result.status)
        assertEquals(listOf("call:9", "call:8"), result.records.map { it.metadata.recordId })
        assertNull(query!!.cursor)
        assertEquals(policy.directions, query!!.directions)
        assertEquals("+8613800000000", (result.records.first().counterpartyNumber as NormalizedContent.Released).value)
        assertEquals(NormalizedContent.Withheld, result.records.last().counterpartyNumber)
    }

    @Test
    fun `auto send sorts by time and id and uses durable state epoch in event identity`() = runBlocking {
        val policy = CallLogTestFixtures.policy()
        val sync = RecordingSyncState(CallLogSyncState(3u, CallLogCursor(100, 1), policy.policyRevision))
        var query: CallLogQuery? = null
        val provider = provider(policy, sync = sync, reader = CallLogTestFixtures.reader { request ->
            query = request
            listOf(CallLogTestFixtures.row(42, 200), CallLogTestFixtures.row(41, 200), CallLogTestFixtures.row(7, 150))
        })

        val events = provider.observeAutoSend(CallLogTestFixtures.autoScope(policy)).toList()

        assertEquals(CallLogCursor(100, 1), query!!.cursor)
        assertEquals(listOf("call:3:7", "call:3:41", "call:3:42"), events.map { it.eventId })
        assertEquals(listOf("call:7", "call:41", "call:42"), events.map { it.record.metadata.recordId })
        assertTrue(events.all { it.capability.name == "CALLS" && it.policyRevision == policy.policyRevision })
        assertEquals(0, sync.mutations)
    }

    @Test
    fun `disabled unsupported and permission required return stable failure without querying`() = runBlocking {
        listOf(
            CapabilityAvailability.DISABLED to "CALL_LOG_DISABLED",
            CapabilityAvailability.PLATFORM_UNSUPPORTED to "CALL_LOG_PLATFORM_UNSUPPORTED",
            CapabilityAvailability.PERMISSION_REQUIRED to "CALL_LOG_PERMISSION_REQUIRED",
        ).forEach { (availability, expected) ->
            var queried = false
            val result = provider(CallLogTestFixtures.policy(), availability = availability, reader = CallLogTestFixtures.reader { queried = true; emptyList() })
                .read(CallLogTestFixtures.readScope())
            assertEquals(CapabilityReadStatus.FAILED, result.status)
            assertEquals(expected, result.failureReason)
            assertFalse(queried)
        }
    }

    @Test
    fun `reader failures are stable redacted and all or nothing`() = runBlocking {
        val cases = listOf(
            SecurityException("secret") to "CALL_LOG_PERMISSION_REQUIRED",
            CallLogPermissionRequiredException() to "CALL_LOG_PERMISSION_REQUIRED",
            CallLogQueryException() to "CALL_LOG_QUERY_FAILED",
            CallLogInvalidRowException() to "CALL_LOG_INVALID_ROW",
        )
        cases.forEach { (error, expected) ->
            val result = provider(CallLogTestFixtures.policy(), reader = CallLogTestFixtures.reader { throw error })
                .read(CallLogTestFixtures.readScope())
            assertEquals(CapabilityReadStatus.FAILED, result.status)
            assertEquals(expected, result.failureReason)
            assertTrue(result.records.isEmpty())
            assertFalse(result.toString().contains("secret"))
        }
    }

    @Test
    fun `unexpected reader exceptions propagate rather than being relabeled as provider failures`() = runBlocking {
        val provider = provider(
            CallLogTestFixtures.policy(),
            reader = CallLogTestFixtures.reader { throw IllegalStateException("programming failure") },
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking { provider.read(CallLogTestFixtures.readScope()) }
        }
        Unit
    }

    @Test
    fun `one invalid row discards all records and auto events`() = runBlocking {
        val policy = CallLogTestFixtures.policy()
        val reader = CallLogTestFixtures.reader { throw CallLogInvalidRowException() }
        val provider = provider(policy, reader = reader, sync = RecordingSyncState(CallLogSyncState(1u, null, policy.policyRevision)))

        assertTrue(provider.read(CallLogTestFixtures.readScope(policy)).records.isEmpty())
        assertThrows(CallLogProviderException::class.java) {
            runBlocking { provider.observeAutoSend(CallLogTestFixtures.autoScope(policy)).toList() }
        }
        Unit
    }

    @Test
    fun `policy change during query fences results and emits no events`() = runBlocking {
        val policy = CallLogTestFixtures.policy()
        val authority = CallLogTestFixtures.authority(policy)
        val provider = AndroidCallLogCapabilityProvider(
            reader = CallLogTestFixtures.reader {
                authority.beginRevocation(2u, 8u, null, authorizationRevision = 2u)
                listOf(CallLogTestFixtures.row(1, 100))
            },
            settings = authority,
            availability = CallLogAvailabilitySource { CapabilityAvailability.READY },
            syncState = RecordingSyncState(CallLogSyncState(1u, null, policy.policyRevision)),
        )

        val read = provider.read(CallLogTestFixtures.readScope(policy))
        assertEquals("CALL_LOG_POLICY_STALE", read.failureReason)
        assertTrue(read.records.isEmpty())
        assertThrows(CallLogProviderException::class.java) {
            runBlocking { provider.observeAutoSend(CallLogTestFixtures.autoScope(policy)).toList() }
        }
        Unit
    }

    @Test
    fun `cancellation propagates and on demand leaves sync state untouched`() = runBlocking {
        val policy = CallLogTestFixtures.policy()
        val state = RecordingSyncState(CallLogSyncState(1u, null, policy.policyRevision))
        val cancelled = provider(policy, reader = CallLogTestFixtures.reader { throw CancellationException("cancel") }, sync = state)
        assertThrows(CancellationException::class.java) { runBlocking { cancelled.read(CallLogTestFixtures.readScope(policy)) } }
        assertEquals(0, state.mutations)
    }

    @Test
    fun `auto-send cancellation survives recoverable audit clock and sink failures`() {
        val policy = CallLogTestFixtures.policy()
        val cases = listOf(
            "clock" to Pair<() -> Long, CallLogAuditSink>(
                cancellingCompletionClock(),
                CallLogAuditSink {},
            ),
            "sink" to Pair(
                { 0L },
                CallLogAuditSink { throw CancellationException("audit sink private") },
            ),
        )

        cases.forEach { (label, auditDependencies) ->
            val sourceCancellation = CancellationException("source cancellation")
            val provider = AndroidCallLogCapabilityProvider(
                reader = CallLogTestFixtures.reader { throw sourceCancellation },
                settings = CallLogTestFixtures.authority(policy),
                availability = CallLogAvailabilitySource { CapabilityAvailability.READY },
                syncState = RecordingSyncState(CallLogSyncState(3u, null, policy.policyRevision)),
                elapsedRealtimeMs = auditDependencies.first,
                auditSink = auditDependencies.second,
            )

            val thrown = assertThrows(CancellationException::class.java) {
                runBlocking { provider.observeAutoSend(CallLogTestFixtures.autoScope(policy)).toList() }
            }

            assertSame(label, sourceCancellation, thrown)
        }
    }

    @Test
    fun `audit errors remain fatal`() {
        val policy = CallLogTestFixtures.policy()
        val provider = AndroidCallLogCapabilityProvider(
            reader = CallLogTestFixtures.reader { emptyList() },
            settings = CallLogTestFixtures.authority(policy),
            availability = CallLogAvailabilitySource { CapabilityAvailability.READY },
            syncState = RecordingSyncState(CallLogSyncState(3u, null, policy.policyRevision)),
            auditSink = CallLogAuditSink { throw AssertionError("fatal audit") },
        )

        assertThrows(AssertionError::class.java) {
            runBlocking { provider.observeAutoSend(CallLogTestFixtures.autoScope(policy)).toList() }
        }
    }

    @Test
    fun `audit uses fixed safe fields and monotonic latency buckets`() = runBlocking {
        val audit = mutableListOf<CallLogAuditEvent>()
        val policy = CallLogTestFixtures.policy()
        val provider = AndroidCallLogCapabilityProvider(
            reader = CallLogTestFixtures.reader { listOf(CallLogTestFixtures.row(42, 1234, number = "15551234567")) },
            settings = CallLogTestFixtures.authority(policy),
            availability = CallLogAvailabilitySource { CapabilityAvailability.READY },
            syncState = RecordingSyncState(null),
            nowEpochMs = { 9_999_999 },
            elapsedRealtimeMs = sequenceOf(0L, 100L).iterator()::next,
            auditSink = CallLogAuditSink(audit::add),
        )

        provider.read(CallLogTestFixtures.readScope(policy))

        val event = audit.single()
        assertEquals("calls.metadata", event.capability)
        assertEquals(CallLogLatencyBucket.LT_1_S, event.latencyBucket)
        assertEquals(1, event.readCount)
        assertFalse(event.toString().contains("42"))
        assertFalse(event.toString().contains("1555"))
        assertFalse(event.toString().contains("999"))
    }

    private fun provider(
        policy: CallLogLocalPolicy,
        reader: CallLogReader,
        availability: CapabilityAvailability = CapabilityAvailability.READY,
        sync: CallLogSyncStateStore = RecordingSyncState(null),
    ) = AndroidCallLogCapabilityProvider(
        reader = reader,
        settings = CallLogTestFixtures.authority(policy),
        availability = CallLogAvailabilitySource { availability },
        syncState = sync,
        nowEpochMs = { 1_000L },
    )

    private fun cancellingCompletionClock(): () -> Long {
        var calls = 0
        return {
            if (calls++ == 0) 0L else throw CancellationException("audit clock private")
        }
    }

    private class RecordingSyncState(private val state: CallLogSyncState?) : CallLogSyncStateStore {
        var mutations = 0
        override fun snapshot(): CallLogSyncState? = state
        override fun initialize(state: CallLogSyncState) { mutations += 1 }
        override fun advance(expectedSourceEpoch: ULong, cursor: CallLogCursor, policyRevision: ULong): Boolean { mutations += 1; return true }
        override fun resetWithRotatedKey(state: CallLogSyncState) { mutations += 1 }
    }
}
