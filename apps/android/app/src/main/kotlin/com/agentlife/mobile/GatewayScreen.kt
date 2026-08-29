package com.agentlife.mobile

data class GatewayDestination(
    val baseUrl: String,
    val isConnected: Boolean = false,
    val activeAccountId: String? = null,
)

data class GatewayState(
    val endpointUrl: String = "https://gw.example.com",
    val accounts: List<String> = emptyList(),
    val isOnline: Boolean = false,
)

class GatewayPresenter(
    private var state: GatewayState = GatewayState(),
) {
    fun currentState(): GatewayState = state

    fun connect(url: String) {
        state = state.copy(endpointUrl = url, isOnline = true)
    }

    fun disconnect() {
        state = state.copy(isOnline = false)
    }
}

