package com.openandroidintelligence.gateway.auth

import com.openandroidintelligence.gateway.account.AccountProfile

/**
 * Records what the session manager hands to the credential store so a test can
 * assert the password never reaches persistence: [persistedPassword] is only
 * non-null when a stored credential is byte-identical to the password under
 * test.
 */
internal class FakeGatewayCredentialStore : GatewayCredentialStore {

    private val credentials = LinkedHashMap<String, ByteArray>()
    private val deviceKeys = LinkedHashMap<String, Unit>()

    var passwordUnderTest: ByteArray = ByteArray(0)

    val persistedPassword: ByteArray?
        get() = credentials.values.firstOrNull { it.contentEquals(passwordUnderTest) }
            ?.takeIf { passwordUnderTest.isNotEmpty() }

    fun refreshCredential(profileId: String): ByteArray? = credentials[profileId]?.copyOf()

    fun hasDeviceKey(profileId: String): Boolean = deviceKeys.containsKey(profileId)

    override fun saveRefresh(profileId: String, credential: ByteArray) {
        credentials[profileId] = credential.copyOf()
        deviceKeys[profileId] = Unit
    }

    override fun loadRefresh(profileId: String): ByteArray? = credentials[profileId]?.copyOf()

    override fun clearRefresh(profileId: String) {
        credentials.remove(profileId)
    }

    override fun clearDeviceKey(profileId: String) {
        deviceKeys.remove(profileId)
    }
}

/**
 * Mints a distinct refresh credential per call, which is what makes the
 * rotation assertion meaningful.
 */
internal class FakeGatewayAuthTransport : GatewayAuthTransport {

    private var counter = 0

    val unpairedProfileIds = mutableListOf<String>()
    val unpairCredentials = mutableListOf<ByteArray>()

    override fun loginWithPassword(profile: AccountProfile, password: CharArray): ByteArray {
        require(password.isNotEmpty()) { "password must be supplied for login" }
        return mint(profile)
    }

    override fun refresh(profile: AccountProfile, credential: ByteArray): ByteArray = mint(profile)

    override fun unpair(profile: AccountProfile, credential: ByteArray?) {
        unpairedProfileIds += profile.localProfileId
        unpairCredentials += credential?.copyOf() ?: ByteArray(0)
    }

    private fun mint(profile: AccountProfile): ByteArray =
        "refresh-${profile.localProfileId}-${++counter}".toByteArray(Charsets.UTF_8)
}
