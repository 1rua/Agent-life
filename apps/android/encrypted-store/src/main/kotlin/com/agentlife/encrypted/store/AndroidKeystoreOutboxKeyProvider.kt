package com.agentlife.encrypted.store

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Provides a non-exportable AES-GCM key owned by Android Keystore. */
class AndroidKeystoreOutboxKeyProvider(
    private val alias: String = DEFAULT_ALIAS,
) {
    fun getOrCreate(): SecretKey {
        require(alias.isNotBlank()) { "outbox key alias must not be blank" }
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(alias, null)
        if (existing != null) return existing as? SecretKey ?: error("outbox alias is not a secret key")

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    companion object {
        const val DEFAULT_ALIAS = "agent_life_notification_outbox_v1"
    }
}
