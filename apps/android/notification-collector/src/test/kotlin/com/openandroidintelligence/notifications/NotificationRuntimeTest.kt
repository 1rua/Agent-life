package com.openandroidintelligence.notifications

import com.openandroidintelligence.core.model.AuthorizationDecision
import com.openandroidintelligence.core.model.DurableEvent
import com.openandroidintelligence.core.model.NotificationCaptureResult
import com.openandroidintelligence.core.model.NotificationCollectionPolicyV1
import com.openandroidintelligence.core.model.NotificationContent
import com.openandroidintelligence.core.model.NotificationDeliveryMode
import com.openandroidintelligence.core.model.NotificationFieldAccess
import com.openandroidintelligence.core.model.NotificationMetadata
import com.openandroidintelligence.core.model.NotificationAuthorization
import com.openandroidintelligence.core.model.NotificationOutbox
import com.openandroidintelligence.core.model.NotificationRecordV1
import com.openandroidintelligence.core.model.NotificationRuleMode
import com.openandroidintelligence.core.model.OnDemandNotificationRead
import com.openandroidintelligence.policy.InMemoryNotificationPolicyPersistence
import com.openandroidintelligence.policy.PersistentNotificationPolicyAuthority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.coroutines.startCoroutine
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationRuntimeTest {
    @Test
    fun on_demand_keeps_local_capture_but_skips_new_outbox_enqueue() {
        val authority = authorityWithContentPolicy(NotificationDeliveryMode.ON_DEMAND)
        val collector = AndroidNotificationCollector(authorization = authority).also {
            it.applyPolicyBlocking(authority.snapshot().policy)
        }
        val outbox = RecordingOutbox()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val runtime = NotificationRuntime(
            initialCollector = collector,
            outbox = outbox,
            scope = scope,
            policyAuthority = authority,
        )

        assertTrue(collector.onPosted(RawNotification("mail", "key", "Mail", "title", "body", null, 1)))
        val capture = runSuspendRuntime {
            collector.captureOnDemand(OnDemandNotificationRead("on-demand", 1u, 10))
        }

        assertEquals(1, capture.records.size)
        runSuspendRuntime { runtime.persistAndDispatch(capture) }
        assertTrue(outbox.events.isEmpty())
        scope.cancel()
    }

    @Test
    fun auto_send_enqueues_new_local_capture() {
        val authority = authorityWithContentPolicy(NotificationDeliveryMode.AUTO_SEND)
        val collector = AndroidNotificationCollector(authorization = authority).also {
            it.applyPolicyBlocking(authority.snapshot().policy)
        }
        val outbox = RecordingOutbox()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val runtime = NotificationRuntime(
            initialCollector = collector,
            outbox = outbox,
            scope = scope,
            policyAuthority = authority,
        )

        assertTrue(collector.onPosted(RawNotification("mail", "key", "Mail", "title", "body", null, 1)))
        val capture = runSuspendRuntime {
            collector.captureOnDemand(OnDemandNotificationRead("auto-send", 1u, 10))
        }

        runSuspendRuntime { runtime.persistAndDispatch(capture) }
        assertEquals(1, outbox.events.size)
        scope.cancel()
    }

    @Test
    fun on_demand_mutation_waits_for_auto_send_enqueue_and_then_stops_future_writes() {
        val authority = authorityWithContentPolicy(NotificationDeliveryMode.AUTO_SEND)
        val outbox = BlockingOutbox()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val runtime = NotificationRuntime(
            initialCollector = AndroidNotificationCollector(authorization = authority),
            outbox = outbox,
            scope = scope,
            policyAuthority = authority,
        )
        val capture = NotificationCaptureResult(listOf(record()))
        val enqueueThread = Thread {
            runSuspendRuntime { runtime.persistAndDispatch(capture) }
        }
        val mutationFinished = CountDownLatch(1)
        val mutationThread = Thread {
            authority.localController().apply(
                authority.snapshot().policy,
                authorizationRevision = 2u,
                granted = true,
                deliveryMode = NotificationDeliveryMode.ON_DEMAND,
            )
            mutationFinished.countDown()
        }

        enqueueThread.start()
        try {
            assertTrue(outbox.enqueueEntered.await(5, TimeUnit.SECONDS))
            mutationThread.start()
            assertFalse(mutationFinished.await(200, TimeUnit.MILLISECONDS))

            outbox.releaseEnqueue.countDown()
            enqueueThread.join(5_000)
            mutationThread.join(5_000)
        } finally {
            outbox.releaseEnqueue.countDown()
            enqueueThread.join(5_000)
            mutationThread.join(5_000)
            scope.cancel()
        }

        assertFalse(enqueueThread.isAlive)
        assertFalse(mutationThread.isAlive)
        assertEquals(NotificationDeliveryMode.ON_DEMAND, authority.snapshot().deliveryMode)
        assertEquals(1, outbox.events.size)

        runSuspendRuntime { runtime.persistAndDispatch(capture) }

        assertEquals(1, outbox.events.size)
    }

    @Test
    fun authority_present_egress_gate_still_refuses_auto_enqueue() {
        val authority = authorityWithContentPolicy(NotificationDeliveryMode.AUTO_SEND)
        val outbox = RecordingOutbox()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val runtime = NotificationRuntime(
            initialCollector = AndroidNotificationCollector(authorization = authority),
            outbox = outbox,
            scope = scope,
            egressGate = NotificationRecordEgressGate { false },
            policyAuthority = authority,
        )

        runSuspendRuntime {
            runtime.persistAndDispatch(NotificationCaptureResult(listOf(record())))
        }

        assertTrue(outbox.events.isEmpty())
        scope.cancel()
    }

    @Test
    fun null_authority_preserves_legacy_enqueue_behavior() {
        val outbox = RecordingOutbox()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val runtime = NotificationRuntime(
            initialCollector = AndroidNotificationCollector(
                authorization = { _, _, _ -> AuthorizationDecision.allow() },
            ),
            outbox = outbox,
            scope = scope,
        )

        runSuspendRuntime {
            runtime.persistAndDispatch(NotificationCaptureResult(listOf(record())))
        }

        assertEquals(1, outbox.events.size)
        scope.cancel()
    }

    @Test
    fun start_reconciles_policy_update_that_lands_during_initial_apply() {
        val authority = authorityWithContentPolicy(NotificationDeliveryMode.AUTO_SEND)
        val blockingAuthorization = BlockingNotificationAuthorization()
        val collector = AndroidNotificationCollector(authorization = blockingAuthorization).also {
            it.applyPolicyBlocking(authority.snapshot().policy)
            assertTrue(it.onPosted(RawNotification("mail", "mail-key", "Mail", "title", "body", null, 1)))
        }
        blockingAuthorization.blockNextEvaluation()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val runtime = NotificationRuntime(
            initialCollector = collector,
            outbox = RecordingOutbox(),
            scope = scope,
            policyAuthority = authority,
        )
        val updatePublished = CountDownLatch(1)
        val updateObserver = authority.addListener { state ->
            if (state.policy.policyRevision == 2uL) updatePublished.countDown()
        }
        val startThread = Thread { runtime.start() }
        val updateThread = Thread {
            authority.localController().apply(
                NotificationCollectionPolicyV1(
                    NotificationRuleMode.ALLOWLIST,
                    listOf("chat"),
                    NotificationFieldAccess.CONTENT,
                    2u,
                ),
                authorizationRevision = 2u,
                granted = true,
                deliveryMode = NotificationDeliveryMode.AUTO_SEND,
            )
        }

        startThread.start()
        try {
            assertTrue(blockingAuthorization.entered.await(5, TimeUnit.SECONDS))
            updateThread.start()
            assertTrue(updatePublished.await(5, TimeUnit.SECONDS))
        } finally {
            blockingAuthorization.release()
            startThread.join(5_000)
            updateThread.join(5_000)
            updateObserver.close()
            runtime.stop()
            scope.cancel()
        }

        assertFalse(startThread.isAlive)
        assertFalse(updateThread.isAlive)
        assertTrue(collector.onPosted(RawNotification("chat", "chat-key", "Chat", "title", "body", null, 2)))
        assertEquals(
            2uL,
            collector.captureOnDemandBlocking(OnDemandNotificationRead("latest-policy", 2u, 10)).policyRevision,
        )
    }

    @Test
    fun start_applies_latest_snapshot_after_initial_apply_when_listener_is_delayed() {
        val authority = authorityWithContentPolicy(NotificationDeliveryMode.AUTO_SEND)
        val blockingAuthorization = BlockingNotificationAuthorization()
        val collector = AndroidNotificationCollector(authorization = blockingAuthorization).also {
            it.applyPolicyBlocking(authority.snapshot().policy)
            assertTrue(it.onPosted(RawNotification("mail", "mail-key", "Mail", "title", "body", null, 1)))
        }
        blockingAuthorization.blockNextEvaluation()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val runtime = NotificationRuntime(
            initialCollector = collector,
            outbox = RecordingOutbox(),
            scope = scope,
            policyAuthority = authority,
        )
        val listenerEntered = CountDownLatch(1)
        val allowListenerToReturn = CountDownLatch(1)
        val delayedListener = authority.addListener { state ->
            if (state.policy.policyRevision == 2uL) {
                listenerEntered.countDown()
                check(allowListenerToReturn.await(5, TimeUnit.SECONDS)) { "delayed listener was not released" }
            }
        }
        val startThread = Thread { runtime.start() }
        val updateThread = Thread {
            authority.localController().apply(
                NotificationCollectionPolicyV1(
                    NotificationRuleMode.ALLOWLIST,
                    listOf("chat"),
                    NotificationFieldAccess.CONTENT,
                    2u,
                ),
                authorizationRevision = 2u,
                granted = true,
                deliveryMode = NotificationDeliveryMode.AUTO_SEND,
            )
        }

        startThread.start()
        try {
            assertTrue(blockingAuthorization.entered.await(5, TimeUnit.SECONDS))
            updateThread.start()
            assertTrue(listenerEntered.await(5, TimeUnit.SECONDS))
            blockingAuthorization.release()
            startThread.join(5_000)

            assertFalse(startThread.isAlive)
            assertTrue(collector.onPosted(RawNotification("chat", "chat-key", "Chat", "title", "body", null, 2)))
            assertEquals(
                2uL,
                collector.captureOnDemandBlocking(OnDemandNotificationRead("latest-policy", 2u, 10)).policyRevision,
            )
        } finally {
            blockingAuthorization.release()
            allowListenerToReturn.countDown()
            startThread.join(5_000)
            updateThread.join(5_000)
            delayedListener.close()
            runtime.stop()
            scope.cancel()
        }

        assertFalse(updateThread.isAlive)
    }

    @Test
    fun revoke_race_is_linearized_between_admission_and_egress_enqueue() {
        val authority = authorityWithContentPolicy(NotificationDeliveryMode.AUTO_SEND)
        val gateEntered = CountDownLatch(1)
        val releaseGate = CountDownLatch(1)
        val outbox = RecordingOutbox()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val runtime = NotificationRuntime(
            initialCollector = AndroidNotificationCollector(authorization = authority),
            outbox = outbox,
            scope = scope,
            egressGate = NotificationRecordEgressGate { record ->
                gateEntered.countDown()
                check(releaseGate.await(5, TimeUnit.SECONDS)) { "egress gate was not released" }
                authority.allows(record)
            },
            policyAuthority = authority,
        )
        val capture = NotificationCaptureResult(listOf(record()))
        val enqueueThread = Thread {
            runSuspendRuntime { runtime.persistAndDispatch(capture) }
        }
        val mutationFinished = CountDownLatch(1)
        val mutationThread = Thread {
            try {
                authority.localController().revoke(authorizationRevision = 2u)
            } finally {
                mutationFinished.countDown()
            }
        }

        enqueueThread.start()
        try {
            assertTrue(gateEntered.await(5, TimeUnit.SECONDS))
            mutationThread.start()
            assertFalse(mutationFinished.await(200, TimeUnit.MILLISECONDS))

            releaseGate.countDown()
            enqueueThread.join(5_000)
            mutationThread.join(5_000)
        } finally {
            releaseGate.countDown()
            enqueueThread.join(5_000)
            mutationThread.join(5_000)
            scope.cancel()
        }

        assertFalse(enqueueThread.isAlive)
        assertFalse(mutationThread.isAlive)
        assertFalse(authority.snapshot().granted)
        assertEquals(1, outbox.events.size)
    }

    @Test
    fun local_revoke_reapplies_policy_and_removes_active_collector_state() {
        val authority = PersistentNotificationPolicyAuthority(InMemoryNotificationPolicyPersistence())
        val controller = authority.localController()
        val policy = NotificationCollectionPolicyV1(
            NotificationRuleMode.ALLOWLIST,
            listOf("mail"),
            NotificationFieldAccess.CONTENT,
            1u,
        )
        controller.apply(policy, authorizationRevision = 1u, granted = true)
        val collector = AndroidNotificationCollector(authorization = authority)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val runtime = NotificationRuntime(
            initialCollector = collector,
            outbox = RecordingOutbox(),
            scope = scope,
            egressGate = NotificationRecordEgressGate(authority::allows),
            policyAuthority = authority,
        )
        runtime.start()
        assertTrue(collector.onPosted(RawNotification("mail", "key", "Mail", "title", "body", null, 1)))

        controller.revoke(authorizationRevision = 2u)

        assertFalse(collector.onRemoved("key"))
        runtime.stop()
        scope.cancel()
    }

    private fun record() = NotificationRecordV1.Upsert(
        sourceEpoch = 1u,
        occurrenceId = "occ",
        recordKey = "key",
        recordRevision = 1u,
        cursor = 1u,
        capturedAtEpochMs = 1,
        captureRevision = 1u,
        metadata = NotificationMetadata("mail", "Mail", null, 1),
        content = NotificationContent("title", "body"),
    )

    private fun authorityWithContentPolicy(
        deliveryMode: NotificationDeliveryMode,
    ): PersistentNotificationPolicyAuthority {
        val authority = PersistentNotificationPolicyAuthority(InMemoryNotificationPolicyPersistence())
        authority.localController().apply(
            NotificationCollectionPolicyV1(
                NotificationRuleMode.ALLOWLIST,
                listOf("mail"),
                NotificationFieldAccess.CONTENT,
                1u,
            ),
            authorizationRevision = 1u,
            granted = true,
            deliveryMode = deliveryMode,
        )
        return authority
    }
}

