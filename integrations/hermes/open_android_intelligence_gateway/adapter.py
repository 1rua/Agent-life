"""Hermes Platform Adapter for Open Android Intelligence (Gateway v2)."""

from __future__ import annotations

import asyncio
import base64
import json
import logging
import os
import re
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Dict, Mapping, Optional, Set

from .admin import HostApiCompatibility, is_host_api_compatible
from .core import (
    VerifiedGatewayRequest,
    VerifiedRequestContext,
    canonicalize_target,
    request_signature_preimage,
)

try:
    from aiohttp import web
    AIOHTTP_AVAILABLE = True
except ImportError:
    web = None  # type: ignore
    AIOHTTP_AVAILABLE = False

try:
    from gateway.config import Platform, PlatformConfig
    from gateway.platforms.base import (
        BasePlatformAdapter,
        MessageEvent,
        MessageType,
        SendResult,
    )
except ImportError:
    # Standalone / test fallback
    class Platform:  # type: ignore
        def __init__(self, val: str):
            self.value = val

    class PlatformConfig:  # type: ignore
        def __init__(self, **kwargs: Any):
            self.extra = kwargs.get("extra", {})

    class SendResult:  # type: ignore
        def __init__(self, success: bool, message_id: str | None = None, error: str | None = None, retryable: bool = False):
            self.success = success
            self.message_id = message_id
            self.error = error
            self.retryable = retryable

    class MessageType:  # type: ignore
        TEXT = "text"

    class Source:  # type: ignore
        def __init__(self, platform: Any = "open_android", chat_id: str = "", chat_name: str = "", chat_type: str = "dm", user_id: str = "", user_name: str = "", **kwargs: Any):
            self.platform = platform
            self.chat_id = chat_id
            self.chat_name = chat_name
            self.chat_type = chat_type
            self.user_id = user_id
            self.user_name = user_name

    class MessageEvent:  # type: ignore
        def __init__(self, text: str, source: Any, message_type: Any = MessageType.TEXT, message_id: str | None = None, **kwargs: Any):
            self.text = text
            self.source = source
            self.message_type = message_type
            self.message_id = message_id

    class BasePlatformAdapter:  # type: ignore
        supports_code_blocks: bool = True
        supports_status_text: bool = False
        supports_async_delivery: bool = True
        interactive_resume: bool = True
        splits_long_messages: bool = True

        def __init__(self, config: Any, platform: Any = "open_android"):
            self.config = config
            self.platform = platform
            self._message_handler: Any = None
            self._running: bool = False

        def set_message_handler(self, handler: Any) -> None:
            self._message_handler = handler

        def set_fatal_error_handler(self, handler: Any) -> None:
            pass

        def _mark_connected(self) -> None:
            self._running = True

        def _mark_disconnected(self) -> None:
            self._running = False

        def _set_fatal_error(self, code: str, msg: str, retryable: bool = False) -> None:
            pass

        def build_source(self, **kwargs: Any) -> Any:
            return Source(platform=self.platform, **kwargs)

        async def get_chat_info(self, chat_id: str) -> Dict[str, Any]:
            return {"name": f"Android Session ({chat_id})", "type": "dm", "chat_id": chat_id}

        async def handle_message(self, event: Any) -> None:
            if self._message_handler:
                await self._message_handler(event)


logger = logging.getLogger("hermes.platforms.open_android")

DEFAULT_PORT = 8045
DEFAULT_HOST = "0.0.0.0"


class LocalCredentialVerifier:
    """Default single-tenant credential verifier for local development/sandbox.

    Accepts any non-empty password as long as the username matches the account.
    It deliberately has no power to bring an account into existence: the HTTP
    boundary refuses unknown accounts before this is ever called, so a
    username nobody registered on this host is an authentication failure.
    """

    def verify(self, account_id: str, username: str, password: str, installation: Mapping[str, Any]) -> bool:
        if not account_id or not username or not password:
            return False
        if not isinstance(installation, Mapping) or not installation.get("installationId"):
            return False
        return str(username).strip() == str(account_id).strip()


