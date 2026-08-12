#!/usr/bin/env python3
"""Host checks for the closed Android capability-port contract.

The capability ports intentionally have no Android provider implementation in
this slice.  These checks protect the boundary while the real adapters remain
separately gated by platform permission and device tests.
"""

from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]
PORT_ROOT = ROOT / "capability-ports"
PORT_SOURCE = (
    PORT_ROOT
    / "src"
    / "main"
    / "kotlin"
    / "com"
    / "agentlife"
    / "capability"
    / "CapabilityPorts.kt"
)
PROVIDER_SOURCE = (
    PORT_ROOT
    / "src"
    / "main"
    / "kotlin"
    / "com"
    / "agentlife"
    / "capability"
    / "CapabilityProviderContracts.kt"
)
SMS_CONTRACT_SOURCE = (
    PORT_ROOT
    / "src"
    / "main"
    / "kotlin"
    / "com"
    / "agentlife"
    / "capability"
    / "SmsCapabilityContracts.kt"
)


class CapabilityPortsStaticTest(unittest.TestCase):
    def read_source(self) -> str:
        self.assertTrue(PORT_SOURCE.is_file(), PORT_SOURCE)
        return PORT_SOURCE.read_text(encoding="utf-8")

    def test_module_is_source_only_and_has_no_android_provider(self):
        build = (ROOT / "capability-ports" / "build.gradle.kts").read_text(
            encoding="utf-8"
        )
        self.assertIn('namespace = "com.agentlife.capability"', build)
        self.assertIn('implementation(project(":core-model"))', build)
        self.assertNotIn("NotificationListenerService", self.read_source())
        self.assertNotIn("ContentResolver", self.read_source())

    def test_capability_set_and_modes_are_closed(self):
        source = self.read_source()
        self.assertIn("enum class MobileDataCapability", source)
        for capability in (
            "NOTIFICATIONS",
            "SMS",
            "CALLS",
            "CONTACTS",
            "CLIPBOARD",
            "LOCATION",
            "HEALTH",
            "SENSORS",
            "CALENDAR",
            "ALARMS",
            "CURRENT_WINDOW",
            "SCREEN_CONTENT",
        ):
            self.assertRegex(source, rf"\b{capability}\b")
        self.assertIn("enum class DataSyncMode", source)
        self.assertIn("ON_DEMAND", source)
        self.assertIn("AUTO_SEND", source)
        self.assertIn("sealed interface CapabilityFilter", source)
        self.assertNotRegex(source, r"Map<\s*String\s*,|Set<\s*String\s*>")

    def test_notification_filter_is_closed_and_canonical(self):
        source = self.read_source()
        self.assertIn("data class Notifications", source)
        self.assertIn("NotificationFieldAccess", source)
        self.assertIn("compareUnicodeCodePoints", source)
        self.assertIn("package IDs must be unique", source)
        self.assertIn("package IDs must be sorted by Unicode code point", source)
        self.assertIn("data object Sms", source)
        self.assertIn("data object Contacts", source)

    def test_agent_request_requires_explicit_local_grant(self):
        source = self.read_source()
        self.assertIn("data class AgentDataRequest", source)
        self.assertIn("agentMayRequest", source)
        self.assertIn("AgentRequestAuthorization", source)
        self.assertIn("NO_LOCAL_GRANT", source)
        self.assertIn("AGENT_REQUESTS_DISABLED", source)
        self.assertIn("MODE_NOT_GRANTED", source)
        self.assertIn("POLICY_REVISION_STALE", source)
        self.assertRegex(source, r"grant\s*:\s*CapabilityGrant\?")
        self.assertIn("DEFAULT_DENY", source)

    def test_ports_separate_on_demand_and_auto_send_authorization(self):
        source = self.read_source()
        self.assertIn("AuthorizedOnDemandRequest", source)
        self.assertIn("AuthorizedAutoSendSubscription", source)
        self.assertIn("suspend fun read", source)
        self.assertIn("observeAutoSend", source)
        self.assertIn("CapabilityReadResult", source)
        self.assertIn("CapabilityEvent", source)
        self.assertIn("class DefaultAgentRequestAuthorizer", source)

    def test_no_root_shell_or_generic_process_escape_in_ports(self):
        source = self.read_source()
        forbidden = re.compile(
            r"Runtime\.getRuntime|ProcessBuilder|\bexec\s*\(|\bsh\s+-c\b|"
            r"/system/bin/sh|\broot\b|\bshell\b|Socket|ServerSocket|DatagramSocket",
            re.IGNORECASE,
        )
        self.assertEqual([], [line for line in source.splitlines() if forbidden.search(line)])

    def test_typed_provider_contracts_require_authorized_scopes_and_normalize_content(self):
        """Removing a provider scope check or returning raw content is a security bug."""
        self.assertTrue(PROVIDER_SOURCE.is_file(), PROVIDER_SOURCE)
        source = PROVIDER_SOURCE.read_text(encoding="utf-8")

        for provider, capability, payload in (
            ("SmsCapabilityProvider", "SMS", "SmsPayload"),
            ("CallsCapabilityProvider", "CALLS", "CallsPayload"),
            ("ContactsCapabilityProvider", "CONTACTS", "ContactsPayload"),
            ("ClipboardCapabilityProvider", "CLIPBOARD", "ClipboardPayload"),
            ("LocationCapabilityProvider", "LOCATION", "LocationPayload"),
            ("HealthCapabilityProvider", "HEALTH", "HealthPayload"),
            ("SensorsCapabilityProvider", "SENSORS", "SensorsPayload"),
            ("CalendarCapabilityProvider", "CALENDAR", "CalendarPayload"),
            ("AlarmsCapabilityProvider", "ALARMS", "AlarmsPayload"),
            ("CurrentWindowCapabilityProvider", "CURRENT_WINDOW", "CurrentWindowPayload"),
            ("ScreenContentCapabilityProvider", "SCREEN_CONTENT", "ScreenContentPayload"),
        ):
            self.assertIn(f"interface {provider}", source)
            self.assertIn(f"MobileDataCapability.{capability}", source)
            self.assertIn(f"data class {payload}", source)

        self.assertIn("data class AuthorizedReadScope internal constructor", source)
        self.assertIn("data class AuthorizedAutoSendScope internal constructor", source)
        self.assertIn("fun AuthorizedOnDemandRequest.requireReadScope", source)
        self.assertIn("fun AuthorizedAutoSendSubscription.requireAutoSendScope", source)
        self.assertIn("request.capability == capability", source)
        self.assertIn("request.policyRevision == grantRevision", source)
        self.assertIn("sealed interface NormalizedContent", source)
        self.assertIn("data object Withheld", source)
        self.assertIn("data class Released", source)
        self.assertIn("fun <T> normalizeContent", source)
        self.assertIn("NotificationFieldAccess.CONTENT", source)
        self.assertIn("class ScreenContentSnapshot private constructor", source)
        self.assertIn("fun copyBytes(): ByteArray = bytes.copyOf()", source)
        self.assertIn("ScreenContentSnapshot.copyOf(raw.content)", source)

        forbidden = re.compile(
            r"android\\.|ContentResolver|SmsManager|CallLog|ContactsContract|"
            r"HealthConnect|SensorManager|CalendarContract|AlarmManager|"
            r"AccessibilityService|MediaProjection|Runtime\\.getRuntime|ProcessBuilder",
            re.IGNORECASE,
        )
        self.assertEqual([], [line for line in source.splitlines() if forbidden.search(line)])

    def test_sms_scope_has_a_reviewed_complete_content_release_branch(self):
        source = PROVIDER_SOURCE.read_text(encoding="utf-8")

        self.assertIn("CapabilityFilter.Sms -> true", source)
        self.assertIn("fun normalizeContent(rawContent: String?, scope: AuthorizedReadScope)", source)
        self.assertIn("fun normalizeContent(rawContent: String?, scope: AuthorizedAutoSendScope)", source)
        self.assertIn("NormalizedContent.Released(rawContent)", source)
        self.assertIn("NormalizedContent.Withheld", source)

    def test_sms_metadata_and_local_history_interval_contracts_are_closed(self):
        self.assertTrue(SMS_CONTRACT_SOURCE.is_file(), SMS_CONTRACT_SOURCE)
        source = SMS_CONTRACT_SOURCE.read_text(encoding="utf-8")

        self.assertIn("data class SmsMetadata", source)
        for field in (
            "override val recordId: String",
            "val senderAddress: String?",
            "val threadId: String?",
            "val messageAtEpochMs: Long",
            "override val observedAtEpochMs: Long",
            "val read: Boolean",
            "val subscriptionId: Int?",
        ):
            self.assertIn(field, source)

        self.assertIn("data class SmsHistoryPolicy", source)
        self.assertIn("val fromEpochMs: Long?", source)
        self.assertIn("val maxRecords: Int", source)
        self.assertIn("MAX_SMS_BATCH_RECORDS", source)
        self.assertIn("enum class SmsSyncInterval", source)
        for interval in ("MANUAL", "MINUTES_15", "MINUTES_30", "MINUTES_60"):
            self.assertRegex(source, rf"\b{interval}\b")


if __name__ == "__main__":
    unittest.main()
