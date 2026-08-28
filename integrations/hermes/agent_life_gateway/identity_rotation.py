"""Gateway identity rotation with durable continuity evidence."""

from __future__ import annotations

import hashlib
import uuid
from datetime import datetime
from pathlib import Path
from typing import Any

from .core import GatewayCore, GatewayError, iso_millis


class IdentityRotationService:
    def __init__(self, storage_root: str | Path | None = None, core: GatewayCore | None = None):
        self.core = core or GatewayCore(storage_root=storage_root)

    def rotate(
        self, account_id: str, previous_identity_ref: str,
        next_identity_ref: str, signed_by_previous: str,
        correlation_id: str, now: datetime | str | None = None,
    ) -> dict[str, Any]:
        account = self.core.open_gateway_account(account_id)
        try:
            with account.store.transaction():
                current = account.store.database.execute(
                    "SELECT value FROM account_metadata WHERE key = 'gateway_identity_ref'"
                ).fetchone()[0]
                if current != previous_identity_ref:
                    raise GatewayError("TLS_IDENTITY_REQUIRED")
                receipt_id = f"rot_{uuid.uuid4()}"
                rotated_at = iso_millis(now)
                account.store.database.execute(
                    "INSERT INTO identity_rotation_receipts(receipt_id, previous_identity_ref, next_identity_ref, proof_hash, master_key_ref, rotated_at, correlation_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    (
                        receipt_id, previous_identity_ref, next_identity_ref,
                        hashlib.sha256(signed_by_previous.encode("utf-8")).hexdigest(),
                        account.master_key_ref, rotated_at, correlation_id,
                    ),
                )
                account.store.database.execute(
                    "UPDATE account_metadata SET value = ? WHERE key = 'gateway_identity_ref'",
                    (next_identity_ref,),
                )
                generation = int(account.store.database.execute(
                    "SELECT value FROM account_metadata WHERE key = 'pairing_generation'"
                ).fetchone()[0])
                account.store.database.execute(
                    "UPDATE account_metadata SET value = ? WHERE key = 'pairing_generation'",
                    (str(generation + 1),),
                )
                account.audit.append(
                    "gateway.identity.rotated", {"accountId": account_id},
                    {
                        "receiptId": receipt_id,
                        "previousIdentitySha256": hashlib.sha256(previous_identity_ref.encode("utf-8")).hexdigest(),
                        "nextIdentitySha256": hashlib.sha256(next_identity_ref.encode("utf-8")).hexdigest(),
                    }, correlation_id, rotated_at,
                )
                return {
                    "receiptId": receipt_id, "accountId": account_id,
                    "previousIdentityRef": previous_identity_ref, "nextIdentityRef": next_identity_ref,
                    "masterKeyRef": account.master_key_ref, "rotatedAt": rotated_at,
                }
        finally:
            account.close()


def rotate_identity(request: dict[str, Any], storage_root: str | Path | None = None) -> dict[str, Any]:
    return IdentityRotationService(storage_root=storage_root).rotate(
        request["accountId"], request["previousIdentityRef"], request["nextIdentityRef"],
        request["signedByPrevious"], request["correlationId"], request.get("now"),
    )