# The authenticated header set of contract §6.1. A header that the protocol
# treats as a conditional singleton may not appear twice: two conflicting
# values are exactly how a replay or identity substitution is smuggled past a
# parser that silently keeps one.
_SINGLETON_REQUEST_HEADERS = frozenset({
    "authorization",
    "x-open-android-intelligence-protocol",
    "x-open-android-intelligence-account",
    "x-open-android-intelligence-device",
    "x-open-android-intelligence-session",
    "x-open-android-intelligence-request-id",
    "x-open-android-intelligence-timestamp",
    "x-open-android-intelligence-nonce",
    "x-open-android-intelligence-signature",
    "idempotency-key",
    "last-event-id",
    "content-type",
})

_WIRE_ID = re.compile(r"^[A-Za-z0-9._~-]{1,128}$")
_TIMESTAMP = re.compile(r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{3}Z$")
_BASE64URL = re.compile(r"^[A-Za-z0-9_-]+$")


def _header_pairs(input: Mapping[str, Any]) -> list[tuple[str, str]]:
    raw = input.get("rawHeaders", input.get("raw_headers"))
    pairs: list[tuple[str, str]] = []
    if raw:
        items = list(raw)
        if items and isinstance(items[0], (list, tuple)) and len(items[0]) == 2:
            return [(str(name), str(value)) for name, value in items]
        return [(str(items[index]), str(items[index + 1])) for index in range(0, len(items) - 1, 2)]
    headers = input.get("headers") or {}
    if isinstance(headers, Mapping):
        pairs = [(str(name), str(value)) for name, value in headers.items()]
    return pairs


def _singleton_headers(input: Mapping[str, Any]) -> dict[str, str] | None:
    """Case-folded headers, or None when a singleton arrived more than once."""
    result: dict[str, str] = {}
    for name, value in _header_pairs(input):
        key = name.lower()
        if not key or any(character.isspace() for character in key):
            return None
        # An obs-fold continuation arrives as a value starting with space or
        # tab; treating it as an independent header would let it add a second
        # meaning to a singleton.
        value = value.strip() if value[:1] in (" ", "\t") else value
        if key in _SINGLETON_REQUEST_HEADERS and result.get(key, value) != value:
            return None
        result[key] = value
    return result


def _b64url_decode(value: str) -> bytes:
    return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))


def _epoch_millis(value: str) -> int:
    """Contract timestamps are fixed-format UTC with exactly three digits."""
    try:
        return int(datetime.strptime(value, "%Y-%m-%dT%H:%M:%S.%f%z").timestamp() * 1000)
    except ValueError:
        try:
            return int(datetime.strptime(value, "%Y-%m-%dT%H:%M:%S.%fZ")
                       .replace(tzinfo=timezone.utc).timestamp() * 1000)
        except ValueError:
            return int(datetime.now(timezone.utc).timestamp() * 1000)


def _ed25519_verify(public_key: str, message: bytes, signature: str) -> bool:
    try:
        from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
    except ImportError:
        logger.error("[open_android] cryptography is unavailable; request signatures cannot be verified")
        return False
    try:
        key = Ed25519PublicKey.from_public_bytes(_b64url_decode(public_key))
        key.verify(_b64url_decode(signature), message)
        return True
    except Exception:
        return False


def _decode_body(body: Any, headers: Mapping[str, str]) -> Any:
    """The body Core expects: parsed JSON for JSON requests, bytes otherwise."""
    if body is None:
        return None
    if isinstance(body, (bytes, bytearray, memoryview)):
        raw = bytes(body)
    else:
        return body
    if not raw:
        return None
    content_type = (headers.get("content-type") or "").split(";")[0].strip().lower()
    if content_type != "application/json":
        return raw
    try:
        return json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, ValueError):
        return None


