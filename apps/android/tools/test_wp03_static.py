#!/usr/bin/env python3
"""SDK-free WP-03 contract tests.

These checks intentionally inspect the Kotlin boundary when the Android/Gradle
toolchain is unavailable.  The executable Kotlin/JVM tests live beside each
module and are the authoritative implementation tests when the lock is
installed.
"""
from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]


class Wp03StaticBoundaryTest(unittest.TestCase):
    def read(self, relative: str) -> str:
        return (ROOT / relative).read_text(encoding="utf-8")

    def test_contracts_define_closed_policy_and_record_unions(self):
        source = self.read(
            "core-model/src/main/kotlin/com/agentlife/core/model/NotificationContracts.kt"
        )
        self.assertIn("sealed interface NotificationRecordV1", source)
        self.assertIn("data class Upsert", source)
        self.assertIn("data class DeleteTombstone", source)
        self.assertIn("data class LossMarker", source)
        self.assertIn("sourceCapability", source)
        self.assertIn("NotificationCollectionPolicyV1", source)
        self.assertIn("ALLOWLIST", source)
        self.assertIn("DENYLIST", source)
        self.assertRegex(source, r"policyRevision\s*:\s*ULong")

    def test_policy_engine_has_default_deny_and_authorization_gate(self):
        source = self.read(
            "policy-engine/src/main/kotlin/com/agentlife/policy/NotificationPolicyEvaluator.kt"
        )
        self.assertIn("default", source.lower())
        self.assertIn("NotificationAuthorization", source)
        self.assertRegex(source, r"ALLOWLIST|DENYLIST")
        self.assertIn("content", source.lower())

    def test_collector_does_not_persist_before_policy_and_strips_content(self):
        source = self.read(
            "notification-collector/src/main/kotlin/com/agentlife/notifications/AndroidNotificationCollector.kt"
        )
        self.assertIn("NotificationListenerService", source)
        self.assertIn("captureOnDemand", source)
        self.assertIn("observeAutoSend", source)
        self.assertIn("else {\n            null", source)
        self.assertIn("policyRevision", source)
        self.assertIn("copy(title = null, body = null)", source)

    def test_removed_notification_rechecks_current_authorization_before_tombstone(self):
        source = self.read(
            "notification-collector/src/main/kotlin/com/agentlife/notifications/AndroidNotificationCollector.kt"
        )
        removed = re.search(
            r"fun onRemoved\(.*?^\s*}\n\n\s*/\*\* Loss is explicit",
            source,
            re.MULTILINE | re.DOTALL,
        )
        self.assertIsNotNone(removed)
        self.assertIn("evaluator.evaluate", removed.group(0))

    def test_queue_loss_checks_package_authorization_not_notification_keys(self):
        source = self.read(
            "notification-collector/src/main/kotlin/com/agentlife/notifications/AndroidNotificationCollector.kt"
        )
        loss = re.search(
            r"fun onQueueLoss\(.*?^\s*}\n\n\s*private fun toUpsert",
            source,
            re.MULTILINE | re.DOTALL,
        )
        self.assertIsNotNone(loss)
        self.assertIn("active.values", loss.group(0))
        self.assertIn("raw.packageName", loss.group(0))

    def test_outbox_encrypts_persisted_bytes_and_requires_ack_verifier(self):
        source = self.read(
            "encrypted-store/src/main/kotlin/com/agentlife/encrypted/store/NotificationOutboxStore.kt"
        )
        self.assertIn("AES/GCM/NoPadding", source)
        self.assertIn("recoverUnacknowledged", source)
        self.assertIn("acknowledge", source)
        self.assertIn("EventAckVerifier", source)
        self.assertIn("GCMParameterSpec", source)
        self.assertNotRegex(source, r"\b(Log|println|printStackTrace)\b")

    def test_outbox_ack_restores_memory_when_persistence_commit_fails(self):
        source = self.read(
            "encrypted-store/src/main/kotlin/com/agentlife/encrypted/store/NotificationOutboxStore.kt"
        )
        ack = re.search(
            r"fun acknowledgeBlocking\(.*?^\s*}\n\n\s*override suspend fun recoverUnacknowledged",
            source,
            re.MULTILINE | re.DOTALL,
        )
        self.assertIsNotNone(ack)
        self.assertIn("try", ack.group(0))
        self.assertIn("events.clear()", ack.group(0))
        self.assertIn("events.putAll", ack.group(0))

    def test_no_forbidden_network_or_vpn_surfaces_added(self):
        forbidden = re.compile(
            r"VpnService|BIND_VPN_SERVICE|TunInterface|\bTUN\b|addRoute|"
            r"setHttpProxy|ProxyInfo|LocalAPI|\bListen\s*\(|\bDial\s*\(",
            re.IGNORECASE,
        )
        roots = [ROOT / "core-model", ROOT / "capability-ports", ROOT / "policy-engine", ROOT / "notification-collector", ROOT / "encrypted-store"]
        violations = []
        for root in roots:
            for path in root.rglob("*"):
                if path.is_file() and path.suffix in {".kt", ".java", ".xml"}:
                    for line_no, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
                        if forbidden.search(line):
                            violations.append(f"{path}:{line_no}")
        self.assertEqual([], violations)


if __name__ == "__main__":
    unittest.main()
