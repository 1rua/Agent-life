package com.openandroidintelligence.kernel

/**
 * Developer trust mode is the only door to native plugins.
 *
 * A native plugin runs in the host process and shares its UID, so it can read
 * every permission the host holds and every byte the host can reach. That is
 * why enabling this mode requires an explicit acknowledgement, and why leaving
 * it stops every native plugin immediately rather than at the next restart.
 */
class DeveloperTrustMode {
    /** The acknowledgement the host must collect before the mode can be turned on. */
    data class Acknowledgement(val text: String) {
        companion object {
            /**
             * The host must show native code shares the process and UID: this
             * string is the proof it did, not a licence agreement the user
             * scrolls past.
             */
            const val REQUIRED_TEXT =
                "Native plugins run inside this app and can read every permission and all data this app can reach."
        }
    }

    @Volatile
    private var enabled = false

    private val listeners = mutableListOf<(Boolean) -> Unit>()

    fun isEnabled(): Boolean = enabled

    /** Returns false when the acknowledgement does not match what the host had to show. */
    fun enable(acknowledgement: Acknowledgement): Boolean {
        if (acknowledgement.text != Acknowledgement.REQUIRED_TEXT) return false
        if (enabled) return true
        enabled = true
        notifyListeners(true)
        return true
    }

    fun disable() {
        if (!enabled) return
        enabled = false
        // Order matters: listeners unload native code before anything else can
        // observe the new state and try to start a plugin again.
        notifyListeners(false)
    }

    /**
     * Registers the hook that tears native plugins down.
     *
     * The hook is invoked immediately on registration when the mode is already
     * off, so a component created after the mode was disabled cannot miss the
     * transition.
     */
    fun onChange(listener: (Boolean) -> Unit) {
        listeners += listener
        listener(enabled)
    }

    private fun notifyListeners(value: Boolean) {
        listeners.toList().forEach { it(value) }
    }
}
