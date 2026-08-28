import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from agent_life_gateway.plugin import register


class FakeHermesContext:
    def __init__(self, plugin_data_dir, host_version="1.0.0"):
        self.plugin_data_dir = plugin_data_dir
        self.secret_store = None
        self.host_version = host_version
        self.platform_ids = []
        self.admin_surfaces = []
        self.http_routes = []
        self.cli_registrations = []

    def register_platform(self, platform):
        self.platform_ids.append(platform.platform_id)

    def register_admin(self, admin):
        self.admin_surfaces.append(admin)

    def register_http_route(self, route):
        self.http_routes.append(route)

    def register_cli(self, registrar, options):
        self.cli_registrations.append((registrar, options))


def test_registers_platform_and_management_surface(tmp_path):
    context = FakeHermesContext(tmp_path)

    register(context)

    assert context.platform_ids == ["agent-life-gateway"]
    assert len(context.admin_surfaces) == 1


def test_registration_exposes_native_core_routes_and_local_only_management(tmp_path):
    context = FakeHermesContext(tmp_path)

    register(context)

    platform = context.platform_ids and context.admin_surfaces[0].panel
    assert platform.id == "agent-life-gateway"
    assert platform.remote_port is None
    assert context.admin_surfaces[0].local_only is True
    assert {route["path"] for route in context.http_routes} >= {
        "/agent-life/v2/negotiate",
        "/agent-life/v2/events",
        "/agent-life/v2/attachments",
        "/agent-life/v2/device-requests/",
    }
    assert context.cli_registrations[0][1]["commands"] == ["agent-life"]


def test_registration_marks_unknown_host_read_only_before_writes(tmp_path):
    context = FakeHermesContext(tmp_path, host_version="not-a-version")

    register(context)

    assert context.admin_surfaces[0].read_only is True
    result = context.admin_surfaces[0].create_account({"accountId": "acct", "localConfirmation": True})
    assert result["error"]["code"] == "HOST_INCOMPATIBLE"
    assert not (Path(tmp_path) / "agent-life-gateway" / "accounts").exists()
