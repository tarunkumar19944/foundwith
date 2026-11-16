"""Rules for the display name people choose.

The name is shown to other people next to phone numbers, so it has to look like a person's name
and must not pass for a bank, the police or a support desk. This is a first line of defence;
the report and suspend path handles what gets past it."""

from __future__ import annotations

import re
import unicodedata

from .errors import NameNotAllowed

MIN_LENGTH, MAX_LENGTH = 2, 40
_ALLOWED_PUNCTUATION = frozenset(" .'\u2019-")
_JOINERS = frozenset("\u200c\u200d")  # needed inside words in several Indian scripts
_SPLIT = re.compile(r"[\s\-]+")
_STRIP = str.maketrans("", "", ".'\u2019\u200c\u200d")

# Whole words only: "Bankim" is fine, "Bank" is not.
BLOCKED_WORDS = frozenset({
    "police", "cbi", "rbi", "sbi", "bank", "banking", "govt", "government", "official", "support",
    "helpline", "helpdesk", "customercare", "verification", "verify", "verified", "kyc", "upi", "npci",
    "aadhaar", "aadhar", "uidai", "court", "customs", "cyber", "department", "dept", "ministry",
    "officer", "inspector", "admin", "administrator", "refund", "lottery", "security", "foundwith",
    "truecaller", "trai",
    # the same in Devanagari and Kannada
    "पुलिस", "बैंक", "सरकार", "सरकारी", "अधिकारी", "ಪೊಲೀಸ್", "ಬ್ಯಾಂಕ್", "ಸರ್ಕಾರ", "ಸರ್ಕಾರಿ", "ಅಧಿಕಾರಿ",
})
# A dot inside a word is fine for initials ("K.S.Rao"), not for web addresses ("loans.in").
_DOMAIN_ENDINGS = frozenset({"in", "com", "net", "org", "co", "io", "app", "xyz", "info", "biz", "me", "ly", "shop", "online"})
BLOCKED_PAIRS = frozenset({
    ("customer", "care"), ("customer", "service"), ("income", "tax"), ("found", "with"), ("crime", "branch"),
    ("call", "center"), ("call", "centre"), ("help", "desk"), ("help", "line"), ("tech", "team"),
})


def clean_display_name(raw: str) -> str:
    """Return the name as it will be stored, or raise NameNotAllowed with a message for the user."""
    name = " ".join(unicodedata.normalize("NFC", str(raw)).split())
    if not MIN_LENGTH <= len(name) <= MAX_LENGTH:
        raise NameNotAllowed(f"Names are {MIN_LENGTH} to {MAX_LENGTH} characters long.")
    letters = 0
    for ch in name:
        category = unicodedata.category(ch)
        if category.startswith("L"):
            letters += 1
        elif category.startswith("M") or ch in _ALLOWED_PUNCTUATION or ch in _JOINERS:
            continue
        else:
            raise NameNotAllowed("Use letters only. Names can't contain numbers, symbols or emoji.")
    if letters < 2:
        raise NameNotAllowed("Enter your name the way you'd like people to see it.")

    for token in name.casefold().split():
        labels = [label for label in token.split(".") if label]
        if len(labels) > 1 and (labels[0] == "www" or (labels[-1] in _DOMAIN_ENDINGS and len(labels[-2]) > 2)):
            raise NameNotAllowed("Use your own name, not a web address.")

    words = [w.translate(_STRIP).casefold() for w in _SPLIT.split(name)]
    words = [w for w in words if w]
    if any(w in BLOCKED_WORDS for w in words) or any(pair in BLOCKED_PAIRS for pair in zip(words, words[1:])):
        raise NameNotAllowed('Use your own name. Names can\'t include words like "bank", "police" or "support".')
    return name
