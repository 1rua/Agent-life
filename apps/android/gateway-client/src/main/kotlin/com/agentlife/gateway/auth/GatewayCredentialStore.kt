package com.agentlife.gateway.auth

/**
 * Owns the per-account secrets held in Android Keystore: the refresh
 * credential and the device key. Everything is addressed by
 * [profileId], so material belonging to one account is never reachable
 * through another account's id.
 */
interface GatewayCredentialStore {
    fun saveRefresh(profileId: String, credential: ByteArray)

    fun loadRefresh(profileId: String): ByteArray?

    fun clearRefresh(profileId: String)

    /**
     * Deletes the account's device key and any other Keystore entries owned by
     * it. Unpairing calls this so a revoked pairing leaves no local key behind.
     */
    fun clearDeviceKey(profileId: String)
}
