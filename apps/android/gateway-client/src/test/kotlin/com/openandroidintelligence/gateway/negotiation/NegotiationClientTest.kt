package com.openandroidintelligence.gateway.negotiation

import com.openandroidintelligence.gateway.http.GatewayResponse
import com.openandroidintelligence.gateway.http.SignedGatewayRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class NegotiationClientTest {

    @Test
    fun readsNegotiationFieldsFromTheProtocolDataEnvelope() = runBlocking {
        val client = NegotiationClient(
            execute = { _: SignedGatewayRequest ->
                GatewayResponse(
                    status = 200,
                    headers = emptyList(),
                    body = """
                        {
                          "requestId":"req-1",
                          "correlationId":"cor-1",
                          "protocol":"2.0",
                          "data":{
                            "protocol":{"major":2,"minor":0},
                            "features":{
                              "messages":"chat-v1",
                              "attachments":"staged-sha256-v1",
                              "events":"sse-cursor-v1",
                              "deviceRequests":"risk-queue-v1"
                            },
                            "limits":{
                              "maxSingleAttachmentBytes":26214400,
                              "maxMessageAttachmentBytes":52428800,
                              "allowedMediaTypes":["image/png"],
                              "attachmentTtlSeconds":3600,
                              "eventRetentionSeconds":86400
                            },
                            "gatewayIdentity":{
                              "deploymentId":"deploy-1",
                              "tlsSpkiSha256":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                            }
                          }
                        }
                    """.trimIndent().toByteArray(),
                )
            },
            installationId = "install-1",
            appVersion = "2.0.0",
            platformApi = 35,
        )

        val result = client.negotiate("neg-1")

        assertEquals(2, result.protocolMajor)
        assertEquals("deploy-1", result.deploymentId)
        assertEquals(
            "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            result.tlsSpkiSha256,
        )
        assertEquals(26214400L, result.limits.maxSingleAttachmentBytes)
        assertEquals(listOf("image/png"), result.limits.allowedMediaTypes)
    }
}