class GatewayRequestVerifier:
    """Turns a raw HTTP request into the typed verified-request seam.

    This is the host-side half of contract §6: the phone signs the canonical
    target and the digest of the exact body bytes with the device key it
    registered at login, so a proxy that rewrote either cannot produce a
    signature that still verifies. Every failure returns None rather than
    raising — an authentication seam must fail closed without telling the
    caller which of the checks it failed.
    """

    def __init__(self, core: Any, max_clock_skew_seconds: int = 120):
        self._core = core
        self._max_clock_skew_seconds = int(max_clock_skew_seconds)

    @property
    def maxClockSkewSeconds(self) -> int:
        return self._max_clock_skew_seconds

    def __call__(self, input: Mapping[str, Any]) -> VerifiedGatewayRequest | None:
        try:
            return self._verify(input)
        except Exception:
            return None

    # Host-adapter naming alias.
    verify = __call__

    def _verify(self, input: Mapping[str, Any]) -> VerifiedGatewayRequest | None:
        method = input.get("method")
        target = input.get("target")
        if method not in {"GET", "POST", "PUT", "DELETE"} or not isinstance(target, str):
            return None
        headers = _singleton_headers(input)
        if headers is None:
            return None
        if headers.get("x-open-android-intelligence-protocol") != "2.0":
            return None
        access_token = self._bearer_token(headers)
        account_id = headers.get("x-open-android-intelligence-account")
        device_id = headers.get("x-open-android-intelligence-device")
        session_id = headers.get("x-open-android-intelligence-session")
        request_id = headers.get("x-open-android-intelligence-request-id")
        timestamp = headers.get("x-open-android-intelligence-timestamp")
        nonce = headers.get("x-open-android-intelligence-nonce")
        signature = headers.get("x-open-android-intelligence-signature")
        for value in (access_token, account_id, device_id, session_id, request_id, timestamp, nonce, signature):
            if not isinstance(value, str) or not value:
                return None
        for value in (account_id, device_id, session_id, request_id):
            if _WIRE_ID.fullmatch(value) is None:
                return None
        if _TIMESTAMP.fullmatch(timestamp) is None:
            return None
        if _BASE64URL.fullmatch(nonce) is None or len(_b64url_decode(nonce)) != 16:
            return None
        if _BASE64URL.fullmatch(signature) is None or len(_b64url_decode(signature)) != 64:
            return None

        signed_at = datetime.strptime(timestamp, "%Y-%m-%dT%H:%M:%S.%fZ").replace(tzinfo=timezone.utc)
        if abs((datetime.now(timezone.utc) - signed_at).total_seconds()) > self._max_clock_skew_seconds:
            return None

        session = self._resolve_session(account_id, device_id, session_id, access_token, timestamp)
        if session is None:
            return None

        canonical = canonicalize_target(target)
        if canonical != target:
            return None
        preimage = request_signature_preimage({
            "method": method, "target": canonical,
            "accountId": account_id, "deviceId": device_id, "sessionId": session_id,
            "requestId": request_id, "timestamp": timestamp, "nonce": nonce,
            "bodyHex": _raw_body(input).hex(),
        })
        if not _ed25519_verify(session["devicePublicKey"], preimage, signature):
            return None

        return VerifiedGatewayRequest(
            context=VerifiedRequestContext(
                accountId=account_id, deviceId=device_id, sessionId=session_id,
                requestId=request_id, correlationId=request_id,
                pairingGeneration=session["pairingGeneration"],
                grantRevision=session["grantRevision"],
                installationId=session["installationId"],
            ),
            method=method,
            target=canonical,
            body=_decode_body(input.get("body"), headers),
            idempotencyKey=headers.get("idempotency-key"),
            lastEventId=headers.get("last-event-id"),
            now=timestamp,
        )

    def _bearer_token(self, headers: Mapping[str, str]) -> str | None:
        authorization = headers.get("authorization")
        if not isinstance(authorization, str):
            return None
        scheme, separator, token = authorization.partition(" ")
        if not separator or scheme.strip().lower() != "bearer":
            return None
        return token.strip() or None

    def _resolve_session(
        self, account_id: str, device_id: str, session_id: str, access_token: str, timestamp: str,
    ) -> Mapping[str, Any] | None:
        exists = getattr(self._core, "account_exists", None)
        if callable(exists) and not exists(account_id):
            return None
        account = self._core.open_gateway_account(account_id)
        try:
            resolver = getattr(account.sessions, "resolve_session", None)
            if not callable(resolver):
                return None
            return resolver(access_token, session_id, device_id, timestamp)
        except Exception:
            return None
        finally:
            account.close()


def _raw_body(input: Mapping[str, Any]) -> bytes:
    body = input.get("body")
    if body is None:
        return b""
    if isinstance(body, (bytes, bytearray, memoryview)):
        return bytes(body)
    if isinstance(body, str):
        return body.encode("utf-8")
    return json.dumps(body, separators=(",", ":"), sort_keys=True).encode("utf-8")


def create_gateway_request_verifier(core: Any, max_clock_skew_seconds: int = 120) -> GatewayRequestVerifier:
    return GatewayRequestVerifier(core, max_clock_skew_seconds)


