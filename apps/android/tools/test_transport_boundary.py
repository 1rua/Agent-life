#!/usr/bin/env python3
"""Host-side tests for the Android MVP transport boundary.

These tests intentionally do not require an Android SDK, Gradle, or a JVM.  They
check the source-level contract that must remain true even when the full Android
build is unavailable in CI.
"""

from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]
PRODUCTION = (
    ROOT / "core-model" / "src" / "main",
    ROOT / "capability-ports" / "src" / "main",
    ROOT / "control-ports" / "src" / "main",
    ROOT / "artifact-ports" / "src" / "main",
    ROOT / "tailnet-core" / "src" / "main",
    ROOT / "transport" / "src" / "main",
)


class TransportBoundaryTest(unittest.TestCase):
    def test_expected_modules_and_sources_exist(self):
        expected = (
            ROOT / "settings.gradle.kts",
            ROOT / "build.gradle.kts",
            ROOT / "app" / "build.gradle.kts",
            ROOT / "assistant-holder" / "build.gradle.kts",
            ROOT / "gradle" / "mvp-forbidden-surfaces.gradle.kts",
            ROOT / "artifact-ports" / "build.gradle.kts",
            ROOT / "artifact-ports" / "src" / "main" / "kotlin" / "com" / "agentlife" / "artifact" / "ArtifactSelectionPorts.kt",
            ROOT / "core-model" / "src" / "main" / "kotlin" / "com" / "agentlife" / "core" / "model" / "TransportContracts.kt",
            ROOT / "tailnet-core" / "src" / "main" / "kotlin" / "com" / "agentlife" / "tailnet" / "core" / "VerifiedPairingTransportBinding.kt",
            ROOT / "tailnet-core" / "src" / "main" / "kotlin" / "com" / "agentlife" / "tailnet" / "core" / "TailscaleUserspaceCore.kt",
            ROOT / "tailnet-core" / "src" / "main" / "kotlin" / "com" / "agentlife" / "tailnet" / "core" / "PairingReconnectState.kt",
            ROOT / "transport" / "src" / "testFixtures" / "kotlin" / "com" / "agentlife" / "transport" / "FakeUserspaceTransport.kt",
            ROOT / "transport" / "src" / "main" / "kotlin" / "com" / "agentlife" / "transport" / "TsnetPairedBridgeTransport.kt",
            ROOT / "transport" / "src" / "main" / "kotlin" / "com" / "agentlife" / "transport" / "PairedBridgeSessionCoordinator.kt",
        )
        missing = [str(path.relative_to(ROOT)) for path in expected if not path.is_file()]
        self.assertEqual([], missing)

    def test_gradle_wrapper_uses_pinned_distribution(self):
        properties = (ROOT / "gradle" / "wrapper" / "gradle-wrapper.properties").read_text(
            encoding="utf-8"
        )
        self.assertIn("gradle-8.12-bin.zip", properties)
        self.assertTrue((ROOT / "gradle" / "wrapper" / "gradle-wrapper.jar").is_file())

    def test_closed_contract_modules_are_registered_and_scanned(self):
        """A source-only module must still be compiled and covered by no-VPN CI.

        Leaving a contract directory out of settings makes its Kotlin code
        invisible to Gradle, while omitting it from the root gate lets a later
        adapter add a forbidden network surface without being rejected.
        """
        settings = (ROOT / "settings.gradle.kts").read_text(encoding="utf-8")
        gate = (ROOT / "gradle" / "mvp-forbidden-surfaces.gradle.kts").read_text(
            encoding="utf-8"
        )
        for module in ("artifact-ports", "capability-ports", "control-ports"):
            self.assertIn(f'":{module}"', settings, module)
            self.assertIn(f'"{module}"', gate, module)

    def test_no_vpn_gate_covers_production_sources(self):
        forbidden = re.compile(
            r"VpnService|BIND_VPN_SERVICE|TunInterface|\bTUN\b|addRoute|setHttpProxy|ProxyInfo|LocalAPI|\bListen\s*\(|\bDial\s*\(",
            re.IGNORECASE,
        )
        violations = []
        for root in PRODUCTION:
            for path in root.rglob("*"):
                if path.is_file() and path.suffix in {".kt", ".java", ".xml"}:
                    for line_no, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
                        if forbidden.search(line):
                            violations.append(f"{path.relative_to(ROOT)}:{line_no}: {line.strip()}")
        self.assertEqual([], violations)

    def test_no_vpn_gate_rejects_common_socket_and_http_surfaces(self):
        gate = (ROOT / "gradle" / "mvp-forbidden-surfaces.gradle.kts").read_text(encoding="utf-8")
        for token in ("Socket", "ServerSocket", "DatagramSocket", "URLConnection", "WebSocket", "HttpClient"):
            self.assertIn(token, gate)

    def test_transport_interfaces_do_not_accept_endpoints(self):
        source = (ROOT / "core-model" / "src" / "main" / "kotlin" / "com" / "agentlife" / "core" / "model" / "TransportContracts.kt").read_text(encoding="utf-8")
        interface_block = re.search(r"interface PairedBridgeTransport.*?^}\s*$", source, re.MULTILINE | re.DOTALL)
        self.assertIsNotNone(interface_block)
        block = interface_block.group(0)
        self.assertIn("open(binding: VerifiedPairingTransportBinding)", block)
        self.assertNotRegex(block, r"\b(host|port|url|socket|route|dns|endpoint)\b")

    def test_fake_and_real_adapter_share_pairing_binding_and_generation(self):
        fake = (ROOT / "transport" / "src" / "testFixtures" / "kotlin" / "com" / "agentlife" / "transport" / "FakeUserspaceTransport.kt").read_text(encoding="utf-8")
        real = (ROOT / "transport" / "src" / "main" / "kotlin" / "com" / "agentlife" / "transport" / "TsnetPairedBridgeTransport.kt").read_text(encoding="utf-8")
        for source in (fake, real):
            self.assertIn("PairedBridgeTransport", source)
            self.assertIn("VerifiedPairingTransportBinding", source)
            self.assertIn("connectionGeneration", source)

    def test_two_apk_manifests_have_distinct_packages_and_no_holder_network(self):
        app_manifest = (ROOT / "app" / "src" / "main" / "AndroidManifest.xml").read_text(encoding="utf-8")
        holder_manifest = (ROOT / "assistant-holder" / "src" / "main" / "AndroidManifest.xml").read_text(encoding="utf-8")
        app_build = (ROOT / "app" / "build.gradle.kts").read_text(encoding="utf-8")
        holder_build = (ROOT / "assistant-holder" / "build.gradle.kts").read_text(encoding="utf-8")
        app_namespaces = re.findall(r'namespace\s*=\s*"([^"]+)"', app_build)
        holder_namespaces = re.findall(r'namespace\s*=\s*"([^"]+)"', holder_build)
        app_ids = re.findall(r'applicationId\s*=\s*"([^"]+)"', app_build)
        holder_ids = re.findall(r'applicationId\s*=\s*"([^"]+)"', holder_build)
        self.assertEqual(["com.agentlife.mobile"], app_namespaces)
        self.assertEqual(["com.agentlife.assistant"], holder_namespaces)
        self.assertEqual(["com.agentlife.mobile"], app_ids)
        self.assertEqual(["com.agentlife.assistant"], holder_ids)
        self.assertNotEqual(app_namespaces[0], holder_namespaces[0])
        self.assertNotEqual(app_ids[0], holder_ids[0])
        self.assertNotRegex(app_manifest, r"\bpackage\s*=")
        self.assertNotRegex(holder_manifest, r"\bpackage\s*=")
        self.assertIn('android:allowBackup="false"', app_manifest)
        self.assertIn('android:allowBackup="false"', holder_manifest)
        self.assertNotIn("android.permission.INTERNET", holder_manifest)
        self.assertNotIn("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE", holder_manifest)

    def test_assistant_holder_exposes_only_the_default_assistant_entry_seam(self):
        holder_manifest = (ROOT / "assistant-holder" / "src" / "main" / "AndroidManifest.xml").read_text(encoding="utf-8")
        holder_source = "\n".join(path.read_text(encoding="utf-8") for path in (ROOT / "assistant-holder" / "src" / "main" / "kotlin" / "com" / "agentlife" / "assistant").glob("*.kt"))
        self.assertIn("VoiceInteractionService", holder_manifest)
        self.assertIn('android:name=".AssistantSessionService"', holder_manifest)
        self.assertIn("android.permission.BIND_VOICE_INTERACTION", holder_manifest)
        self.assertIn("AssistantVoiceService", holder_source)
        self.assertIn("AssistantSessionService", holder_source)
        self.assertIn("FLAG_GRANT_READ_URI_PERMISSION", holder_source)
        self.assertIn('uri.scheme == "content"', holder_source)
        self.assertNotIn("android.permission.INTERNET", holder_manifest)

    def test_only_transport_depends_directly_on_tailnet_core(self):
        for module in ("app", "assistant-holder", "core-model", "policy-engine", "notification-collector", "encrypted-store"):
            source = (ROOT / module / "build.gradle.kts").read_text(encoding="utf-8")
            production_dependencies = re.findall(r"^\s*implementation\((.+)\)", source, re.MULTILINE)
            self.assertNotIn('project(":tailnet-core")', production_dependencies, module)
        transport = (ROOT / "transport" / "build.gradle.kts").read_text(encoding="utf-8")
        self.assertRegex(transport, r'(?:implementation|api)\(project\(":tailnet-core"\)\)')

    def test_main_apk_wires_closed_contract_libraries(self):
        """The app must carry the reviewed contracts it will eventually adapt."""
        app_build = (ROOT / "app" / "build.gradle.kts").read_text(encoding="utf-8")
        for module in ("artifact-ports", "capability-ports", "control-ports"):
            self.assertIn(f'implementation(project(":{module}"))', app_build, module)



