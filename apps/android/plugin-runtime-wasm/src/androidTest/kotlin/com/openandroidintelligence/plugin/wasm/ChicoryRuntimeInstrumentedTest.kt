package com.openandroidintelligence.plugin.wasm

import com.openandroidintelligence.kernel.ResourceBudget
import com.openandroidintelligence.plugin.pkg.PluginIdentity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Runs the plugin sandbox on a real device.
 *
 * The point of doing this on hardware rather than on the JVM is that the
 * guarantee being tested concerns the interpreter as it actually runs on the
 * phone: that a module which asks for something outside the kernel ABI never
 * gets it, and that a module which refuses to stop is stopped anyway.
 */
class ChicoryRuntimeInstrumentedTest {

    private val identity = PluginIdentity("org.openandroidintelligence.fixture", "A".repeat(43), "1.0.0")

    private val budget = ResourceBudget(
        maxInvocationMillis = 2_000L,
        maxMemoryBytes = 1_048_576L,
        maxOutputBytes = 65_536L,
        maxConcurrentInvocations = 1,
        maxDailyNetworkBytes = 0L,
    )

    private fun runtime(wasm: ByteArray): ChicoryPluginRuntime = ChicoryPluginRuntime(
        budget = InvocationBudget(
            maxInvocationMillis = 2_000L,
            maxMemoryBytes = 1_048_576L,
            maxOutputBytes = 65_536L,
        ),
        moduleSource = { wasm },
        // A fine interval makes the deadline test fast and deterministic on
        // hardware whose speed is unknown.
        deadlineCheckInterval = 256,
    )

    private fun rejected(bytes: ByteArray): String {
        try {
            runtime(bytes).load(bytes)
        } catch (cause: PluginRejected) {
            return cause.message!!
        }
        throw AssertionError("expected the module to be rejected")
    }

    @Test
    fun rejectsWasiAndUnknownImports() {
        assertTrue(
            rejected(WasmFixtures.importsWasi())
                .contains("IMPORT_NOT_ALLOWED:wasi_snapshot_preview1"),
        )
        assertTrue(
            rejected(WasmFixtures.importsUnknownKernelCall())
                .contains("IMPORT_NOT_ALLOWED:open_android_intelligence_kernel_v1.kernel_open_socket"),
        )
    }

    @Test
    fun rejectsAKnownImportDeclaredWithTheWrongSignature() {
        assertTrue(
            "a known name with the wrong signature must not link",
            rejected(WasmFixtures.importsKernelWithWrongSignature())
                .contains("PLUGIN_REJECTED:"),
        )
    }

    @Test
    fun rejectsAModuleWithNoEntrypoint() {
        assertTrue(rejected(WasmFixtures.missingEntrypoint()).contains("MISSING_ENTRYPOINT"))
    }

    @Test
    fun rejectsBytesThatAreNotAModule() {
        assertTrue(rejected(WasmFixtures.garbage()).contains("PARSE"))
    }

    @Test
    fun rejectsAModuleThatDeclaresMoreMemoryThanTheBudget() {
        assertTrue(rejected(WasmFixtures.declaresTooMuchMemory()).contains("MEMORY_LIMIT"))
    }

    @Test
    fun echoFixtureRunsInsideItsBudget() {
        val request = "ping".toByteArray(Charsets.UTF_8)
        val output = runtime(WasmFixtures.echo()).invoke(identity, budget, request)

        assertArrayEquals(request, output)
        assertEquals("ping", output.toString(Charsets.UTF_8))
    }

    @Test
    fun echoFixtureHandlesAnEmptyRequest() {
        assertArrayEquals(
            ByteArray(0),
            runtime(WasmFixtures.echo()).invoke(identity, budget, ByteArray(0)),
        )
    }

    @Test
    fun aModuleThatNeverStopsIsStoppedByItsDeadline() {
        try {
            runtime(WasmFixtures.infiniteLoop()).invoke(identity, budget, ByteArray(0))
            fail("expected the deadline to fire")
        } catch (cause: BudgetExceeded) {
            assertTrue(
                "expected DEADLINE, got ${cause.message}",
                cause.message!!.contains("DEADLINE"),
            )
        }
    }

    @Test
    fun aModuleThatGrowsItsMemoryIsStillBounded() {
        try {
            runtime(WasmFixtures.memoryBomb()).invoke(identity, budget, ByteArray(0))
            fail("expected the memory bomb to be stopped")
        } catch (cause: BudgetExceeded) {
            assertTrue(
                "expected the bomb to be bounded, got ${cause.message}",
                cause.message!!.contains("DEADLINE"),
            )
        }
    }

    @Test
    fun outputLargerThanTheBudgetIsRefused() {
        try {
            // The echo fixture reports the request length, so an oversized
            // request produces an oversized response: the ceiling is on the
            // way out, not just on the way in.
            runtime(WasmFixtures.echo())
                .invoke(identity, budget.copy(maxOutputBytes = 64L), ByteArray(4_096))
            fail("expected the output ceiling to apply")
        } catch (cause: BudgetExceeded) {
            assertTrue("expected OUTPUT, got ${cause.message}", cause.message!!.contains("OUTPUT"))
        }
    }

    @Test
    fun resultPackingRoundTrips() {
        val packed = KernelAbi.packResult(pointer = 16, length = 48)
        assertEquals(16, KernelAbi.resultPointer(packed))
        assertEquals(48, KernelAbi.resultLength(packed))
    }
}
