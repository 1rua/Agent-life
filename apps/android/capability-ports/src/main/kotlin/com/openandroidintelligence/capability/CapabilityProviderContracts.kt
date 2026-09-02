package com.openandroidintelligence.capability

import com.openandroidintelligence.core.model.NotificationFieldAccess
import kotlinx.coroutines.flow.Flow

/**
 * Content is deliberately not represented as a nullable string.  A missing
 * content grant and an empty piece of content are different states, and only
 * the normalizer can construct the released state.
 */
sealed interface NormalizedContent<out T> {
    data object Withheld : NormalizedContent<Nothing>

    data class Released<T> internal constructor(val value: T) : NormalizedContent<T> {
        override fun toString(): String = "Released(<redacted>)"
    }
}

/** A provider only receives this after an on-demand access object is checked. */
data class AuthorizedReadScope internal constructor(
    val capability: MobileDataCapability,
    val filter: CapabilityFilter,
    val policyRevision: ULong,
    internal val contentDisclosureAllowed: Boolean,
)

/** A provider only receives this after an auto-send access object is checked. */
data class AuthorizedAutoSendScope internal constructor(
    val capability: MobileDataCapability,
    val filter: CapabilityFilter,
    val policyRevision: ULong,
    internal val contentDisclosureAllowed: Boolean,
)

/**
 * Converts an internally minted access token into the narrow scope accepted by
 * a typed provider.  A provider must never read directly from a request.
 */
fun AuthorizedOnDemandRequest.requireReadScope(
    capability: MobileDataCapability,
): AuthorizedReadScope {
    val request = request
    require(request.mode == DataSyncMode.ON_DEMAND) { "on-demand scope requires ON_DEMAND mode" }
    require(request.capability == capability) { "authorized request capability mismatch" }
    require(request.filter.capability == capability) { "authorized filter capability mismatch" }
    require(request.policyRevision == grantRevision) { "authorized request policy revision is stale" }
    return AuthorizedReadScope(
        capability = capability,
        filter = request.filter,
        policyRevision = request.policyRevision,
        contentDisclosureAllowed = request.filter.allowsContentDisclosure(),
    )
}

fun AuthorizedAutoSendSubscription.requireAutoSendScope(
    capability: MobileDataCapability,
): AuthorizedAutoSendScope {
    val request = request
    require(request.mode == DataSyncMode.AUTO_SEND) { "auto-send scope requires AUTO_SEND mode" }
    require(request.capability == capability) { "authorized subscription capability mismatch" }
    require(request.filter.capability == capability) { "authorized subscription filter capability mismatch" }
    require(request.policyRevision == grantRevision) { "authorized subscription policy revision is stale" }
    return AuthorizedAutoSendScope(
        capability = capability,
        filter = request.filter,
        policyRevision = request.policyRevision,
        contentDisclosureAllowed = request.filter.allowsContentDisclosure(),
    )
}

private fun CapabilityFilter.allowsContentDisclosure(): Boolean = when (this) {
    CapabilityFilter.Sms -> true
    is CapabilityFilter.Notifications -> fieldAccess == NotificationFieldAccess.CONTENT
    else -> false
}

/**
 * The only normalizer that may release raw provider content. SMS and
 * notification content access are the reviewed filters that may release it;
 * all other sealed filters remain withheld by default.
 */
@JvmName("normalizeStringContentRead")
fun normalizeContent(rawContent: String?, scope: AuthorizedReadScope): NormalizedContent<String> = when {
    !scope.contentDisclosureAllowed -> NormalizedContent.Withheld
    rawContent != null -> NormalizedContent.Released(rawContent)
    scope.capability == MobileDataCapability.SMS -> NormalizedContent.Released("")
    else -> NormalizedContent.Withheld
}

@JvmName("normalizeStringContentAutoSend")
fun normalizeContent(rawContent: String?, scope: AuthorizedAutoSendScope): NormalizedContent<String> = when {
    !scope.contentDisclosureAllowed -> NormalizedContent.Withheld
    rawContent != null -> NormalizedContent.Released(rawContent)
    scope.capability == MobileDataCapability.SMS -> NormalizedContent.Released("")
    else -> NormalizedContent.Withheld
}

