package com.agentlife.mobile.util

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.agentlife.conversation.ports.LocalAttachmentSelection
import java.io.ByteArrayOutputStream

object ContentResolverExtensions {

    const val MAX_ATTACHMENT_BYTES = 25L * 1024 * 1024 // 25 MB

    /**
     * Resolves an open SAF Uri into a strongly typed [LocalAttachmentSelection].
     *
     * Validates that the file exists, has a supported size (<= 25MB), and safely reads
     * the stream into memory.
     */
    fun resolveAttachment(contentResolver: ContentResolver, uri: Uri): LocalAttachmentSelection {
        var filename = "attachment_${System.currentTimeMillis()}"
        var sizeBytes: Long = -1L

        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    val name = cursor.getString(nameIndex)
                    if (!name.isNullOrBlank()) {
                        filename = name
                    }
                }
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex != -1) {
                    sizeBytes = cursor.getLong(sizeIndex)
                }
            }
        }

        if (sizeBytes > MAX_ATTACHMENT_BYTES) {
            throw IllegalArgumentException("ATTACHMENT_TOO_LARGE: 文件大小超出 25MB 限制 ($sizeBytes bytes)")
        }

        val mediaType = contentResolver.getType(uri) ?: "application/octet-stream"

        val bytes = contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(8192)
            val output = ByteArrayOutputStream()
            var totalRead = 0L
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                totalRead += read
                if (totalRead > MAX_ATTACHMENT_BYTES) {
                    throw IllegalArgumentException("ATTACHMENT_TOO_LARGE: 文件大小超出 25MB 限制")
                }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        } ?: throw IllegalArgumentException("ATTACHMENT_READ_FAILED: 无法读取文件数据")

        return LocalAttachmentSelection(
            filename = filename,
            mediaType = mediaType,
            bytes = bytes,
        )
    }
}
