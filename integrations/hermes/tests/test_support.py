"""Test-only secure storage provider doubles for Hermes Gateway tests."""

from __future__ import annotations

import hashlib
import hmac
import secrets


class HermesAeadProviderDouble:
    """A deterministic test double for the host-provided AEAD boundary.

    Production code never uses this implementation.  It only provides an
    authenticated, non-plaintext round trip so tests can verify that the
    Gateway calls the host encryption interface and binds associated data.
    """

    algorithm = "test-aead"
    authenticated = True

    def __init__(self, reference: str, key: bytes):
        self.reference = reference
        self._key = key

    def _stream(self, nonce: bytes, length: int) -> bytes:
        blocks: list[bytes] = []
        counter = 0
        while sum(len(block) for block in blocks) < length:
            blocks.append(hmac.new(self._key, nonce + counter.to_bytes(4, "big"), hashlib.sha256).digest())
            counter += 1
        return b"".join(blocks)[:length]

    def encrypt(self, plaintext: bytes, associated_data: bytes) -> bytes:
        nonce = secrets.token_bytes(12)
        ciphertext = bytes(value ^ mask for value, mask in zip(plaintext, self._stream(nonce, len(plaintext))))
        tag = hmac.new(self._key, associated_data + nonce + ciphertext, hashlib.sha256).digest()
        return b"test-aead-v1" + nonce + tag + ciphertext

    def decrypt(self, ciphertext: bytes, associated_data: bytes) -> bytes:
        prefix = b"test-aead-v1"
        if not ciphertext.startswith(prefix) or len(ciphertext) < len(prefix) + 12 + 32:
            raise ValueError("invalid test ciphertext")
        offset = len(prefix)
        nonce = ciphertext[offset:offset + 12]
        offset += 12
        tag = ciphertext[offset:offset + 32]
        encrypted = ciphertext[offset + 32:]
        expected = hmac.new(self._key, associated_data + nonce + encrypted, hashlib.sha256).digest()
        if not hmac.compare_digest(tag, expected):
            raise ValueError("test ciphertext authentication failed")
        return bytes(value ^ mask for value, mask in zip(encrypted, self._stream(nonce, len(encrypted))))


class HermesSecretStoreDouble:
    """Per-name stable key provider used only by tests."""

    def __init__(self):
        self._keys: dict[str, bytes] = {}

    def get_or_create_aead(self, name: str) -> HermesAeadProviderDouble:
        key = self._keys.setdefault(name, secrets.token_bytes(32))
        return HermesAeadProviderDouble(f"test-secret://{name}", key)


def make_secret_store() -> HermesSecretStoreDouble:
    return HermesSecretStoreDouble()


class IdentityProofVerifierDouble:
    """Test-only verifier for the structured identity continuity seam."""

    def verify(self, payload: bytes, signature: str, previous_identity_ref: str) -> bool:
        return bool(payload) and previous_identity_ref and signature == "valid-rotation-proof"
