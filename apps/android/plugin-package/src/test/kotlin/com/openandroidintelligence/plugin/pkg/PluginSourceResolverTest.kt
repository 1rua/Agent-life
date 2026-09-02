package com.openandroidintelligence.plugin.pkg

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PluginSourceResolverTest {
    private val pins = setOf("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")

    private fun resolver(
        fetcher: PackageFetcher = PackageFetcher { _, _ -> ByteArrayInputStream(byteArrayOf(1, 2, 3)) },
        maxBytes: Long = 1024,
    ) = PluginSourceResolver(fetcher = fetcher, maxBytes = maxBytes)

    @Test
    fun resolvesLocalFile() {
        val file = File.createTempFile("alp-source", ".alp")
        file.writeBytes(byteArrayOf(9, 9, 9))

        val stream = resolver().resolve(PluginSource.LocalFile(file))

        assertEquals(3, stream.bytes.size)
        assertEquals(null, stream.expectedSha256)
    }

    @Test
    fun rejectsLocalFileBeyondLimit() {
        val file = File.createTempFile("alp-source", ".alp")
        file.writeBytes(ByteArray(2048))

        val failure = runCatching {
            resolver(maxBytes = 1024).resolve(PluginSource.LocalFile(file))
        }.exceptionOrNull()

        assertEquals("SIZE_LIMIT:source", (failure as? PackageRejected)?.message)
    }

    @Test
    fun rejectsUnpinnedHttpsSource() {
        val failure = runCatching {
            resolver().resolve(PluginSource.HttpsUrl("https://example.org/p.alp", emptySet()))
        }.exceptionOrNull()

        assertEquals("SOURCE_UNPINNED", (failure as? PackageRejected)?.message)
    }

    @Test
    fun rejectsNonHttpsSource() {
        val failure = runCatching {
            resolver().resolve(PluginSource.HttpsUrl("http://example.org/p.alp", pins))
        }.exceptionOrNull()

        assertEquals("SOURCE_NOT_HTTPS", (failure as? PackageRejected)?.message)
    }

    @Test
    fun pinnedHttpsSourceIsFetchedWithPins() {
        var seenPins: Set<String>? = null
        val resolver = resolver(
            fetcher = PackageFetcher { _, pins ->
                seenPins = pins
                ByteArrayInputStream(byteArrayOf(1))
            },
        )

        resolver.resolve(PluginSource.HttpsUrl("https://example.org/p.alp", pins))

        assertEquals(pins, seenPins)
    }

    @Test
    fun fixedReleaseWithWrongDigestIsRejected() {
        val sha = "0".repeat(64)
        val failure = runCatching {
            resolver().resolve(
                PluginSource.FixedRelease("https://example.org/p.alp", sha),
            )
        }.exceptionOrNull()

        assertEquals("SOURCE_DIGEST_MISMATCH", (failure as? PackageRejected)?.message)
    }

    @Test
    fun fixedReleaseWithMatchingDigestIsAccepted() {
        val bytes = byteArrayOf(1, 2, 3)
        val sha = java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }

        val stream = resolver(
            fetcher = PackageFetcher { _, _ -> ByteArrayInputStream(bytes) },
        ).resolve(PluginSource.FixedRelease("https://example.org/p.alp", sha))

        assertEquals(sha, stream.expectedSha256)
    }

    @Test
    fun fetchFailureIsRejectedNotPropagated() {
        val failure = runCatching {
            resolver(fetcher = PackageFetcher { _, _ -> throw IOException("boom") })
                .resolve(PluginSource.HttpsUrl("https://example.org/p.alp", pins))
        }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals("SOURCE_FETCH_FAILED", (failure as? PackageRejected)?.message)
    }
}
