#!/usr/bin/env python3
"""SDK-free checks for the Android assistant audio backend contracts."""

from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]
SOURCES = (
    ROOT
    / "artifact-ports/src/main/kotlin/com/agentlife/artifact/ArtifactSelectionPorts.kt",
    ROOT / "core-model/src/main/kotlin/com/agentlife/core/model/AssistantAudioContracts.kt",
)


class AssistantAudioBackendStaticTest(unittest.TestCase):
    def read(self, source: Path) -> str:
        self.assertTrue(source.is_file(), source)
        return source.read_text(encoding="utf-8")

    def test_audio_contracts_are_closed_and_bounded(self):
        artifact_source, assistant_source = (self.read(source) for source in SOURCES)
        for required in (
            "AUDIO_MP4",
            "MAX_AUDIO_ARTIFACT_BYTES",
            "MAX_AUDIO_DURATION_MS",
            "durationMs",
            "artifactId",
            "MESSAGE_COMMITTED",
        ):
            self.assertIn(required, artifact_source)
        for required in (
            "MAX_ASSISTANT_AUDIO_BYTES",
            "MAX_ASSISTANT_AUDIO_DURATION_MS",
            "data class AssistantAudioAttachment",
            "enum class AssistantReplyEventKind",
            "data class AssistantReplyEvent",
        ):
            self.assertIn(required, assistant_source)

    def test_backend_sources_exclude_platform_and_data_access_surfaces(self):
        forbidden = re.compile(
            r"^\s*import\s+android\.|\bUri\b|\b(path|url|rawBytes)\b|"
            r"\b(Socket|ServerSocket|ProcessBuilder)\b|Runtime\.getRuntime|"
            r"\b(VpnService|Recorder|MediaRecorder|AudioRecord)\b",
            re.IGNORECASE,
        )
        for source in SOURCES:
            with self.subTest(source=source):
                self.assertEqual(
                    [],
                    [line for line in self.read(source).splitlines() if forbidden.search(line)],
                )


if __name__ == "__main__":
    unittest.main()
