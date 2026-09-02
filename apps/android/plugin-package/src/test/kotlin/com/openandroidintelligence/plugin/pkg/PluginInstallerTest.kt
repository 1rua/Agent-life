package com.openandroidintelligence.plugin.pkg

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PluginInstallerTest {
    private lateinit var root: File
    private lateinit var installer: PluginInstaller

    private val author = "A".repeat(43)
    private val otherAuthor = "B".repeat(43)

    private val surface = SecuritySurface(
        kernelPrimitives = emptySet(),
        networkHosts = emptySet(),
        maxStorageBytes = 1_000L,
        maxMemoryBytes = 2_000L,
        maxInvocationMillis = 1_000L,
        maxConcurrentInvocations = 1,
        maxDailyNetworkBytes = 0L,
        backgroundRequested = false,
        companionPackageName = null,
        nativeAbis = emptySet(),
    )

    @Before
    fun setUp() {
        root = createTempDir("alp-install")
        installer = PluginInstaller(installRoot = root)
    }

    private fun verified(
        version: String,
        authorKey: String = author,
        stagedContent: Map<String, ByteArray> = mapOf("payload/plugin.wasm" to byteArrayOf(0, 0x61, 0x73, 0x6d)),
    ): VerifiedPluginPackage {
        val staged = createTempDir("alp-staged")
        for ((path, bytes) in stagedContent) {
            val target = File(staged, path)
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
        }
        return VerifiedPluginPackage(
            identity = PluginIdentity(
                pluginId = "org.example.notifications",
                authorKeyFingerprint = authorKey,
                version = version,
            ),
            version = SemVer.parse(version)!!,
            runtime = RuntimeDeclaration("protected-wasm", "1.0", "open_android_intelligence_plugin_main", "payload/plugin.wasm"),
            capabilities = CapabilityDeclaration(emptySet(), emptySet()),
            security = SecurityDeclaration(surface),
            stagedDirectory = staged,
        )
    }

    @Test
    fun installsAndExposesLiveVersionOnly() {
        val installed = installer.install(verified("1.0.0"), current = null)

        assertTrue(File(installed.directory, "payload/plugin.wasm").exists())
        // No staging directory survives a successful commit.
        val leftovers = File(root, ".staging").listFiles() ?: emptyArray()
        assertTrue("staging must be consumed, found ${leftovers.size}", leftovers.isEmpty())
    }

    @Test
    fun keepsPreviousVerifiedVersionAfterUpdate() {
        val first = installer.install(verified("1.0.0"), current = null)
        val second = installer.install(verified("1.1.0"), current = first)

        assertEquals(second.directory, File(root, "org.example.notifications"))
        assertEquals(File(second.directory, "payload/plugin.wasm").readBytes().size, 4)
        assertNotNull(second.previousVersionDirectory)
    }

    @Test
    fun rejectsDowngrade() {
        val first = installer.install(verified("1.1.0"), current = null)

        val failure = runCatching {
            installer.install(verified("1.0.0"), current = first)
        }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals("DOWNGRADE", (failure as PackageRejected).message)
        // The live version is untouched.
        assertTrue(File(root, "org.example.notifications").exists())
    }

    @Test
    fun rejectsDifferentAuthor() {
        val first = installer.install(verified("1.0.0"), current = null)

        val failure = runCatching {
            installer.install(verified("1.1.0", authorKey = otherAuthor), current = first)
        }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals("AUTHOR_MISMATCH", (failure as PackageRejected).message)
    }

    @Test
    fun requiresApprovalForWidenedSecurity() {
        val first = installer.install(verified("1.0.0"), current = null)
        val widened = verified("1.1.0").copy(
            security = SecurityDeclaration(surface.copy(networkHosts = setOf("evil.example.org"))),
        )

        val denied = runCatching { installer.install(widened, current = first) }.exceptionOrNull()
        assertEquals("APPROVAL_REQUIRED:NETWORK_HOST_ADDED", (denied as? PackageRejected)?.message)

        val granted = installer.install(widened, current = first, approvalGranted = true)
        assertEquals("1.1.0", granted.identity.version)
    }

    @Test
    fun rollbackRestoresPreviousVersion() {
        val first = installer.install(verified("1.0.0"), current = null)
        File(first.directory, "payload/plugin.wasm").writeBytes(byteArrayOf(1, 2, 3, 4))
        val second = installer.install(verified("1.1.0"), current = first)

        // Reinstall the original bytes as the retained previous version, then roll back.
        val rolledBack = installer.rollback(second)
        assertTrue(File(rolledBack.directory, "payload/plugin.wasm").exists())
    }

    @Test
    fun rollbackWithoutPreviousFailsClosed() {
        val first = installer.install(verified("1.0.0"), current = null)

        val failure = runCatching { installer.rollback(first) }.exceptionOrNull()
        assertEquals("NO_PREVIOUS_VERSION", (failure as? PackageRejected)?.message)
    }

    @Test
    fun copyFailureLeavesLiveVersionIntact() {
        // A failure partway through staging is the window an interrupted install
        // sits in: the pointer has not moved, so the live version must survive.
        val first = installer.install(verified("1.0.0"), current = null)

        val staged = createTempDir("alp-broken")
        val unreadable = File(staged, "payload/plugin.wasm")
        unreadable.parentFile?.mkdirs()
        unreadable.writeBytes(byteArrayOf(0, 0x61, 0x73, 0x6d))
        unreadable.setReadable(false)
        val pending = verified("1.1.0", stagedContent = emptyMap()).copy(stagedDirectory = staged)

        val failure = runCatching { installer.install(pending, current = first) }.exceptionOrNull()

        val live = File(root, "org.example.notifications")
        assertTrue("live version must survive a failed staging copy", live.exists())
        assertTrue(File(live, "payload/plugin.wasm").exists())
        assertFalse(
            "the pointer must not have moved",
            File(root, "org.example.notifications.retired").exists(),
        )
        unreadable.setReadable(true)
        assertNotNull("staging copy was expected to fail", failure)
    }
}
