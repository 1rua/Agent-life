package com.openandroidintelligence.gateway.http

import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * The single owned outbound transport surface of the mobile app.
 *
 * Direct HTTPS + SSE is the only transport (see the architecture spec).
 */
class HttpsConnectionFactory {

    /**
     * Opens a connection for a given HTTPS URL.
     *
     * Pins are carried alongside HTTPS connections and enforced by
     * [SpkiPinning.verify] once the handshake has completed; an empty pin set
     * means the caller has not pinned this profile and relies on system trust.
     */
    fun open(url: URL): HttpURLConnection {
        require(url.protocol == "https") {
            "gateway transport requires https-only, got ${url.protocol}"
        }
        val connection = url.openConnection() as? HttpsURLConnection
            ?: error("gateway transport requires an HTTPS connection")
        connection.instanceFollowRedirects = false
        connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = READ_TIMEOUT_MILLIS
        return connection
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_TIMEOUT_MILLIS = 30_000
    }
}
