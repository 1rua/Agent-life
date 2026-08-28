package com.agentlife.gateway.http

import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * The single owned outbound HTTPS surface of the mobile app.
 *
 * Direct HTTPS + SSE is the default transport (see the architecture spec);
 * every other module reaches the Gateway through this module instead of
 * opening its own connection.
 */
internal class HttpsConnectionFactory {

    fun open(url: URL): HttpsURLConnection {
        require(url.protocol == "https") { "gateway transport is https-only, got ${url.protocol}" }
        val connection = url.openConnection() as HttpsURLConnection
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
