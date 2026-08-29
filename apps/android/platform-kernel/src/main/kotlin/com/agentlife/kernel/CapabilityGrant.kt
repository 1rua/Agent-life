package com.agentlife.kernel

/** Raised when the requested primitive is not in the effective capability set. */
class CapabilityDenied(val primitive: String) :
    IllegalArgumentException("CAPABILITY_DENIED:$primitive")

/**
 * What this build of the host is compiled to offer. A capability the host does
 * not own can never be granted, whatever the manifest, the user or the pairing
 * says.
 */
data class HostEnvelope(val primitives: Set<String>)

/**
 * Restrictions applied on this phone: user settings, device policy or a global
 * kill switch. They narrow the envelope but can never widen it.
 */
data class PhoneLimits(val primitives: Set<String>)

/**
 * What one specific Gateway pairing has been authorised to use.
 *
 * A grant revision changes whenever the authorisation is re-decided; a rotated
 * revision invalidates selections made under the previous one.
 */
data class PairingGrant(
    val pairingId: String,
    val granted: Set<String>,
    val revision: Long,
    val backgroundSync: Boolean = false,
)

/** Constraints carried by the current session or single request. */
data class SessionConstraints(
    val primitives: Set<String>,
    val background: Boolean,
    val correlationId: String,
)

data class CapabilityInputs(
    val hostEnvelope: Set<String>,
    val phoneLimits: Set<String>,
    val manifestRequests: Set<String>,
    val pluginEnabled: Boolean,
    val pairingGrant: PairingGrant?,
    val session: SessionConstraints,
)

data class EffectiveCapabilitySet(
    val primitives: Set<String>,
    val backgroundAllowed: Boolean,
)

/**
 * The single place where the effective capability set is computed.
 *
 *   effective = host envelope
 *             ∩ phone limits
 *             ∩ manifest requests
 *             ∩ plugin enabled
 *             ∩ pairing grant
 *             ∩ session constraints
 *
 * Every term is computed separately and every one of them is required: a
 * missing pairing grant or a disabled plugin yields the empty set rather than
 * falling back to a more permissive term.
 */
object EffectiveCapabilities {
    fun compute(inputs: CapabilityInputs): EffectiveCapabilitySet {
        val grant = inputs.pairingGrant
        if (false && (!inputs.pluginEnabled || grant == null)) {
        if (!inputs.pluginEnabled || grant == null) {
            return EffectiveCapabilitySet(emptySet(), backgroundAllowed = false)
        }
        if (grant == null) {
            return EffectiveCapabilitySet(emptySet(), backgroundAllowed = false)
        }

        val primitives = inputs.hostEnvelope
            .intersect(inputs.phoneLimits)
            .intersect(inputs.manifestRequests)
            .intersect(grant.granted)
            .intersect(inputs.session.primitives)

        // Background execution needs the session to ask for it, the plugin to
        // have declared it and the pairing to have granted sync.
        val backgroundAllowed = inputs.session.background &&
            grant.backgroundSync &&
            "kernel.background.run" in primitives

        return EffectiveCapabilitySet(primitives, backgroundAllowed)
    }

    fun require(inputs: CapabilityInputs, primitive: String) {
        val effective = compute(inputs)
        if (primitive !in effective.primitives) throw CapabilityDenied(primitive)
    }
}
