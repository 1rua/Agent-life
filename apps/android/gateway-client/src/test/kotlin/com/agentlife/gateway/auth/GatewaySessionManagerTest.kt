package com.agentlife.gateway.auth

import com.agentlife.gateway.account.AccountProfile
import com.agentlife.gateway.account.AccountProfileStore
import com.agentlife.gateway.account.InMemoryAccountProfileStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Session lifecycle contract: the password never reaches persistence, the
 * refresh credential rotates on every resume, logging out keeps the profile
 * while removing the account deletes it, and unpairing revokes server-side
 * material.
 */
class GatewaySessionManagerTest {

    private fun manager(
        profileStore: AccountProfileStore = InMemoryAccountProfileStore(),
        credentialStore: GatewayCredentialStore = FakeGatewayCredentialStore(),
        transport: GatewayAuthTransport = FakeGatewayAuthTransport(),
    ) = GatewaySessionManager(profileStore, credentialStore, transport)

    private fun profile(id: String = "profile-a") = AccountProfile(
        localProfileId = id,
        gatewayBaseUrl = "https://gateway.example.com",
        username = "user-$id",
        tlsTrustId = "trust-$id",
    )

    @Test
    fun passwordIsNeverPersistedAndRefreshRotates() {
        val profileStore = InMemoryAccountProfileStore().apply { save(profile()) }
        val fakeStore = FakeGatewayCredentialStore()
        fakeStore.passwordUnderTest = "secret".toByteArray(Charsets.UTF_8)
        val manager = manager(profileStore = profileStore, credentialStore = fakeStore)

        manager.loginWithPassword("profile-a", "secret".toCharArray())
        assertNull("password must never reach persistence", fakeStore.persistedPassword)

        val first = fakeStore.refreshCredential("profile-a")
        assertNotNull(first)

        manager.resume("profile-a")
        val second = fakeStore.refreshCredential("profile-a")
        assertNotNull(second)
        assertNotEquals("refresh credential must rotate on resume", first!!.decode(), second!!.decode())

        manager.logout("profile-a")
        assertNull("logout clears the refresh credential", fakeStore.refreshCredential("profile-a"))
        assertNotNull("logout keeps the profile", profileStore.find("profile-a"))

        manager.removeLocalAccount("profile-a")
        assertNull("removeLocalAccount deletes the profile", profileStore.find("profile-a"))
    }

    @Test
    fun passwordBufferIsScrubbedAfterLogin() {
        val profileStore = InMemoryAccountProfileStore().apply { save(profile()) }
        val manager = manager(profileStore = profileStore)
        val password = "correct horse battery staple".toCharArray()

        manager.loginWithPassword("profile-a", password)

        assertTrue(
            "the caller's password buffer must be zeroed after use",
            password.all { it == '\u0000' },
        )
    }

    @Test
    fun resumeRotatesCredentialOnEveryCall() {
        val profileStore = InMemoryAccountProfileStore().apply { save(profile()) }
        val fakeStore = FakeGatewayCredentialStore()
        val manager = manager(profileStore = profileStore, credentialStore = fakeStore)

        manager.loginWithPassword("profile-a", "secret".toCharArray())
        val seen = linkedSetOf(fakeStore.refreshCredential("profile-a")!!.decode())
        repeat(3) {
            manager.resume("profile-a")
            seen += fakeStore.refreshCredential("profile-a")!!.decode()
        }

        assertEquals("every resume must mint a distinct credential", 4, seen.size)
    }

    @Test
    fun logoutKeepsProfileButClearsCredential() {
        val profileStore = InMemoryAccountProfileStore().apply { save(profile()) }
        val fakeStore = FakeGatewayCredentialStore()
        val manager = manager(profileStore = profileStore, credentialStore = fakeStore)

        manager.loginWithPassword("profile-a", "secret".toCharArray())
        manager.logout("profile-a")

        assertNull(fakeStore.refreshCredential("profile-a"))
        assertEquals(profile(), profileStore.find("profile-a"))
        assertEquals(listOf(profile()), profileStore.list())
    }

    @Test
    fun removeLocalAccountClearsProfileAndPluginAccountData() {
        val profileStore = InMemoryAccountProfileStore().apply { save(profile()) }
        val fakeStore = FakeGatewayCredentialStore()
        val manager = manager(profileStore = profileStore, credentialStore = fakeStore)

        manager.loginWithPassword("profile-a", "secret".toCharArray())
        manager.removeLocalAccount("profile-a")

        assertNull(profileStore.find("profile-a"))
        assertNull("removing the account must also clear its credentials", fakeStore.refreshCredential("profile-a"))
        assertTrue(profileStore.list().isEmpty())
    }

    @Test
    fun unpairRevokesCredentialWithoutDeletingProfile() {
        val profileStore = InMemoryAccountProfileStore().apply { save(profile()) }
        val fakeStore = FakeGatewayCredentialStore()
        val transport = FakeGatewayAuthTransport()
        val manager = manager(
            profileStore = profileStore,
            credentialStore = fakeStore,
            transport = transport,
        )

        manager.loginWithPassword("profile-a", "secret".toCharArray())
        val credentialBeforeUnpair = fakeStore.refreshCredential("profile-a")!!.copyOf()
        manager.unpair("profile-a")

        assertEquals(
            "unpair must revoke the device once, with the current credential",
            listOf("profile-a"),
            transport.unpairedProfileIds,
        )
        assertArrayEquals(
            credentialBeforeUnpair,
            transport.unpairCredentials.single(),
        )
        assertNull(fakeStore.refreshCredential("profile-a"))
        assertNotNull(profileStore.find("profile-a"))
    }

    @Test
    fun resumeWithoutCredentialFailsClosed() {
        val profileStore = InMemoryAccountProfileStore().apply { save(profile()) }
        val manager = manager(profileStore = profileStore)

        val error = runCatching { manager.resume("profile-a") }.exceptionOrNull()
        assertNotNull("resume with no stored credential must fail", error)
        assertTrue(error!!.message!!.contains("NO_REFRESH_CREDENTIAL"))
    }

    private fun ByteArray.decode(): String = String(this, Charsets.UTF_8)
}
