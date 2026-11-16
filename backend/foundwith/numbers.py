"""Phone-number normalisation and the keyed hash that stands in for every number we store.

Clients send numbers the way people save them ("098450 31207", "+91-98450-31207",
"919845031207"). Two people only match if both copies normalise to the same E.164 string,
so this module is the single source of truth for that conversion. Clients send raw numbers
and the server normalises them; the Android app must not pre-normalise.
"""

from __future__ import annotations

import base64
import binascii
import hashlib
import hmac
import re
import unicodedata

_ASCII_NUMBER = re.compile(r"\+?[0-9]+")
_INDIAN_MOBILE = re.compile(r"\+91[6-9][0-9]{9}")
_REF = re.compile(r"[A-Za-z0-9_-]{43}")
_TOLL_FREE = ("1800", "1860")
_PUNCTUATION = frozenset("()./")
_DROPPED_CATEGORIES = frozenset({"Cf", "Pd", "Zs"})  # invisible format marks, dashes, spaces
_MAX_RAW_LENGTH = 64


def _clean(raw: object) -> str | None:
    """Reduce a saved number to ASCII digits with an optional plus sign.

    Real address books contain digits typed in Indian scripts, full-width characters,
    non-breaking spaces and hyphens, and the invisible direction marks that come with numbers
    copied from chat apps. All of those are handled here. Anything else (letters, "*", "#",
    pause characters before an extension) makes the entry unmatchable."""
    if raw is None:
        return None
    text = str(raw)
    if len(text) > _MAX_RAW_LENGTH:
        return None
    out: list[str] = []
    for ch in unicodedata.normalize("NFKC", text):
        if "0" <= ch <= "9" or ch == "+":
            out.append(ch)
        elif ch.isdecimal():
            out.append(str(unicodedata.decimal(ch)))
        elif ch in _PUNCTUATION or ch.isspace() or unicodedata.category(ch) in _DROPPED_CATEGORIES:
            continue
        else:
            return None
    return "".join(out)


def normalise(raw: object, country_code: str = "91") -> str | None:
    """Return the number in E.164 form (``+919845031207``), or ``None`` if it can't be matched.
    The result is always plain ASCII.

    Rules for Indian numbers (the default country):
      * 10-digit national numbers, e.g. ``98450 31207`` or a landline with its STD code ``80 4123 5566``
      * trunk prefix: ``0 98450 31207``
      * country code without plus: ``91 98450 31207`` or ``091 ...``
      * international prefix: ``00 91 98450 31207``
      * toll-free and shared-cost numbers: ``1800 ...`` / ``1860 ...``
    Numbers that already carry ``+`` and another country code are kept as they are.
    Short codes (121, 198), and landlines saved without their STD code, return ``None``.
    """
    s = _clean(raw)
    if not s:
        return None
    if s.startswith("00"):
        s = "+" + s[2:]
    if not _ASCII_NUMBER.fullmatch(s):
        return None

    if s.startswith("+"):
        digits = s[1:]
        if digits.startswith(country_code):
            return _national(digits[len(country_code):], country_code, allow_trunk=True)
        if digits.startswith("0") or not 8 <= len(digits) <= 15:
            return None
        return "+" + digits

    digits = s
    if digits.startswith("0" + country_code) and len(digits) >= 13:
        digits = digits[1:]
    if digits.startswith(country_code) and len(digits) == len(country_code) + 10:
        return _national(digits[len(country_code):], country_code, allow_trunk=False)
    return _national(digits, country_code, allow_trunk=True)


def _national(nsn: str, country_code: str, *, allow_trunk: bool) -> str | None:
    if allow_trunk and nsn.startswith("0"):
        nsn = nsn[1:]
    if nsn.startswith(_TOLL_FREE) and 10 <= len(nsn) <= 11:
        return f"+{country_code}{nsn}"
    if len(nsn) == 10 and nsn[0] != "0":
        return f"+{country_code}{nsn}"
    return None


def is_indian_mobile(e164: str | None) -> bool:
    return bool(e164) and _INDIAN_MOBILE.fullmatch(e164) is not None


class NumberHasher:
    """Keyed hash of an E.164 number. The pepper lives in Secret Manager, never in the database,
    so a leaked database dump alone doesn't reveal anyone's numbers. Changing the pepper breaks
    every match, so it must never be rotated in place (see "If the pepper leaks" in docs/SPEC.md)."""

    def __init__(self, pepper: bytes) -> None:
        if len(pepper) < 32:
            raise ValueError("NUMBER_PEPPER must be at least 32 bytes")
        self._key = pepper

    def digest(self, e164: str) -> bytes:
        return hmac.new(self._key, e164.encode("ascii"), hashlib.sha256).digest()


def to_ref(digest: bytes) -> str:
    """Opaque, URL-safe handle for a hashed number (what the API returns instead of numbers)."""
    return base64.urlsafe_b64encode(digest).rstrip(b"=").decode("ascii")


def from_ref(ref: str) -> bytes:
    if not isinstance(ref, str) or not _REF.fullmatch(ref):
        raise ValueError("not a number reference")
    try:
        digest = base64.urlsafe_b64decode(ref + "=")
    except (binascii.Error, ValueError) as exc:  # pragma: no cover - regex already guards this
        raise ValueError("not a number reference") from exc
    if len(digest) != 32:
        raise ValueError("not a number reference")
    return digest
