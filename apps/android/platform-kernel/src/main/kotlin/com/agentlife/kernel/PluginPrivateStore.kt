package com.agentlife.kernel

/** Raised when a plugin reaches for storage that is not its own. */
class StorageDenied(code: String) : IllegalArgumentException("STORAGE_DENIED:$code")

/**
 * Where a plugin's mutable data lives: partitioned by plugin identity, Gateway
 * account and this Android installation.
 *
 * Code and static assets are shared at phone level, but mutable data must never
 * leak across accounts implicitly, so the partition key is part of every
 * operation and is re-checked on every call rather than trusted from the caller.
 */
interface PrivateStoreBackend {
    fun read(partition: String, key: String): ByteArray?
    fun write(partition: String, key: String, value: ByteArray)
    fun delete(partition: String, key: String)
    fun usedBytes(partition: String): Long
    fun keys(partition: String): Set<String>
}

/** An in-process backend. The shipped host replaces it with an encrypted store. */
class InMemoryPrivateStoreBackend : PrivateStoreBackend {
    private val data = HashMap<String, MutableMap<String, ByteArray>>()

    override fun read(partition: String, key: String): ByteArray? =
        data[partition]?.get(key)?.copyOf()

    override fun write(partition: String, key: String, value: ByteArray) {
        data.getOrPut(partition) { HashMap() }[key] = value.copyOf()
    }

    override fun delete(partition: String, key: String) {
        data[partition]?.remove(key)
    }

    override fun usedBytes(partition: String): Long =
        data[partition]?.values?.sumOf { it.size.toLong() } ?: 0L

    override fun keys(partition: String): Set<String> =
        data[partition]?.keys?.toSet() ?: emptySet()

    /** Wipes one plugin's data for one account, used when that account is removed. */
    fun clear(partitionPrefix: String) {
        data.keys.filter { it.startsWith(partitionPrefix) }.forEach { data.remove(it) }
    }
}

/**
 * An open partition, bound to one plugin and one account.
 *
 * The handle is a capability: holding it lets you act on exactly that
 * partition, and every call re-checks the account so a handle captured under
 * one account cannot be replayed against another.
 */
class StorageHandle internal constructor(
    internal val partition: String,
    val pluginId: String,
    val accountId: String,
    val installId: String,
)

class PluginPrivateStore(
    private val installId: String,
    private val backend: PrivateStoreBackend,
    private val maxBytesPerPartition: Long,
    private val maxKeyLength: Int = 256,
) {
    fun open(pluginId: String, accountId: String): StorageHandle =
        StorageHandle(
            partition = partitionOf(pluginId, accountId),
            pluginId = pluginId,
            accountId = accountId,
            installId = installId,
        )

    fun read(handle: StorageHandle, accountId: String, key: String): ByteArray? {
        checkScope(handle, accountId, key)
        return backend.read(handle.partition, key)
    }

    fun write(handle: StorageHandle, accountId: String, key: String, value: ByteArray) {
        checkScope(handle, accountId, key)
        val current = backend.usedBytes(handle.partition)
        val existing = backend.read(handle.partition, key)?.size?.toLong() ?: 0L
        if (current - existing + value.size > maxBytesPerPartition) {
            throw StorageDenied("QUOTA")
        }
        backend.write(handle.partition, key, value)
    }

    fun delete(handle: StorageHandle, accountId: String, key: String) {
        checkScope(handle, accountId, key)
        backend.delete(handle.partition, key)
    }

    fun keys(handle: StorageHandle, accountId: String): Set<String> {
        if (handle.accountId != accountId) throw StorageDenied("ACCOUNT_MISMATCH")
        if (handle.installId != installId) throw StorageDenied("INSTALL_MISMATCH")
        return backend.keys(handle.partition)
    }

    /** Removes every partition belonging to one account, on account removal. */
    fun eraseAccount(accountId: String) {
        val backendAsMemory = backend as? InMemoryPrivateStoreBackend
        backendAsMemory?.clear("$installId|$accountId|")
    }

    private fun checkScope(handle: StorageHandle, accountId: String, key: String) {
        if (handle.accountId != accountId) throw StorageDenied("ACCOUNT_MISMATCH")
        if (handle.installId != installId) throw StorageDenied("INSTALL_MISMATCH")
        if (key.isEmpty() || key.length > maxKeyLength) throw StorageDenied("BAD_KEY")
    }

    private fun partitionOf(pluginId: String, accountId: String): String =
        "$installId|$accountId|$pluginId"
}
