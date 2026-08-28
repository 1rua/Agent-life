import sys
import hashlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from agent_life_gateway.account_paths import account_paths
from agent_life_gateway.core import (
    GatewayError,
    GatewayResponse,
    VerifiedGatewayRequest,
    VerifiedRequestContext,
    create_gateway_core,
)
from agent_life_gateway.audit import AuditStore
from agent_life_gateway.plugin import register


class FakeHermesContext:
    def __init__(self, plugin_data_dir):
        self.plugin_data_dir = plugin_data_dir
        self.secret_store = None
        self.platform_ids = []
        self.admin_surfaces = []

    def register_platform(self, platform):
        self.platform_ids.append(platform.platform_id)

    def register_admin(self, admin):
        self.admin_surfaces.append(admin)


def test_registers_gateway_platform(tmp_path):
    context = FakeHermesContext(tmp_path)

    register(context)

    assert context.platform_ids == ["agent-life-gateway"]


def test_accounts_never_share_database(tmp_path):
    alice = account_paths(tmp_path, "alice")
    bob = account_paths(tmp_path, "bob")

    assert alice.database != bob.database


def _context(**overrides):
    context = {
        "accountId": "acct_alice",
        "deviceId": "dev_1",
        "sessionId": "sess_1",
        "requestId": "req_1",
        "correlationId": "cor_1",
        "pairingGeneration": 1,
        "grantRevision": 1,
    }
    context.update(overrides)
    return context


def test_account_state_and_event_cursors_never_cross_databases(tmp_path):
    core = create_gateway_core(storage_root=tmp_path)
    alice = core.open_gateway_account("acct_alice")
    bob = core.open_gateway_account("acct_bob")

    event = alice.events.append(
        event_type="gateway.notice",
        correlation_id="cor_alice",
        payload={"notice": "ready"},
    )
    assert event["eventId"]
    assert event["eventId"] in [item["eventId"] for item in alice.events.read_after(None)]
    try:
        bob.events.read_after(event["eventId"])
    except GatewayError as exc:
        assert exc.code == "CURSOR_EXPIRED"
    else:
        raise AssertionError("Bob must not read Alice's cursor")

    conversation = bob.conversations.create(
        client_conversation_id="conv_bob",
        title="Bob",
        correlation_id="cor_bob",
    )
    try:
        bob.conversations.accept_message(
            conversation_id=conversation["conversationId"],
            client_message_id="msg_bob",
            text="do not retain this body",
            attachment_ids=["att_alice"],
            device_id="dev_bob",
            request_id="req_bob",
            correlation_id="cor_bob_message",
        )
    except GatewayError as exc:
        assert exc.code == "ATTACHMENT_EXPIRED"
    else:
        raise AssertionError("Bob must not reference Alice's attachment")
    assert "do not retain this body" not in str(bob.audit.list())


def test_handle_enforces_identity_idempotency_and_sse_cursor_binding(tmp_path):
    core = create_gateway_core(storage_root=tmp_path)
    account = core.open_gateway_account("acct_alice")
    old_event = account.events.append(
        event_type="gateway.notice",
        correlation_id="cor_old",
        payload={"notice": "old"},
    )
    account.close()

    identity = core.handle(
        {
            "context": _context(requestId="req_identity"),
            "method": "POST",
            "target": "/agent-life/v2/conversations",
            "idempotencyKey": "req_identity",
            "body": {"clientConversationId": "conv_identity", "accountId": "acct_bob"},
        }
    )
    assert identity["error"]["code"] == "IDENTITY_OVERRIDE_REJECTED"

    request = {
        "context": _context(requestId="req_create", correlationId="cor_create"),
        "method": "POST",
        "target": "/agent-life/v2/conversations",
        "idempotencyKey": "req_create",
        "body": {"clientConversationId": "conv_create", "title": "A"},
    }
    first = core.handle(request)
    assert first["data"]["conversation"]["clientConversationId"] == "conv_create"
    assert core.handle(request) == first
    conflict = core.handle({**request, "body": {"clientConversationId": "conv_changed"}})
    assert conflict["error"]["code"] == "IDEMPOTENCY_CONFLICT"

    cursor_conflict = core.handle(
        {
            "context": _context(requestId="req_cursor"),
            "method": "GET",
            "target": f"/agent-life/v2/events?cursor={old_event['eventId']}",
            "lastEventId": "evt_other",
        }
    )
    assert cursor_conflict["error"]["code"] == "CURSOR_CONFLICT"


