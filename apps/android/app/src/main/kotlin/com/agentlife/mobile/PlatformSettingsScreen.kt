package com.agentlife.mobile

data class DistributionPolicy(
    val allowRuntimePlugins: Boolean,
    val allowDeveloperTrustMode: Boolean,
)

data class PlatformSettingsState(
    val developerTrustModeEnabled: Boolean = false,
    val installedPluginsCount: Int = 0,
    val auditLogEntriesCount: Int = 0,
)

class PlatformSettingsPresenter(
    private val policy: DistributionPolicy,
    private var state: PlatformSettingsState = PlatformSettingsState(),
) {
    fun currentState(): PlatformSettingsState = state

    fun toggleDeveloperTrustMode(enabled: Boolean): Boolean {
        if (!policy.allowDeveloperTrustMode && enabled) {
            return false
        }
        state = state.copy(developerTrustModeEnabled = enabled)
        return true
    }
}

