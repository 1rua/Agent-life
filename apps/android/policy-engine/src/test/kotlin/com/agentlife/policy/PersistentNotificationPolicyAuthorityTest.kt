package com.agentlife.policy

import com.agentlife.core.model.NotificationCollectionPolicyV1
import com.agentlife.core.model.NotificationDeliveryMode
import com.agentlife.core.model.NotificationFieldAccess
import com.agentlife.core.model.NotificationRuleMode
import com.agentlife.core.model.PolicyRevisionRace
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentNotificationPolicyAuthorityTest {
    @Test
    fun delivery_mode_round_trips_with_grant_and_policy() {
        val persistence = InMemoryNotificationPolicyPersistence()
        val first = PersistentNotificationPolicyAuthority(persistence)
        val policy = NotificationCollectionPolicyV1(
            mode = NotificationRuleMode.ALLOWLIST,
            packageIds = listOf("com.example.mail"),
            fieldAccess = NotificationFieldAccess.CONTENT,
            policyRevision = 1u,
        )
        first.localController().apply(
            policy,
            authorizationRevision = 1u,
            granted = true,
            deliveryMode = NotificationDeliveryMode.AUTO_SEND,
        )

        val restored = PersistentNotificationPolicyAuthority(persistence).snapshot()
        assertEquals(NotificationDeliveryMode.AUTO_SEND, restored.deliveryMode)
        assertEquals(true, restored.granted)
        assertEquals(1uL, restored.policy.policyRevision)
    }

    @Test
    fun changing_delivery_mode_without_an_authorization_revision_is_rejected() {
        val authority = PersistentNotificationPolicyAuthority(InMemoryNotificationPolicyPersistence())
        val controller = authority.localController()
        controller.apply(
            NotificationCollectionPolicyV1.default(),
            1u,
            true,
            NotificationDeliveryMode.ON_DEMAND,
        )

        assertThrows(PolicyRevisionRace::class.java) {
            controller.apply(
                NotificationCollectionPolicyV1.default(),
                1u,
                true,
                NotificationDeliveryMode.AUTO_SEND,
            )
        }
    }

    @Test
    fun corrupt_persisted_bytes_restore_as_deny_first_corrupted_state() {
        val persistence = InMemoryNotificationPolicyPersistence()
        persistence.write(byteArrayOf(0x41, 0x42, 0x43))

        val snapshot = PersistentNotificationPolicyAuthority(persistence).snapshot()
        assertFalse(snapshot.granted)
        assertTrue(snapshot.corrupted)
    }

    @Test
    fun legacy_v1_bytes_restore_with_on_demand_delivery() {
        val persistence = InMemoryNotificationPolicyPersistence()
        persistence.write(legacyV1Bytes())

        val snapshot = PersistentNotificationPolicyAuthority(persistence).snapshot()
        assertEquals(7uL, snapshot.authorizationRevision)
        assertTrue(snapshot.granted)
        assertEquals(NotificationRuleMode.ALLOWLIST, snapshot.policy.mode)
        assertEquals(NotificationFieldAccess.METADATA, snapshot.policy.fieldAccess)
        assertEquals(3uL, snapshot.policy.policyRevision)
        assertEquals(listOf("mail"), snapshot.policy.packageIds)
        assertEquals(NotificationDeliveryMode.ON_DEMAND, snapshot.deliveryMode)
    }

    @Test
    fun three_argument_apply_and_revoke_preserve_delivery_mode() {
        val authority = PersistentNotificationPolicyAuthority(InMemoryNotificationPolicyPersistence())
        val controller = authority.localController()
        val policy = NotificationCollectionPolicyV1.default()
        controller.apply(policy, 1u, true, NotificationDeliveryMode.AUTO_SEND)

        controller.apply(policy, 2u, true)
        assertEquals(NotificationDeliveryMode.AUTO_SEND, authority.snapshot().deliveryMode)

        controller.revoke(3u)
        assertEquals(NotificationDeliveryMode.AUTO_SEND, authority.snapshot().deliveryMode)
        assertFalse(authority.snapshot().granted)
    }

    private fun legacyV1Bytes(): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            writeLegacyString(output, "AGENT_LIFE_NOTIFICATION_AUTHORITY_V1")
            output.writeLong(7L)
            output.writeBoolean(true)
            output.writeByte(NotificationRuleMode.ALLOWLIST.ordinal)
            output.writeByte(NotificationFieldAccess.METADATA.ordinal)
            output.writeLong(3L)
            output.writeInt(1)
            writeLegacyString(output, "mail")
        }
        bytes.toByteArray()
    }

    private fun writeLegacyString(output: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        output.writeInt(bytes.size)
        output.write(bytes)
    }
}
