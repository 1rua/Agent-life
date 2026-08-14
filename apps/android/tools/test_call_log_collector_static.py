#!/usr/bin/env python3
"""Host checks for the call-log collector module and manifest boundary."""

from pathlib import Path
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
ALLOWED_PRODUCTION_DEPENDENCIES = {
    ("implementation", 'project(":capability-ports")'),
    ("implementation", 'project(":core-model")'),
    ("implementation", '"org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0"'),
}
DEPENDENCY_CONFIGURATIONS = ("api", "compileOnly", "implementation", "runtimeOnly", "testImplementation")


def manifest_permissions(root: ET.Element) -> list[str | None]:
    return [
        element.get(f"{ANDROID_NS}name")
        for element in root
        if isinstance(element.tag, str)
        and (element.tag == "uses-permission" or element.tag.startswith("uses-permission-"))
    ]


def dependency_declarations(build: str) -> set[tuple[str, str]]:
    declarations: set[tuple[str, str]] = set()
    for raw_line in build.splitlines():
        line = raw_line.strip()
        for configuration in DEPENDENCY_CONFIGURATIONS:
            prefix = f"{configuration}("
            if line.startswith(prefix) and line.endswith(")"):
                declarations.add((configuration, line[len(prefix) : -1].strip()))
                break
    return declarations


def production_dependencies(build: str) -> set[tuple[str, str]]:
    return {
        declaration
        for declaration in dependency_declarations(build)
        if declaration[0] != "testImplementation"
    }


def assert_production_dependencies_allowed(build: str) -> None:
    actual = production_dependencies(build)
    if actual != ALLOWED_PRODUCTION_DEPENDENCIES:
        raise AssertionError(
            f"production dependency set differs from allowlist: {sorted(actual)!r}"
        )


class CallLogCollectorStaticTest(unittest.TestCase):
    def test_call_log_collector_is_registered_with_only_approved_dependencies(self):
        self.assertIn('\":call-log-collector\"', SETTINGS_GRADLE.read_text(encoding="utf-8"))
        self.assertIn(
            'implementation(project(\":call-log-collector\"))',
            APP_BUILD_GRADLE.read_text(encoding="utf-8"),
        )

        build = (CALL_LOG_ROOT / "build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn('namespace = "com.agentlife.calls"', build)
        self.assertEqual(ALLOWED_PRODUCTION_DEPENDENCIES, production_dependencies(build))

    def test_production_dependency_allowlist_rejects_other_scopes_and_targets(self):
        valid_build = """
            dependencies {
                implementation(project(":capability-ports"))
                implementation(project(":core-model"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                testImplementation("junit:junit:4.13.2")
            }
        """
        for invalid_declaration in (
            'implementation(project(":transport"))',
            'implementation("com.squareup.okhttp3:okhttp:4.12.0")',
            'api(project(":capability-ports"))',
            'compileOnly("com.example:compile-only:1.0")',
            'runtimeOnly("com.example:runtime-only:1.0")',
        ):
            with self.subTest(invalid_declaration=invalid_declaration):
                self.assertRaises(
                    AssertionError,
                    assert_production_dependencies_allowed,
                    valid_build.replace(
                        'testImplementation("junit:junit:4.13.2")',
                        f"{invalid_declaration}\n"
                        '                testImplementation("junit:junit:4.13.13")',
                    ),
                )

    def test_main_manifest_is_read_only_for_sms_and_call_log(self):
        root = ET.parse(APP_MANIFEST).getroot()
        declared_permissions = manifest_permissions(root)
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
            permission
            for manifest in ROOT.rglob("AndroidManifest.xml")
            for permission in manifest_permissions(ET.parse(manifest).getroot())
        }
        self.assertFalse(forbidden_permissions & declared_permissions)

    def test_assistant_holder_has_no_call_log_dependency_or_permission(self):
        self.assertNotIn(
            "call-log-collector",
            ASSISTANT_HOLDER_BUILD_GRADLE.read_text(encoding="utf-8"),
        )
        declared_permissions = manifest_permissions(ET.parse(ASSISTANT_HOLDER_MANIFEST).getroot())
        self.assertEqual([], declared_permissions)

    def test_permission_walk_includes_sdk_specific_permission_elements(self):
        root = ET.fromstring(
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android">'
            '<uses-permission-sdk-23 android:name="android.permission.CALL_PHONE" />'
            '</manifest>'
        )
        self.assertEqual(["android.permission.CALL_PHONE"], manifest_permissions(root))

    def test_call_log_module_is_in_the_root_forbidden_surface_scan(self):
        guard = FORBIDDEN_SURFACES.read_text(encoding="utf-8")
        self.assertRegex(guard, r'listOf\([^)]*"call-log-collector"')


if __name__ == "__main__":
    unittest.main()
