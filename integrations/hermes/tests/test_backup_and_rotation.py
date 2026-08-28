import hashlib
import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from agent_life_gateway.backup import GatewayBackupService
from agent_life_gateway.core import GatewayError, create_gateway_core
from agent_life_gateway.identity_rotation import IdentityRotationService


def test_portable_backup_excludes_active_identity_credentials_queue_and_content(tmp_path):
    core = create_gateway_core(storage_root=tmp_path)
    account = core.open_gateway_account("acct_alice")
    initial_master_key_ref = account.master_key_ref
    session = account.sessions.create_password_session(
        username="alice",
        password="backup password",
        installation={
            "installationId": "install_backup",
            "displayName": "Alice phone",
            "devicePublicKey": "device-public-key",
        },
        correlation_id="cor_login",
    )
    body = b"backup must not contain this staged body"
    attachment = account.attachments.create(
        client_attachment_id="att_backup",
        filename="backup.txt",
        media_type="text/plain",
        size_bytes=len(body),
        sha256=hashlib.sha256(body).hexdigest(),
        correlation_id="cor_attachment",
    )
    account.attachments.upload_content(attachment["attachmentId"], body)
    account.attachments.commit(attachment["attachmentId"])
    account.attachments.mark_delivered(attachment["attachmentId"])
    account.attachments.acknowledge(attachment["attachmentId"], "cor_ack")
    account.device_requests.enqueue(
        request_id="device_req_pending_backup",
        device_id=session["deviceId"],
        pairing_generation=1,
        grant_revision=1,
        risk="read",
        capability={"id": "org.agentlife.sms.query", "version": "1.0.0"},
        provider={"pluginId": "org.agentlife.sms", "authorKeyId": "sha256:" + "a" * 64},
        parameters={},
        correlation_id="cor_device",
        now="2026-08-27T00:00:00.000Z",
    )
    account.close()

    receipt = IdentityRotationService(storage_root=tmp_path).rotate(
        account_id="acct_alice",
        previous_identity_ref="spki_initial",
        next_identity_ref="spki_new",
        signed_by_previous="rotation_proof",
        correlation_id="cor_rotate",
    )
    assert receipt["accountId"] == "acct_alice"
    assert receipt["previousIdentityRef"] == "spki_initial"
    assert receipt["nextIdentityRef"] == "spki_new"
    assert receipt["masterKeyRef"] == initial_master_key_ref

    reopened = core.open_gateway_account("acct_alice")
    assert reopened.master_key_ref == initial_master_key_ref
    backup = GatewayBackupService(storage_root=tmp_path).export_portable("acct_alice")
    backup_json = json.dumps(backup, ensure_ascii=False)

    assert backup["accountId"] == "acct_alice"
    assert len(backup["masterKeyContinuitySha256"]) == 64
    assert "backup.txt" in backup_json
    assert "backup password" not in backup_json
    assert session["refreshCredential"] not in backup_json
    assert session["accessToken"] not in backup_json
    assert "backup must not contain this staged body" not in backup_json
    assert "device_req_pending_backup" not in backup_json
    assert "spki_new" not in backup_json
    assert "rotation_proof" not in backup_json
    assert "backup password" not in json.dumps(reopened.audit.list())
    reopened.close()


def test_refresh_rotation_and_reuse_revokes_all_credentials_for_the_device(tmp_path):
    core = create_gateway_core(storage_root=tmp_path)
    account = core.open_gateway_account("acct_alice")
    first = account.sessions.create_password_session(
        username="alice",
        password="password",
        installation={"installationId": "install_1", "displayName": "Alice", "devicePublicKey": "key"},
        correlation_id="cor_login",
    )
    second = account.sessions.refresh(
        refresh_credential=first["refreshCredential"],
        installation_id="install_1", device_id=first["deviceId"],
        correlation_id="cor_refresh", now="2026-08-27T00:01:00.000Z",
    )
    assert second["deviceId"] == first["deviceId"]
    assert second["refreshCredential"] != first["refreshCredential"]
    with pytest.raises(GatewayError, match="REFRESH_REUSED"):
        account.sessions.refresh(
            refresh_credential=first["refreshCredential"],
            installation_id="install_1", device_id=first["deviceId"],
            correlation_id="cor_reuse", now="2026-08-27T00:02:00.000Z",
        )
    assert account.sessions.active_refresh_credential_count(first["deviceId"]) == 0
