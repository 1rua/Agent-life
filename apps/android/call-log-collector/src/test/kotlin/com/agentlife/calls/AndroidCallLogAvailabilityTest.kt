package com.agentlife.calls

import com.agentlife.capability.CapabilityAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AndroidCallLogAvailabilityTest {
    @Test
    fun current_stops_at_the_first_unavailable_gate_and_re_reads_each_seam() {
        assertAvailability(
            expected = CapabilityAvailability.DISABLED,
            localEnabled = false,
            providerAvailable = true,
            permissionGranted = true,
        )
        assertAvailability(
            expected = CapabilityAvailability.PLATFORM_UNSUPPORTED,
            localEnabled = true,
            providerAvailable = false,
            permissionGranted = true,
        )
        assertAvailability(
            expected = CapabilityAvailability.PERMISSION_REQUIRED,
            localEnabled = true,
            providerAvailable = true,
            permissionGranted = false,
        )
    }

    @Test
    fun current_maps_security_probe_to_permission_required_and_other_probe_failure_to_platform_unsupported() {
        assertAvailability(
            expected = CapabilityAvailability.PERMISSION_REQUIRED,
            localEnabled = true,
            providerAvailable = true,
            permissionGranted = true,
            probe = { throw SecurityException("private provider denial") },
        )
        assertAvailability(
            expected = CapabilityAvailability.PLATFORM_UNSUPPORTED,
            localEnabled = true,
            providerAvailable = true,
            permissionGranted = true,
            probe = { throw IllegalStateException("private provider failure") },
        )
    }

    @Test
    fun current_maps_a_real_reader_query_security_exception_to_permission_required_without_provider_details() {
        val reader = AndroidCallLogReader { _, _, _, _, _, _ ->
            throw SecurityException("provider exposed 15551234567 at 1700000000000")
        }
        val availability = AndroidCallLogAvailability(
            localEnabled = { true },
            providerAvailable = { true },
            permissionGranted = { true },
            probe = reader::probe,
        )

        assertEquals(CapabilityAvailability.PERMISSION_REQUIRED, availability.current())
        val failure = assertThrows(CallLogPermissionRequiredException::class.java) { reader.probe() }
        assertEquals("CALL_LOG_PERMISSION_REQUIRED", failure.message)
        assertNull(failure.cause)
        assertFalse(failure.message!!.contains("15551234567"))
        assertFalse(failure.message!!.contains("1700000000000"))
    }

    @Test
    fun current_reports_ready_only_after_ordered_probe_and_never_caches() {
        val calls = mutableListOf<String>()
        val availability = AndroidCallLogAvailability(
            localEnabled = { calls += "enabled"; true },
            providerAvailable = { calls += "provider"; true },
            permissionGranted = { calls += "permission"; true },
            probe = { calls += "probe" },
        )

        assertEquals(CapabilityAvailability.READY, availability.current())
        assertEquals(CapabilityAvailability.READY, availability.current())
        assertEquals(
            listOf("enabled", "provider", "permission", "probe", "enabled", "provider", "permission", "probe"),
            calls,
        )
    }

    private fun assertAvailability(
        expected: CapabilityAvailability,
        localEnabled: Boolean,
        providerAvailable: Boolean,
        permissionGranted: Boolean,
        probe: () -> Unit = {},
    ) {
        val calls = mutableListOf<String>()
        val availability = AndroidCallLogAvailability(
            localEnabled = { calls += "enabled"; localEnabled },
            providerAvailable = { calls += "provider"; providerAvailable },
            permissionGranted = { calls += "permission"; permissionGranted },
            probe = { calls += "probe"; probe() },
        )

        assertEquals(expected, availability.current())
        val expectedCalls = when (expected) {
            CapabilityAvailability.DISABLED -> listOf("enabled")
            CapabilityAvailability.PLATFORM_UNSUPPORTED ->
                if (providerAvailable) listOf("enabled", "provider", "permission", "probe") else listOf("enabled", "provider")
            CapabilityAvailability.PERMISSION_REQUIRED ->
                if (permissionGranted) listOf("enabled", "provider", "permission", "probe") else listOf("enabled", "provider", "permission")
            CapabilityAvailability.READY -> listOf("enabled", "provider", "permission", "probe")
        }
        assertEquals(expectedCalls, calls)
    }
}
