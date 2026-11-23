"""Phone-number normalisation and the keyed hash that stands in for every number we store.

Clients send numbers the way people save them ("098450 31207", "+91-98450-31207",
"919845031207"). Two people only match if both copies normalise to the same E.164 string,
so this module is the single source of truth for that conversion. Clients send raw numbers
and the server normalises them; the Android app keeps an identical copy (core/PhoneNumbers.kt)
only to read the server's replies, and a parity test keeps the two the same.
WhoHas checks Indian (+91) numbers only.
"""

from __future__ import annotations

import base64
import binascii
import hashlib
import hmac
import re
import unicodedata

from .india_mobile import PATTERN as _INDIA_MOBILE_PATTERN

_ASCII_NUMBER = re.compile(r"\+?[0-9]+")
_MOBILE_SHAPE = re.compile(r"\+91[6-9][0-9]{9}")
_INDIA_MOBILE = re.compile(_INDIA_MOBILE_PATTERN)
_REF = re.compile(r"[A-Za-z0-9_-]{43}")
_TOLL_FREE = ("1800", "1860")
_INSIDE = frozenset("().")                         # brackets and dots inside one number
_DROPPED_CATEGORIES = frozenset({"Cf", "Pd", "Zs"})  # invisible format marks, dashes, spaces
_MAX_RAW_LENGTH = 64


def _is_digit(ch: str) -> bool:
    return "0" <= ch <= "9" or ch.isdecimal()


def _runs(text: str):
    """Each stretch of the text that could be one phone number, in order.

    A stretch starts at a digit or "+" and runs through digits, spaces, dashes, brackets, dots and
    invisible marks. Anything else ends it: letters ("tel:", "ext", "(home)"), "/" and "," between
    two numbers, ";" or "," before an extension, "*" and "#"."""
    run: list[str] = []
    for ch in text:
        if run and (_is_digit(ch) or ch.isspace() or ch in _INSIDE or unicodedata.category(ch) in _DROPPED_CATEGORIES):
            run.append(ch)
            continue
        if run:
            yield "".join(run)
            run = []
        if _is_digit(ch) or ch == "+":
            run = [ch]
    if run:
        yield "".join(run)


def _clean(run: str) -> str:
    """One stretch reduced to ASCII digits with an optional leading plus sign. Digits typed in
    Indian scripts, full-width characters and the invisible marks copied from chat apps all count."""
    out: list[str] = []
    for ch in run:
        if "0" <= ch <= "9" or ch == "+":
            out.append(ch)
        elif ch.isdecimal():
            out.append(str(unicodedata.decimal(ch)))
    return "".join(out)


def normalise(raw: object, country_code: str = "91") -> str | None:
    """Return the number in E.164 form (``+919845031207``), or ``None``.
    The result is always plain ASCII. Every kind of number is matched (Y, 23 Nov 2026): Indian
    mobiles and landlines, toll-free, short and service numbers, and other countries' numbers.

    The first full number in the field is used, so ``tel:+91 98450 31207``,
    ``98450 31207 (home)``, ``080 4123 5566,204`` (an extension) and
    ``98450 31207 / 98450 31208`` (two numbers) all work; two numbers are never joined into one.

    Rules for one number:
      * 10-digit national numbers, e.g. ``98450 31207`` or a landline with its STD code ``80 4123 5566``
      * trunk prefix: ``0 98450 31207``
      * country code without plus: ``91 98450 31207`` or ``091 ...``; the code typed twice: ``+91 91 ...``
      * international prefix: ``00 91 98450 31207``
      * toll-free and shared-cost numbers: ``1800 ...`` / ``1860 ...``
      * other countries' numbers written with ``+`` or ``00``: ``+971 50 123 4567`` -> ``+971501234567``
      * short and service numbers (3 to 9 digits, like 121, 198, 56161) and landlines saved without
        their STD code: ``+91`` and the digits (``+91121``), when the field holds that one number only,
        so the halves of ``98450/31207`` are never taken as two short numbers. USSD codes (``*121#``)
        are not numbers.
    """
    if isinstance(raw, int) and not isinstance(raw, bool):
        raw = str(raw)
    if not isinstance(raw, str) or len(raw) > _MAX_RAW_LENGTH:
        return None
    runs = list(_runs(unicodedata.normalize("NFKC", raw)))
    for run in runs:
        e164 = _one(_clean(run), country_code)
        if e164:
            return e164
    if len(runs) == 1 and "*" not in raw and "#" not in raw:
        return _short(_clean(runs[0]), country_code)
    return None


def _short(s: str, country_code: str) -> str | None:
    """A short or service number, or a landline without its STD code: 3 to 9 digits, never starting with 0."""
    if s.startswith("+" + country_code):
        s = s[1 + len(country_code):]
    if not s.isdigit() or s.startswith("0") or not 3 <= len(s) <= 9:
        return None
    return f"+{country_code}{s}"


def _one(s: str, country_code: str) -> str | None:
    if not s:
        return None
    if s.startswith("00"):
        s = "+" + s[2:]
    if not _ASCII_NUMBER.fullmatch(s):
        return None

    if s.startswith("+"):
        digits = s[1:]
        if not digits.startswith(country_code):
            # Another country's number: kept as written, 8 to 15 digits, never starting with 0.
            return s if 8 <= len(digits) <= 15 and not digits.startswith("0") else None
        rest = digits[len(country_code):]
        if rest.startswith(country_code) and len(rest) == len(country_code) + 10:
            rest = rest[len(country_code):]                  # +91 91 98450 31207
        return _national(rest, country_code, allow_trunk=True)

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
    """True only for India's mobile ranges (Google's data), so a Bengaluru landline
    (+91 80 4123 5566) is not a mobile even though it starts with 8. Use it where WhatsApp or an
    SMS must reach the number."""
    return bool(e164) and len(e164) == 13 and e164.startswith("+91") and _INDIA_MOBILE.fullmatch(e164[3:]) is not None


def is_mobile_shaped(e164: str | None) -> bool:
    """10 digits starting with 6-9: the loose check for search and sign-in, so a newly opened
    mobile series still works before Google's data knows it."""
    return bool(e164) and _MOBILE_SHAPE.fullmatch(e164) is not None


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
