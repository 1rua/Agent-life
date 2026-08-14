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
ALLOWED_TEST_DEPENDENCIES = {
    ("testImplementation", '"junit:junit:4.13.2"'),
}


def manifest_permissions(root: ET.Element) -> list[str | None]:
    return [
        element.get(f"{ANDROID_NS}name")
        for element in root
        if isinstance(element.tag, str)
        and (element.tag == "uses-permission" or element.tag.startswith("uses-permission-"))
    ]


def _skip_string_or_comment(source: str, index: int) -> int:
    if source.startswith("//", index):
        newline = source.find("\n", index + 2)
        return len(source) if newline == -1 else newline + 1
    if source.startswith("/*", index):
        end = source.find("*/", index + 2)
        return len(source) if end == -1 else end + 2
    quote = source[index]
    if quote not in ('"', "'"):
        return index
    index += 1
    while index < len(source):
        if source[index] == "\\":
            index += 2
        elif source[index] == quote:
            return index + 1
        else:
            index += 1
    return len(source)


def _skip_whitespace_and_comments(source: str, index: int) -> int:
    while index < len(source):
        if source[index].isspace():
            index += 1
        elif source.startswith("//", index) or source.startswith("/*", index):
            index = _skip_string_or_comment(source, index)
        else:
            return index
    return index


def _matching_delimiter(source: str, opening_index: int, opening: str, closing: str) -> int:
    depth = 1
    index = opening_index + 1
    while index < len(source):
        if source.startswith("//", index) or source.startswith("/*", index):
            index = _skip_string_or_comment(source, index)
            continue
        if source[index] in ('"', "'"):
            index = _skip_string_or_comment(source, index)
            continue
        if source[index] == opening:
            depth += 1
        elif source[index] == closing:
            depth -= 1
            if depth == 0:
                return index
        index += 1
    raise ValueError(f"unbalanced {opening}{closing} in Gradle source")


def _dependencies_block(build: str) -> str:
    index = 0
    while index < len(build):
        index = _skip_whitespace_and_comments(build, index)
        if index >= len(build):
            break
        if build[index] in ('"', "'"):
            index = _skip_string_or_comment(build, index)
            continue
        if build.startswith("dependencies", index):
            before_is_identifier = index > 0 and (build[index - 1].isalnum() or build[index - 1] == "_")
            after = index + len("dependencies")
            after_is_identifier = after < len(build) and (build[after].isalnum() or build[after] == "_")
            if not before_is_identifier and not after_is_identifier:
                opening = _skip_whitespace_and_comments(build, after)
                if opening < len(build) and build[opening] == "{":
                    closing = _matching_delimiter(build, opening, "{", "}")
                    return build[opening + 1 : closing]
        index += 1
    raise ValueError("Gradle source has no dependencies block")


def dependency_declarations(build: str) -> list[tuple[str, str]]:
    body = _dependencies_block(build)
    declarations: list[tuple[str, str]] = []
    index = 0
    curly_depth = 0
    while index < len(body):
        if body.startswith("//", index) or body.startswith("/*", index):
            index = _skip_string_or_comment(body, index)
            continue
        if body[index] in ('"', "'"):
            index = _skip_string_or_comment(body, index)
            continue
        if body[index] == "{":
            curly_depth += 1
            index += 1
            continue
        if body[index] == "}":
            curly_depth -= 1
            index += 1
            continue
        if body[index].isalpha() or body[index] == "_":
            start = index
            index += 1
            while index < len(body) and (body[index].isalnum() or body[index] == "_"):
                index += 1
            configuration = body[start:index]
            opening = _skip_whitespace_and_comments(body, index)
            if curly_depth == 0 and opening < len(body) and body[opening] == "(":
                closing = _matching_delimiter(body, opening, "(", ")")
                argument = " ".join(body[opening + 1 : closing].split())
                declarations.append((configuration, argument))
                index = closing + 1
            continue
        index += 1
    return declarations


def production_dependencies(build: str) -> list[tuple[str, str]]:
    return [
        declaration
        for declaration in dependency_declarations(build)
        if declaration[0] != "testImplementation"
    ]


def test_dependencies(build: str) -> list[tuple[str, str]]:
    return [
        declaration
        for declaration in dependency_declarations(build)
        if declaration[0] == "testImplementation"
    ]


def assert_production_dependencies_allowed(build: str) -> None:
    actual = production_dependencies(build)
    if sorted(actual) != sorted(ALLOWED_PRODUCTION_DEPENDENCIES):
        raise AssertionError(
            f"production dependency set differs from allowlist: {sorted(actual)!r}"
        )
    actual_test = test_dependencies(build)
    if sorted(actual_test) != sorted(ALLOWED_TEST_DEPENDENCIES):
        raise AssertionError(
            f"test dependency set differs from allowlist: {sorted(actual_test)!r}"
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
        assert_production_dependencies_allowed(build)

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
            'implementation(\n'
            '                    "com.squareup.okhttp3:okhttp:4.12.0"\n'
            '                )',
            'debugImplementation("com.example:debug-only:1.0")',
        ):
            with self.subTest(invalid_declaration=invalid_declaration):
                self.assertRaises(
                    AssertionError,
                    assert_production_dependencies_allowed,
                    valid_build.replace(
                        'testImplementation("junit:junit:4.13.2")',
                        f"{invalid_declaration}\n"
                        '                testImplementation("junit:junit:4.13.2")',
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
