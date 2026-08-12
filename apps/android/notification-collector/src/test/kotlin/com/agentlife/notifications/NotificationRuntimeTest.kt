package com.agentlife.notifications

import com.agentlife.core.model.AuthorizationDecision
import com.agentlife.core.model.DurableEvent
import com.agentlife.core.model.NotificationCaptureResult
import com.agentlife.core.model.NotificationCollectionPolicyV1
import com.agentlife.core.model.NotificationContent
import com.agentlife.core.model.NotificationDeliveryMode
import com.agentlife.core.model.NotificationFieldAccess
import com.agentlife.core.model.NotificationMetadata
import com.agentlife.core.model.NotificationAuthorization
import com.agentlife.core.model.NotificationOutbox
import com.agentlife.core.model.NotificationRecordV1
import com.agentlife.core.model.NotificationRuleMode
import com.agentlife.core.model.OnDemandNotificationRead
import com.agentlife.policy.InMemoryNotificationPolicyPersistence
import com.agentlife.policy.PersistentNotificationPolicyAuthority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.coroutines.startCoroutine
import java.util.concurrent.CountDownLatch
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
    }

    @Test
    fun revoke_race_is_rechecked_before_enqueue() {
        val outbox = RecordingOutbox()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val runtime = NotificationRuntime(
            initialCollector = AndroidNotificationCollector(
                authorization = { _, _, _ -> AuthorizationDecision.allow() },
            ),
            outbox = outbox,
            scope = scope,
            egressGate = NotificationRecordEgressGate { false },
        )

        runSuspendRuntime {
            runtime.persistAndDispatch(NotificationCaptureResult(listOf(record())))
        }

        assertTrue(outbox.events.isEmpty())
        scope.cancel()
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
