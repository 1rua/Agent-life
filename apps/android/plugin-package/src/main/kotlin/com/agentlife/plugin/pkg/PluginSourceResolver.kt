package com.agentlife.plugin.pkg

import java.io.File
import java.io.InputStream

sealed interface PluginSource {
    data class LocalFile(val file: File) : PluginSource
    data class HttpsUrl(val url: String, val pinnedSpkiSha256: Set<String>) : PluginSource
    data class FixedRelease(val url: String, val sha256: String) : PluginSource
    data class OrganizationRepository(val url: String, val pinnedSpkiSha256: Set<String>) : PluginSource
    data class OptionalIndex(val url: String, val pinnedSpkiSha256: Set<String>) : PluginSource
}

data class PackageStream(
    val bytes: ByteArray,
    /** Non-null only for sources whose integrity is externally pinned up front. */
    val expectedSha256: String?,
)

/** Network opener, injected so the resolver holds no transport of its own. */
fun interface PackageFetcher {
    @Throws(Exception::class)
    fun open(url: String, pinnedSpkiSha256: Set<String>): InputStream
}

/**
 * Resolves a plugin source to bytes.
 *
 * The source is never a root of trust: whatever it returns is verified by
 * [AlpVerifier] before it can be installed. Pinning only protects the fetch
 * itself, so HTTPS sources without pins are rejected outright.
 */
class PluginSourceResolver(
    private val fetcher: PackageFetcher,
    private val maxBytes: Long,
) {
    fun resolve(source: PluginSource): PackageStream = when (source) {
        is PluginSource.LocalFile -> PackageStream(
            bytes = source.file.readBytes().also {
                if (it.size.toLong() > maxBytes) throw PackageRejected("SIZE_LIMIT:source")
            },
            expectedSha256 = null,
        )

        is PluginSource.HttpsUrl -> fetch(source.url, source.pinnedSpkiSha256, null)

        is PluginSource.FixedRelease -> fetch(source.url, emptySet(), source.sha256)

        is PluginSource.OrganizationRepository -> fetch(source.url, source.pinnedSpkiSha256, null)

        is PluginSource.OptionalIndex -> fetch(source.url, source.pinnedSpkiSha256, null)
    }

    private fun fetch(url: String, pins: Set<String>, expectedSha256: String?): PackageStream {
        if (!url.startsWith("https://")) throw PackageRejected("SOURCE_NOT_HTTPS")
        if (url != java.text.Normalizer.normalize(url, java.text.Normalizer.Form.NFC)) {
            throw PackageRejected("SOURCE_NON_NFC_URL")
        }
        // An unpinned HTTPS source reduces TLS to whatever the device trusts,
        // which is not the pinning model this product promises.
        if (pins.isEmpty() && expectedSha256 == null) {
            throw PackageRejected("SOURCE_UNPINNED")
        }

        val bytes = runCatching {
            fetcher.open(url, pins).use { stream ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxBytes) throw PackageRejected("SIZE_LIMIT:source")
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
        }.getOrElse { cause ->
            if (cause is PackageRejected) throw cause
            throw PackageRejected("SOURCE_FETCH_FAILED")
        }

        if (expectedSha256 != null) {
            val actual = sha256Hex(bytes)
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                throw PackageRejected("SOURCE_DIGEST_MISMATCH")
            }
        }
        return PackageStream(bytes = bytes, expectedSha256 = expectedSha256)
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
