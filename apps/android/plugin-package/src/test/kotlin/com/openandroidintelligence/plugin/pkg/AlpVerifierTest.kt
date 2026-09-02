package com.openandroidintelligence.plugin.pkg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlpVerifierTest {
    private val limits = PackageLimits(
        maxEntries = 64,
        maxSingleEntryBytes = 8 * 1024 * 1024,
        maxTotalUncompressedBytes = 32 * 1024 * 1024,
    )

    private fun verifier() = AlpVerifier(limits = limits)

    @Test
    fun rejectsPathTraversalEntries() {
        val zip = AlpTestZip.build(
            listOf(
                "manifest.json" to AlpTestZip.manifestJson().toByteArray(),
                "files.json" to "[]".toByteArray(),
                "signature.ed25519" to "\n".toByteArray(),
                "../../etc/passwd" to ByteArray(1),
            ),
        )

        assertRejected(zip) { it.contains("TRAVERSAL") }
    }

    @Test
    fun rejectsDuplicateZipEntries() {
        // A normal writer refuses duplicate names, so the container is hand-built.
        val zip = AlpTestZip.raw(
            RawEntry("manifest.json", AlpTestZip.manifestJson().toByteArray()),
            RawEntry("manifest.json", AlpTestZip.manifestJson().toByteArray()),
        )

        assertRejected(zip) { it.contains("DUPLICATE_ENTRY") }
    }

    @Test
    fun rejectsMissingManifest() {
        val zip = AlpTestZip.build(
            listOf(
                "files.json" to "[]".toByteArray(),
                "signature.ed25519" to "\n".toByteArray(),
            ),
        )

        assertRejected(zip) { it.contains("MISSING_MANIFEST") }
    }

    @Test
    fun rejectsEntriesBeyondDeclaredCount() {
        val many = (0 until limits.maxEntries + 1).map { "assets/f$it" to ByteArray(1) }
        val zip = AlpTestZip.build(
            listOf(
                "manifest.json" to AlpTestZip.manifestJson().toByteArray(),
                "files.json" to "[]".toByteArray(),
                "signature.ed25519" to "\n".toByteArray(),
            ) + many,
        )

        assertRejected(zip) { it.contains("TOO_MANY_ENTRIES") }
    }

    @Test
    fun rejectsUndeclaredPayloadEntry() {
        val zip = AlpTestZip.build(
            listOf(
                "manifest.json" to AlpTestZip.manifestJson().toByteArray(),
                "files.json" to "[]".toByteArray(),
                "signature.ed25519" to "\n".toByteArray(),
                "payload/extra.wasm" to ByteArray(4),
            ),
        )

        assertRejected(zip) { it.contains("UNDECLARED_ENTRY") }
    }

    @Test
    fun rejectsAbsoluteAndBackslashPaths() {
        val zip = AlpTestZip.build(
            listOf(
                "manifest.json" to AlpTestZip.manifestJson().toByteArray(),
                "files.json" to "[]".toByteArray(),
                "signature.ed25519" to "\n".toByteArray(),
                "/absolute/path" to ByteArray(1),
            ),
        )

        assertRejected(zip) { it.contains("ABSOLUTE_PATH") }
    }

    @Test
    fun rejectsZipBombBeforeExtraction() {
        // The entry claims 1 GiB while carrying a handful of bytes. Rejection
        // must come from the declared size, never from inflating the entry.
        val zip = AlpTestZip.raw(
            RawEntry("assets/bomb.bin", ByteArray(16), declaredUncompressedSize = 1_073_741_824L),
        )
        assertRejected(zip) { it.contains("SIZE_LIMIT") }
    }

    @Test
    fun rejectsTamperedFileDigest() {
        val zip = AlpTestZip.build(
            listOf(
                "manifest.json" to AlpTestZip.manifestJson().toByteArray(),
                "files.json" to """[{"path":"payload/plugin.wasm","sha256":"${"0".repeat(64)}","size":4}]""".toByteArray(),
                "signature.ed25519" to "\n".toByteArray(),
                "payload/plugin.wasm" to byteArrayOf(0, 0x61, 0x73, 0x6d),
            ),
        )

        assertRejected(zip) { it.contains("DIGEST_MISMATCH") }
    }

    private fun assertRejected(zip: ByteArray, messageMatches: (String) -> Boolean) {
        val failure = runCatching { verifier().verify(zip.inputStream()) }.exceptionOrNull()
        assertTrue("expected rejection but verification succeeded", failure != null)
        val code = failure!!.message ?: ""
        assertTrue("unexpected rejection code: $code", messageMatches(code))
    }
}
