import pytest

from foundwith.numbers import NumberHasher, from_ref, is_indian_mobile, normalise, to_ref


@pytest.mark.parametrize("raw", [
    "9845031207", "98450 31207", "98450-31207", "+91 98450 31207", "+91-98450-31207", "(+91) 98450 31207",
    "09845031207", "919845031207", "0919845031207", "00919845031207", "+91 0 98450 31207", "098450\u00a031207",
])
def test_indian_mobile_formats_all_match(raw):
    assert normalise(raw) == "+919845031207"


@pytest.mark.parametrize("raw, expected", [
    ("080 4123 5566", "+918041235566"),        # Bengaluru landline with STD code
    ("011-2345-6789", "+911123456789"),        # Delhi landline
    ("1800 425 1234", "+9118004251234"),       # toll-free
    ("9198450312", "+919198450312"),           # a mobile that happens to start with 91
    ("919198450312", "+919198450312"),
    ("+1 (415) 555-0100", "+14155550100"),     # other countries keep their code
    ("00 44 20 7946 0958", "+442079460958"),
])
def test_other_formats(raw, expected):
    assert normalise(raw) == expected


@pytest.mark.parametrize("raw", ["", "   ", None, "121", "198", "41235566", "+91 12345", "+0123456789",
                                 "98450 3120", "call me", "9845031207 ext 2", "+12345"])
def test_unmatchable_numbers_are_skipped(raw):
    assert normalise(raw) is None


def test_indian_mobile_check():
    assert is_indian_mobile("+919845031207")
    assert is_indian_mobile("+916366109284")
    # A landline whose STD code starts with 6-9 can't be told apart from a mobile by its digits alone;
    # search accepts it, which is harmless.
    assert is_indian_mobile("+918041235566")
    assert not is_indian_mobile("+915123456789")
    assert not is_indian_mobile("+911123456789")
    assert not is_indian_mobile("+14155550100")
    assert not is_indian_mobile(None)


def test_hash_is_keyed_and_stable():
    a = NumberHasher(b"a" * 32)
    b = NumberHasher(b"b" * 32)
    assert a.digest("+919845031207") == a.digest("+919845031207")
    assert a.digest("+919845031207") != b.digest("+919845031207")
    assert len(a.digest("+919845031207")) == 32
    with pytest.raises(ValueError):
        NumberHasher(b"short")


def test_refs_round_trip_and_reject_junk():
    digest = NumberHasher(b"k" * 32).digest("+919845031207")
    ref = to_ref(digest)
    assert len(ref) == 43 and "=" not in ref
    assert from_ref(ref) == digest
    for junk in ["", "abc", ref + "x", ref[:-1] + "!", 123]:
        with pytest.raises(ValueError):
            from_ref(junk)


# ---- what real address books contain (each of these crashed or was dropped in v0.1)

@pytest.mark.parametrize("raw", [
    "९८४५०३१२०७",                       # Devanagari digits
    "೯೮೪೫೦೩೧೨೦೭",                       # Kannada digits
    "٩٨٤٥٠٣١٢٠٧",                       # Arabic-Indic digits
    "＋９１ ９８４５０ ３１２０７",           # full-width characters
    "\u202a+91 98450 31207\u202c",       # direction marks, as copied from a chat app
    "98450\u201131207",                  # non-breaking hyphen
    "98450\u201331207",                  # en dash
    "98450\u200b31207",                  # zero-width space
    "+91\u00a098450\u00a031207",         # non-breaking spaces
    9845031207,                          # a number rather than a string
])
def test_real_world_spellings_match(raw):
    assert normalise(raw) == "+919845031207"


@pytest.mark.parametrize("raw", ["*121#", "9845031207,204", "9845031207;204", "98450x31207", "+91+9845031207",
                                 "9" * 65, {"number": "9845031207"}, ["9845031207"], "\x00" + "9845031207"])
def test_junk_entries_are_skipped_not_fatal(raw):
    assert normalise(raw) is None


def test_whatever_normalise_returns_can_always_be_hashed():
    hasher = NumberHasher(b"k" * 32)
    samples = ["९८४५०३१२०७", "＋９１９８４５０３１２０７", "٠٩٨٤٥٠٣١٢٠٧", "+৯১৯৮৪৫০৩১২০৭", "௯௮௪௫௦௩௧௨௦௭", "૯૮૪૫૦૩૧૨૦૭"]
    for raw in samples:
        e164 = normalise(raw)
        assert e164 == "+919845031207", raw
        assert e164.isascii()
        hasher.digest(e164)
