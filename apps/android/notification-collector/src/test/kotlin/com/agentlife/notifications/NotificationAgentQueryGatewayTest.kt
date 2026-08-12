package com.agentlife.notifications

import com.agentlife.core.model.NotificationCaptureStatus
import com.agentlife.core.model.NotificationCollectionPolicyV1
import com.agentlife.core.model.NotificationCollector
import com.agentlife.core.model.NotificationFieldAccess
import com.agentlife.core.model.NotificationRuleMode
import com.agentlife.core.model.NotificationRecordV1
import com.agentlife.core.model.sortNotificationPackageIds
import com.agentlife.policy.InMemoryNotificationPolicyPersistence
import com.agentlife.policy.PersistentNotificationPolicyAuthority
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
        val rejected = assertThrows(NotificationQueryRejected::class.java) {
            runSuspend { gateway.query(request.copy(limit = 9)) }
        }
        assertEquals("OPERATION_IDENTITY_MISMATCH", rejected.code)
        Unit
    }

    @Test
    fun cached_result_is_rechecked_against_current_grant_before_returning() = runSuspend {
        val authority = authorityWithContentPolicy()
        val countingCollector = CountingNotificationCollector(realCollectorWithOneRecord())
        val gateway = NotificationAgentQueryGateway(countingCollector, authority)
        val request = NotificationAgentQueryRequest("cached-revoked", 1u, 10)

        gateway.query(request)
        authority.localController().revoke(2u)

        val result = gateway.query(request)

        assertFailed(result, "LOCAL_GRANT_REQUIRED", 1u)
        assertEquals(1, countingCollector.captureCount)
    }

    @Test
    fun cached_result_is_rechecked_against_current_revision_before_returning() = runSuspend {
        val authority = authorityWithContentPolicy()
        val countingCollector = CountingNotificationCollector(realCollectorWithOneRecord())
        val gateway = NotificationAgentQueryGateway(countingCollector, authority)
        val request = NotificationAgentQueryRequest("cached-stale", 1u, 10)

        gateway.query(request)
        authority.localController().apply(
            authority.snapshot().policy.copy(policyRevision = 2u),
            authorizationRevision = 2u,
            granted = true,
        )

        val result = gateway.query(request)

        assertFailed(result, "AUTHORIZATION_REVISION_STALE", 2u)
        assertEquals(1, countingCollector.captureCount)
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
    fun corrupt_persisted_policy_returns_failed_capture_with_current_revision() = runSuspend {
        val persistence = InMemoryNotificationPolicyPersistence().also {
            it.write(byteArrayOf(0x41, 0x42, 0x43))
        }
        val authority = PersistentNotificationPolicyAuthority(persistence)
        val collector = AndroidNotificationCollector(authorization = authority).also {
            it.applyPolicyBlocking(authority.snapshot().policy)
        }

        val result = NotificationAgentQueryGateway(collector, authority).query(
            NotificationAgentQueryRequest("corrupt-policy", 0u, 10),
        )

        assertFailed(result, "LOCAL_POLICY_CORRUPTED", 0u)
    }

    @Test
    fun pre_gate_failures_do_not_access_the_collector() = runSuspend {
        val staleAuthority = authorityWithContentPolicy()
        val staleCollector = countingCollector(staleAuthority)
        val staleResult = NotificationAgentQueryGateway(staleCollector, staleAuthority).query(
            NotificationAgentQueryRequest("pre-gate-stale", 0u, 10),
        )
        assertFailed(staleResult, "AUTHORIZATION_REVISION_STALE", 1u)
        assertEquals(0, staleCollector.captureCount)

        val corruptPersistence = InMemoryNotificationPolicyPersistence().also {
            it.write(byteArrayOf(0x41, 0x42, 0x43))
        }
        val corruptAuthority = PersistentNotificationPolicyAuthority(corruptPersistence)
        val corruptCollector = countingCollector(corruptAuthority)
        val corruptResult = NotificationAgentQueryGateway(corruptCollector, corruptAuthority).query(
            NotificationAgentQueryRequest("pre-gate-corrupt", 0u, 10),
        )
        assertFailed(corruptResult, "LOCAL_POLICY_CORRUPTED", 0u)
        assertEquals(0, corruptCollector.captureCount)

        val ungrantedAuthority = PersistentNotificationPolicyAuthority(InMemoryNotificationPolicyPersistence())
        val ungrantedCollector = countingCollector(ungrantedAuthority)
        val ungrantedResult = NotificationAgentQueryGateway(ungrantedCollector, ungrantedAuthority).query(
            NotificationAgentQueryRequest("pre-gate-ungranted", 0u, 10),
        )
        assertFailed(ungrantedResult, "LOCAL_GRANT_REQUIRED", 0u)
        assertEquals(0, ungrantedCollector.captureCount)

        val contentIneligibleAuthority = authorityWithMetadataPolicy()
        val contentIneligibleCollector = countingCollector(contentIneligibleAuthority)
        val contentIneligibleResult = NotificationAgentQueryGateway(
            contentIneligibleCollector,
            contentIneligibleAuthority,
        ).query(
            NotificationAgentQueryRequest(
                operationId = "pre-gate-content-ineligible",
                policyRevision = 1u,
                limit = 10,
                filter = NotificationQueryFilter(fieldAccess = NotificationFieldAccess.CONTENT),
            ),
        )
        assertFailed(contentIneligibleResult, "FIELD_ACCESS_NOT_GRANTED", 1u)
        assertEquals(0, contentIneligibleCollector.captureCount)
    }

    @Test
    fun non_empty_package_filters_are_pre_gated_for_allowlist_and_denylist() = runSuspend {
        val allowlistAuthority = authorityWithContentPolicy()
        val allowlistCollector = countingCollectorWithOneRecord(allowlistAuthority)
        val allowlistResult = NotificationAgentQueryGateway(allowlistCollector, allowlistAuthority).query(
            NotificationAgentQueryRequest(
                operationId = "pre-gate-allowlist-package",
                policyRevision = 1u,
                limit = 10,
                filter = NotificationQueryFilter(listOf("com.blocked")),
            ),
        )
        assertFailed(allowlistResult, "PACKAGE_NOT_ALLOWED", 1u)
        assertEquals(0, allowlistCollector.captureCount)

        val denylistAuthority = authorityWithDenylistContentPolicy()
        val denylistCollector = countingCollectorWithOneRecord(denylistAuthority)
        val denylistResult = NotificationAgentQueryGateway(denylistCollector, denylistAuthority).query(
            NotificationAgentQueryRequest(
                operationId = "pre-gate-denylist-package",
                policyRevision = 1u,
                limit = 10,
                filter = NotificationQueryFilter(listOf("com.chat")),
            ),
        )
        assertFailed(denylistResult, "PACKAGE_NOT_ALLOWED", 1u)
        assertEquals(0, denylistCollector.captureCount)
    }

    @Test
    fun authority_is_rechecked_after_real_capture_before_returning_records() = runSuspend {
        val authority = authorityWithContentPolicy()
        val countingCollector = CountingNotificationCollector(
            realCollectorWithOneRecord(),
            afterCapture = { authority.localController().revoke(2u) },
        )

        val result = NotificationAgentQueryGateway(countingCollector, authority).query(
            NotificationAgentQueryRequest("capture-revoked", 1u, 10),
        )

        assertFailed(result, "LOCAL_GRANT_REQUIRED", 1u)
        assertEquals(1, countingCollector.captureCount)
    }

    @Test
    fun invalid_operation_id_and_query_limits_are_rejected() {
        assertRejectedCode("OPERATION_ID_INVALID") {
            NotificationAgentQueryRequest("   ", 1u, 10)
            Unit
        }
        assertRejectedCode("LIMIT_INVALID") {
            NotificationAgentQueryRequest("zero", 1u, 0)
            Unit
        }
        assertRejectedCode("LIMIT_INVALID") {
            NotificationAgentQueryRequest("too-many", 1u, 101)
            Unit
        }
    }

    @Test
    fun unsorted_duplicate_and_malformed_package_filters_are_rejected() {
        assertRejectedCode("PACKAGE_IDS_UNSORTED") {
            NotificationQueryFilter(listOf("com.mail", "com.chat"))
            Unit
        }
        assertRejectedCode("PACKAGE_IDS_DUPLICATE") {
            NotificationQueryFilter(listOf("com.mail", "com.mail"))
            Unit
        }
        assertRejectedCode("PACKAGE_ID_INVALID") {
            NotificationQueryFilter(listOf("not a package"))
            Unit
        }
    }

    @Test
    fun package_ordering_uses_unicode_code_points_not_utf16_units() {
        assertEquals(
            listOf("com.a", "com.\uE000", "com.\uD800\uDC00"),
            sortNotificationPackageIds(listOf("com.\uD800\uDC00", "com.a", "com.\uE000")),
        )
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

private fun authorityWithDenylistContentPolicy(): PersistentNotificationPolicyAuthority {
    val authority = PersistentNotificationPolicyAuthority(InMemoryNotificationPolicyPersistence())
    authority.localController().apply(
        NotificationCollectionPolicyV1(
            NotificationRuleMode.DENYLIST,
            listOf("com.chat"),
            NotificationFieldAccess.CONTENT,
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

private fun countingCollector(authority: PersistentNotificationPolicyAuthority): CountingNotificationCollector =
    CountingNotificationCollector(
        AndroidNotificationCollector(authorization = authority).also {
            it.applyPolicyBlocking(authority.snapshot().policy)
        },
    )

private fun countingCollectorWithOneRecord(
    authority: PersistentNotificationPolicyAuthority,
): CountingNotificationCollector = CountingNotificationCollector(
    AndroidNotificationCollector(authorization = authority).also {
        it.applyPolicyBlocking(authority.snapshot().policy)
        it.onPosted(raw("com.mail", "mail", "subject", "body"))
    },
)

private fun raw(packageName: String, key: String, title: String, body: String): RawNotification =
    RawNotification(packageName, key, packageName, title, body, null, 1)

private class CountingNotificationCollector(
    private val delegate: NotificationCollector,
    private val afterCapture: (() -> Unit)? = null,
) : NotificationCollector {
    var captureCount: Int = 0
        private set

    override suspend fun applyPolicy(policy: NotificationCollectionPolicyV1) = delegate.applyPolicy(policy)

    override suspend fun captureOnDemand(
        request: com.agentlife.core.model.OnDemandNotificationRead,
    ): com.agentlife.core.model.NotificationCaptureResult {
        captureCount += 1
        return delegate.captureOnDemand(request).also { afterCapture?.invoke() }
    }

    override fun observeAutoSend() = delegate.observeAutoSend()
}

private fun assertRejectedCode(expected: String, action: () -> Unit) {
    val rejected = assertThrows(NotificationQueryRejected::class.java, action)
    assertEquals(expected, rejected.code)
}

private fun <T> runSuspend(block: suspend () -> T): T {
    var result: Result<T>? = null
    block.startCoroutine(object : Continuation<T> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(value: Result<T>) { result = value }
    })
    return checkNotNull(result).getOrThrow()
}
