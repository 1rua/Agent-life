package com.agentlife.plugin.wasm

import com.agentlife.kernel.PluginRuntime
import com.agentlife.kernel.ResourceBudget
import com.agentlife.plugin.pkg.PluginIdentity
import com.dylibso.chicory.runtime.ExecutionListener
import com.dylibso.chicory.runtime.ExportFunction
import com.dylibso.chicory.runtime.HostFunction
import com.dylibso.chicory.runtime.ImportValues
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.runtime.MStack
import com.dylibso.chicory.wasm.Parser
import com.dylibso.chicory.wasm.types.ExternalType
import com.dylibso.chicory.wasm.types.FunctionType
import com.dylibso.chicory.wasm.types.Instruction
import com.dylibso.chicory.wasm.types.MemoryLimits
import com.dylibso.chicory.wasm.types.ValType
import java.security.SecureRandom

/** Raised for any module this runtime refuses to load or run. */
class PluginRejected(code: String) : IllegalArgumentException("PLUGIN_REJECTED:$code")

/** Raised when an invocation exceeds its deadline, memory or output budget. */
class BudgetExceeded(code: String) : IllegalStateException("BUDGET_EXCEEDED:$code")

/**
 * The kernel ABI a protected plugin is allowed to import.
 *
 * The list is deliberately short. A plugin gets structured logging, host
 * randomness and a host clock; it does not get a filesystem, an environment, a
 * process, or any way to reach the network directly. Every privileged operation
 * is mediated by the kernel outside the sandbox.
 */
object KernelAbi {
    const val MODULE = "agent_life_kernel_v1"
    const val ENTRYPOINT = "agent_life_plugin_main"

    const val LOG = "kernel_log"
    const val RANDOM_FILL = "kernel_random_fill"
    const val NOW_MILLIS = "kernel_now_millis"

    /**
     * The reserved exchange region: the kernel writes the request at this
     * offset and the plugin may write its response over it. Plugin static data
     * and heap live above this region, so the two never overlap.
     */
    const val EXCHANGE_OFFSET = 0
    const val EXCHANGE_SIZE_BYTES = 65_536

    /** Host functions, by name, with their signatures. */
    val FUNCTIONS: Map<String, FunctionType> = mapOf(
        LOG to FunctionType.of(
            arrayOf(ValType.I32, ValType.I32, ValType.I32),
            emptyArray(),
        ),
        RANDOM_FILL to FunctionType.of(
            arrayOf(ValType.I32, ValType.I32),
            emptyArray(),
        ),
        NOW_MILLIS to FunctionType.of(
            emptyArray(),
            arrayOf(ValType.I64),
        ),
    )

    /**
     * Result packing for [ENTRYPOINT]: the 64-bit return carries the response
     * address in the high 32 bits and its length in the low 32 bits.
     */
    fun packResult(pointer: Int, length: Int): Long =
        (pointer.toLong() and 0xFFFFFFFFL) shl 32 or (length.toLong() and 0xFFFFFFFFL)

    fun resultPointer(packed: Long): Int = (packed ushr 32).toInt()

    fun resultLength(packed: Long): Int = packed.toInt()
}

/** The per-invocation slice of a plugin's declared resource budget. */
data class InvocationBudget(
    val maxInvocationMillis: Long,
    val maxMemoryBytes: Long,
    val maxOutputBytes: Long,
) {
    companion object {
        fun from(budget: ResourceBudget) = InvocationBudget(
            maxInvocationMillis = budget.maxInvocationMillis,
            maxMemoryBytes = budget.maxMemoryBytes,
            maxOutputBytes = budget.maxOutputBytes,
        )
    }

    /** Narrows these limits so none of them exceeds [other]. */
    fun clampTo(other: InvocationBudget) = InvocationBudget(
        maxInvocationMillis = minOf(maxInvocationMillis, other.maxInvocationMillis),
        maxMemoryBytes = minOf(maxMemoryBytes, other.maxMemoryBytes),
        maxOutputBytes = minOf(maxOutputBytes, other.maxOutputBytes),
    )
}

/**
 * Runs a protected plugin in the Chicory interpreter.
 *
 * Chicory is pure Java and loads no native library, so the sandbox lives
 * entirely inside the host process' managed runtime: there is no JNI boundary
 * for a plugin to escape through.
 */
