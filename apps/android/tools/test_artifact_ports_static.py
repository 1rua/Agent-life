#!/usr/bin/env python3
"""SDK-free checks for the Android selected-attachment boundary.

This module is intentionally a typed source contract.  The real Photo Picker/
SAF adapter, encrypted scratch implementation and Bridge uploader remain
separate production-gated work; this test prevents the MVP seam from growing
path/URL based access or an early-delete escape hatch.
"""

from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]
MODULE = ROOT / "artifact-ports"
SOURCE = (
    MODULE
    / "src"
    / "main"
    / "kotlin"
    / "com"
    / "agentlife"
    / "artifact"
    / "ArtifactSelectionPorts.kt"
)


class ArtifactPortsStaticTest(unittest.TestCase):
    def read_source(self) -> str:
        self.assertTrue(SOURCE.is_file(), SOURCE)
        return SOURCE.read_text(encoding="utf-8")

    def test_module_is_source_only_and_documented(self):
        self.assertTrue((MODULE / "build.gradle.kts").is_file())
        build = (MODULE / "build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn('namespace = "com.agentlife.artifact"', build)
        self.assertNotIn("implementation(project", build)
        readme = (MODULE / "README.md").read_text(encoding="utf-8")
        self.assertIn("source-only", readme)
        self.assertIn("Photo Picker", readme)
        self.assertIn("encrypted scratch", readme)

    def test_selection_and_media_contracts_are_closed(self):
        source = self.read_source()
        self.assertIn("enum class ArtifactSelectionSource", source)
        self.assertIn("PHOTO_PICKER", source)
        self.assertIn("SAF", source)
        self.assertIn("enum class ArtifactMediaType", source)
        for media in ("JPEG", "PNG", "WEBP", "PDF", "TEXT_PLAIN"):
            self.assertRegex(source, rf"\b{media}\b")
        self.assertIn("data class GrantedArtifactSelection internal constructor", source)
        self.assertIn("ArtifactReadGrant", source)
        self.assertIn("selectionId", source)
        self.assertNotRegex(source, r"Map<\s*String\s*,|Set<\s*String\s*>")

    def test_digest_and_limits_are_required_before_ticket(self):
        source = self.read_source()
        self.assertIn("MAX_ARTIFACT_FILES", source)
        self.assertIn("MAX_SINGLE_ARTIFACT_BYTES", source)
        self.assertIn("MAX_MESSAGE_ARTIFACT_BYTES", source)
        self.assertIn("ORPHAN_RECLAIM_AFTER_MS", source)
        self.assertIn("data class ArtifactDigest", source)
        self.assertIn("sha256Hex", source)
        self.assertIn("data class ArtifactSummary", source)
        self.assertIn("interface ArtifactDigestPort", source)
        self.assertIn("interface ArtifactTicketPort", source)
        self.assertIn("digest: ArtifactDigest", source)
        self.assertIn("byteSize", source)

    def test_encrypted_scratch_and_commit_delete_are_explicitly_ordered(self):
        source = self.read_source()
        self.assertIn("data class EncryptedArtifactCopy", source)
        self.assertIn("interface EncryptedArtifactScratchStore", source)
        self.assertIn("fun stageEncryptedCopy", source)
        self.assertIn("fun deleteAfterCommit", source)
        self.assertIn("ArtifactCommitReceipt", source)
        self.assertIn("data class ArtifactCommitReceipt internal constructor", source)
        self.assertIn("fun discardInterruptedCopy", source)
        self.assertIn("ArtifactUploadInterrupted", source)
        self.assertIn("data class ArtifactUploadInterrupted internal constructor", source)
        self.assertIn("localCopyDeletionAllowed", source)
        self.assertIn("commitMessage", source)
        self.assertIn("markMessageCommitted", source)

    def test_ticket_and_copy_are_opaque_and_fenced(self):
        source = self.read_source()
        self.assertIn("data class ArtifactTicket", source)
        self.assertIn("ArtifactTicketStatus", source)
        for status in (
            "ISSUED",
            "PROOF_VERIFIED",
            "UPLOADING",
            "UPLOAD_INTERRUPTED",
            "MESSAGE_COMMITTED",
            "ORPHAN_RECLAIMED",
        ):
            self.assertIn(status, source)
        self.assertIn("pairingGeneration", source)
        self.assertIn("connectionGeneration", source)
        self.assertIn("policyRevision", source)
        self.assertIn("remoteProof", source)
        self.assertIn("requiresFreshTicket", source)

    def test_no_uri_path_url_or_platform_upload_implementation(self):
        source = self.read_source()
        forbidden = re.compile(
            r"^\s*import\s+android\.|ContentResolver|MediaStore|Uri\b|"
            r"\b(path|url|uri|filePath|rawBytes)\b|"
            r"Runtime\.getRuntime|ProcessBuilder|Socket|ServerSocket|"
            r"URLConnection|OkHttp|WebSocket|VpnService|"
            r"Cipher\.getInstance|SecretKeySpec|MessageDigest\.getInstance",
            re.IGNORECASE,
        )
        self.assertEqual([], [line for line in source.splitlines() if forbidden.search(line)])


if __name__ == "__main__":
    unittest.main()
