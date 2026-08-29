package com.agentlife.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DistributionVariantTest {

    @Test
    fun fullVariantAllowsRuntimePluginsAndTrustMode() {
        val policy = DistributionPolicy(
            allowRuntimePlugins = true,
            allowDeveloperTrustMode = true,
        )
        assertTrue(policy.allowRuntimePlugins)
        assertTrue(policy.allowDeveloperTrustMode)
    }

    @Test
    fun playVariantForbidsRuntimePluginsAndTrustMode() {
        val policy = DistributionPolicy(
            allowRuntimePlugins = false,
            allowDeveloperTrustMode = false,
        )
        assertFalse(policy.allowRuntimePlugins)
        assertFalse(policy.allowDeveloperTrustMode)
    }
}