def test_consumes_the_shared_schema_and_vector_registry(tmp_path):
    core = create_gateway_core(storage_root=tmp_path)

    results = core.run_shared_vectors()

    assert results
    assert {result["status"] for result in results} == {"pass"}
    assert {result["implementation"] for result in results} == {"hermes-python"}


def test_attachment_staging_is_account_local_and_expires_after_ack_or_ttl(tmp_path):
    core = create_gateway_core(storage_root=tmp_path)
    alice = core.open_gateway_account("acct_alice")
    bob = core.open_gateway_account("acct_bob")
    body = b"short lived content"
    digest = hashlib.sha256(body).hexdigest()

    attachment = alice.attachments.create(
        client_attachment_id="att_client_1",
        filename="note.txt",
        media_type="text/plain",
        size_bytes=len(body),
        sha256=digest,
        correlation_id="cor_attachment",
    )
    alice.attachments.upload_content(attachment["attachmentId"], body)
    verified = alice.attachments.commit(attachment["attachmentId"])
    assert verified["state"] == "verified"
    assert verified["hasStagedBytes"] is True
    try:
        bob.attachments.get(attachment["attachmentId"])
    except GatewayError as exc:
        assert exc.code == "ATTACHMENT_EXPIRED"
    else:
        raise AssertionError("Bob must not read Alice's staged attachment")

    alice.attachments.mark_delivered(attachment["attachmentId"])
    acknowledged = alice.attachments.acknowledge(attachment["attachmentId"], "cor_ack")
    assert acknowledged["state"] == "acknowledged"
    assert acknowledged["hasStagedBytes"] is False

    expiring = alice.attachments.create(
        client_attachment_id="att_client_2",
        filename="ttl.txt",
        media_type="text/plain",
        size_bytes=len(body),
        sha256=digest,
        correlation_id="cor_ttl",
        expires_at="2026-08-24T00:00:00.000Z",
    )
    alice.attachments.upload_content(expiring["attachmentId"], body)
    assert alice.attachments.expire_due("2026-08-24T00:00:01.000Z") == 1
    assert alice.attachments.get(expiring["attachmentId"])["state"] == "expired"
    assert alice.attachments.get(expiring["attachmentId"])["hasStagedBytes"] is False


def test_attachment_digest_failure_keeps_stage_until_explicit_cleanup(tmp_path):
    core = create_gateway_core(storage_root=tmp_path)
    account = core.open_gateway_account("acct_alice")
    body = b"digest failure body"
    attachment = account.attachments.create(
        client_attachment_id="att_digest",
        filename="digest.txt",
        media_type="text/plain",
        size_bytes=len(body),
        sha256=hashlib.sha256(b"different").hexdigest(),
        correlation_id="cor_digest",
    )
    account.attachments.upload_content(attachment["attachmentId"], body)

    try:
        account.attachments.commit(attachment["attachmentId"])
    except GatewayError as exc:
        assert exc.code == "ATTACHMENT_DIGEST_MISMATCH"
    else:
        raise AssertionError("A digest mismatch must fail closed")
    failed = account.attachments.get(attachment["attachmentId"])
    assert failed["state"] == "failed"
    assert failed["hasStagedBytes"] is True
    assert account.attachments.cleanup() == 1
    assert account.attachments.get(attachment["attachmentId"])["state"] == "deleted"


def test_attachment_reconciliation_recovers_an_orphaned_stage_for_the_same_account(tmp_path):
    core = create_gateway_core(storage_root=tmp_path)
    account = core.open_gateway_account("acct_alice")
    body = b"recoverable stage"
    attachment = account.attachments.create(
        client_attachment_id="att_recover",
        filename="recover.txt",
        media_type="text/plain",
        size_bytes=len(body),
        sha256=hashlib.sha256(body).hexdigest(),
        correlation_id="cor_recover",
        expires_at="2030-01-01T00:10:00.000Z",
    )
    stage_path = account.paths.attachments / f"{attachment['attachmentId']}.stage"
    stage_path.write_bytes(body)

    assert account.attachments.cleanup() == 0
    assert account.attachments.get(attachment["attachmentId"])["state"] == "uploading"
    assert account.attachments.get(attachment["attachmentId"])["hasStagedBytes"] is True
    assert account.attachments.expire_due("2030-01-01T00:10:01.000Z") == 1
    assert account.attachments.get(attachment["attachmentId"])["hasStagedBytes"] is False


