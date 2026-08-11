#!/usr/bin/env python3
"""SDK-free checks for the notification listener/runtime composition seam."""

from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]
COLLECTOR = ROOT / "notification-collector/src/main/kotlin/com/agentlife/notifications"
RUNTIME = COLLECTOR / "NotificationRuntime.kt"
SERVICE = COLLECTOR / "AndroidNotificationCollector.kt"


class NotificationRuntimeStaticTest(unittest.TestCase):
    def read(self, path: Path) -> str:
        self.assertTrue(path.is_file(), path)
        return path.read_text(encoding="utf-8")

    def test_runtime_has_typed_outbox_injection_and_lifecycle(self):
        source = self.read(RUNTIME) + "\n" + self.read(SERVICE)
        self.assertIn("class NotificationRuntime", source)
        self.assertIn("NotificationOutbox", source)
        self.assertIn("observeAutoSend", source)
        self.assertIn("enqueueAccepted", source)
        self.assertIn("fun start", source)
        self.assertIn("fun stop", source)
        self.assertIn("CoroutineScope", source)
        self.assertIn("SupervisorJob", source)
        self.assertIn("import kotlinx.coroutines.CoroutineScope", source)
        self.assertIn("import kotlinx.coroutines.Dispatchers", source)
        self.assertIn("import kotlinx.coroutines.SupervisorJob", source)
        self.assertIn("import kotlinx.coroutines.cancel", source)
        self.assertIn("NotificationAuthorization", source)
        self.assertIn("AuthorizationDecision.deny", source)
        self.assertIn("NotificationRuntimeFactory", source)
        self.assertIn("NotificationRuntimeFactoryRegistry", source)

    def test_listener_lifecycle_creates_and_stops_runtime(self):
        source = self.read(SERVICE)
        self.assertIn("override fun onCreate()", source)
        self.assertIn("NotificationRuntimeFactoryRegistry", source)
        self.assertIn("runtime", source)
        self.assertIn("runtime?.start", source)
        self.assertIn("override fun onDestroy()", source)
        self.assertIn("runtime?.stop", source)
        self.assertIn("installCollector", source)

    def test_runtime_has_no_network_or_generic_execution_surface(self):
        source = self.read(RUNTIME) + "\n" + self.read(SERVICE)
        forbidden = re.compile(
            r"VpnService|BIND_VPN_SERVICE|Socket|ServerSocket|DatagramSocket|"
            r"URLConnection|WebSocket|HttpClient|OkHttp|ProcessBuilder|"
            r"Runtime\.getRuntime|sendBroadcast|startService|bindService",
            re.IGNORECASE,
        )
        self.assertEqual([], [line for line in source.splitlines() if forbidden.search(line)])


if __name__ == "__main__":
    unittest.main()
