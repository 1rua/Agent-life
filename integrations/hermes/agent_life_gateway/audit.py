"""Minimal, content-free Gateway audit storage."""

from __future__ import annotations

import json
import re
from datetime import datetime, timedelta, timezone
from typing import Any, Mapping


_SECRET_FIELD = re.compile(r"password|credential|secret|token|body|text|content|privateKey", re.I)


def _now(value: datetime | str | None = None) -> datetime:
    if value is None:
        return datetime.now(timezone.utc)
    if isinstance(value, str):
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    else:
        parsed = value
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def _iso_millis(value: datetime | str | None = None) -> str:
    return _now(value).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def _json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)


def scrub(value: Any) -> Any:
    if isinstance(value, list):
        return [scrub(item) for item in value]
    if not isinstance(value, Mapping):
        return value
    return {key: scrub(child) for key, child in value.items() if not _SECRET_FIELD.search(str(key))}


class AuditStore:
    """Stores only actor/action/result metadata, never message or attachment content."""

    def __init__(self, store: Any, account_id: str):
        self.store = store
        self.account_id = account_id

    def append(
        self,
        event_type: str,
        actor: Mapping[str, Any],
        subject: Mapping[str, Any],
        correlation_id: str,
        occurred_at: datetime | str | None = None,
    ) -> None:
        self.store.database.execute(
            "INSERT INTO audit_events(event_type, actor_json, subject_json, correlation_id, occurred_at) VALUES (?, ?, ?, ?, ?)",
            (event_type, _json(scrub(actor)), _json(scrub(subject)), correlation_id, _iso_millis(occurred_at)),
        )

    def list(self) -> list[dict[str, Any]]:
        rows = self.store.database.execute(
            "SELECT event_type, actor_json, subject_json, correlation_id, occurred_at FROM audit_events ORDER BY audit_id"
        ).fetchall()
        return [
            {
                "eventType": row["event_type"], "actor": json.loads(row["actor_json"]),
                "subject": json.loads(row["subject_json"]), "correlationId": row["correlation_id"],
                "occurredAt": row["occurred_at"],
            }
            for row in rows
        ]

    def purge(self, before: datetime | str | None = None) -> int:
        cutoff = _now(before or (datetime.now(timezone.utc) - timedelta(days=30)))
        with self.store.transaction():
            return self.store.database.execute(
                "DELETE FROM audit_events WHERE occurred_at < ?", (_iso_millis(cutoff),)
            ).rowcount
