package com.agentlife.kernel

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

enum class AuditOutcome { ALLOWED, DENIED, FAILED }

/**
 * One audit record.
 *
 * The fields are exactly what the contract permits: who acted, what they
 * attempted, whether it was allowed, and the correlation ID that ties the
 * record to a request. There is deliberately nowhere to put a message body,
 * an attachment, a capability argument or a result payload, so a caller cannot
 * leak content even by trying to.
 */
data class AuditEvent(
    val pluginId: String,
    val accountId: String,
    val pairingId: String,
    val action: String,
    val outcome: AuditOutcome,
    val correlationId: String,
    val timestampUtc: String,
)

interface AuditSink {
    fun write(event: AuditEvent)
}

class InMemoryAuditSink : AuditSink {
    private val events = mutableListOf<AuditEvent>()

    override fun write(event: AuditEvent) {
        events += event
    }

    fun events(): List<AuditEvent> = events.toList()
}

/**
 * The authoritative Android audit log.
 *
 * Actions are identifiers, not free text: a plugin that tries to record its
 * payload under `action` gets a redacted record instead. That is what keeps the
 * audit log useful as evidence without turning it into a second copy of the
 * user's data.
 */
class AndroidAuditStore(
    private val sink: AuditSink = InMemoryAuditSink(),
    private val clock: () -> Instant = { Instant.now() },
) {
    companion object {
        private val ACTION_PATTERN = Regex("^[a-z][a-z0-9]*(?:\\.[a-z0-9-]+)*$")
        private const val MAX_ACTION_LENGTH = 64
        private const val REDACTED = "redacted"

        /**
         * Protocol timestamps must keep their milliseconds: `ISO_INSTANT`
         * silently drops the fraction when it is zero, producing a preimage the
         * Gateway cannot reproduce.
         */
        private val TIMESTAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'")
                .withZone(ZoneOffset.UTC)
    }

    fun record(
        pluginId: String,
        accountId: String,
        pairingId: String,
        action: String,
        outcome: AuditOutcome,
        correlationId: String,
    ): AuditEvent {
        val event = AuditEvent(
            pluginId = pluginId,
            accountId = accountId,
            pairingId = pairingId,
            action = sanitiseAction(action),
            outcome = outcome,
            correlationId = correlationId,
            timestampUtc = TIMESTAMP.format(clock()),
        )
        sink.write(event)
        return event
    }

    /**
     * Renders one record with exactly the permitted fields. Any value that does
     * not look like an identifier is replaced, so a malformed action cannot
     * smuggle content into the log through the renderer.
     */
    fun render(event: AuditEvent): String = listOf(
        "ts=" + event.timestampUtc,
        "plugin=" + sanitiseToken(event.pluginId),
        "account=" + sanitiseToken(event.accountId),
        "pairing=" + sanitiseToken(event.pairingId),
        "action=" + sanitiseAction(event.action),
        "outcome=" + event.outcome.name,
        "correlation=" + sanitiseToken(event.correlationId),
    ).joinToString(" ")

    private fun sanitiseAction(action: String): String =
        if (action.length <= MAX_ACTION_LENGTH && ACTION_PATTERN.matches(action)) action else REDACTED

    private fun sanitiseToken(value: String): String =
        if (value.isEmpty() || value.length > 128 || value.any { it == ' ' || it == '\n' || it == '\r' }) {
            REDACTED
        } else {
            value
        }
}
