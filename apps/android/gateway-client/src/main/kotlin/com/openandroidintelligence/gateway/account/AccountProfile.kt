package com.openandroidintelligence.gateway.account

/**
 * One local view of one Gateway account.
 *
 * A profile is the boundary of isolation: every account owns its own key
 * material, credential slot, queue and audit directory. Nothing in this class
 * holds a secret.
 */
data class AccountProfile(
    val localProfileId: String,
    val gatewayBaseUrl: String,
    val username: String,
    val tlsTrustId: String,
) {
    init {
        require(localProfileId.isNotBlank()) { "localProfileId must not be blank" }
        require(gatewayBaseUrl.startsWith("https://")) { "gateway base url must be https" }
        require(username.isNotBlank()) { "username must not be blank" }
        require(tlsTrustId.isNotBlank()) { "tlsTrustId must not be blank" }
    }
}
