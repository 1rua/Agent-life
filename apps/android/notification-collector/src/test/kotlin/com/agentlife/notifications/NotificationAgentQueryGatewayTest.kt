package com.agentlife.notifications

import com.agentlife.core.model.NotificationCaptureStatus
import com.agentlife.core.model.NotificationCollectionPolicyV1
import com.agentlife.core.model.NotificationCollector
import com.agentlife.core.model.NotificationFieldAccess
import com.agentlife.core.model.NotificationRuleMode
import com.agentlife.core.model.NotificationRecordV1
import com.agentlife.policy.InMemoryNotificationPolicyPersistence
import com.agentlife.policy.PersistentNotificationPolicyAuthority
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NotificationAgentQueryGatewayTest {
    @Test
    fun query_returns_requested_package_and_content_only_when_local_policy_allows_it() = runSuspend {
        val authority = authorityWithContentPolicy()
        val collector = AndroidNotificationCollector(authorization = authority)
        collector.applyPolicyBlocking(authority.snapshot().policy)
        collector.onPosted(raw("com.mail", "mail", "subject", "body"))
        collector.onPosted(raw("com.chat", "chat", "chat subject", "chat body"))

        val result = NotificationAgentQueryGateway(collector, authority).query(
            NotificationAgentQueryRequest(
                operationId = "op-1",
                policyRevision = 1u,
                limit = 10,
                filter = NotificationQueryFilter(listOf("com.mail"), NotificationFieldAccess.CONTENT),
            ),
        )

        assertEquals(NotificationCaptureStatus.COMPLETE, result.status)
        assertEquals("com.mail", (result.records.single() as NotificationRecordV1.Upsert).metadata.packageName)
        assertEquals("body", (result.records.single() as NotificationRecordV1.Upsert).content?.body)
    }

    @Test
    fun metadata_query_redacts_content_even_when_local_policy_allows_content() = runSuspend {
        val gateway = gatewayWithContentPolicyAndOneRecord()
        val result = gateway.query(NotificationAgentQueryRequest("metadata-op", 1u, 10))

        assertEquals(null, (result.records.single() as NotificationRecordV1.Upsert).content)
    }

    @Test
    fun identical_operation_retry_does_not_capture_twice_and_conflicting_reuse_is_rejected() = runSuspend {
        val countingCollector = CountingNotificationCollector(realCollectorWithOneRecord())
        val gateway = NotificationAgentQueryGateway(countingCollector, authorityWithContentPolicy())
        val request = NotificationAgentQueryRequest("same-op", 1u, 10)

        assertEquals(gateway.query(request), gateway.query(request))
        assertEquals(1, countingCollector.captureCount)
        assertThrows(NotificationQueryRejected::class.java) {
            runSuspend { gateway.query(request.copy(limit = 9)) }
        }
        Unit
    }

    @Test
    fun stale_policy_revision_returns_failed_capture() = runSuspend {
        val result = gatewayWithContentPolicyAndOneRecord().query(
            NotificationAgentQueryRequest("stale", 0u, 10),
        )

        assertFailed(result, "AUTHORIZATION_REVISION_STALE", 1u)
    }

    @Test
    fun missing_local_grant_returns_failed_capture() = runSuspend {
        val authority = PersistentNotificationPolicyAuthority(InMemoryNotificationPolicyPersistence())
        val collector = AndroidNotificationCollector(authorization = authority).also {
            it.applyPolicyBlocking(authority.snapshot().policy)
        }

        val result = NotificationAgentQueryGateway(collector, authority).query(
            NotificationAgentQueryRequest("ungranted", 0u, 10),
        )

        assertFailed(result, "LOCAL_GRANT_REQUIRED", 0u)
    }

    @Test
    fun content_request_under_metadata_policy_returns_failed_capture() = runSuspend {
        val authority = authorityWithMetadataPolicy()
        val collector = AndroidNotificationCollector(authorization = authority).also {
            it.applyPolicyBlocking(authority.snapshot().policy)
            it.onPosted(raw("com.mail", "mail", "subject", "body"))
        }

        val result = NotificationAgentQueryGateway(collector, authority).query(
            NotificationAgentQueryRequest(
                operationId = "content-with-metadata-policy",
                policyRevision = 1u,
                limit = 10,
                filter = NotificationQueryFilter(fieldAccess = NotificationFieldAccess.CONTENT),
            ),
        )

        assertFailed(result, "FIELD_ACCESS_NOT_GRANTED", 1u)
    }

    @Test
    fun invalid_operation_id_and_query_limits_are_rejected() {
        assertThrows(NotificationQueryRejected::class.java) {
            NotificationAgentQueryRequest("   ", 1u, 10)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NotificationAgentQueryRequest("zero", 1u, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NotificationAgentQueryRequest("too-many", 1u, 101)
        }
    }

    @Test
    fun unsorted_duplicate_and_malformed_package_filters_are_rejected() {
        assertThrows(IllegalArgumentException::class.java) {
            NotificationQueryFilter(listOf("com.mail", "com.chat"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            NotificationQueryFilter(listOf("com.mail", "com.mail"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            NotificationQueryFilter(listOf("not a package"))
        }
    }

    private fun assertFailed(
        result: com.agentlife.core.model.NotificationCaptureResult,
        reason: String,
        revision: ULong,
    ) {
        assertEquals(NotificationCaptureStatus.FAILED, result.status)
        assertEquals(emptyList<NotificationRecordV1>(), result.records)
        assertEquals(reason, result.failureReason)
        assertEquals(revision, result.policyRevision)
    }
}

private fun authorityWithContentPolicy(): PersistentNotificationPolicyAuthority {
    val authority = PersistentNotificationPolicyAuthority(InMemoryNotificationPolicyPersistence())
    authority.localController().apply(
        NotificationCollectionPolicyV1(
            NotificationRuleMode.ALLOWLIST,
            listOf("com.chat", "com.mail"),
            NotificationFieldAccess.CONTENT,
            1u,
        ),
        authorizationRevision = 1u,
        granted = true,
    )
    return authority
}

private fun authorityWithMetadataPolicy(): PersistentNotificationPolicyAuthority {
    val authority = PersistentNotificationPolicyAuthority(InMemoryNotificationPolicyPersistence())
    authority.localController().apply(
        NotificationCollectionPolicyV1(
            NotificationRuleMode.ALLOWLIST,
            listOf("com.mail"),
            NotificationFieldAccess.METADATA,
            1u,
        ),
        authorizationRevision = 1u,
        granted = true,
    )
    return authority
}

private fun realCollectorWithOneRecord(): AndroidNotificationCollector {
    val authority = authorityWithContentPolicy()
    return AndroidNotificationCollector(authorization = authority).also { collector ->
        collector.applyPolicyBlocking(authority.snapshot().policy)
        collector.onPosted(RawNotification("com.mail", "mail", "com.mail", "subject", "body", null, 1))
    }
}

private fun gatewayWithContentPolicyAndOneRecord(): NotificationAgentQueryGateway {
    val authority = authorityWithContentPolicy()
    val collector = AndroidNotificationCollector(authorization = authority).also {
        it.applyPolicyBlocking(authority.snapshot().policy)
        it.onPosted(RawNotification("com.mail", "mail", "com.mail", "subject", "body", null, 1))
    }
    return NotificationAgentQueryGateway(collector, authority)
}

private fun raw(packageName: String, key: String, title: String, body: String): RawNotification =
    RawNotification(packageName, key, packageName, title, body, null, 1)

private class CountingNotificationCollector(
    private val delegate: NotificationCollector,
) : NotificationCollector {
    var captureCount: Int = 0
        private set

    override suspend fun applyPolicy(policy: NotificationCollectionPolicyV1) = delegate.applyPolicy(policy)

    override suspend fun captureOnDemand(
        request: com.agentlife.core.model.OnDemandNotificationRead,
    ): com.agentlife.core.model.NotificationCaptureResult {
        captureCount += 1
        return delegate.captureOnDemand(request)
    }

    override fun observeAutoSend() = delegate.observeAutoSend()
}

private fun <T> runSuspend(block: suspend () -> T): T {
    var result: Result<T>? = null
    block.startCoroutine(object : Continuation<T> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(value: Result<T>) { result = value }
    })
    return checkNotNull(result).getOrThrow()
}
