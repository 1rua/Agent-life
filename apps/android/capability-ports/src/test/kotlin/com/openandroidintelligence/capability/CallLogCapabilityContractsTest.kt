package com.openandroidintelligence.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CallLogCapabilityContractsTest {
    @Test
    fun calls_filter_rejects_empty_directions_and_authorizer_requires_exact_filter() {
        assertThrows(IllegalArgumentException::class.java) {
            CapabilityFilter.Calls(emptySet(), CallCounterpartyAccess.WITHHELD)
        }
        val grantFilter = CapabilityFilter.Calls(
            setOf(CallDirection.INCOMING, CallDirection.MISSED),
            CallCounterpartyAccess.WITHHELD,
        )
        val request = AgentDataRequest(
            "calls-read",
            MobileDataCapability.CALLS,
            DataSyncMode.ON_DEMAND,
            CapabilityFilter.Calls(setOf(CallDirection.INCOMING), CallCounterpartyAccess.WITHHELD),
            7u,
        )

        assertEquals(
            AgentRequestAuthorization.Denied(AgentRequestDenialReason.FILTER_NOT_GRANTED),
            DefaultAgentRequestAuthorizer().authorize(
                request,
                CapabilityGrant(MobileDataCapability.CALLS, grantFilter, true, false, true, 7u),
                CapabilityAvailability.READY,
            ),
        )
    }

    @Test
    fun calls_filter_canonicalizes_each_closed_direction_in_declaration_order() {
        val filter = CapabilityFilter.Calls(
            setOf(CallDirection.REJECTED, CallDirection.INCOMING, CallDirection.MISSED, CallDirection.OUTGOING),
            CallCounterpartyAccess.WITHHELD,
        )

        assertEquals(
            listOf(
                CallDirection.INCOMING,
                CallDirection.OUTGOING,
                CallDirection.MISSED,
                CallDirection.REJECTED,
            ),
            filter.canonicalDirections(),
        )
    }

    @Test
    fun call_direction_is_closed_to_the_four_supported_values() {
        assertEquals(
            listOf(
                CallDirection.INCOMING,
                CallDirection.OUTGOING,
                CallDirection.MISSED,
                CallDirection.REJECTED,
            ),
            CallDirection.entries,
        )
    }

    @Test
    fun counterparty_number_releases_only_for_number_allowed_and_non_blank() {
        val numberScope = callReadScope(CallCounterpartyAccess.NUMBER)
        val withheldScope = callReadScope(CallCounterpartyAccess.WITHHELD)

        assertEquals(
            NormalizedContent.Released("+8613800000000"),
            normalizeCallCounterpartyNumber("+8613800000000", CallNumberPresentation.ALLOWED, numberScope),
        )
        assertEquals(
            NormalizedContent.Withheld,
            normalizeCallCounterpartyNumber("+8613800000000", CallNumberPresentation.RESTRICTED, numberScope),
        )
        assertEquals(
            NormalizedContent.Withheld,
            normalizeCallCounterpartyNumber("   ", CallNumberPresentation.ALLOWED, numberScope),
        )
        assertEquals(
            NormalizedContent.Withheld,
            normalizeCallCounterpartyNumber("+8613800000000", CallNumberPresentation.ALLOWED, withheldScope),
        )
    }

    @Test
    fun counterparty_number_accepts_256_utf8_bytes_and_rejects_257() {
        val scope = callReadScope(CallCounterpartyAccess.NUMBER)
        val accepted = "a".repeat(256)
        val rejected = "a".repeat(257)

        assertEquals(
            NormalizedContent.Released(accepted),
            normalizeCallCounterpartyNumber(accepted, CallNumberPresentation.ALLOWED, scope),
        )
        assertEquals(
            NormalizedContent.Withheld,
            normalizeCallCounterpartyNumber(rejected, CallNumberPresentation.ALLOWED, scope),
        )
    }

    @Test
    fun counterparty_number_auto_send_overload_requires_a_calls_scope_and_filter() {
        val autoScope = callAutoSendScope(CallCounterpartyAccess.NUMBER)

        assertEquals(
            NormalizedContent.Released("+8613800000000"),
            normalizeCallCounterpartyNumber("+8613800000000", CallNumberPresentation.ALLOWED, autoScope),
        )
        assertThrows(IllegalArgumentException::class.java) {
            normalizeCallCounterpartyNumber(
                "+8613800000000",
                CallNumberPresentation.ALLOWED,
                AuthorizedAutoSendScope(MobileDataCapability.SMS, CapabilityFilter.Sms, 7u, true),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            normalizeCallCounterpartyNumber(
                "+8613800000000",
                CallNumberPresentation.ALLOWED,
                AuthorizedReadScope(MobileDataCapability.CALLS, CapabilityFilter.Sms, 7u, true),
            )
        }
    }

    @Test
    fun calls_payload_normalizer_uses_metadata_number_presentation() {
        val metadata = CallsMetadata(
            recordId = "call:1",
            observedAtEpochMs = 1,
            direction = CallDirection.INCOMING,
            startedAtEpochMs = 0,
            endedAtEpochMs = 0,
            durationSeconds = 0,
            numberPresentation = CallNumberPresentation.RESTRICTED,
        )

        assertEquals(
            NormalizedContent.Withheld,
            CallsPayloadNormalizer.normalize(
                RawProviderRecord(metadata, "+8613800000000"),
                callReadScope(CallCounterpartyAccess.NUMBER),
            ).counterpartyNumber,
        )
    }

    @Test
    fun call_history_policy_rejects_negative_start_zero_limit_and_over_limit() {
        assertThrows(IllegalArgumentException::class.java) { CallHistoryPolicy(fromEpochMs = -1, maxRecords = 1) }
        assertThrows(IllegalArgumentException::class.java) { CallHistoryPolicy(fromEpochMs = null, maxRecords = 0) }
        assertThrows(IllegalArgumentException::class.java) { CallHistoryPolicy(fromEpochMs = null, maxRecords = 10_001) }
    }

    @Test
    fun call_intervals_expose_only_manual_15_30_and_60_minutes() {
        assertEquals(null, CallLogSyncInterval.MANUAL.periodMs)
        assertEquals(900_000L, CallLogSyncInterval.MINUTES_15.periodMs)
        assertEquals(1_800_000L, CallLogSyncInterval.MINUTES_30.periodMs)
        assertEquals(3_600_000L, CallLogSyncInterval.MINUTES_60.periodMs)
    }

    @Test
    fun calls_metadata_accepts_positive_decimal_long_ids_and_derives_exact_end_time() {
        val metadata = CallsMetadata(
            recordId = "call:${Long.MAX_VALUE}",
            observedAtEpochMs = 1,
            direction = CallDirection.OUTGOING,
            startedAtEpochMs = 1_000,
            endedAtEpochMs = 4_000,
            durationSeconds = 3,
            numberPresentation = CallNumberPresentation.ALLOWED,
        )

        assertEquals(4_000L, metadata.endedAtEpochMs)
        listOf("42", "call:0", "call:01", "call:9223372036854775808").forEach { recordId ->
            assertThrows(IllegalArgumentException::class.java) {
                CallsMetadata(recordId, 1, CallDirection.INCOMING, 0, 0, 0, CallNumberPresentation.UNKNOWN)
            }
        }
    }

    @Test
    fun calls_metadata_rejects_inexact_or_overflowing_end_time() {
        assertThrows(IllegalArgumentException::class.java) {
            CallsMetadata("call:1", 1, CallDirection.INCOMING, 1_000, 3_999, 3, CallNumberPresentation.ALLOWED)
        }
        assertThrows(ArithmeticException::class.java) {
            CallsMetadata("call:1", 1, CallDirection.INCOMING, Long.MAX_VALUE, Long.MAX_VALUE, 1, CallNumberPresentation.ALLOWED)
        }
    }

    @Test
    fun call_diagnostics_redact_history_metadata_payload_released_content_and_event_identity() {
        val metadata = CallsMetadata(
            recordId = "call:42",
            observedAtEpochMs = 9_999,
            direction = CallDirection.INCOMING,
            startedAtEpochMs = 1_000,
            endedAtEpochMs = 4_000,
            durationSeconds = 3,
            numberPresentation = CallNumberPresentation.ALLOWED,
        )
        val payload = CallsPayload(metadata, NormalizedContent.Released("+8613800000000"))
        val event = CapabilityEvent(MobileDataCapability.CALLS, "wire-event-17", payload, 7u)

        listOf(
            CallHistoryPolicy(1_000, 10).toString(),
            metadata.toString(),
            payload.toString(),
            NormalizedContent.Released("+8613800000000").toString(),
            event.toString(),
        ).forEach { diagnostic ->
            assertEquals(false, diagnostic.contains("+8613800000000"))
            assertEquals(false, diagnostic.contains("1000"))
            assertEquals(false, diagnostic.contains("9999"))
            assertEquals(false, diagnostic.contains("42"))
            assertEquals(false, diagnostic.contains("wire-event-17"))
        }
    }

    private fun callReadScope(counterpartyAccess: CallCounterpartyAccess): AuthorizedReadScope {
        val filter = CapabilityFilter.Calls(setOf(CallDirection.INCOMING), counterpartyAccess)
        val request = AgentDataRequest(
            requestId = "call-read",
            capability = MobileDataCapability.CALLS,
            mode = DataSyncMode.ON_DEMAND,
            filter = filter,
            policyRevision = 7u,
        )
        val grant = CapabilityGrant(
            capability = MobileDataCapability.CALLS,
            filter = filter,
            onDemandEnabled = true,
            autoSendEnabled = false,
            agentMayRequest = true,
            policyRevision = 7u,
        )
        val authorization = DefaultAgentRequestAuthorizer().authorize(request, grant, CapabilityAvailability.READY)
            as AgentRequestAuthorization.Allowed
        return (authorization.access as AuthorizedOnDemandRequest).requireReadScope(MobileDataCapability.CALLS)
    }

    private fun callAutoSendScope(counterpartyAccess: CallCounterpartyAccess): AuthorizedAutoSendScope {
        val filter = CapabilityFilter.Calls(setOf(CallDirection.INCOMING), counterpartyAccess)
        val request = AgentDataRequest(
            requestId = "call-auto",
            capability = MobileDataCapability.CALLS,
            mode = DataSyncMode.AUTO_SEND,
            filter = filter,
            policyRevision = 7u,
        )
        val grant = CapabilityGrant(
            capability = MobileDataCapability.CALLS,
            filter = filter,
            onDemandEnabled = false,
            autoSendEnabled = true,
            agentMayRequest = true,
            policyRevision = 7u,
        )
        val authorization = DefaultAgentRequestAuthorizer().authorize(request, grant, CapabilityAvailability.READY)
            as AgentRequestAuthorization.Allowed
        return (authorization.access as AuthorizedAutoSendSubscription).requireAutoSendScope(MobileDataCapability.CALLS)
    }
}
