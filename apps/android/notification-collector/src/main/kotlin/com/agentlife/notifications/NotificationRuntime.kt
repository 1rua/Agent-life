package com.agentlife.notifications

import com.agentlife.core.model.AuthorizationDecision
import com.agentlife.core.model.NotificationAuthorization
import com.agentlife.core.model.NotificationOutbox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Process-local composition seam for the notification listener.  The runtime
 * only forwards policy-accepted auto-send records to an injected encrypted
 * outbox port; it has no Bridge, HTTP, or endpoint knowledge.
 */
class NotificationRuntime(
    initialCollector: AndroidNotificationCollector,
    private val outbox: NotificationOutbox?,
    private val scope: CoroutineScope,
) {
    private val lock = Any()
    private var collector: AndroidNotificationCollector = initialCollector
    private var subscription: Job? = null

    fun currentCollector(): AndroidNotificationCollector = synchronized(lock) { collector }

    /** Idempotent: a listener lifecycle can call start more than once safely. */
    fun start() = synchronized(lock) {
        if (subscription?.isActive == true || outbox == null) return@synchronized
        subscription = scope.launch {
            collector.observeAutoSend().collect { capture ->
                capture.records.forEach { record -> outbox.enqueueAccepted(record) }
            }
        }
    }

    fun stop() = synchronized(lock) {
        subscription?.cancel()
        subscription = null
    }

    /** Keep the test/device injection seam while preserving the outbox sink. */
    fun replaceCollector(value: AndroidNotificationCollector) = synchronized(lock) {
        val wasActive = subscription?.isActive == true
        subscription?.cancel()
        collector = value
        subscription = null
        if (wasActive && outbox != null) {
            subscription = scope.launch {
                collector.observeAutoSend().collect { capture ->
                    capture.records.forEach { record -> outbox.enqueueAccepted(record) }
                }
            }
        }
    }
}

fun interface NotificationRuntimeFactory {
    fun create(scope: CoroutineScope): NotificationRuntime
}

/**
 * The Android service cannot receive constructor injection. This registry is
 * the explicit local composition point; its default factory is deny-first and
 * has no outbox, so an unconfigured install cannot emit notification data.
 */
object NotificationRuntimeFactoryRegistry {
    private val defaultFactory = NotificationRuntimeFactory { scope ->
        NotificationRuntime(
            initialCollector = AndroidNotificationCollector(
                authorization = NotificationAuthorization { _, _, _ ->
                    AuthorizationDecision.deny("NO_AUTHORIZATION")
                },
            ),
            outbox = null,
            scope = scope,
        )
    }

    @Volatile
    private var factory: NotificationRuntimeFactory = defaultFactory

    fun install(value: NotificationRuntimeFactory) {
        factory = value
    }

    fun create(scope: CoroutineScope): NotificationRuntime = factory.create(scope)

    fun reset() {
        factory = defaultFactory
    }
}