fun <T> normalizeContent(
    rawContent: T?,
    scope: AuthorizedReadScope,
): NormalizedContent<T> =
    if (rawContent != null && scope.contentDisclosureAllowed) {
        NormalizedContent.Released(rawContent)
    } else {
        NormalizedContent.Withheld
    }

fun <T> normalizeContent(
    rawContent: T?,
    scope: AuthorizedAutoSendScope,
): NormalizedContent<T> =
    if (rawContent != null && scope.contentDisclosureAllowed) {
        NormalizedContent.Released(rawContent)
    } else {
        NormalizedContent.Withheld
    }

/** Shared shape for non-content metadata emitted by a future provider. */
sealed interface CapabilityMetadata {
    val recordId: String
    val observedAtEpochMs: Long
}

internal fun requireMetadata(recordId: String, observedAtEpochMs: Long) {
    require(recordId.isNotBlank()) { "record ID must not be blank" }
    require(observedAtEpochMs >= 0) { "observed time must not be negative" }
}

data class ContactsMetadata(
    override val recordId: String,
    override val observedAtEpochMs: Long,
) : CapabilityMetadata {
    init { requireMetadata(recordId, observedAtEpochMs) }
}

data class ClipboardMetadata(
    override val recordId: String,
    override val observedAtEpochMs: Long,
) : CapabilityMetadata {
    init { requireMetadata(recordId, observedAtEpochMs) }
}

data class LocationMetadata(
    override val recordId: String,
    override val observedAtEpochMs: Long,
    val accuracyMeters: Double?,
) : CapabilityMetadata {
    init {
        requireMetadata(recordId, observedAtEpochMs)
        require(accuracyMeters == null || accuracyMeters >= 0) { "location accuracy must not be negative" }
    }
}

data class HealthMetadata(
    override val recordId: String,
    override val observedAtEpochMs: Long,
    val dataType: String,
) : CapabilityMetadata {
    init {
        requireMetadata(recordId, observedAtEpochMs)
        require(dataType.isNotBlank()) { "health data type must not be blank" }
    }
}

data class SensorsMetadata(
    override val recordId: String,
    override val observedAtEpochMs: Long,
    val sensorType: String,
) : CapabilityMetadata {
    init {
        requireMetadata(recordId, observedAtEpochMs)
        require(sensorType.isNotBlank()) { "sensor type must not be blank" }
    }
}

data class CalendarMetadata(
    override val recordId: String,
    override val observedAtEpochMs: Long,
    val startsAtEpochMs: Long,
    val endsAtEpochMs: Long,
) : CapabilityMetadata {
    init {
        requireMetadata(recordId, observedAtEpochMs)
        require(startsAtEpochMs >= 0 && endsAtEpochMs >= startsAtEpochMs) {
            "calendar event range must be ordered"
        }
    }
}

data class AlarmsMetadata(
    override val recordId: String,
    override val observedAtEpochMs: Long,
    val scheduledAtEpochMs: Long,
) : CapabilityMetadata {
    init {
        requireMetadata(recordId, observedAtEpochMs)
        require(scheduledAtEpochMs >= 0) { "alarm time must not be negative" }
    }
}

data class CurrentWindowMetadata(
    override val recordId: String,
    override val observedAtEpochMs: Long,
    val packageName: String,
) : CapabilityMetadata {
    init {
        requireMetadata(recordId, observedAtEpochMs)
        require(packageName.isNotBlank()) { "window package name must not be blank" }
    }
}

data class ScreenContentMetadata(
    override val recordId: String,
    override val observedAtEpochMs: Long,
    val widthPixels: Int,
    val heightPixels: Int,
) : CapabilityMetadata {
    init {
        requireMetadata(recordId, observedAtEpochMs)
        require(widthPixels > 0 && heightPixels > 0) { "screen dimensions must be positive" }
    }
}

/**
 * A screen frame is mutable at the JVM boundary. Keep an owned copy here so a
 * future adapter cannot mutate a released frame after authorization.
 */
class ScreenContentSnapshot private constructor(private val bytes: ByteArray) {
    fun copyBytes(): ByteArray = bytes.copyOf()

    companion object {
        internal fun copyOf(bytes: ByteArray): ScreenContentSnapshot =
            ScreenContentSnapshot(bytes.copyOf())
    }
}

