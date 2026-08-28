package com.agentlife.gateway.http

import java.io.IOException
import java.security.MessageDigest
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection

/**
 * SPKI pin enforcement.
 *
 * Verification runs against the certificates the connection actually negotiated,
 * after `connect()`. A pin is a set of SPKI SHA-256 hashes and any certificate
 * in the chain may match any pin.
 *
 * A profile that declares a pin must never be able to degrade to "any
 * system-trusted certificate": that is what makes a fingerprint change on a
 * pinned account a hard failure rather than a warning.
 *
 * The certificate-level entry point exists so the rule can be proven against
 * real certificates without a network round trip.
 */
object SpkiPinning {

    fun verify(connection: HttpsURLConnection, pins: Set<String>) {
        verify(connection.serverCertificates, pins)
    }

    fun verify(certificates: Array<out Certificate>, pins: Set<String>) {
        if (pins.isEmpty()) return

        for (certificate in certificates) {
            if (certificate !is X509Certificate) continue
            if (spkiSha256Base64(certificate) in pins) return
        }
        throw IOException(
            "PIN_MISMATCH: none of ${certificates.size} certificate(s) matched ${pins.size} pin(s)",
        )
    }

    fun spkiSha256Base64(certificate: X509Certificate): String {
        val spki = MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded)
        return java.util.Base64.getEncoder().encodeToString(spki)
    }
}