def test_attachment_reconciliation_failure_protects_stage_until_next_scan(tmp_path):
    core = create_gateway_core(storage_root=tmp_path)
    account = core.open_gateway_account("acct_alice")
    body = b"reconciliation must fail closed"
    attachment = account.attachments.create(
        client_attachment_id="att_reconcile_protected",
        filename="reconcile.txt",
        media_type="text/plain",
        size_bytes=len(body),
        sha256=hashlib.sha256(body).hexdigest(),
        correlation_id="cor_reconcile",
        expires_at="2030-01-01T00:10:00.000Z",
    )
    stage_path = account.paths.attachments / f"{attachment['attachmentId']}.stage"
    stage_path.write_bytes(body)
    account.store.database.execute(
        f"""
        CREATE TRIGGER fail_reconcile
        BEFORE UPDATE OF state, content_path ON attachments
        WHEN OLD.attachment_id = '{attachment['attachmentId']}'
          AND OLD.content_path IS NULL AND NEW.content_path IS NOT NULL
        BEGIN SELECT RAISE(ABORT, 'reconciliation forced failure'); END;
        """
    )

    assert account.attachments.cleanup() == 0
    assert stage_path.exists()
    assert account.attachments.get(attachment["attachmentId"])["state"] == "created"
    account.store.database.execute("DROP TRIGGER fail_reconcile")
    assert account.attachments.cleanup() == 0
    assert account.attachments.get(attachment["attachmentId"])["state"] == "uploading"
    assert account.attachments.expire_due("2030-01-01T00:10:01.000Z") == 1
    assert not stage_path.exists()


def test_attachment_cas_is_not_removed_while_another_account_local_attachment_references_it(tmp_path):
    core = create_gateway_core(storage_root=tmp_path)
    account = core.open_gateway_account("acct_alice")
    body = b"same content address"
    digest = hashlib.sha256(body).hexdigest()
    first = account.attachments.create(
        client_attachment_id="att_cas_1", filename="one.txt", media_type="text/plain",
        size_bytes=len(body), sha256=digest, correlation_id="cor_cas_1",
    )
    second = account.attachments.create(
        client_attachment_id="att_cas_2", filename="two.txt", media_type="text/plain",
        size_bytes=len(body), sha256=digest, correlation_id="cor_cas_2",
    )
    for item in (first, second):
        account.attachments.upload_content(item["attachmentId"], body)
        account.attachments.commit(item["attachmentId"])
        account.attachments.mark_delivered(item["attachmentId"])
    account.attachments.acknowledge(first["attachmentId"], "cor_cas_ack")
    assert account.attachments.get(second["attachmentId"])["hasStagedBytes"] is True


def _device_input(request_id, risk="read", now="2026-08-24T12:00:00.000Z"):
    return {
        "request_id": request_id,
        "device_id": "dev_1",
        "pairing_generation": 4,
        "grant_revision": 7,
        "risk": risk,
        "capability": {"id": "org.agentlife.sms.query", "version": "1.0.0"},
        "provider": {
            "pluginId": "org.agentlife.sms",
            "authorKeyId": "sha256:" + "a" * 64,
        },
        "parameters": {},
        "correlation_id": "cor_device",
        "now": now,
    }


