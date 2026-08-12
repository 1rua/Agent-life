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
FORBIDDEN_BACKEND_SURFACE = re.compile(
    r"^\s*import\s+(?i:android|androidx)\.|"
    r"(?<![A-Za-z0-9])(?i:uri|path|url|socket|raw_?byte(?:s|_?buffer)?)(?=[A-Z_]|\b)|"
    r"(?<=[A-Za-z0-9])(?=[A-Z])(?i:uri|path|url|socket|rawbyte(?:s|buffer)?)(?=[A-Z_]|\b)|"
    r"\b(?i:ProcessBuilder)\b|Runtime\.getRuntime|"
    r"\b(?i:VpnService|Recorder|MediaRecorder|AudioRecord)\b",
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
        for source in SOURCES:
            with self.subTest(source=source):
                self.assertEqual(
                    [],
                    [line for line in self.read(source).splitlines() if FORBIDDEN_BACKEND_SURFACE.search(line)],
                )

    def test_forbidden_snippets_are_rejected_by_the_gate(self):
        snippets = (
            "import android.media.MediaRecorder",
            "import androidx.core.net.toUri",
            "val filePath = \"forbidden\"",
            "val file_path = \"forbidden\"",
            "val uploadUrl = \"forbidden\"",
            "val upload_url = \"forbidden\"",
            "val rawByteBuffer = \"forbidden\"",
            "val raw_bytes = \"forbidden\"",
            "val raw_byte_buffer = \"forbidden\"",
            "val datagramSocket = \"forbidden\"",
            "val datagram_socket = \"forbidden\"",
            "val payload_raw_bytes = \"forbidden\"",
            "val payload_raw_byte_buffer = \"forbidden\"",
            "val FiLePaTh = \"forbidden\"",
        )
        for snippet in snippets:
            with self.subTest(snippet=snippet):
                self.assertIsNotNone(FORBIDDEN_BACKEND_SURFACE.search(snippet))

    def test_harmless_identifier_substrings_are_allowed(self):
        for snippet in ("val sympathyScore = 1", "val curiosity = 1"):
            with self.subTest(snippet=snippet):
                self.assertIsNone(FORBIDDEN_BACKEND_SURFACE.search(snippet))


if __name__ == "__main__":
    unittest.main()
