package com.openandroidintelligence.gateway.account

interface AccountProfileStore {
    fun list(): List<AccountProfile>

    fun find(localProfileId: String): AccountProfile?

    fun save(profile: AccountProfile)

    fun delete(localProfileId: String)
}

/**
 * Process-local profile registry. Used as the default until the app wires a
 * persisted store; it is also the seam unit tests drive the session manager
 * through.
 */
class InMemoryAccountProfileStore : AccountProfileStore {

    private val profiles = LinkedHashMap<String, AccountProfile>()

    @Synchronized
    override fun list(): List<AccountProfile> = profiles.values.toList()

    @Synchronized
    override fun find(localProfileId: String): AccountProfile? = profiles[localProfileId]

    @Synchronized
    override fun save(profile: AccountProfile) {
        profiles[profile.localProfileId] = profile
    }

    @Synchronized
    override fun delete(localProfileId: String) {
        profiles.remove(localProfileId)
    }
}
