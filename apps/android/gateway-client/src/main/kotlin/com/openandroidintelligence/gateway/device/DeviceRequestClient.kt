package com.openandroidintelligence.gateway.device

/**
 * What the server returned when the device claimed a request.
 *
 * Android must not choose or rewrite any of these fields; the Gateway verifies
 * every binding against its own receipt record.
 */
data class ClaimReceipt(
    val claimId: String,
    val requestId: String,
    val accountId: String,
    val deviceId: String,
    val pairingGeneration: Int,
    val grantRevision: Int,
)

sealed class DeviceRequestResult {
    data class Succeeded(val payload: Map<String, Any?>) : DeviceRequestResult()
    data class Failed(val payload: Map<String, Any?>) : DeviceRequestResult()
    data class Denied(val payload: Map<String, Any?>) : DeviceRequestResult()
    data class Cancelled(val payload: Map<String, Any?>) : DeviceRequestResult()
    object OutcomeUnknown : DeviceRequestResult()
}

interface DeviceRequestTransport {
    fun claim(requestId: String, grantRevision: Int): ClaimReceipt

    fun submitResult(requestId: String, body: Map<String, Any?>)
}

/**
 * Claim-then-result device request execution.
 *
 * The device must hold a server-issued receipt before it performs any side
 * effect, and the result may carry only what the receipt supplied. Anything
 * else would let the device write its own identity into the outcome.
 */
class DeviceRequestClient(private val transport: DeviceRequestTransport) {

    private val claimsByRequest = LinkedHashMap<String, ClaimReceipt>()
    private val issuedByClaimId = LinkedHashMap<String, ClaimReceipt>()

    fun claim(requestId: String, grantRevision: Int): ClaimReceipt {
        val receipt = transport.claim(requestId, grantRevision)
        // Idempotent re-claim returns the same receipt; recording it again is
        // therefore a no-op rather than a conflict.
        claimsByRequest[requestId] = receipt
        issuedByClaimId[receipt.claimId] = receipt
        return receipt
    }

    fun submitResult(claim: ClaimReceipt, result: DeviceRequestResult) {
        // A receipt is only usable if this client actually obtained it; a
        // hand-built one is not merely mismatched, it was never issued.
        val issued = issuedByClaimId[claim.claimId]
            ?: throw IllegalArgumentException("NOT_CLAIMED:${claim.claimId}")
        if (issued != claim) {
            throw IllegalArgumentException("RECEIPT_BINDING_MISMATCH:${claim.claimId}")
        }
        if (claimsByRequest[claim.requestId] != claim) {
            throw IllegalArgumentException("RECEIPT_BINDING_MISMATCH:${claim.requestId}")
        }

        transport.submitResult(
            claim.requestId,
            mapOf(
                "claimId" to claim.claimId,
                "grantRevision" to claim.grantRevision,
                "result" to resultName(result),
                "payload" to payloadOf(result),
            ),
        )
    }

    /**
     * Maps a stream event onto the local state, without ever inventing an
     * outcome. `device.request.cancel.requested` is an intent: only an unclaimed
     * pending request may go straight to `cancelled`, a claimed one waits for a
     * trusted result.
     */
    fun stateAfterEvent(eventName: String, wasClaimed: Boolean): String? = when (eventName) {
        "device.request.cancel.requested" -> if (wasClaimed) "cancel_requested" else "cancelled"
        else -> null
    }

    private fun resultName(result: DeviceRequestResult): String = when (result) {
        is DeviceRequestResult.Succeeded -> "result_succeeded"
        is DeviceRequestResult.Failed -> "result_failed"
        is DeviceRequestResult.Denied -> "result_denied"
        is DeviceRequestResult.Cancelled -> "result_cancelled"
        DeviceRequestResult.OutcomeUnknown -> "result_outcome_unknown"
    }

    private fun payloadOf(result: DeviceRequestResult): Map<String, Any?> = when (result) {
        is DeviceRequestResult.Succeeded -> result.payload
        is DeviceRequestResult.Failed -> result.payload
        is DeviceRequestResult.Denied -> result.payload
        is DeviceRequestResult.Cancelled -> result.payload
        DeviceRequestResult.OutcomeUnknown -> emptyMap()
    }
}
