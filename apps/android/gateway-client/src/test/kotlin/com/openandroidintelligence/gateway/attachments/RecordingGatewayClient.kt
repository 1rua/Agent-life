package com.openandroidintelligence.gateway.attachments

/** Records the attachment calls so the uploader can be proven without a network. */
class RecordingGatewayClient : GatewayAttachmentTransport {

    val calls = mutableListOf<String>()

    var created: AttachmentCreateRequest? = null
        private set
    var contentUploaded: ByteArray? = null
        private set
    var contentHeaders: Map<String, String>? = null
        private set

    var commitShouldFail = false

    override suspend fun create(request: AttachmentCreateRequest): String {
        calls += "create"
        created = request
        return "att-server-1"
    }

    override suspend fun uploadContent(
        attachmentId: String,
        content: ByteArray,
        headers: Map<String, String>,
    ) {
        calls += "content"
        contentUploaded = content
        contentHeaders = headers
    }

    override suspend fun commit(attachmentId: String) {
        calls += "commit"
        if (commitShouldFail) throw IllegalStateException("server rejected the commit")
    }
}
