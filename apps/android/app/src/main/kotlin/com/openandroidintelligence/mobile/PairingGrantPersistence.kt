package com.openandroidintelligence.mobile

import android.content.Context
import android.content.SharedPreferences
import com.openandroidintelligence.kernel.PairingGrantBinding
import com.openandroidintelligence.kernel.PairingGrantState
import com.openandroidintelligence.kernel.PairingGrantStore

/** SharedPreferences persistence for non-secret, user-controlled pairing state. */
class SharedPreferencesPairingGrantStore(
    private val preferences: SharedPreferences,
) : PairingGrantStore {

    override fun load(binding: PairingGrantBinding): PairingGrantState {
        val prefix = prefix(binding)
        val revision = preferences.getLong("$prefix.revision", 0L)
        val granted = preferences.getStringSet("$prefix.granted", emptySet()).orEmpty().toSet()
        val screenSelectionEnabled = preferences.getBoolean("$prefix.screen_selection", false)
        return runCatching {
            PairingGrantState(
                pairingId = binding.pairingId,
                granted = granted,
                revision = revision,
                screenSelectionEnabled = screenSelectionEnabled,
            )
        }.getOrDefault(PairingGrantState(binding.pairingId))
    }

    override fun save(binding: PairingGrantBinding, state: PairingGrantState) {
        require(state.pairingId == binding.pairingId) { "pairing grant binding mismatch" }
        val prefix = prefix(binding)
        check(
            preferences.edit()
                .putLong("$prefix.revision", state.revision)
                .putStringSet("$prefix.granted", state.granted.toSet())
                .putBoolean("$prefix.screen_selection", state.screenSelectionEnabled)
                .commit(),
        ) { "PAIRING_GRANT_PERSISTENCE_FAILED" }
    }

    override fun clear(binding: PairingGrantBinding) {
        val prefix = prefix(binding)
        check(
            preferences.edit()
                .remove("$prefix.revision")
                .remove("$prefix.granted")
                .remove("$prefix.screen_selection")
                .commit(),
        ) { "PAIRING_GRANT_CLEAR_FAILED" }
    }

    private fun prefix(binding: PairingGrantBinding): String =
        "pairing_grant_v1.${binding.storageKey}"

    companion object {
        fun from(context: Context): SharedPreferencesPairingGrantStore =
            SharedPreferencesPairingGrantStore(
                context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
            )

        private const val PREFERENCES_NAME = "open_android_intelligence_pairing_grants"
    }
}
