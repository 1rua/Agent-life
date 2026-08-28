import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from agent_life_gateway.admin import HostApiCompatibility
from agent_life_gateway.http import EXPOSURE_MODES, create_gateway_exposure
from agent_life_gateway.core import create_gateway_core


TEST_HOST_API = HostApiCompatibility(
    "1.0.0", "1.0.0", "0123456789abcdef0123456789abcdef01234567"
)


class FakeCore:
    def __init__(self):
        self.requests = []

    def handle(self, request):
        self.requests.append(request)
        context = request["context"] if isinstance(request, dict) else request.context
        request_id = context["requestId"] if isinstance(context, dict) else context.request_id
        correlation_id = context["correlationId"] if isinstance(context, dict) else context.correlation_id
        return {
            "requestId": request_id,
            "correlationId": correlation_id,
            "protocol": "2.0",
            "data": {"accepted": True, "target": request["target"] if isinstance(request, dict) else request.target},
        }


def _verified(target="/agent-life/v2/negotiate"):
    return {
        "context": {
            "accountId": "account-a", "deviceId": "device-a", "sessionId": "session-a",
            "requestId": "request-a", "correlationId": "correlation-a",
            "pairingGeneration": 1, "grantRevision": 1,
        },
        "method": "POST",
        "target": target,
        "body": {},
    }


def test_all_three_exposure_modes_share_routes_and_verified_core_result():
    results = []
    for mode in EXPOSURE_MODES:
        core = FakeCore()
        exposure = create_gateway_exposure(mode, core=core, host_version="1.0.0", host_api=TEST_HOST_API)
        route = next(item for item in exposure.routes if item.path == "/agent-life/v2/negotiate")
        response = route.handle({"verifiedRequest": _verified()})
        results.append(response)
        assert exposure.admin.remote_port is None
        assert exposure.admin.remotePort is None
    assert results[0] == results[1] == results[2]
    assert results[0]["statusCode"] == 200
    assert results[0]["body"]["data"] == {"accepted": True, "target": "/agent-life/v2/negotiate"}


def test_incompatible_or_missing_host_fails_closed_before_core_for_each_mode():
    for host_version in (None, "not-a-version", "2.0.0"):
        for mode in EXPOSURE_MODES:
            core = FakeCore()
            exposure = create_gateway_exposure(mode, core=core, host_version=host_version)
            route = next(item for item in exposure.routes if item.path == "/agent-life/v2/negotiate")
            response = route.handle({"verifiedRequest": _verified()})
            assert response["statusCode"] == 503
            assert response["body"]["error"]["code"] == "HOST_INCOMPATIBLE"
            assert core.requests == []


class RawRequest:
    def __init__(self, body=b"{}", url="/agent-life/v2/negotiate"):
        self.method = "POST"
        self.url = url
        self.headers = {"content-type": "application/json"}
        self.rawHeaders = ("content-type", "application/json")
        self.body = body


class RawResponse:
    def __init__(self):
        self.status_code = 0
        self.headers = {}
        self.body = ""

    def set_header(self, name, value):
        self.headers[name.lower()] = value

    def end(self, body):
        self.body = body


def test_raw_host_boundary_requires_verifier_and_enforces_body_limit():
    core = FakeCore()
    exposure = create_gateway_exposure("host-route", core=core, host_version="1.0.0", host_api=TEST_HOST_API)
    route = next(item for item in exposure.routes if item.path == "/agent-life/v2/events")
    response = RawResponse()
    route.handler(RawRequest(url="/agent-life/v2/events"), response)
    assert response.status_code == 401
    assert core.requests == []

    verifier_calls = []
    exposure = create_gateway_exposure(
        "host-route", core=core, host_version="1.0.0", host_api=TEST_HOST_API, max_body_bytes=1,
        verify_request=lambda request: verifier_calls.append(request) or _verified(),
    )
    route = next(item for item in exposure.routes if item.path == "/agent-life/v2/negotiate")
    response = RawResponse()
    route.handler(RawRequest(), response)
    assert response.status_code == 413
    assert verifier_calls == []


def test_raw_host_boundary_passes_exact_bytes_to_verifier_before_core():
    core = FakeCore()
    seen = []

    def verifier(request):
        seen.append(request)
        return _verified(request["target"])

    exposure = create_gateway_exposure("direct-tls", core=core, host_version="1.0.0", host_api=TEST_HOST_API, verify_request=verifier)
    route = next(item for item in exposure.routes if item.path == "/agent-life/v2/conversations")
    response = RawResponse()
    route.handler(RawRequest(b"{}", url="/agent-life/v2/conversations"), response)
    assert response.status_code == 200
    assert seen[0]["body"] == b"{}"
    assert core.requests[0]["target"] == "/agent-life/v2/conversations"


def test_verified_exposure_seam_reaches_the_independent_python_core(tmp_path):
    core = create_gateway_core(storage_root=tmp_path)
    request = _verified("/agent-life/v2/conversations")
    request["body"] = {"clientConversationId": "conv_exposure"}
    request["idempotencyKey"] = "request-a"
    exposure = create_gateway_exposure("loopback-reverse-proxy", core=core, host_version="1.0.0", host_api=TEST_HOST_API)
    route = next(item for item in exposure.routes if item.path == "/agent-life/v2/conversations")

    response = route.handle({"verifiedRequest": request})

    assert response["statusCode"] == 200
    assert response["body"]["data"]["conversation"]["clientConversationId"] == "conv_exposure"


def test_negotiate_raw_route_has_independent_pre_auth_input_without_verifier(tmp_path):
    core = create_gateway_core(storage_root=tmp_path)
    body = {
        "negotiationId": "neg_raw",
        "protocol": {"major": 2, "minor": 0},
        "client": {"installationId": "install_raw", "appVersion": "2.0.0", "platform": "android", "platformApi": 35},
        "features": {
            "auth": ["password"], "messages": ["chat-v1"], "attachments": ["staged-sha256-v1"],
            "events": ["sse-cursor-v1"], "deviceRequests": ["risk-queue-v1"],
        },
        "schemaHashes": {"core": "sha256:" + "a" * 64},
    }

    exposure = create_gateway_exposure(
        "host-route", core=core, host_version="1.0.0",
        host_api=HostApiCompatibility("1.0.0", "1.0.0", "0123456789abcdef0123456789abcdef01234567"),
    )
    route = next(item for item in exposure.routes if item.path == "/agent-life/v2/negotiate")
    request = RawRequest(json.dumps(body).encode("utf-8"))
    response = RawResponse()

    route.handler(request, response)

    assert response.status_code == 200
    assert json.loads(response.body)["data"]["protocol"] == {"major": 2, "minor": 0}
