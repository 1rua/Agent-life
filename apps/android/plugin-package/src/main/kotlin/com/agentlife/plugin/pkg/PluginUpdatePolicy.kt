package com.agentlife.plugin.pkg

/**
 * The security surface of a plugin: the part of a package that, if widened,
 * must be re-approved by the user before the update is applied.
 *
 * Per device-plugin-package-v1 §9, widening any of these forces approval.
 */
data class SecuritySurface(
    val kernelPrimitives: Set<String>,
    val networkHosts: Set<String>,
    val maxStorageBytes: Long,
    val maxMemoryBytes: Long,
    val maxInvocationMillis: Long,
    val maxConcurrentInvocations: Int,
    val maxDailyNetworkBytes: Long,
    val backgroundRequested: Boolean,
    val companionPackageName: String?,
    val nativeAbis: Set<String>,
)

data class InstalledSurface(
    val authorKey: String,
    val version: String,
    val surface: SecuritySurface,
)

data class CandidateSurface(
    val authorKey: String,
    val version: String,
    val surface: SecuritySurface,
)

sealed interface UpdateDecision {
    data object AutoApply : UpdateDecision
    data class RequireApproval(val reasons: Set<String>) : UpdateDecision
    data class Reject(val reason: String) : UpdateDecision
}

/**
 * Decides how a candidate package may replace an installed one.
 *
 * Source URL, repository and index never participate: identity is
 * `(pluginId, SHA-256(authorKey))`, so a different author key is a different
 * plugin and cannot update this one.
 */
class PluginUpdatePolicy {
    fun classify(current: InstalledSurface, candidate: CandidateSurface): UpdateDecision {
        if (current.authorKey != candidate.authorKey) {
            return UpdateDecision.Reject("AUTHOR_MISMATCH")
        }
        if (compareSemVer(candidate.version, current.version) <= 0) {
            return UpdateDecision.Reject("DOWNGRADE")
        }

        val reasons = mutableSetOf<String>()
        val before = current.surface
        val after = candidate.surface

        if (after.kernelPrimitives - before.kernelPrimitives != emptySet<String>()) {
            reasons += "KERNEL_PRIMITIVE_ADDED"
        }
        if (after.networkHosts - before.networkHosts != emptySet<String>()) {
            reasons += "NETWORK_HOST_ADDED"
        }
        if (after.nativeAbis - before.nativeAbis != emptySet<String>()) {
            reasons += "NATIVE_ABI_ADDED"
        }
        if (after.companionPackageName != null && before.companionPackageName == null) {
            reasons += "COMPANION_ADDED"
        }
        if (after.backgroundRequested && !before.backgroundRequested) {
            reasons += "BACKGROUND_ENABLED"
        }
        if (
            after.maxStorageBytes > before.maxStorageBytes ||
            after.maxMemoryBytes > before.maxMemoryBytes ||
            after.maxInvocationMillis > before.maxInvocationMillis ||
            after.maxConcurrentInvocations > before.maxConcurrentInvocations ||
            after.maxDailyNetworkBytes > before.maxDailyNetworkBytes
        ) {
            reasons += "RESOURCE_LIMIT_RAISED"
        }

        return if (reasons.isEmpty()) UpdateDecision.AutoApply else UpdateDecision.RequireApproval(reasons)
    }

    internal fun compareSemVer(left: String, right: String): Int {
        val a = parseSemVer(left) ?: return 0
        val b = parseSemVer(right) ?: return 0
        for (i in 0 until 3) {
            if (a[i] != b[i]) return a[i].compareTo(b[i])
        }
        return 0
    }

    private fun parseSemVer(value: String): List<Int>? {
        val core = value.substringBefore("-").substringBefore("+")
        val parts = core.split(".")
        if (parts.size != 3) return null
        return parts.map { it.toIntOrNull() ?: return null }
    }
}