def test_device_claim_result_is_bound_and_recoverable_per_account(tmp_path):
    core = create_gateway_core(storage_root=tmp_path)
    alice = core.open_gateway_account("acct_alice")
    bob = core.open_gateway_account("acct_bob")
    read = alice.device_requests.enqueue(**_device_input("device_req_read"))
    write = alice.device_requests.enqueue(**_device_input("device_req_write", "write"))
    high = alice.device_requests.enqueue(**_device_input("device_req_high", "high-privilege-ephemeral"))

    assert read["expiresAt"] == "2026-08-25T12:00:00.000Z"
    assert write["expiresAt"] == "2026-08-24T12:15:00.000Z"
    assert high["state"] == "expired"

    try:
        alice.device_requests.submit_result(
            request_id="device_req_read", device_id="dev_1", pairing_generation=4,
            grant_revision=7, claim_id="missing", result={"outcome": "succeeded"},
            correlation_id="cor_unclaimed", now="2026-08-24T12:01:00.000Z",
        )
    except GatewayError as exc:
        assert exc.code == "OUTCOME_UNKNOWN"
    else:
        raise AssertionError("An unclaimed request cannot accept a result")

    claim = alice.device_requests.claim(
        request_id="device_req_read", device_id="dev_1", pairing_generation=4,
        grant_revision=7, correlation_id="cor_claim", now="2026-08-24T12:02:00.000Z",
    )
    assert alice.device_requests.claim(
        request_id="device_req_read", device_id="dev_1", pairing_generation=4,
        grant_revision=7, correlation_id="cor_claim_retry", now="2026-08-24T12:03:00.000Z",
    ) == claim
    try:
        alice.device_requests.claim(
            request_id="device_req_read", device_id="dev_2", pairing_generation=4,
            grant_revision=7, correlation_id="cor_wrong_device", now="2026-08-24T12:04:00.000Z",
        )
    except GatewayError as exc:
        assert exc.code == "PAIRING_GENERATION_STALE"
    else:
        raise AssertionError("A claim must remain device-bound")
    try:
        alice.device_requests.claim(
            request_id="device_req_read", device_id="dev_1", pairing_generation=4,
            grant_revision=8, correlation_id="cor_wrong_grant", now="2026-08-24T12:04:00.000Z",
        )
    except GatewayError as exc:
        assert exc.code == "GRANT_STALE"
    else:
        raise AssertionError("A claim must remain grant-bound")
    try:
        bob.device_requests.submit_result(
            request_id="device_req_read", device_id="dev_1", pairing_generation=4,
            grant_revision=7, claim_id=claim["claimId"], result={"outcome": "succeeded"},
            correlation_id="cor_cross_account", now="2026-08-24T12:04:00.000Z",
        )
    except GatewayError as exc:
        assert exc.code == "OUTCOME_UNKNOWN"
    else:
        raise AssertionError("A claim receipt must not cross account databases")

    result = alice.device_requests.submit_result(
        request_id="device_req_read", device_id="dev_1", pairing_generation=4,
        grant_revision=7, claim_id=claim["claimId"], result={"outcome": "succeeded", "data": {"ok": True}},
        correlation_id="cor_result", now="2026-08-24T12:05:00.000Z",
    )
    assert result["state"] == "succeeded"
    alice.device_requests.claim(
        request_id="device_req_write", device_id="dev_1", pairing_generation=4,
        grant_revision=7, correlation_id="cor_claim_write", now="2026-08-24T12:05:00.000Z",
    )
    alice.close()
    reopened = core.open_gateway_account("acct_alice")
    assert reopened.device_requests.recover_expired("2026-08-24T12:16:00.000Z") == 1
    assert reopened.device_requests.get("device_req_write")["state"] == "outcome_unknown"


def test_device_request_expiry_is_enforced_at_claim_and_result_entry(tmp_path):
    core = create_gateway_core(storage_root=tmp_path)
    account = core.open_gateway_account("acct_alice")
    account.device_requests.enqueue(**_device_input("device_req_expired", "write", "2026-08-27T00:00:00.000Z"))

    try:
        account.device_requests.claim(
            request_id="device_req_expired", device_id="dev_1", pairing_generation=4,
            grant_revision=7, correlation_id="cor_late_claim", now="2026-08-27T00:16:00.000Z",
        )
    except GatewayError as exc:
        assert exc.code == "OUTCOME_UNKNOWN"
    else:
        raise AssertionError("Late claim must not execute")
    assert account.device_requests.get("device_req_expired")["state"] == "expired"


