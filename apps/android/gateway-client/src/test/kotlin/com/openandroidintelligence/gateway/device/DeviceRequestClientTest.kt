package com.openandroidintelligence.gateway.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A device request may only be executed once it has been claimed, and the result
 * may only carry the identity the server bound into the receipt. Android must
 * never invent or widen those bindings, and the Gateway rejects any mismatch.
 */
class DeviceRequestClientTest {

    private fun client(
        transport: RecordingDeviceRequestTransport = RecordingDeviceRequestTransport(),
    ) = DeviceRequestClient(transport)

    private fun receipt(
        claimId: String = "claim-1",
        requestId: String = "req-1",
        accountId: String = "acct-1",
        deviceId: String = "dev-1",
        pairingGeneration: Int = 3,
        grantRevision: Int = 7,
    ) = ClaimReceipt(claimId, requestId, accountId, deviceId, pairingGeneration, grantRevision)

    @Test
    fun claimIsIssuedBeforeAnySideEffect() {
        val transport = RecordingDeviceRequestTransport()
        val client = client(transport)

        val claim = client.claim("req-1", 7)

        assertEquals("claim-1", claim.claimId)
        assertEquals(listOf("claim"), transport.calls)
    }

    @Test
    fun resultCarriesClaimIdAndGrantRevisionVerbatim() {
        val transport = RecordingDeviceRequestTransport()
        val client = client(transport)

        val claim = client.claim("req-1", 7)
        client.submitResult(claim, DeviceRequestResult.Succeeded(mapOf("count" to 1)))

        val body = transport.lastResultBody!!
        assertEquals("claim-1", body["claimId"])
        assertEquals(7, body["grantRevision"])
        assertEquals(
            "the result must not carry identity the Gateway must verify itself",
            null,
            body["accountId"],
        )
        assertEquals(null, body["deviceId"])
        assertEquals(null, body["pairingGeneration"])
    }

    @Test
    fun submittingWithoutAReceiptFailsClosed() {
        val transport = RecordingDeviceRequestTransport()
        val client = client(transport)

        val forged = receipt()
        val failure = runCatching {
            client.submitResult(forged, DeviceRequestResult.Succeeded(emptyMap()))
        }.exceptionOrNull()

        assertTrue("an unclaimed request must not accept a plain result", failure != null)
        assertTrue(failure!!.message!!.contains("NOT_CLAIMED"))
        assertTrue(transport.lastResultBody == null)
    }

    @Test
    fun receiptBoundToAnotherRequestFailsClosed() {
        val transport = RecordingDeviceRequestTransport()
        val client = client(transport)
        val claim = client.claim("req-1", 7)

        val tampered = claim.copy(requestId = "req-other")
        val failure = runCatching {
            client.submitResult(tampered, DeviceRequestResult.Succeeded(emptyMap()))
        }.exceptionOrNull()

        assertTrue(failure != null)
        assertTrue(failure!!.message!!.contains("RECEIPT_BINDING_MISMATCH"))
    }

    @Test
    fun receiptWithAlteredGrantRevisionFailsClosed() {
        val transport = RecordingDeviceRequestTransport()
        val client = client(transport)
        val claim = client.claim("req-1", 7)

        val failure = runCatching {
            client.submitResult(claim.copy(grantRevision = 8), DeviceRequestResult.Succeeded(emptyMap()))
        }.exceptionOrNull()

        assertTrue(failure != null)
        assertTrue(failure!!.message!!.contains("RECEIPT_BINDING_MISMATCH"))
    }

    @Test
    fun repeatingClaimReturnsTheSameReceipt() {
        val transport = RecordingDeviceRequestTransport()
        val client = client(transport)

        val first = client.claim("req-1", 7)
        val second = client.claim("req-1", 7)

        assertEquals("idempotent claim must return the same receipt", first, second)
        assertEquals(listOf("claim", "claim"), transport.calls)
    }

    @Test
    fun cancelIntentIsNotTreatedAsACancelledOutcome() {
        val transport = RecordingDeviceRequestTransport()
        val client = client(transport)

        val state = client.stateAfterEvent("device.request.cancel.requested", wasClaimed = true)

        assertEquals("cancel_requested", state)
        assertEquals(
            "a cancel SSE must never be turned into a fabricated terminal result",
            null,
            transport.lastResultBody,
        )
    }

    @Test
    fun unclaimedPendingCancelBecomesCancelled() {
        val client = client()

        assertEquals(
            "cancelled",
            client.stateAfterEvent("device.request.cancel.requested", wasClaimed = false),
        )
    }
}