data class SmsPayload(val metadata: SmsMetadata, val content: NormalizedContent<String>) : CapabilityPayload
data class ContactsPayload(val metadata: ContactsMetadata, val content: NormalizedContent<String>) : CapabilityPayload
data class ClipboardPayload(val metadata: ClipboardMetadata, val content: NormalizedContent<String>) : CapabilityPayload
data class LocationPayload(val metadata: LocationMetadata, val content: NormalizedContent<String>) : CapabilityPayload
data class HealthPayload(val metadata: HealthMetadata, val content: NormalizedContent<String>) : CapabilityPayload
data class SensorsPayload(val metadata: SensorsMetadata, val content: NormalizedContent<String>) : CapabilityPayload
data class CalendarPayload(val metadata: CalendarMetadata, val content: NormalizedContent<String>) : CapabilityPayload
data class AlarmsPayload(val metadata: AlarmsMetadata, val content: NormalizedContent<String>) : CapabilityPayload
data class CurrentWindowPayload(val metadata: CurrentWindowMetadata, val content: NormalizedContent<String>) : CapabilityPayload
data class ScreenContentPayload(
    val metadata: ScreenContentMetadata,
    val content: NormalizedContent<ScreenContentSnapshot>,
) : CapabilityPayload

/** Raw values are internal so a provider cannot accidentally publish one. */
internal data class RawProviderRecord<M : CapabilityMetadata, C>(
    val metadata: M,
    val content: C?,
)

/** Every platform adapter must normalize raw data before it reaches a port result. */
fun interface CapabilityPayloadNormalizer<Raw, Payload : CapabilityPayload> {
    fun normalize(raw: Raw, scope: AuthorizedReadScope): Payload
}

internal object SmsPayloadNormalizer : CapabilityPayloadNormalizer<RawProviderRecord<SmsMetadata, String>, SmsPayload> {
    override fun normalize(raw: RawProviderRecord<SmsMetadata, String>, scope: AuthorizedReadScope): SmsPayload =
        SmsPayload(raw.metadata, normalizeContent(raw.content, scope))
}

internal object CallsPayloadNormalizer : CapabilityPayloadNormalizer<RawProviderRecord<CallsMetadata, String>, CallsPayload> {
    override fun normalize(raw: RawProviderRecord<CallsMetadata, String>, scope: AuthorizedReadScope): CallsPayload =
        CallsPayload(
            raw.metadata,
            normalizeCallCounterpartyNumber(raw.content, raw.metadata.numberPresentation, scope),
        )
}

internal object ContactsPayloadNormalizer : CapabilityPayloadNormalizer<RawProviderRecord<ContactsMetadata, String>, ContactsPayload> {
    override fun normalize(raw: RawProviderRecord<ContactsMetadata, String>, scope: AuthorizedReadScope): ContactsPayload =
        ContactsPayload(raw.metadata, normalizeContent(raw.content, scope))
}

internal object ClipboardPayloadNormalizer : CapabilityPayloadNormalizer<RawProviderRecord<ClipboardMetadata, String>, ClipboardPayload> {
    override fun normalize(raw: RawProviderRecord<ClipboardMetadata, String>, scope: AuthorizedReadScope): ClipboardPayload =
        ClipboardPayload(raw.metadata, normalizeContent(raw.content, scope))
}

internal object LocationPayloadNormalizer : CapabilityPayloadNormalizer<RawProviderRecord<LocationMetadata, String>, LocationPayload> {
    override fun normalize(raw: RawProviderRecord<LocationMetadata, String>, scope: AuthorizedReadScope): LocationPayload =
        LocationPayload(raw.metadata, normalizeContent(raw.content, scope))
}

internal object HealthPayloadNormalizer : CapabilityPayloadNormalizer<RawProviderRecord<HealthMetadata, String>, HealthPayload> {
    override fun normalize(raw: RawProviderRecord<HealthMetadata, String>, scope: AuthorizedReadScope): HealthPayload =
        HealthPayload(raw.metadata, normalizeContent(raw.content, scope))
}

internal object SensorsPayloadNormalizer : CapabilityPayloadNormalizer<RawProviderRecord<SensorsMetadata, String>, SensorsPayload> {
    override fun normalize(raw: RawProviderRecord<SensorsMetadata, String>, scope: AuthorizedReadScope): SensorsPayload =
        SensorsPayload(raw.metadata, normalizeContent(raw.content, scope))
}