def test_gateway_core_handle_routes_attachment_and_device_claim_result(tmp_path):
    core = create_gateway_core(storage_root=tmp_path)
    body = b"handle-owned staged bytes"
    create = core.handle({
        "context": _context(requestId="req_attachment_create", correlationId="cor_attachment_create"),
        "method": "POST",
        "target": "/agent-life/v2/attachments",
        "idempotencyKey": "req_attachment_create",
        "body": {
            "clientAttachmentId": "att_client_handle",
            "filename": "handle.txt",
            "mediaType": "text/plain",
            "sizeBytes": len(body),
            "sha256": hashlib.sha256(body).hexdigest(),
        },
    })
    assert "attachment" in create["data"]
    attachment_id = create["data"]["attachment"]["attachmentId"]
    uploaded = core.handle({
        "context": _context(requestId="req_attachment_upload", correlationId="cor_attachment_upload"),
        "method": "PUT",
        "target": f"/agent-life/v2/attachments/{attachment_id}/content",
        "idempotencyKey": "req_attachment_upload",
        "body": body,
    })
    assert uploaded["data"]["attachment"]["state"] == "uploading"
    committed = core.handle({
        "context": _context(requestId="req_attachment_commit", correlationId="cor_attachment_commit"),
        "method": "POST",
        "target": f"/agent-life/v2/attachments/{attachment_id}/commit",
        "idempotencyKey": "req_attachment_commit",
    })
    assert committed["data"]["attachment"]["state"] == "verified"

    account = core.open_gateway_account("acct_alice")
    account.device_requests.enqueue(**_device_input("device_req_handle", now="2026-08-27T00:00:00.000Z"))
    account.close()
    claim = core.handle({
        "context": _context(requestId="req_claim_handle", correlationId="cor_claim_handle", pairingGeneration=4, grantRevision=7),
        "method": "POST",
        "target": "/agent-life/v2/device-requests/device_req_handle/claim",
        "idempotencyKey": "req_claim_handle",
        "now": "2026-08-27T00:01:00.000Z",
    })
    assert claim["data"]["receipt"]["grantRevision"] == 7
    receipt = claim["data"]["receipt"]
    stale = core.handle({
        "context": _context(requestId="req_result_stale", correlationId="cor_result_stale", pairingGeneration=4, grantRevision=8),
        "method": "POST",
        "target": "/agent-life/v2/device-requests/device_req_handle/result",
        "idempotencyKey": "req_result_stale",
        "body": {
            "claimId": receipt["claimId"],
            "grantRevision": 7,
            "result": {"outcome": "succeeded", "data": {"ok": True}},
        },
        "now": "2026-08-27T00:02:00.000Z",
    })
    assert stale["error"]["code"] == "GRANT_STALE"
    result = core.handle({
        "context": _context(requestId="req_result_handle", correlationId="cor_result_handle", pairingGeneration=4, grantRevision=7),
        "method": "POST",
        "target": "/agent-life/v2/device-requests/device_req_handle/result",
        "idempotencyKey": "req_result_handle",
        "body": {
            "claimId": receipt["claimId"],
            "grantRevision": 7,
            "result": {"outcome": "succeeded", "data": {"ok": True}},
        },
        "now": "2026-08-27T00:02:00.000Z",
    })
    assert result["data"]["deviceRequest"]["state"] == "succeeded"


def test_typed_verified_request_and_response_surfaces_keep_camel_case_protocol_names(tmp_path):
    context = VerifiedRequestContext(
        accountId="acct_alice", deviceId="dev_1", sessionId="sess_1",
        requestId="req_typed", correlationId="cor_typed",
        pairingGeneration=1, grantRevision=1,
    )
    request = VerifiedGatewayRequest(
        context=context, method="POST", target="/agent-life/v2/conversations",
        idempotencyKey="req_typed", body={"clientConversationId": "conv_typed"},
    )
    response = create_gateway_core(storage_root=tmp_path).handle(request)

    assert isinstance(response, GatewayResponse)
    assert response.data["conversation"]["clientConversationId"] == "conv_typed"
    assert response.requestId == "req_typed"


def test_gateway_core_negotiates_the_shared_protocol_schema_and_feature_intersection(tmp_path):
    core = create_gateway_core(storage_root=tmp_path)
    response = core.handle({
        "context": _context(requestId="req_negotiate", correlationId="cor_negotiate"),
        "method": "POST",
        "target": "/agent-life/v2/negotiate",
        "idempotencyKey": "req_negotiate",
        "body": {
            "negotiationId": "neg_1",
            "protocol": {"major": 2, "minor": 0},
            "client": {"installationId": "install_1", "appVersion": "2.0.0", "platform": "android", "platformApi": 35},
            "features": {
                "auth": ["password", "refresh"], "messages": ["chat-v1"],
                "attachments": ["staged-sha256-v1"], "events": ["sse-cursor-v1"],
                "deviceRequests": ["risk-queue-v1"],
            },
            "schemaHashes": {"core": "sha256:" + "a" * 64},
        },
    })

    assert response["data"]["protocol"] == {"major": 2, "minor": 0}
    assert response["data"]["features"]["auth"] == ["password", "refresh"]
    assert response["data"]["features"]["messages"] == "chat-v1"
    assert response["data"]["limits"]["attachmentTtlSeconds"] == 3600
    assert response["data"]["gatewayIdentity"]["deploymentId"]


