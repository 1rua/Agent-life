#!/usr/bin/env python3
"""Host checks for the call-log collector module and manifest boundary."""

from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
CALL_LOG_ROOT = ROOT / "call-log-collector"
SETTINGS_GRADLE = ROOT / "settings.gradle.kts"
APP_BUILD_GRADLE = ROOT / "app" / "build.gradle.kts"
APP_MANIFEST = ROOT / "app" / "src" / "main" / "AndroidManifest.xml"
ASSISTANT_HOLDER_BUILD_GRADLE = ROOT / "assistant-holder" / "build.gradle.kts"
ASSISTANT_HOLDER_MANIFEST = ROOT / "assistant-holder" / "src" / "main" / "AndroidManifest.xml"
FORBIDDEN_SURFACES = ROOT / "gradle" / "mvp-forbidden-surfaces.gradle.kts"
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"


class CallLogCollectorStaticTest(unittest.TestCase):
    def test_call_log_collector_is_registered_with_only_approved_dependencies(self):
        self.assertIn('\":call-log-collector\"', SETTINGS_GRADLE.read_text(encoding="utf-8"))
        self.assertIn(
            'implementation(project(\":call-log-collector\"))',
            APP_BUILD_GRADLE.read_text(encoding="utf-8"),
        )

        build = (CALL_LOG_ROOT / "build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn('namespace = "com.agentlife.calls"', build)
        for dependency in (
            'implementation(project(":capability-ports"))',
            'implementation(project(":core-model"))',
            'implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")',
        ):
            self.assertIn(dependency, build)
        self.assertNotRegex(build, r'implementation\(project\(":(?:transport|tailnet-core|control-ports)"\)\)')

    def test_main_manifest_is_read_only_for_sms_and_call_log(self):
        root = ET.parse(APP_MANIFEST).getroot()
        declared_permissions = [
            permission.get(f"{ANDROID_NS}name")
            for permission in root.findall("uses-permission")
        ]
        self.assertEqual(
            [
                "android.permission.INTERNET",
                "android.permission.READ_SMS",
                "android.permission.READ_CALL_LOG",
            ],
            declared_permissions,
        )
        telephony_feature = next(
            (
                feature
                for feature in root.findall("uses-feature")
                if feature.get(f"{ANDROID_NS}name") == "android.hardware.telephony"
            ),
            None,
        )
        self.assertIsNotNone(telephony_feature)
        self.assertEqual("false", telephony_feature.get(f"{ANDROID_NS}required"))

    def test_no_manifest_declares_forbidden_phone_permissions(self):
        forbidden_permissions = {
            "android.permission.WRITE_CALL_LOG",
            "android.permission.CALL_PHONE",
            "android.permission.ANSWER_PHONE_CALLS",
            "android.permission.READ_PHONE_STATE",
            "android.permission.READ_PRECISE_PHONE_STATE",
            "android.permission.READ_VOICEMAIL",
            "android.permission.WRITE_VOICEMAIL",
            "android.permission.RECORD_AUDIO",
            "android.permission.PROCESS_OUTGOING_CALLS",
        }
        declared_permissions = {
            permission.get(f"{ANDROID_NS}name")
            for manifest in ROOT.rglob("AndroidManifest.xml")
            for permission in ET.parse(manifest).getroot().findall("uses-permission")
        }
        self.assertFalse(forbidden_permissions & declared_permissions)

    def test_assistant_holder_has_no_call_log_dependency_or_permission(self):
        self.assertNotIn(
            "call-log-collector",
            ASSISTANT_HOLDER_BUILD_GRADLE.read_text(encoding="utf-8"),
        )
        declared_permissions = [
            permission.get(f"{ANDROID_NS}name")
            for permission in ET.parse(ASSISTANT_HOLDER_MANIFEST).getroot().findall("uses-permission")
        ]
        self.assertEqual([], declared_permissions)

    def test_call_log_module_is_in_the_root_forbidden_surface_scan(self):
        guard = FORBIDDEN_SURFACES.read_text(encoding="utf-8")
        self.assertRegex(guard, r'listOf\([^)]*"call-log-collector"')


if __name__ == "__main__":
    unittest.main()
