package com.openandroidintelligence.kernel

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

interface ObservableAuditSink : AuditSink {
    val eventsFlow: StateFlow<List<AuditEvent>>

    fun events(): List<AuditEvent>
}

class InMemoryAuditSink : ObservableAuditSink {
    private val lock = Any()
    private val _events = MutableStateFlow<List<AuditEvent>>(emptyList())
    override val eventsFlow: StateFlow<List<AuditEvent>> = _events.asStateFlow()

    override fun write(event: AuditEvent) {
        synchronized(lock) {
            _events.value = _events.value + event
        }
    }

    override fun events(): List<AuditEvent> = eventsFlow.value
}

/**
 * Private, metadata-only audit storage for the Android host.
 *
 * The sink stores encoded fields rather than rendered text so a future UI can
 * still render through [AndroidAuditStore]. Invalid lines are ignored during
 * recovery; a damaged audit file must not make the host fail to start.
 */
class PersistentAuditSink(
    private val file: File,
    private val clock: () -> Instant = { Instant.now() },
    private val retention: Duration = Duration.ofDays(30),
) : ObservableAuditSink {

    private val lock = Any()
    private val _events = MutableStateFlow(load())
    override val eventsFlow: StateFlow<List<AuditEvent>> = _events.asStateFlow()

    override fun write(event: AuditEvent) {
        synchronized(lock) {
            val cutoff = clock().minus(retention)
            val next = (_events.value + event).filterNot { isExpired(it, cutoff) }
            persist(next)
            _events.value = next
        }
    }

    override fun events(): List<AuditEvent> = eventsFlow.value

    private fun load(): List<AuditEvent> {
        if (!file.isFile) return emptyList()
        val cutoff = clock().minus(retention)
        return runCatching {
            file.readLines(StandardCharsets.UTF_8)
                .mapNotNull(AuditEventCodec::decode)
                .filterNot { isExpired(it, cutoff) }
        }.getOrDefault(emptyList())
    }

    private fun persist(events: List<AuditEvent>) {
        file.parentFile?.mkdirs()
        val bytes = events.joinToString(
            separator = "\n",
            postfix = if (events.isEmpty()) "" else "\n",
        ) { event -> AuditEventCodec.encode(event) }
            .toByteArray(StandardCharsets.UTF_8)
        val temporary = File.createTempFile("audit", ".tmp", file.parentFile)
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        try {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun isExpired(event: AuditEvent, cutoff: Instant): Boolean =
        runCatching { Instant.parse(event.timestampUtc).isBefore(cutoff) }.getOrDefault(true)
}

private object AuditEventCodec {
    private const val VERSION = "v1"
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(event: AuditEvent): String = VERSION + "|" + listOf(
        event.pluginId,
        event.accountId,
        event.pairingId,
        event.action,
        event.outcome.name,
        event.correlationId,
        event.timestampUtc,
    ).joinToString("|") { value -> encoder.encodeToString(value.toByteArray(StandardCharsets.UTF_8)) }

    fun decode(line: String): AuditEvent? {
        val fields = line.split('|')
        if (fields.size != 8) return null
        if (fields[0] != VERSION) return null
        val values = fields.drop(1).map { field ->
            runCatching { String(decoder.decode(field), StandardCharsets.UTF_8) }.getOrNull()
        }
        if (values.any { it == null }) return null
        val decoded = values.filterNotNull()
        val outcome = runCatching { AuditOutcome.valueOf(decoded[4]) }.getOrNull() ?: return null
        return AuditEvent(
            pluginId = decoded[0],
            accountId = decoded[1],
            pairingId = decoded[2],
            action = decoded[3],
            outcome = outcome,
            correlationId = decoded[5],
            timestampUtc = decoded[6],
        )
    }
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
            pluginId = sanitiseToken(pluginId),
            accountId = sanitiseToken(accountId),
            pairingId = sanitiseToken(pairingId),
            action = sanitiseAction(action),
            outcome = outcome,
            correlationId = sanitiseToken(correlationId),
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
