package com.agentlife.kernel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Developer trust mode on a real device.
 *
 * This runs on hardware because the guarantee is about the process: a native
 * plugin shares the host UID and this app's whole permission set, so the only
 * meaningful test is that the mode is genuinely off by default and that leaving
 * it stops native code even after it was loaded successfully.
 */
class DeveloperTrustModeInstrumentedTest {

    private fun acknowledgement() = DeveloperTrustMode.Acknowledgement(
        DeveloperTrustMode.Acknowledgement.REQUIRED_TEXT,
    )

    @Test
    fun trustModeIsOffByDefaultAndNativePluginsAreUnreachable() {
        val trustMode = DeveloperTrustMode()
        val loader = NativePluginLoader(trustMode)

        assertFalse("developer trust mode must be off on a fresh install", trustMode.isEnabled())

        try {
            loader.load(
                packageInfo = nativePackage("org.example.native"),
                trustEnabled = trustMode.isEnabled(),
                factory = { StubNativePlugin(it.pluginId) },
            )
            fail("expected native code to be refused while trust mode is off")
        } catch (cause: NativePluginRejected) {
            assertTrue(cause.message!!.contains("TRUST_MODE_DISABLED"))
        }
        assertFalse(loader.isLoaded("org.example.native"))
    }

    @Test
    fun enablingTrustModeRequiresTheExactAcknowledgement() {
        val trustMode = DeveloperTrustMode()
        assertFalse(trustMode.enable(DeveloperTrustMode.Acknowledgement("I agree")))
        assertFalse(trustMode.enable(DeveloperTrustMode.Acknowledgement("")))
        assertFalse(trustMode.isEnabled())

        assertTrue(trustMode.enable(acknowledgement()))
        assertTrue(trustMode.isEnabled())
    }

    @Test
    fun leavingTrustModeImmediatelyStopsLoadedNativePlugins() {
        val trustMode = DeveloperTrustMode()
        val loader = NativePluginLoader(trustMode)
        val plugin = RecordingNativePlugin("org.example.native")

        assertTrue(trustMode.enable(acknowledgement()))
        loader.load(
            packageInfo = nativePackage("org.example.native"),
            trustEnabled = trustMode.isEnabled(),
            factory = { plugin },
        )
        assertTrue(loader.isLoaded("org.example.native"))

        trustMode.disable()

        assertFalse("native plugins must stop the moment trust mode is left", loader.isLoaded("org.example.native"))
        assertTrue("the plugin must actually be told to stop", plugin.stopped)
    }

    @Test
    fun aLoaderCreatedAfterTheModeWasLeftStartsUnloaded() {
        val trustMode = DeveloperTrustMode()
        assertTrue(trustMode.enable(acknowledgement()))
        val first = NativePluginLoader(trustMode)
        first.load(
            packageInfo = nativePackage("org.example.native"),
            trustEnabled = true,
            factory = { StubNativePlugin(it.pluginId) },
        )

        trustMode.disable()

        // A component created later must not inherit the old allowance: the
        // listener fires on registration with the current state.
        val later = NativePluginLoader(trustMode)
        assertFalse(later.isLoaded("org.example.native"))
        try {
            later.load(
                packageInfo = nativePackage("org.example.native"),
                trustEnabled = trustMode.isEnabled(),
                factory = { StubNativePlugin(it.pluginId) },
            )
            fail("expected the new loader to refuse as well")
        } catch (cause: NativePluginRejected) {
            assertTrue(cause.message!!.contains("TRUST_MODE_DISABLED"))
        }
    }

    @Test
    fun aNativePluginMayNotNameAHostClassAsItsEntrypoint() {
        val trustMode = DeveloperTrustMode()
        assertTrue(trustMode.enable(acknowledgement()))
        val loader = NativePluginLoader(trustMode)

        try {
            loader.load(
                packageInfo = nativePackage("com.agentlife.kernel.PluginKernel"),
                trustEnabled = true,
                factory = { StubNativePlugin(it.pluginId) },
            )
            fail("expected a host class entrypoint to be refused")
        } catch (cause: NativePluginRejected) {
            assertTrue(cause.message!!.contains("HOST_CLASS_ENTRYPOINT"))
        }
    }

    @Test
    fun theKernelRefusesToEnableANativePluginWhileTrustModeIsOff() {
        val trustMode = DeveloperTrustMode()
        val kernel = PluginKernel(
            hostEnvelope = HostEnvelope(emptySet()),
            phoneLimits = PhoneLimits(emptySet()),
            runtimes = emptyMap(),
            audit = AndroidAuditStore(),
            trustMode = trustMode,
            nativeLoader = NativePluginLoader(trustMode),
            providerSelector = CapabilityProviderSelector(emptyMap()),
            grants = { null },
        )
        kernel.register(
            PluginRegistration(
                identity = com.agentlife.plugin.pkg.PluginIdentity(
                    "org.example.native", "A".repeat(43), "1.0.0",
                ),
                runtimeType = PluginKernel.RUNTIME_DEVELOPER_NATIVE,
                declaredPrimitives = emptySet(),
                budget = ResourceBudget(
                    maxInvocationMillis = 100L,
                    maxMemoryBytes = 1_048_576L,
                    maxOutputBytes = 1_024L,
                    maxConcurrentInvocations = 1,
                    maxDailyNetworkBytes = 0L,
                ),
            ),
        )

        try {
            kernel.enable("org.example.native")
            fail("expected the kernel to refuse to enable a native plugin")
        } catch (cause: NativePluginRejected) {
            assertTrue(cause.message!!.contains("TRUST_MODE_DISABLED"))
        }
    }

    private fun nativePackage(entrypoint: String) = NativePluginPackage(
        pluginId = "org.example.native",
        authorKeyFingerprint = "C".repeat(43),
        entrypointClass = entrypoint,
        abis = setOf("arm64-v8a"),
    )

    private class StubNativePlugin(override val pluginId: String) : NativePlugin {
        override fun invoke(input: ByteArray): ByteArray = input
        override fun stop() = Unit
    }

    private class RecordingNativePlugin(override val pluginId: String) : NativePlugin {
        var stopped = false

        override fun invoke(input: ByteArray): ByteArray = input

        override fun stop() {
            stopped = true
        }
    }
}
