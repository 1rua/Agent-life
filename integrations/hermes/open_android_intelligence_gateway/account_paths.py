"""Portable, file-level account isolation paths for the Hermes Gateway."""

from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass
from pathlib import Path


WIRE_ID_PATTERN = re.compile(r"^[A-Za-z0-9._~-]{1,128}$")


@dataclass(frozen=True)
class AccountPaths:
    root: Path
    database: Path
    attachments: Path
    audit: Path


def assert_opaque_id(account_id: str) -> None:
    if not isinstance(account_id, str) or WIRE_ID_PATTERN.fullmatch(account_id) is None:
        raise ValueError("SCHEMA_INVALID")


def sha256_hex(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def resolve_within(root: str | Path, child: str | Path) -> Path:
    resolved_root = Path(root).resolve()
    resolved_child = (resolved_root / child).resolve()
    if resolved_child == resolved_root or resolved_root not in resolved_child.parents:
        raise ValueError("SCHEMA_INVALID")
    return resolved_child


def account_paths(storage_root: str | Path, account_id: str) -> AccountPaths:
    assert_opaque_id(account_id)
    account_root = resolve_within(storage_root, sha256_hex(account_id))
    return AccountPaths(
        root=account_root,
        database=account_root / "gateway.sqlite",
        attachments=account_root / "attachments",
        audit=account_root / "audit",
    )


def ensure_account_directories(paths: AccountPaths) -> None:
    paths.root.mkdir(parents=True, exist_ok=True, mode=0o700)
    paths.attachments.mkdir(parents=True, exist_ok=True, mode=0o700)
    paths.audit.mkdir(parents=True, exist_ok=True, mode=0o700)
    for directory in (paths.root, paths.attachments, paths.audit):
        try:
            directory.chmod(0o700)
        except OSError:
            pass


def default_hermes_gateway_root() -> Path:
    return (Path.cwd() / ".open-android-intelligence-hermes" / "accounts").resolve()


accountPaths = account_paths
assertOpaqueId = assert_opaque_id
ensureAccountDirectories = ensure_account_directories