class ProductionBoundaryTest(unittest.TestCase):
    def test_fake_is_never_in_production_sources(self):
        main_fake = ROOT / "transport" / "src" / "main" / "kotlin" / "com" / "agentlife" / "transport" / "FakeUserspaceTransport.kt"
        self.assertFalse(main_fake.exists())
        fixture = ROOT / "transport" / "src" / "testFixtures" / "kotlin" / "com" / "agentlife" / "transport" / "FakeUserspaceTransport.kt"
        self.assertTrue(fixture.is_file())

    def test_sealed_production_factory_exists(self):
        factory = ROOT / "transport" / "src" / "main" / "kotlin" / "com" / "agentlife" / "transport" / "ProductionTailnetTransportFactory.kt"
        self.assertTrue(factory.is_file())
        text = factory.read_text(encoding="utf-8")
        self.assertIn("sealed interface ProductionPairedBridgeTransport", text)
        self.assertIn("ProductionTailnetTransportFactory", text)
        self.assertNotIn("FakeUserspaceTransport", text)

    def test_native_adapter_is_the_only_generated_import(self):
        generated = []
        for path in (ROOT / "tailnet-core" / "src" / "main").rglob("*.kt"):
            text = path.read_text(encoding="utf-8")
            if "import tsnetbridge." in text:
                generated.append(path.relative_to(ROOT).as_posix())
        self.assertEqual(
            ["tailnet-core/src/main/kotlin/com/agentlife/tailnet/core/AndroidTsnetBinding.kt"],
            generated,
        )


if __name__ == "__main__":
    unittest.main()