class ChicoryPluginRuntime(
    private val budget: InvocationBudget,
    /**
     * Resolves a plugin identity to its verified module bytes.
     *
     * The runtime does not take module bytes as an invocation argument: the
     * module is the installed, verified payload, and the invocation argument is
     * the request. Keeping them apart is what stops a caller from sneaking an
     * unverified module in through the request path.
     */
    private val moduleSource: (PluginIdentity) -> ByteArray,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val random: SecureRandom = SecureRandom(),
    /** Instructions between deadline checks; lower is tighter but slower. */
    private val deadlineCheckInterval: Int = 4_096,
) : PluginRuntime {

    /** Where host-observed plugin log lines go. The shipped host wires this to the audit sink. */
    var logSink: (String) -> Unit = {}

    /**
     * Runs one request against the plugin's verified module.
     *
     * A fresh instance is built per invocation rather than reused. An instance
     * owns its linear memory, so sharing one across concurrent calls would let
     * two requests overwrite each other's exchange region — exactly the
     * cross-account and cross-call leak the sandbox exists to prevent. The cost
     * is re-parsing the module on every call.
     */
    override fun invoke(
        identity: PluginIdentity,
        budget: ResourceBudget,
        input: ByteArray,
    ): ByteArray {
        val module = load(moduleSource(identity))
        // The request ceiling can only be tightened by the caller, never
        // widened past what this runtime was constructed with.
        val limits = InvocationBudget.from(budget).clampTo(this.budget)
        return module.invoke(input, limits)
    }

    /**
     * Validates and instantiates a module.
     *
     * Import screening happens before instantiation and against the parsed
     * import section, so a module that names WASI or an unknown kernel call
     * never reaches a state where it could run. The remaining type checking is
     * Chicory's own: a module that imports a known name with the wrong
     * signature fails to link, and that failure is translated here so every
     * refusal carries the runtime's own code.
     */
    fun load(wasm: ByteArray): LoadedPlugin {
        val guard = DeadlineGuard(
            deadlineMillis = clock() + LOAD_DEADLINE_MILLIS,
            clock = clock,
            interval = deadlineCheckInterval,
        )
        currentGuard = guard

        val module = try {
            Parser.parse(wasm)
        } catch (cause: Exception) {
            throw PluginRejected("PARSE:${cause.message}")
        } finally {
            // No module may execute outside an explicit invocation.
            guard.expire()
        }

        val importSection = module.importSection()
        for (index in 0 until importSection.importCount()) {
            val entry = importSection.getImport(index)
            val importModule = entry.module()
            val importName = entry.name()

            if (importModule != KernelAbi.MODULE) {
                throw PluginRejected("IMPORT_NOT_ALLOWED:$importModule.$importName")
            }
            if (importName !in KernelAbi.FUNCTIONS) {
                throw PluginRejected("IMPORT_NOT_ALLOWED:$importModule.$importName")
            }
            if (entry.importType() != ExternalType.FUNCTION) {
                throw PluginRejected("IMPORT_TYPE_NOT_ALLOWED:$importName")
            }
        }

        // Enforced statically, before instantiation: the declared minimum is
        // the floor the interpreter would have to allocate, so a module asking
        // for more than the budget is refused rather than allowed to allocate
        // and then be measured.
        val memorySection = module.memorySection().orElse(null)
        if (memorySection != null && memorySection.memoryCount() > 1) {
            throw PluginRejected("MULTIPLE_MEMORIES")
        }
        val declared = memorySection?.takeIf { it.memoryCount() > 0 }?.getMemory(0)?.limits()
        val maximumPages = pagesFor(budget.maxMemoryBytes)
        if (declared != null) {
            // Only the declared minimum is checked statically. That is the part
            // the interpreter must allocate up front, so it is the part a
            // hostile module can use to make the host commit memory before any
            // budget is attached.
            //
            // The declared *maximum* is deliberately not compared here: an
            // undecorated memory section reports the architectural ceiling
            // (65536 pages) rather than -1, so comparing it would reject every
            // module that does not cap itself. Growth is bounded at run time by
            // `withMemoryLimits` instead, where `memory.grow` simply fails.
            if (declared.initialPages() > maximumPages) throw PluginRejected("MEMORY_LIMIT")
            if (declared.shared()) throw PluginRejected("SHARED_MEMORY")
        }

        guard.reset(clock() + LOAD_DEADLINE_MILLIS)
        val instance = try {
            Instance.builder(module)
                .withImportValues(kernelImports())
                .withMemoryLimits(MemoryLimits(1, maximumPages))
                // `_start` is not run: instantiation must not be a place where
                // unrelated work happens before any budget is attached.
                .withStart(false)
                .withUnsafeExecutionListener(guard)
                .build()
        } catch (cause: BudgetExceeded) {
            throw cause
        } catch (cause: Exception) {
            throw PluginRejected("LINK:${cause.message}")
        } finally {
            guard.expire()
        }

        // Chicory signals a missing export by throwing, not by returning null,
        // so an absent entrypoint must be caught and translated rather than
        // allowed to escape as an interpreter error.
        val entrypoint = runCatching { instance.export(KernelAbi.ENTRYPOINT) }.getOrNull()
            ?: throw PluginRejected("MISSING_ENTRYPOINT:${KernelAbi.ENTRYPOINT}")

        return LoadedPlugin(instance = instance, entrypoint = entrypoint, guard = guard)
    }

    /** A validated, instantiated module ready to be invoked. */
    inner class LoadedPlugin internal constructor(
        private val instance: Instance,
        private val entrypoint: ExportFunction,
        private val guard: DeadlineGuard,
    ) {
        fun invoke(request: ByteArray, limits: InvocationBudget): ByteArray {
            val memory = instance.memory() ?: throw PluginRejected("NO_MEMORY")
            if (request.size > KernelAbi.EXCHANGE_SIZE_BYTES) {
                throw BudgetExceeded("REQUEST")
            }

            currentMemory = memory
            guard.reset(clock() + limits.maxInvocationMillis)
            return try {
                memory.write(KernelAbi.EXCHANGE_OFFSET, request)
                val packed = entrypoint.apply(
                    KernelAbi.EXCHANGE_OFFSET.toLong(),
                    request.size.toLong(),
                ).firstOrNull() ?: throw PluginRejected("NO_RESULT")

                val pointer = KernelAbi.resultPointer(packed)
                val length = KernelAbi.resultLength(packed)
                if (length < 0 || pointer < 0) throw PluginRejected("BAD_RESULT")
                if (length > limits.maxOutputBytes) throw BudgetExceeded("OUTPUT")

                guard.check()
                memory.readBytes(pointer, length)
            } finally {
                guard.expire()
                currentMemory = null
            }
        }
    }

    private var currentMemory: com.dylibso.chicory.runtime.Memory? = null
    private var currentGuard: DeadlineGuard? = null

    /**
     * The host functions a plugin may call.
     *
     * Each one is a deliberate, narrow grant. None of them can name a file, an
     * address or a process.
     */
    private fun kernelImports(): ImportValues = ImportValues.builder()
        .addFunction(
            HostFunction(
                KernelAbi.MODULE,
                KernelAbi.LOG,
                KernelAbi.FUNCTIONS.getValue(KernelAbi.LOG),
            ) { instance, args ->
                currentGuard?.check()
                val level = args[0].toInt()
                val pointer = args[1].toInt()
                val length = args[2].toInt()
                if (length in 0..MAX_LOG_BYTES) {
                    val text = instance.memory()?.readString(pointer, length).orEmpty()
                    logSink("level=$level text=$text")
                }
                LongArray(0)
            },
        )
        .addFunction(
            HostFunction(
                KernelAbi.MODULE,
                KernelAbi.RANDOM_FILL,
                KernelAbi.FUNCTIONS.getValue(KernelAbi.RANDOM_FILL),
            ) { instance, args ->
                currentGuard?.check()
                val pointer = args[0].toInt()
                val length = args[1].toInt()
                if (length < 0 || length > MAX_RANDOM_BYTES) throw BudgetExceeded("RANDOM")
                val bytes = ByteArray(length)
                random.nextBytes(bytes)
                instance.memory()?.write(pointer, bytes)
                LongArray(0)
            },
        )
        .addFunction(
            HostFunction(
                KernelAbi.MODULE,
                KernelAbi.NOW_MILLIS,
                KernelAbi.FUNCTIONS.getValue(KernelAbi.NOW_MILLIS),
            ) { _, _ ->
                currentGuard?.check()
                longArrayOf(clock())
            },
        )
        .build()

    private fun pagesFor(maxMemoryBytes: Long): Int {
        val pages = (maxMemoryBytes + PAGE_SIZE_BYTES - 1) / PAGE_SIZE_BYTES
        return pages.coerceIn(1L, MAX_PAGES.toLong()).toInt()
    }

    /**
     * Bounds wall-clock time from inside the interpreter.
     *
     * A pure interpreter cannot be pre-empted safely from another thread, so
     * the deadline is checked on the interpreter's own execution hook. A plugin
     * that never calls back into the host is still bounded, because the hook
     * fires on the plugin's instructions rather than on host calls.
     */
    internal class DeadlineGuard(
        private val deadlineMillis: Long,
        private val clock: () -> Long,
        private val interval: Int,
    ) : ExecutionListener {
        @Volatile
        private var deadline = deadlineMillis

        @Volatile
        private var counter = 0

        override fun onExecution(instruction: Instruction, stack: MStack) {
            if (++counter >= interval) {
                counter = 0
                check()
            }
        }

        fun reset(deadlineMillis: Long) {
            counter = 0
            deadline = deadlineMillis
            check()
        }

        /** Makes any further instruction fail, so nothing runs outside an invocation. */
        fun expire() {
            deadline = clock() - 1
        }

        fun check() {
            if (clock() > deadline) throw BudgetExceeded("DEADLINE")
        }
    }

    private companion object {
        const val PAGE_SIZE_BYTES = 65_536L
        const val MAX_PAGES = 65_536

        /** Instantiation is bounded too: a hostile module must not stall in `_start` or data init. */
        const val LOAD_DEADLINE_MILLIS = 5_000L
        const val MAX_LOG_BYTES = 1_024
        const val MAX_RANDOM_BYTES = 4_096
    }
}
