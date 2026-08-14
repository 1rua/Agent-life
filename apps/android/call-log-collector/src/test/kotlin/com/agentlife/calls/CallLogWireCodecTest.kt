package com.agentlife.calls

import com.agentlife.capability.CallDirection
import com.agentlife.capability.CallNumberPresentation
import com.agentlife.capability.CallsMetadata
import com.agentlife.capability.CallsPayload
import com.agentlife.capability.normalizeCallCounterpartyNumber
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class CallLogWireCodecTest {
    private val codec: CallLogEventCodec = DeterministicCallLogEventCodec()
    private val record = CallsPayload(
        CallsMetadata("call:42", 1_700_000_000_999, CallDirection.INCOMING, 1_700_000_000_000, 1_700_000_060_000, 60, CallNumberPresentation.ALLOWED),
        normalizeCallCounterpartyNumber("+15551234567", CallNumberPresentation.ALLOWED, CallLogTestFixtures.readScope(CallLogTestFixtures.policy(counterpartyAccess = com.agentlife.capability.CallCounterpartyAccess.NUMBER))),
    )

    @Test fun `encodes released fixture in frozen key order`() {
        val expected = fixture("call-record-released.json")
        assertArrayEquals(expected, codec.encode("call:7:42", record, 7u, 9u))
    }

    @Test fun `decodes only recovery facts and redacts diagnostics`() {
        val decoded = codec.decode(fixture("call-record-released.json"))
        assertEquals("call:42", decoded.recordId)
        assertEquals(7uL, decoded.sourceEpoch)
        assertEquals(CallLogCursor(1_700_000_000_000, 42), decoded.cursor)
        assertEquals(9uL, decoded.captureRevision)
        assertEquals(9uL, decoded.policyRevision)
        require(!decoded.toString().contains("42"))
    }

    @Test fun `rejects duplicate reordered trailing and malformed wire`() {
        val wire = String(fixture("call-record-released.json"), Charsets.UTF_8)
        rejects(wire.replaceFirst("\"kind\":\"upsert\",", "\"kind\":\"upsert\",\"kind\":\"upsert\","))
        rejects(wire.replaceFirst("\"direction\":\"incoming\",", "\"direction\":\"incoming\",\"direction\":\"incoming\","))
        rejects(wire.replaceFirst("\"kind\":\"upsert\",\"record_id\":\"call:42\"", "\"record_id\":\"call:42\",\"kind\":\"upsert\""))
        rejects("$wire ")
        rejects(wire.replace("\"source_epoch\":\"7\"", "\"source_epoch\":\"07\""))
        rejects(wire.replace("\"direction\":\"incoming\"", "\"direction\":\"incoming\\q\""))
        rejects(wire.replace("\"direction\":\"incoming\"", "\"direction\":\"\\uD800\""))
        rejects(wire.replace("\"record_revision\":\"1\"", "\"record_revision\":\"2\""))
        rejects(wire.replace("\"record_id\":\"call:42\"", "\"record_id\":\"call:43\""))
        rejects(wire.replace("\"value\":\"+15551234567\"", "\"value\":\"${"x".repeat(257)}\""))
    }

    @Test fun `proves the shared withheld fixture and rejects its repository newline`() {
        val withheld = CallsPayload(
            CallsMetadata("call:43", 1_700_000_100_999, CallDirection.MISSED, 1_700_000_100_000, 1_700_000_100_000, 0, CallNumberPresentation.RESTRICTED),
            normalizeCallCounterpartyNumber("+15551234567", CallNumberPresentation.RESTRICTED, CallLogTestFixtures.readScope()),
        )
        assertArrayEquals(fixture("call-record-withheld.json"), codec.encode("call:8:43", withheld, 8uL, 10uL))
        val raw = rawFixture("call-record-withheld.json")
        if (raw.lastOrNull() == '\n'.code.toByte()) rejects(raw.toString(Charsets.UTF_8))
    }

    private fun rejects(value: String) {
        try { codec.decode(value.toByteArray()) ; fail("expected strict decode failure") } catch (_: CallLogWireFormatException) { }
    }

    private fun fixture(name: String): ByteArray {
        var directory = File(System.getProperty("user.dir") ?: error("user.dir is missing"))
        while (!File(directory, "mvp-contract/fixtures/v1/$name").isFile) directory = directory.parentFile ?: error("fixture not found")
        val bytes = rawFixture(name)
        return if (bytes.lastOrNull() == '\n'.code.toByte()) bytes.dropLast(1).toByteArray() else bytes
    }

    private fun rawFixture(name: String): ByteArray {
        var directory = File(System.getProperty("user.dir") ?: error("user.dir is missing"))
        while (!File(directory, "mvp-contract/fixtures/v1/$name").isFile) directory = directory.parentFile ?: error("fixture not found")
        return File(directory, "mvp-contract/fixtures/v1/$name").readBytes()
    }
}
