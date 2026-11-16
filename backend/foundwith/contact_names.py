"""Contact names: how people saved a number, used for the popular name.

A name is stored two ways, both keyed by NAME_KEY (Secret Manager, never in the database):
  - name_hmac: a keyed hash of the tidied name, so the nightly job can count how many people saved
    the same name for a number without reading any name;
  - name_enc: the name itself, encrypted with AES-GCM and bound to the number it belongs to, so it
    can only be decrypted next to that number.
A stolen copy of the database reveals neither names nor numbers.

There is no word filter: the popular name is whatever most people saved (product decision,
16 Nov 2026). Names that carry no letters (a saved number, emoji only) are not names and are dropped.
"""

from __future__ import annotations

import hashlib
import hmac
import os
import unicodedata

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

MAX_NAME_LENGTH = 60
_NONCE = 12


def tidy_contact_name(raw: object) -> str | None:
    """The name as it will be shown: Unicode-normalised, spaces collapsed, at most 60 characters.
    None when there is no name to keep."""
    if raw is None or not isinstance(raw, str):
        return None
    name = " ".join(unicodedata.normalize("NFC", raw).split())
    # Drop invisible format characters (direction marks copied from chats), but keep the zero-width
    # joiners Indian scripts need inside words.
    name = "".join(ch for ch in name if unicodedata.category(ch) != "Cf" or ch in "‌‍").strip()
    if not name or not any(unicodedata.category(ch).startswith("L") for ch in name):
        return None
    return name[:MAX_NAME_LENGTH].rstrip()


def counting_key(name: str) -> str:
    """What makes two saved names "the same" for counting: case, width and spacing don't matter,
    nor do dots and similar punctuation ("Dr. Anand" = "dr anand")."""
    folded = unicodedata.normalize("NFKC", name).casefold()
    kept = "".join(ch if (unicodedata.category(ch)[0] in "LMN" or ch in "‌‍") else " " for ch in folded)
    return " ".join(kept.split())


class NameCipher:
    def __init__(self, key: bytes) -> None:
        if len(key) < 32:
            raise ValueError("NAME_KEY must be at least 32 bytes")
        self._mac_key = hmac.new(key, b"found-with name hash v1", hashlib.sha256).digest()
        self._aead = AESGCM(hmac.new(key, b"found-with name encryption v1", hashlib.sha256).digest())

    def name_hmac(self, name: str) -> bytes:
        return hmac.new(self._mac_key, counting_key(name).encode("utf-8"), hashlib.sha256).digest()

    def encrypt(self, name: str, number_hmac: bytes) -> bytes:
        nonce = os.urandom(_NONCE)
        return nonce + self._aead.encrypt(nonce, name.encode("utf-8"), number_hmac)

    def decrypt(self, blob: bytes, number_hmac: bytes) -> str | None:
        """None if the blob doesn't belong to this number or was tampered with."""
        try:
            blob = bytes(blob)
            return self._aead.decrypt(blob[:_NONCE], blob[_NONCE:], number_hmac).decode("utf-8")
        except Exception:  # noqa: BLE001 - InvalidTag, short blob, bad UTF-8: treat as no name
            return None
