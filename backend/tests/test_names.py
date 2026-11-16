import pytest

from foundwith.errors import NameNotAllowed
from foundwith.names import clean_display_name


@pytest.mark.parametrize("raw, stored", [
    ("  Arjun   Rao ", "Arjun Rao"),
    ("D'Souza", "D'Souza"),
    ("Mary-Anne O'Neil", "Mary-Anne O'Neil"),
    ("Dr. Kavitha S.", "Dr. Kavitha S."),
    ("K.S.Rao", "K.S.Rao"),
    ("Bankim Chandra", "Bankim Chandra"),          # contains "bank" but is not the word "bank"
    ("Courtney Banks", "Courtney Banks"),
    ("ಅರ್ಜುನ್ ರಾವ್", "ಅರ್ಜುನ್ ರಾವ್"),
    ("अर्जुन राव", "अर्जुन राव"),
    ("முருகன்", "முருகன்"),
    ("محمد علي", "محمد علي"),
])
def test_real_names_are_accepted(raw, stored):
    assert clean_display_name(raw) == stored


@pytest.mark.parametrize("raw", [
    "", "A", "x" * 41, "..", "- -",
    "Arjun 2", "Arjun@Rao", "Arjun_Rao", "Arjun 😀", "Arjun\tRao\x07",
    "SBI", "S.B.I", "s b i bank", "State Bank", "HDFC Bank KYC", "Police", "Cyber Cell", "Income Tax Dept",
    "Customer Care", "CustomerCare", "Customer-Care", "Help Line", "Tech Support", "Found With", "FoundWith Team",
    "Govt Officer", "RBI Official", "Aadhaar Verification", "UPI Refund", "Lottery Winner",
    "www.loans.in", "loans.in", "Cheap Loans.com",
    "पुलिस", "बैंक मैनेजर", "ಪೊಲೀಸ್", "ಬ್ಯಾಂಕ್",
])
def test_names_that_are_not_names_are_refused(raw):
    with pytest.raises(NameNotAllowed):
        clean_display_name(raw)


def test_invisible_joiners_cannot_smuggle_a_blocked_word():
    zero_width_joiner = chr(0x200D)
    with pytest.raises(NameNotAllowed):
        clean_display_name("Ba" + zero_width_joiner + "nk Manager")
