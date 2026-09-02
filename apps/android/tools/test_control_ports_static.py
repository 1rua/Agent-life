#!/usr/bin/env python3
"""SDK-free checks for the high-risk Android control boundary.

The control ports are a source-only contract in this slice.  They describe
typed writes, semantic screen sessions and closed restricted-command
templates, but deliberately do not contain Android providers, accessibility
or projection services, shell execution, or a generic process escape.
"""

from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]
CONTROL_ROOT = ROOT / "control-ports"
SOURCE = (
    CONTROL_ROOT
    / "src"
    / "main"
    / "kotlin"
    / "com"
    / "openandroidintelligence"
    / "control"
    / "ControlPorts.kt"
)
README = CONTROL_ROOT / "README.md"


class ControlPortsStaticTest(unittest.TestCase):
    def read_source(self) -> str:
        self.assertTrue(SOURCE.is_file(), SOURCE)
        return SOURCE.read_text(encoding="utf-8")

    def test_module_and_source_only_readme_exist(self):
        self.assertTrue((CONTROL_ROOT / "build.gradle.kts").is_file())
        build = (CONTROL_ROOT / "build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn('namespace = "com.openandroidintelligence.control"', build)
        self.assertTrue(README.is_file())
        readme = README.read_text(encoding="utf-8")
        self.assertIn("source-only", readme)
        self.assertIn("fail closed", readme.lower())

    def test_typed_writes_are_a_closed_sealed_union(self):
        source = self.read_source()
        self.assertIn("sealed interface TypedWriteAction", source)
        for action in (
            "ClipboardWrite",
            "SmsSend",
            "CalendarCreate",
            "CalendarUpdate",
            "AlarmCreate",
            "AlarmModifyOwned",
            "DeviceNotify",
        ):
            self.assertRegex(source, rf"\b{action}\b")
        for capability in (
            "CLIPBOARD_WRITE",
            "SMS_SEND",
            "CALENDAR_WRITE",
            "ALARMS_WRITE",
            "DEVICE_NOTIFY",
        ):
            self.assertRegex(source, rf"\b{capability}\b")
        self.assertNotRegex(source, r"Map<\s*String\s*,|JsonObject|JsonElement|vararg")
        self.assertIn("parameterDigest", source)

    def test_screen_contract_is_semantic_and_session_bound(self):
        source = self.read_source()
        self.assertIn("sealed interface ScreenSemanticAction", source)
        for action in ("TapResource", "SetText", "Scroll", "Back"):
            self.assertRegex(source, rf"\b{action}\b")
        self.assertIn("data class ScreenControlSession", source)
        self.assertIn("ScreenTargetIdentity", source)
        self.assertIn("screenSessionId", source)
        self.assertIn("windowGeneration", source)
        self.assertIn("MAX_SCREEN_SESSION_TTL_MS", source)
        self.assertIn("isUsable", source)
        self.assertIn("currentWindow", source)

    def test_restricted_commands_have_only_closed_templates(self):
        source = self.read_source()
        self.assertIn("sealed interface RestrictedCommandTemplate", source)
        self.assertIn("RestrictedCommandTemplateId", source)
        for template in (
            "ReconnectBridge",
            "PurgeExpiredLocalData",
            "OpenAppSettings",
        ):
            self.assertRegex(source, rf"\b{template}\b")
        for forbidden_field in (
            "executable",
            "argv",
            "environment",
            "cwd",
            "stdin",
            "script",
            "shellCommand",
        ):
            self.assertNotRegex(source, rf"\b(val|var)\s+{forbidden_field}\b")
        self.assertIn("templateId", source)
        self.assertIn("SHELL_RESTRICTED", source)

    def test_authorization_is_deny_first_and_fenced(self):
        source = self.read_source()
        self.assertIn("data class ControlAuthorizationRequest", source)
        self.assertIn("data class ControlRevision", source)
        self.assertIn("data class UserConfirmation", source)
        self.assertIn("interface ControlAuthorizer", source)
        self.assertIn("class DefaultControlAuthorizer", source)
        for denial in (
            "NO_LOCAL_GRANT",
            "AGENT_REQUESTS_DISABLED",
            "CONFIRMATION_REQUIRED",
            "CONFIRMATION_MISMATCH",
            "REQUEST_EXPIRED",
            "PAIRING_GENERATION_STALE",
            "CONNECTION_GENERATION_STALE",
            "AUTHORIZATION_EPOCH_STALE",
            "POLICY_REVISION_STALE",
            "SESSION_NOT_ACTIVE",
            "WINDOW_CHANGED",
            "TEMPLATE_NOT_GRANTED",
        ):
            self.assertIn(denial, source)
        self.assertIn("revision == request.revision", source)
        self.assertIn("request.confirmation", source)

    def test_no_platform_or_generic_execution_implementation(self):
        source = self.read_source()
        forbidden = re.compile(
            r"^\s*import\s+android\.|"
            r"AccessibilityService|MediaProjection|VirtualDisplay|"
            r"Runtime\.getRuntime|ProcessBuilder|\bexec\s*\(|\bsh\s+-c\b|"
            r"/system/bin/sh|ServerSocket|DatagramSocket|URLConnection|"
            r"VpnService|BIND_VPN_SERVICE|Socket\s*\(",
            re.IGNORECASE,
        )
        self.assertEqual([], [line for line in source.splitlines() if forbidden.search(line)])

    def test_control_port_does_not_expose_backend_implementation(self):
        source = self.read_source()
        self.assertIn("interface TypedWritePort", source)
        self.assertIn("interface ScreenSemanticControlPort", source)
        self.assertIn("interface RestrictedCommandPort", source)
        self.assertIn("AuthorizedControlRequest", source)
        self.assertNotIn("AccessibilityService", source)
        self.assertNotIn("MediaProjection", source)


if __name__ == "__main__":
    unittest.main()
