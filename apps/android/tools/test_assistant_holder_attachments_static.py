#!/usr/bin/env python3
"""SDK-free checks for the assistant-holder attachment boundary.

The holder receives Android intent grants, but it must not expose a provider
URI/path to the rest of the product.  Artifact digesting, encrypted scratch
storage and upload stay behind the separate source-only artifact-ports module.
"""

from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]
HOLDER = ROOT / "assistant-holder"
SOURCE = HOLDER / "src/main/kotlin/com/openandroidintelligence/assistant/AssistantActivity.kt"
CONTRACT = HOLDER / "src/main/kotlin/com/openandroidintelligence/assistant/AssistantAttachmentContract.kt"


class AssistantHolderAttachmentStaticTest(unittest.TestCase):
    def read(self, path: Path) -> str:
        self.assertTrue(path.is_file(), path)
        return path.read_text(encoding="utf-8")

    def test_typed_attachment_contract_is_present_and_closed(self):
        source = self.read(CONTRACT)
        self.assertIn("enum class AssistantAttachmentSource", source)
        self.assertIn("PHOTO_PICKER", source)
        self.assertIn("SAF", source)
        self.assertIn("data class AssistantAttachmentSelection", source)
        self.assertIn("AssistantReadGrant", source)
        self.assertIn("interface AssistantAttachmentGrantIssuer", source)
        self.assertIn("AssistantProviderSelection", source)
        self.assertIn("selectionId", source)
        self.assertIn("mediaTypeHint", source)
        self.assertIn("MAX_ASSISTANT_ATTACHMENTS", source)
        self.assertNotRegex(source, r"Map<\s*String\s*,|Set<\s*String\s*>")
        self.assertNotRegex(source, r"\b(path|url|filePath|rawBytes|attachmentUris)\b", re.IGNORECASE)

    def test_launch_payload_does_not_expose_uri_or_arbitrary_location(self):
        source = self.read(SOURCE)
        self.assertIn("AssistantLaunchPayload", source)
        self.assertIn("attachmentSelections", source)
        self.assertNotIn("attachmentUris", source)
        payload = re.search(r"data class AssistantLaunchPayload\(.*?^\)", source, re.MULTILINE | re.DOTALL)
        self.assertIsNotNone(payload)
        self.assertNotRegex(payload.group(0), r"\bUri\b|path|url|filePath", re.IGNORECASE)

    def test_capture_requires_read_grant_content_scheme_and_closed_count(self):
        source = self.read(SOURCE)
        self.assertIn("FLAG_GRANT_READ_URI_PERMISSION", source)
        self.assertIn('uri.scheme == "content"', source)
        self.assertIn("take(MAX_ASSISTANT_ATTACHMENTS)", source)
        self.assertIn("AssistantAttachmentSelection", source)
        self.assertRegex(source, r"if\s*\(hasReadGrant\b")
        self.assertIn("AssistantAttachmentGrantIssuer", source)
        self.assertIn("grantIssuer", source)
        self.assertIn("selection.source == source", source)
        self.assertIn("latestLaunchIntent", source)
        self.assertIn("latestLaunchIntent?.let(::captureLaunch)", source)
        self.assertIn("override fun onDestroy()", source)
        self.assertIn("latestLaunchIntent = null", source)
        self.assertNotIn('"assistant-selection-"', source)
        self.assertNotIn('"assistant-grant-"', source)

    def test_holder_has_no_network_shell_or_early_artifact_implementation(self):
        forbidden = re.compile(
            r"VpnService|BIND_VPN_SERVICE|Socket|ServerSocket|DatagramSocket|"
            r"URLConnection|WebSocket|HttpClient|ProcessBuilder|Runtime\.getRuntime|"
            r"Cipher\.getInstance|SecretKeySpec|MessageDigest\.getInstance|"
            r"ContentResolver|MediaStore|openInputStream|FileInputStream",
            re.IGNORECASE,
        )
        violations = []
        for path in (HOLDER / "src/main").rglob("*"):
            if path.is_file() and path.suffix in {".kt", ".java", ".xml"}:
                for line_no, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
                    if forbidden.search(line):
                        violations.append(f"{path}:{line_no}: {line.strip()}")
        self.assertEqual([], violations)


if __name__ == "__main__":
    unittest.main()
