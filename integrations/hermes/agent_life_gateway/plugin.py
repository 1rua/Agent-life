"""Hermes host registration boundary for the native Python Gateway."""

from __future__ import annotations

from pathlib import Path
from typing import Any, Mapping, Protocol

from .admin import (
    HERMES_HOST_API,
    AdminService,
    bind_admin_service,
    create_admin_cli_registrar,
    create_admin_panel,
    create_admin_service,
    normalize_host_api,
)
from .core import GatewayCore, create_gateway_core
from .http import EXPOSURE_MODES, GatewayExposure, create_gateway_exposure


HERMES_PLUGIN_MANIFEST = {
    "id": "agent-life-gateway",
    "backend": "hermes",
    "protocolVersion": "gateway-protocol-v2",
    "hostApi": {
        "status": "unverified",
        "min": None,
        "max": None,
        "commit": None,
    },
    "exposureModes": list(EXPOSURE_MODES),
    "management": {
        "surface": "host-ui-and-local-cli", "localOnly": True,
        "remotePort": None, "sensitiveOperations": "local-confirmation",
    },
    "securityBoundary": {
        "rawHeaders": "delegated-to-verified-request-seam",
        "tls": "host-or-explicit-terminator", "legacyBridge": "not-used",
    },
}

class HermesPluginContext(Protocol):
    plugin_data_dir: str | Path
    secret_store: Any
    credential_verifier: Any

    def register_platform(self, platform: GatewayPlatform) -> None:
        ...

    def register_admin(self, admin: AdminSurface) -> None:
        ...


def _attr(value: Any, *names: str, default: Any = None) -> Any:
    for name in names:
        if isinstance(value, Mapping) and name in value:
            return value[name]
        if hasattr(value, name):
            return getattr(value, name)
    return default


class GatewayPlatform:
    platform_id = "agent-life-gateway"
    id = "agent-life-gateway"

    def __init__(self, core: GatewayCore, exposure: GatewayExposure, admin: AdminService):
        self.core = core
        self.exposure = exposure
        self.admin = admin
        self.management = create_admin_panel(admin)
        self.read_only = admin.read_only

    @property
    def readOnly(self) -> bool:
        return self.read_only

    def handle(self, request: Any) -> dict[str, Any]:
        if self.read_only:
            context = _attr(request, "context", default={})
            request_id = str(_attr(context, "requestId", "request_id", default="agent-life-route"))
            correlation_id = str(_attr(context, "correlationId", "correlation_id", default="agent-life-route"))
            return {
                "requestId": request_id, "correlationId": correlation_id, "protocol": "2.0",
                "error": {
                    "code": "HOST_INCOMPATIBLE", "message": "HOST_INCOMPATIBLE",
                    "retryable": False, "retryAfterSeconds": None, "details": {},
                },
            }
        return self.core.handle(request)


class AdminSurface:
    id = "agent-life-gateway"
    local_only = True
    remote_port = None

    def __init__(self, service: AdminService):
        self.service = service
        self.panel = create_admin_panel(service)

    @property
    def read_only(self) -> bool:
        return self.service.read_only

    @property
    def readOnly(self) -> bool:
        return self.read_only

    @property
    def localOnly(self) -> bool:
        return self.local_only

    @property
    def remotePort(self) -> None:
        return self.remote_port

    def create_account(self, input: Mapping[str, Any]) -> dict[str, Any]:
        return self.service.create_account(input)

    createAccount = create_account

    def status(self) -> dict[str, Any]:
        return self.service.status()

    def execute(self, command: Mapping[str, Any]) -> dict[str, Any]:
        return self.service.execute(command)


class GatewayServices:
    def __init__(self, core: GatewayCore, admin: AdminService, exposure: GatewayExposure):
        self.core = core
        self.admin = admin
        self.exposure = exposure
        self.admin_panel = create_admin_panel(admin)


def _storage_root(ctx: Any) -> Path | None:
    value = _attr(ctx, "plugin_data_dir", "pluginDataDir", default=None)
    if value is None:
        return None
    return Path(value).resolve() / "agent-life-gateway" / "accounts"


def compose_gateway_services(ctx: Any) -> GatewayServices:
    host_api = normalize_host_api(_attr(ctx, "host_api", "hostApi", default=None))
    host_version = _attr(ctx, "host_version", "hostVersion", default=None)
    core = _attr(ctx, "gateway_core", "gatewayCore", default=None)
    if core is None:
        core = create_gateway_core(
            storage_root=_storage_root(ctx),
            secret_store=_attr(ctx, "secret_store", "secretStore", default=None),
            contract_root=_attr(ctx, "contract_root", "contractRoot", default=None),
            credential_verifier=_attr(ctx, "credential_verifier", "credentialVerifier", default=None),
        )
    admin = create_admin_service(core=core, host_version=host_version, host_api=host_api)
    config = _attr(ctx, "plugin_config", "pluginConfig", default={}) or {}
    mode = config.get("exposureMode", "host-route") if isinstance(config, Mapping) else "host-route"
    if mode not in EXPOSURE_MODES:
        mode = "host-route"
    exposure = create_gateway_exposure(
        mode, core=core, host_version=host_version, host_api=host_api,
        verify_request=_attr(ctx, "verify_request", "verifyRequest", default=None),
        max_body_bytes=_attr(ctx, "max_body_bytes", "maxBodyBytes", default=None),
    )
    return GatewayServices(core, admin, exposure)


def register(ctx: HermesPluginContext) -> None:
    services = compose_gateway_services(ctx)
    bind_admin_service(services.admin)
    ctx.register_platform(GatewayPlatform(services.core, services.exposure, services.admin))
    ctx.register_admin(AdminSurface(services.admin))
    register_route = _attr(ctx, "register_http_route", "registerHttpRoute", "register_route", default=None)
    if callable(register_route):
        for route in services.exposure.routes:
            register_route({"path": route.path, "auth": route.auth, "match": route.match, "handler": route.handler})
    register_cli = _attr(ctx, "register_cli", "registerCli", default=None)
    if callable(register_cli):
        register_cli(create_admin_cli_registrar(services.admin), {
            "parentPath": [], "commands": ["agent-life"],
            "descriptors": [{
                "name": "agent-life", "description": "Manage Agent-life Gateway accounts",
                "hasSubcommands": True,
            }],
        })


HERMES_PLUGIN = {
    "id": "agent-life-gateway",
    "name": "Agent-life Gateway",
    "description": "Agent-life Gateway Protocol v2 native Hermes platform",
    "register": register,
}


__all__ = [
    "AdminSurface", "GatewayPlatform", "GatewayServices", "HermesPluginContext", "HERMES_PLUGIN", "HERMES_PLUGIN_MANIFEST",
    "compose_gateway_services", "register",
]
