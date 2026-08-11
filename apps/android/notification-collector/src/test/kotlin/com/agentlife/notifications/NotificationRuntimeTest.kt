package com.agentlife.notifications

import com.agentlife.core.model.AuthorizationDecision
import com.agentlife.core.model.DurableEvent
import com.agentlife.core.model.NotificationCaptureResult
import com.agentlife.core.model.NotificationCollectionPolicyV1
import com.agentlife.core.model.NotificationContent
import com.agentlife.core.model.NotificationFieldAccess
import com.agentlife.core.model.NotificationMetadata
import com.agentlife.core.model.NotificationOutbox
import com.agentlife.core.model.NotificationRecordV1
import com.agentlife.core.model.NotificationRuleMode
import com.agentlife.policy.InMemoryNotificationPolicyPersistence
import com.agentlife.policy.PersistentNotificationPolicyAuthority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationRuntimeTest {
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

private fun <T> runSuspendRuntime(block: suspend () -> T): T {
    var result: Result<T>? = null
    block.startCoroutine(object : kotlin.coroutines.Continuation<T> {
        override val context = kotlin.coroutines.EmptyCoroutineContext
        override fun resumeWith(value: Result<T>) { result = value }
    })
    return checkNotNull(result).getOrThrow()
}
