package com.openandroidintelligence.gateway.attachments

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The uploader declares size and SHA-256 up front, then the server commits only
 * on an exact match. Anything that would let a declared digest disagree with the
 * bytes actually sent must fail closed before content is uploaded.
 */
class AttachmentUploaderTest {

    @Test
    fun testUploadWithVisualContext() = runBlocking {
        val transport = recorder()
        val uploader = AttachmentUploader(transport)
        val vc = VisualAttachmentMetadata(
            bounds = NormalizedCropBounds(0.0, 0.0, 1.0, 1.0),
            displayMetrics = DisplayDensityMetrics(1080, 1920, 420),
            uiHierarchySummary = "RootView",
        )
        val id = uploader.upload(
            SelectedAttachment(
                filename = "screen.png",
                mediaType = "image/png",
                content = "PNGDATA".toByteArray(Charsets.UTF_8),
                visualContext = vc,
            ),
        )
        assertEquals("att-server-1", id)
        assertEquals(vc, transport.created?.visualContext)
    }

    private fun recorder() = RecordingGatewayClient()

    private fun uploader(client: RecordingGatewayClient = recorder(), maxBytes: Long = 1024) =
        AttachmentUploader(client, AttachmentLimits(maxBytes = maxBytes))

    @Test
    fun declaresSizeAndDigestFromTheStreamedBytes() = runBlocking {
        val client = recorder()
        val uploader = uploader(client)
        val content = "hello gateway".toByteArray()

        uploader.upload(SelectedAttachment("report.txt", "text/plain", content))

        assertEquals(content.size.toLong(), client.created?.sizeBytes)
        assertEquals(sha256Hex(content), client.created?.sha256)
    }

    @Test
    fun digestMismatchFailsBeforeContentIsSent() = runBlocking {
        val client = recorder()
        val uploader = uploader(client)

        val failure = runCatching {
            uploader.upload(
                SelectedAttachment(
                    filename = "report.txt",
                    mediaType = "text/plain",
                    content = "actual bytes".toByteArray(),
                    declaredSha256 = "sha256:" + "a".repeat(64),
                ),
            )
        }.exceptionOrNull()

        assertTrue("a wrong declared digest must fail closed", failure != null)
        assertTrue(failure!!.message!!.contains("DIGEST_MISMATCH"))
        assertEquals("content must not be uploaded when the digest disagrees", null, client.contentUploaded)
    }

    @Test
    fun oversizedAttachmentIsRejectedBeforeUpload() = runBlocking {
        val client = recorder()
        val uploader = uploader(client, maxBytes = 8)

        val failure = runCatching {
            uploader.upload(SelectedAttachment("big.bin", "application/octet-stream", ByteArray(9)))
        }.exceptionOrNull()

        assertTrue(failure != null)
        assertTrue(failure!!.message!!.contains("ATTACHMENT_TOO_LARGE"))
        assertEquals(null, client.created)
    }

    @Test
    fun contentRequestCarriesContentLengthAndDigest() = runBlocking {
        val client = recorder()
        val uploader = uploader(client)
        val content = "payload".toByteArray()

        uploader.upload(SelectedAttachment("f.bin", "application/octet-stream", content))

        assertEquals(content.size.toLong(), client.contentHeaders?.get("Content-Length")?.toLong())
        assertEquals("sha-256=" + base64(content.sha256()), client.contentHeaders?.get("Digest"))
    }

    @Test
    fun threeStepsRunInOrderCreateThenContentThenCommit() = runBlocking {
        val client = recorder()
        val uploader = uploader(client)

        uploader.upload(SelectedAttachment("f.bin", "application/octet-stream", "abc".toByteArray()))

        assertEquals(listOf("create", "content", "commit"), client.calls)
    }

    @Test
    fun commitFailurePropagatesAndDoesNotReportSuccess() = runBlocking {
        val client = recorder().apply { commitShouldFail = true }
        val uploader = uploader(client)

        val failure = runCatching {
            uploader.upload(SelectedAttachment("f.bin", "application/octet-stream", "abc".toByteArray()))
        }.exceptionOrNull()

        assertTrue("a rejected commit must not look like success", failure != null)
        assertTrue(failure!!.message!!.contains("COMMIT_FAILED"))
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun base64(bytes: ByteArray): String =
        java.util.Base64.getEncoder().encodeToString(bytes)

    private fun ByteArray.sha256(): ByteArray =
        java.security.MessageDigest.getInstance("SHA-256").digest(this)
}