internal object CalendarPayloadNormalizer : CapabilityPayloadNormalizer<RawProviderRecord<CalendarMetadata, String>, CalendarPayload> {
    override fun normalize(raw: RawProviderRecord<CalendarMetadata, String>, scope: AuthorizedReadScope): CalendarPayload =
        CalendarPayload(raw.metadata, normalizeContent(raw.content, scope))
}

internal object AlarmsPayloadNormalizer : CapabilityPayloadNormalizer<RawProviderRecord<AlarmsMetadata, String>, AlarmsPayload> {
    override fun normalize(raw: RawProviderRecord<AlarmsMetadata, String>, scope: AuthorizedReadScope): AlarmsPayload =
        AlarmsPayload(raw.metadata, normalizeContent(raw.content, scope))
}

internal object CurrentWindowPayloadNormalizer : CapabilityPayloadNormalizer<RawProviderRecord<CurrentWindowMetadata, String>, CurrentWindowPayload> {
    override fun normalize(raw: RawProviderRecord<CurrentWindowMetadata, String>, scope: AuthorizedReadScope): CurrentWindowPayload =
        CurrentWindowPayload(raw.metadata, normalizeContent(raw.content, scope))
}

internal object ScreenContentPayloadNormalizer : CapabilityPayloadNormalizer<RawProviderRecord<ScreenContentMetadata, ByteArray>, ScreenContentPayload> {
    override fun normalize(raw: RawProviderRecord<ScreenContentMetadata, ByteArray>, scope: AuthorizedReadScope): ScreenContentPayload {
        val snapshot = if (raw.content == null) null else ScreenContentSnapshot.copyOf(raw.content)
        return ScreenContentPayload(raw.metadata, normalizeContent(snapshot, scope))
    }
}

/**
 * A typed provider receives a checked scope, never an arbitrary Agent request.
 * The interfaces only describe future adapters; this module contains none.
 */
interface TypedCapabilityProvider<T : CapabilityPayload> {
    val capability: MobileDataCapability

    suspend fun read(scope: AuthorizedReadScope): CapabilityReadResult<T>

    fun observeAutoSend(scope: AuthorizedAutoSendScope): Flow<CapabilityEvent<T>>
}

interface SmsCapabilityProvider : TypedCapabilityProvider<SmsPayload> {
    override val capability: MobileDataCapability get() = MobileDataCapability.SMS
}

interface CallsCapabilityProvider : TypedCapabilityProvider<CallsPayload> {
    override val capability: MobileDataCapability get() = MobileDataCapability.CALLS
}

interface ContactsCapabilityProvider : TypedCapabilityProvider<ContactsPayload> {
    override val capability: MobileDataCapability get() = MobileDataCapability.CONTACTS
}

interface ClipboardCapabilityProvider : TypedCapabilityProvider<ClipboardPayload> {
    override val capability: MobileDataCapability get() = MobileDataCapability.CLIPBOARD
}

interface LocationCapabilityProvider : TypedCapabilityProvider<LocationPayload> {
    override val capability: MobileDataCapability get() = MobileDataCapability.LOCATION
}

interface HealthCapabilityProvider : TypedCapabilityProvider<HealthPayload> {
    override val capability: MobileDataCapability get() = MobileDataCapability.HEALTH
}

interface SensorsCapabilityProvider : TypedCapabilityProvider<SensorsPayload> {
    override val capability: MobileDataCapability get() = MobileDataCapability.SENSORS
}

interface CalendarCapabilityProvider : TypedCapabilityProvider<CalendarPayload> {
    override val capability: MobileDataCapability get() = MobileDataCapability.CALENDAR
}

interface AlarmsCapabilityProvider : TypedCapabilityProvider<AlarmsPayload> {
    override val capability: MobileDataCapability get() = MobileDataCapability.ALARMS
}

interface CurrentWindowCapabilityProvider : TypedCapabilityProvider<CurrentWindowPayload> {
    override val capability: MobileDataCapability get() = MobileDataCapability.CURRENT_WINDOW
}

interface ScreenContentCapabilityProvider : TypedCapabilityProvider<ScreenContentPayload> {
    override val capability: MobileDataCapability get() = MobileDataCapability.SCREEN_CONTENT
}