def test_gateway_core_serves_account_local_conversation_reads(tmp_path):
    core = create_gateway_core(storage_root=tmp_path)
    created = core.handle({
        "context": _context(requestId="req_read_create"),
        "method": "POST", "target": "/agent-life/v2/conversations",
        "idempotencyKey": "req_read_create",
        "body": {"clientConversationId": "conv_read"},
    })
    conversation_id = created["data"]["conversation"]["conversationId"]
    listed = core.handle({
        "context": _context(requestId="req_read_list"),
        "method": "GET", "target": "/agent-life/v2/conversations",
    })
    fetched = core.handle({
        "context": _context(requestId="req_read_fetch"),
        "method": "GET", "target": f"/agent-life/v2/conversations/{conversation_id}",
    })

    assert listed["data"]["conversations"][0]["conversationId"] == conversation_id
    assert fetched["data"]["conversation"]["clientConversationId"] == "conv_read"


def test_idempotency_expiry_and_replay_binding_fail_closed(tmp_path):
    core = create_gateway_core(storage_root=tmp_path)
    request = {
        "context": _context(requestId="req_expiring", correlationId="cor_expiring"),
        "method": "POST", "target": "/agent-life/v2/conversations",
        "idempotencyKey": "req_expiring",
        "body": {"clientConversationId": "conv_expiring"},
        "now": "2026-08-27T00:00:00.000Z",
    }
    first = core.handle(request)
    assert first["data"]["conversation"]
    account = core.open_gateway_account("acct_alice")
    account.store.database.execute(
        "UPDATE idempotency_ledger SET expires_at = ? WHERE device_id = ? AND request_id = ?",
        ("2026-08-27T00:00:01.000Z", "dev_1", "req_expiring"),
    )
    account.close()
    expired = core.handle({**request, "now": "2026-08-27T00:00:02.000Z"})
    assert expired["error"]["code"] == "OUTCOME_UNKNOWN"

    account = core.open_gateway_account("acct_alice")
    account.device_requests.enqueue(**_device_input("device_req_replay", now="2026-08-27T00:00:00.000Z"))
    account.close()
    claim = core.handle({
        "context": _context(requestId="req_claim_replay", correlationId="cor_claim_replay", pairingGeneration=4, grantRevision=7),
        "method": "POST", "target": "/agent-life/v2/device-requests/device_req_replay/claim",
        "idempotencyKey": "req_claim_replay", "now": "2026-08-27T00:01:00.000Z",
    })
    assert claim["data"]["receipt"]
    stale_replay = core.handle({
        "context": _context(requestId="req_claim_replay", correlationId="cor_claim_replay", pairingGeneration=5, grantRevision=7),
        "method": "POST", "target": "/agent-life/v2/device-requests/device_req_replay/claim",
        "idempotencyKey": "req_claim_replay", "now": "2026-08-27T00:01:01.000Z",
    })
    assert stale_replay["error"]["code"] == "PAIRING_GENERATION_STALE"

    old = core.open_gateway_account("acct_bob")
    event = old.events.append("gateway.notice", "cor_old", {"notice": "old"}, "2026-08-24T00:00:00.000Z")
    old.close()
    cursor_expired = core.handle({
        "context": _context(accountId="acct_bob", requestId="req_cursor_expired"),
        "method": "GET", "target": f"/agent-life/v2/events?cursor={event['eventId']}",
        "lastEventId": event["eventId"], "now": "2026-08-25T00:00:01.000Z",
    })
    assert cursor_expired["error"]["code"] == "CURSOR_EXPIRED"
    assert cursor_expired["error"]["details"]["recoverableResources"] == ["conversations", "attachments", "device-requests"]


def test_account_uses_the_dedicated_audit_store_module(tmp_path):
    account = create_gateway_core(storage_root=tmp_path).open_gateway_account("acct_audit")

    assert isinstance(account.audit, AuditStore)
