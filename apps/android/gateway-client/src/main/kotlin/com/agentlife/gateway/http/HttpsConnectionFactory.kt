package com.agentlife.gateway.http

import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * The single owned outbound transport surface of the mobile app.
 *
 * Direct HTTPS + SSE is the default transport (see the architecture spec);
 * HTTP is permitted for local developer setups.
 */
class HttpsConnectionFactory {

    /**
     * Opens a connection for a given HTTP or HTTPS URL.
     *
     * Pins are carried alongside HTTPS connections and enforced by
     * [SpkiPinning.verify] once the handshake has completed; an empty pin set
     * means the caller has not pinned this profile and relies on system trust.
     */
    fun open(url: URL): HttpURLConnection {
        require(url.protocol == "https" || url.protocol == "http") {
            "gateway transport requires http or https, got ${url.protocol}"
        }
        val connection = url.openConnection() as HttpURLConnection
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