private class RecordingOutbox : NotificationOutbox {
    val events = mutableListOf<NotificationRecordV1>()

    override suspend fun enqueueAccepted(record: NotificationRecordV1): DurableEvent {
        events += record
        return DurableEvent("event-${events.size}", record, byteArrayOf(events.size.toByte()))
    }

    override suspend fun acknowledge(eventId: String, eventAckWire: ByteArray) = Unit

    override suspend fun recoverUnacknowledged(): List<DurableEvent> = emptyList()
}

private class BlockingOutbox : NotificationOutbox {
    val events = CopyOnWriteArrayList<NotificationRecordV1>()
    val enqueueEntered = CountDownLatch(1)
    val releaseEnqueue = CountDownLatch(1)

    override suspend fun enqueueAccepted(record: NotificationRecordV1): DurableEvent {
        enqueueEntered.countDown()
        check(releaseEnqueue.await(5, TimeUnit.SECONDS)) { "blocking enqueue was not released" }
        events += record
        return DurableEvent("event-${events.size}", record, byteArrayOf(events.size.toByte()))
    }

    override suspend fun acknowledge(eventId: String, eventAckWire: ByteArray) = Unit

    override suspend fun recoverUnacknowledged(): List<DurableEvent> = emptyList()
}

private class BlockingNotificationAuthorization : NotificationAuthorization {
    private val blockNext = java.util.concurrent.atomic.AtomicBoolean(false)
    val entered = CountDownLatch(1)
    private val released = CountDownLatch(1)

    fun blockNextEvaluation() {
        blockNext.set(true)
    }

    fun release() {
        released.countDown()
    }

    override fun decide(
        packageName: String,
        fieldAccess: NotificationFieldAccess,
        policyRevision: ULong,
    ): AuthorizationDecision {
        if (blockNext.compareAndSet(true, false)) {
            entered.countDown()
            check(released.await(5, TimeUnit.SECONDS)) { "blocked authorization was not released" }
        }
        return AuthorizationDecision.allow()
    }
}

private fun <T> runSuspendRuntime(block: suspend () -> T): T {
    var result: Result<T>? = null
    block.startCoroutine(object : kotlin.coroutines.Continuation<T> {
        override val context = kotlin.coroutines.EmptyCoroutineContext
        override fun resumeWith(value: Result<T>) { result = value }
    })
    return checkNotNull(result).getOrThrow()
}
