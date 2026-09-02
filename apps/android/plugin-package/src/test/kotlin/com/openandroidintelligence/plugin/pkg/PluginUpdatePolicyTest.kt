package com.openandroidintelligence.plugin.pkg

import org.junit.Assert.assertEquals
import org.junit.Test

class PluginUpdatePolicyTest {
    private val authorA = "A".repeat(43)
    private val authorB = "B".repeat(43)

    private val base = SecuritySurface(
        kernelPrimitives = setOf("kernel.notifications.read"),
        networkHosts = setOf("api.example.org"),
        maxStorageBytes = 1_000L,
        maxMemoryBytes = 2_000L,
        maxInvocationMillis = 1_000L,
        maxConcurrentInvocations = 1,
        maxDailyNetworkBytes = 0L,
        backgroundRequested = false,
        companionPackageName = null,
        nativeAbis = emptySet(),
    )

    private fun policy() = PluginUpdatePolicy()

    @Test
    fun rejectsDowngrade() {
        val result = policy().classify(
            current = InstalledSurface(authorKey = authorA, version = "1.2.0", surface = base),
            candidate = CandidateSurface(authorKey = authorA, version = "1.1.0", surface = base),
        )
        assertEquals(UpdateDecision.Reject("DOWNGRADE"), result)
    }

    @Test
    fun rejectsDifferentAuthorKey() {
        val result = policy().classify(
            current = InstalledSurface(authorKey = authorA, version = "1.2.0", surface = base),
            candidate = CandidateSurface(authorKey = authorB, version = "1.3.0", surface = base),
        )
        assertEquals(UpdateDecision.Reject("AUTHOR_MISMATCH"), result)
    }

    @Test
    fun autoAppliesSameKeyWideningFreeUpdate() {
        val result = policy().classify(
            current = InstalledSurface(authorKey = authorA, version = "1.2.0", surface = base),
            candidate = CandidateSurface(authorKey = authorA, version = "1.3.0", surface = base),
        )
        assertEquals(UpdateDecision.AutoApply, result)
    }

    @Test
    fun requiresApprovalForNewKernelPrimitive() {
        val widened = base.copy(kernelPrimitives = base.kernelPrimitives + "kernel.sms.read")
        val result = policy().classify(
            current = InstalledSurface(authorKey = authorA, version = "1.2.0", surface = base),
            candidate = CandidateSurface(authorKey = authorA, version = "1.3.0", surface = widened),
        )
        val approval = result as? UpdateDecision.RequireApproval
            ?: error("expected approval, got \$result")
        assert(approval.reasons.contains("KERNEL_PRIMITIVE_ADDED")) { "reasons=${approval.reasons}" }
    }

    @Test
    fun requiresApprovalForNewNetworkHost() {
        val widened = base.copy(networkHosts = base.networkHosts + "evil.example.org")
        val result = policy().classify(
            current = InstalledSurface(authorKey = authorA, version = "1.2.0", surface = base),
            candidate = CandidateSurface(authorKey = authorA, version = "1.3.0", surface = widened),
        )
        val approval = result as? UpdateDecision.RequireApproval
            ?: error("expected approval, got \$result")
        assert(approval.reasons.contains("NETWORK_HOST_ADDED")) { "reasons=${approval.reasons}" }
    }

    @Test
    fun requiresApprovalForRaisedResourceLimits() {
        val widened = base.copy(maxStorageBytes = base.maxStorageBytes * 2)
        val result = policy().classify(
            current = InstalledSurface(authorKey = authorA, version = "1.2.0", surface = base),
            candidate = CandidateSurface(authorKey = authorA, version = "1.3.0", surface = widened),
        )
        val approval = result as? UpdateDecision.RequireApproval
            ?: error("expected approval, got \$result")
        assert(approval.reasons.contains("RESOURCE_LIMIT_RAISED")) { "reasons=${approval.reasons}" }
    }

    @Test
    fun requiresApprovalForEnabledBackground() {
        val widened = base.copy(backgroundRequested = true)
        val result = policy().classify(
            current = InstalledSurface(authorKey = authorA, version = "1.2.0", surface = base),
            candidate = CandidateSurface(authorKey = authorA, version = "1.3.0", surface = widened),
        )
        val approval = result as? UpdateDecision.RequireApproval
            ?: error("expected approval, got \$result")
        assert(approval.reasons.contains("BACKGROUND_ENABLED")) { "reasons=${approval.reasons}" }
    }

    @Test
    fun requiresApprovalForNewCompanion() {
        val widened = base.copy(companionPackageName = "org.example.companion")
        val result = policy().classify(
            current = InstalledSurface(authorKey = authorA, version = "1.2.0", surface = base),
            candidate = CandidateSurface(authorKey = authorA, version = "1.3.0", surface = widened),
        )
        val approval = result as? UpdateDecision.RequireApproval
            ?: error("expected approval, got \$result")
        assert(approval.reasons.contains("COMPANION_ADDED")) { "reasons=${approval.reasons}" }
    }

    @Test
    fun narrowingSecurityIsAutoApplied() {
        val narrowed = base.copy(networkHosts = emptySet(), maxStorageBytes = base.maxStorageBytes / 2)
        val result = policy().classify(
            current = InstalledSurface(authorKey = authorA, version = "1.2.0", surface = base),
            candidate = CandidateSurface(authorKey = authorA, version = "1.3.0", surface = narrowed),
        )
        assertEquals(UpdateDecision.AutoApply, result)
    }
}
