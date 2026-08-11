#!/usr/bin/env python3
"""SDK-free checks for the assistant-to-main-app typed handoff seam.

The two APKs must not grow an implicit broadcast, arbitrary Intent extra, or
network bridge just to exchange assistant input.  This test keeps the shared
contract closed and requires the main Activity to fail closed until a local
user handoff gate explicitly accepts a typed request.
"""

from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]
CORE = ROOT / "core-model/src/main/kotlin/com/agentlife/core/model"
CONTRACT = CORE / "AssistantHandoffContracts.kt"
MAIN = ROOT / "app/src/main/kotlin/com/agentlife/mobile/MainActivity.kt"
HOLDER = ROOT / "assistant-holder/src/main/kotlin/com/agentlife/assistant/AssistantActivity.kt"


class AssistantHandoffStaticTest(unittest.TestCase):
    def read(self, path: Path) -> str:
        self.assertTrue(path.is_file(), path)
        return path.read_text(encoding="utf-8")

    def test_shared_contract_is_closed_and_bounded(self):
        source = self.read(CONTRACT)
        self.assertIn("data class AssistantHandoffRequest", source)
        self.assertIn("AssistantHandoffAttachment", source)
        self.assertIn("AssistantHandoffSource", source)
        self.assertIn("AssistantHandoffMediaType", source)
        self.assertIn("AssistantHandoffGate", source)
        self.assertIn("DefaultAssistantHandoffGate", source)
        self.assertIn("USER_INITIATED", source)
        self.assertIn("AGENT_REQUEST", source)
        self.assertIn("SYSTEM_RESTORE", source)
        self.assertIn("MAX_ASSISTANT_HANDOFF_ATTACHMENTS", source)
        self.assertIn("MAX_ASSISTANT_TEXT_CHARS", source)
        self.assertNotRegex(source, r"Map<\s*String\s*,|Set<\s*String\s*>")
        self.assertNotRegex(source, r"\b(Uri|path|url|filePath|rawBytes)\b", re.IGNORECASE)

    def test_main_activity_uses_deny_first_typed_gate(self):
        source = self.read(MAIN)
        self.assertIn("AssistantHandoffRequest", source)
        self.assertIn("AssistantHandoffGate", source)
        self.assertIn("DefaultAssistantHandoffGate", source)
        self.assertIn("evaluateAssistantHandoff", source)
        self.assertNotRegex(
            source,
            r"sendBroadcast|registerReceiver|startService|bindService|Socket|"
            r"ProcessBuilder|Runtime\.getRuntime|VpnService",
            re.IGNORECASE,
        )

    def test_holder_exposes_typed_handoff_without_uri_or_implicit_ipc(self):
        source = self.read(HOLDER)
        self.assertIn("AssistantHandoffRequest", source)
        self.assertIn("currentLaunchHandoff", source)
        self.assertNotIn("startActivity", source)
        self.assertNotIn("sendBroadcast", source)
        self.assertNotRegex(source, r"\b(path|url|filePath|rawBytes)\b", re.IGNORECASE)


if __name__ == "__main__":
    unittest.main()
