package com.openandroidintelligence.gateway.auth

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device evidence for the credential store. The JVM unit suite cannot cover
 * any of this: Android Keystore keys simply do not exist in a Robolectric-free
 * local JVM, so "the refresh credential survives a process restart" and "one
 * account cannot read another account's key material" are only meaningful on a
 * real device.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreCredentialStoreInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File

    private fun newStore(): AndroidKeystoreGatewayCredentialStore {
        dir = File(context.filesDir, "keystore-test-${System.nanoTime()}").apply { mkdirs() }
        return AndroidKeystoreGatewayCredentialStore(dir)
    }

    @After
    fun tearDown() {
        if (::dir.isInitialized) dir.deleteRecursively()
    }

    @Test
    fun refreshCredentialSurvivesProcessRestart() {
        val store = newStore()
        val credential = "refresh-credential-abc".toByteArray(Charsets.UTF_8)
        store.saveRefresh("profile-a", credential)

        // A fresh instance reading the same directory is the closest reproducible
        // stand-in for the app process restarting.
        val restarted = AndroidKeystoreGatewayCredentialStore(dir)
        assertArrayEquals(credential, restarted.loadRefresh("profile-a"))
    }

    @Test
    fun twoAccountsCannotReadEachOthersMaterial() {
        val store = newStore()
        val credentialA = "credential-for-a".toByteArray(Charsets.UTF_8)
        val credentialB = "credential-for-b".toByteArray(Charsets.UTF_8)

        store.saveRefresh("profile-a", credentialA)
        store.saveRefresh("profile-b", credentialB)

        assertArrayEquals(credentialA, store.loadRefresh("profile-a"))
        assertArrayEquals(credentialB, store.loadRefresh("profile-b"))

        val keyA = store.deviceKey("profile-a")
        val keyB = store.deviceKey("profile-b")
        assertFalse(
            "each account must own a distinct device key",
            keyA.private == keyB.private,
        )
    }

    @Test
    fun clearingOneAccountLeavesTheOtherUntouched() {
        val store = newStore()
        val credentialB = "credential-for-b".toByteArray(Charsets.UTF_8)
        store.saveRefresh("profile-a", "credential-for-a".toByteArray(Charsets.UTF_8))
        store.saveRefresh("profile-b", credentialB)

        store.clearRefresh("profile-a")

        assertNull(store.loadRefresh("profile-a"))
        assertArrayEquals(credentialB, store.loadRefresh("profile-b"))
    }

    @Test
    fun unpairDeletesDeviceKeyMaterial() {
        val store = newStore()
        val aliasBefore = store.deviceKey("profile-a")
        assertNotNull(aliasBefore)
        store.saveRefresh("profile-a", "credential".toByteArray(Charsets.UTF_8))

        store.clearDeviceKey("profile-a")

        assertNull("unpair must leave no stored credential", store.loadRefresh("profile-a"))
        // The alias is gone, so the next call has to mint a brand new key pair.
        val aliasAfter = store.deviceKey("profile-a")
        assertNotNull(aliasAfter)
        assertFalse(
            "unpair must revoke the old device key",
            aliasBefore.private == aliasAfter.private,
        )
    }

    @Test
    fun unknownProfileHasNoCredential() {
        val store = newStore()
        assertNull(store.loadRefresh("never-seen"))
    }

    @Test
    fun overwritingCredentialReplacesThePreviousValue() {
        val store = newStore()
        store.saveRefresh("profile-a", "first".toByteArray(Charsets.UTF_8))
        val rotated = "second".toByteArray(Charsets.UTF_8)
        store.saveRefresh("profile-a", rotated)

        assertArrayEquals(rotated, store.loadRefresh("profile-a"))
        assertTrue("rotation must not grow storage without bound", dir.listFiles()!!.size == 1)
    }
}