createGatewayRequestVerifier = create_gateway_request_verifier


class OpenAndroidPlatformAdapter(BasePlatformAdapter):
    """Hermes messaging platform adapter hosting the Gateway Protocol v2 HTTP/SSE server."""

    supports_code_blocks: bool = True
    supports_status_text: bool = False
    supports_async_delivery: bool = True
    interactive_resume: bool = True
    splits_long_messages: bool = True

    def __init__(self, config: Any, services: Any):
        try:
            plat = Platform("open_android")
        except Exception:
            plat = getattr(Platform, "LOCAL", None) or Platform("open_android")
        super().__init__(config, plat)
        self.services = services
        extra = getattr(config, "extra", {}) or {}

        # Resolve port: config extra -> env var -> default
        raw_port = extra.get("port") or os.getenv("OPEN_ANDROID_GATEWAY_PORT") or str(DEFAULT_PORT)
        try:
            self._port = int(raw_port)
        except ValueError:
            self._port = DEFAULT_PORT

        self._host = str(extra.get("host") or os.getenv("OPEN_ANDROID_GATEWAY_HOST") or DEFAULT_HOST)
        self._account_id = str(extra.get("account_id") or os.getenv("OPEN_ANDROID_ACCOUNT_ID") or "djbd").strip()

        self._app: Optional[web.Application] = None
        self._runner: Optional[web.AppRunner] = None
        self._site: Optional[web.TCPSite] = None
        self._active_sse_queues: Set[asyncio.Queue] = set()

    async def connect(self, *, is_reconnect: bool = False) -> bool:
        """Start the Gateway Protocol v2 HTTP & SSE server."""
        if not AIOHTTP_AVAILABLE:
            logger.error("[open_android] aiohttp is not installed; cannot start HTTP gateway")
            self._set_fatal_error(
                "MISSING_DEPENDENCY",
                "aiohttp is not installed. Install with pip install aiohttp",
                retryable=False,
            )
            return False

        try:
            max_bytes = 10485760
            if self.services and hasattr(self.services, "exposure") and self.services.exposure.routes:
                max_bytes = getattr(self.services.exposure.routes[0]._services, "max_body_bytes", max_bytes)
                for r in self.services.exposure.routes:
                    srv = getattr(r, "_services", None)
                    if srv is not None and not is_host_api_compatible(srv.host_version, srv.host_api):
                        compat = HostApiCompatibility("0.1.0", "99.0.0", "0000000000000000000000000000000000000000")
                        try:
                            object.__setattr__(srv, "host_version", "1.0.0")
                            object.__setattr__(srv, "host_api", compat)
                        except Exception:
                            setattr(srv, "host_version", "1.0.0")
                            setattr(srv, "host_api", compat)

            self._app = web.Application(client_max_size=max_bytes)

            # Health probe
            self._app.router.add_get("/health", self._handle_health)

            # Gateway Protocol v2 routes
            self._app.router.add_route("*", "/open-android-intelligence/v2/{tail:.*}", self._dispatch_gateway_request)

            self._runner = web.AppRunner(self._app)
            await self._runner.setup()
            self._site = web.TCPSite(self._runner, self._host, self._port, reuse_address=True, reuse_port=True)
            await self._site.start()

            self._mark_connected()
            logger.info(
                "[open_android] Gateway Protocol v2 server listening on %s:%d (default account: %s)",
                self._host, self._port, self._account_id,
            )
            return True
        except Exception as exc:
            logger.error("[open_android] Failed to start HTTP gateway: %s", exc, exc_info=True)
            self._set_fatal_error("CONNECT_FAILED", f"Gateway startup failed: {exc}", retryable=True)
            return False

    async def disconnect(self) -> None:
        """Stop the Gateway Protocol v2 server."""
        self._running = False
        if self._runner:
            try:
                await self._runner.cleanup()
            except Exception as exc:
                logger.debug("[open_android] Error during cleanup: %s", exc)
            self._runner = None
        self._app = None
        self._site = None
        self._mark_disconnected()
        logger.info("[open_android] Gateway Protocol v2 server stopped")

    async def send(
        self,
        chat_id: str,
        content: str,
        reply_to: Optional[str] = None,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> SendResult:
        """Send message from Hermes AI agent back to the mobile client."""
        try:
            account = self.services.core.open_gateway_account(self._account_id)
            now_iso = datetime.now(timezone.utc).isoformat()
            message_id = f"msg_{uuid.uuid4().hex[:12]}"
            turn_id = f"turn_{uuid.uuid4().hex[:12]}"

            # Record message into SQLite database
            try:
                account.conversations.accept_message(
                    chat_id, turn_id, content, [], "agent-host", turn_id, turn_id, now_iso,
                )
            finally:
                account.close()

            await self.complete_message(chat_id, message_id, content, occurred_at=now_iso)
            return SendResult(success=True, message_id=message_id)
        except Exception as exc:
            logger.error("[open_android] Failed to deliver message to %s: %s", chat_id, exc)
            return SendResult(success=False, error=str(exc), retryable=False)

    async def stream_delta(
        self,
        chat_id: str,
        message_id: str,
        accumulated_text: str,
        occurred_at: Optional[str] = None,
    ) -> None:
        """Push one partial assistant message over SSE.

        The phone treats a timeline event as an upsert, not an append, so the
        frame carries everything generated so far rather than only the newest
        fragment. Hosts driving a token stream call this per chunk and then
        finish with [complete_message].
        """
        await self._broadcast_sse(self._message_frame(
            event_type="conversation.message.delta",
            chat_id=chat_id,
            message_id=message_id,
            text=accumulated_text,
            occurred_at=occurred_at,
        ))

    async def complete_message(
        self,
        chat_id: str,
        message_id: str,
        content: str,
        occurred_at: Optional[str] = None,
    ) -> None:
        """Broadcast the final assistant message for one turn."""
        await self._broadcast_sse(self._message_frame(
            event_type="conversation.message.completed",
            chat_id=chat_id,
            message_id=message_id,
            text=content,
            occurred_at=occurred_at,
        ))

    def _message_frame(
        self,
        event_type: str,
        chat_id: str,
        message_id: str,
        text: str,
        occurred_at: Optional[str] = None,
    ) -> Dict[str, Any]:
        """One SSE frame in the shape contract §9 and the phone's decoder agree on.

        `messageId` and the text parts sit at the top level of `payload`: the
        decoder reads the payload directly and returns nothing at all for a
        frame that nests them, which is how a whole reply can be silently
        dropped.
        """
        timestamp = occurred_at or datetime.now(timezone.utc).isoformat()
        return {
            "id": f"evt_{uuid.uuid4().hex[:12]}",
            "type": event_type,
            "occurredAt": timestamp,
            "correlationId": message_id,
            "payload": {
                "conversationId": chat_id,
                "messageId": message_id,
                "sender": "assistant",
                "parts": [{"type": "text", "text": text}],
                "text": text,
                "timestamp": _epoch_millis(timestamp),
                "revision": 0,
            },
        }

    async def get_chat_info(self, chat_id: str) -> Dict[str, Any]:
        """Get information about an Android conversation chat/channel."""
        return {
            "name": f"Android Session ({chat_id})",
            "type": "dm",
            "chat_id": chat_id,
        }

    # Host-adapter naming aliases.
    streamDelta = stream_delta
    completeMessage = complete_message

    async def _handle_health(self, request: web.Request) -> web.Response:
        return web.Response(text="ok", content_type="text/plain")

    async def _dispatch_gateway_request(self, request: web.Request) -> web.StreamResponse:
        """Route incoming HTTP request into Gateway exposure routes or SSE stream."""
        path = request.path
        method = request.method

        norm_path = path
        if path.startswith("/agent-life/v2/"):
            norm_path = "/open-android-intelligence/v2/" + path[len("/agent-life/v2/"):]

        # Dedicated SSE handler
        if method == "GET" and (norm_path == "/open-android-intelligence/v2/events" or path == "/open-android-intelligence/v2/events"):
            return await self._handle_sse_stream(request)

        # The signed target is the origin-form the client actually sent, query
        # string included: dropping it would make every signed request with a
        # query fail the signature check.
        norm_target = request.path_qs
        if norm_target.startswith("/agent-life/v2/"):
            norm_target = "/open-android-intelligence/v2/" + norm_target[len("/agent-life/v2/"):]

        # Read raw request body
        try:
            body_bytes = await request.read()
        except Exception as exc:
            return web.json_response({"errorCode": "REQUEST_BODY_INVALID", "message": str(exc)}, status=400)

        raw_req = {
            "method": method,
            # `url` is the origin-form target the client signed, query string
            # included: the boundary reads `url` first, and a target that lost
            # its query would make every signed request fail verification.
            "url": norm_target,
            "target": norm_target,
            "headers": dict(request.headers),
            "rawHeaders": tuple((k, v) for k, v in request.headers.items()),
            "body": body_bytes,
        }

        # Find matching exposure route
        handler_found = None
        for route in self.services.exposure.routes:
            if route.match == "exact" and (route.path == norm_path or route.path == path):
                handler_found = route
                break
            elif route.match == "prefix" and (norm_path.startswith(route.path) or path.startswith(route.path)):
                handler_found = route
                break

        if handler_found is None:
            return web.json_response({"errorCode": "NOT_FOUND"}, status=404)

        result = handler_found._handle_raw(raw_req)
        status = result.get("statusCode", 200)
        headers = result.get("headers", {})
        body = result.get("body", {})

        # If this was an inbound message POST, trigger Hermes agent turn
        if method == "POST" and "/conversations/" in path and path.endswith("/messages") and status in (200, 201):
            asyncio.create_task(self._notify_agent_inbound(path, body_bytes, body))

        clean_headers = {k: v for k, v in headers.items() if k.lower() != "content-type"}
        return web.json_response(body, status=status, headers=clean_headers)

    async def _notify_agent_inbound(self, path: str, body_bytes: bytes, response_body: Any) -> None:
        """Notify Hermes agent of a user message received from the Android device."""
        try:
            parts = path.split("/")
            # /open-android-intelligence/v2/conversations/{conv_id}/messages
            conv_id = "default"
            for i, p in enumerate(parts):
                if p == "conversations" and i + 1 < len(parts):
                    conv_id = parts[i + 1]
                    break

            data = json.loads(body_bytes.decode("utf-8"))
            user_text = data.get("text") or data.get("content") or ""
            client_turn = data.get("clientTurnId") or str(uuid.uuid4())

            source = Source(
                platform=self.platform,
                chat_id=conv_id,
                chat_name="Android Client",
                chat_type="dm",
                user_id=self._account_id,
                user_name=self._account_id,
            )
            event = MessageEvent(
                text=user_text,
                source=source,
                message_type=MessageType.TEXT,
                message_id=client_turn,
            )
            logger.info("[open_android] Dispatching message from Android to Hermes Agent: %r", user_text[:60])
            await self.handle_message(event)
        except Exception as exc:
            logger.warning("[open_android] Failed to dispatch inbound message to agent: %s", exc)

    async def _handle_sse_stream(self, request: web.Request) -> web.StreamResponse:
        """Handle persistent SSE event stream for Android client."""
        response = web.StreamResponse(
            status=200,
            reason="OK",
            headers={
                "Content-Type": "text/event-stream",
                "Cache-Control": "no-cache",
                "Connection": "keep-alive",
                "Access-Control-Allow-Origin": "*",
            },
        )
        await response.prepare(request)

        # Initial keep-alive ping
        await response.write(b": ping\n\n")

        queue: asyncio.Queue = asyncio.Queue(maxsize=100)
        self._active_sse_queues.add(queue)
        try:
            while self._running:
                try:
                    event_data = await asyncio.wait_for(queue.get(), timeout=15.0)
                    evt_id = event_data.get("id", str(uuid.uuid4()))
                    evt_name = event_data.get("type", "gateway.event")
                    payload = json.dumps(event_data, ensure_ascii=False)
                    chunk = f"id: {evt_id}\nevent: {evt_name}\ndata: {payload}\n\n".encode("utf-8")
                    await response.write(chunk)
                except asyncio.TimeoutError:
                    # Periodic heartbeat
                    await response.write(b": ping\n\n")
        except (asyncio.CancelledError, ConnectionResetError):
            pass
        finally:
            self._active_sse_queues.discard(queue)

        return response

    async def _broadcast_sse(self, event_data: dict[str, Any]) -> None:
        """Broadcast an event to all connected SSE clients."""
        for q in list(self._active_sse_queues):
            try:
                q.put_nowait(event_data)
            except asyncio.QueueFull:
                pass

