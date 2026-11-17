"""Writes India's mobile number ranges, taken from Google's libphonenumber data, into the server
and the app, so both tell a mobile from a landline the same way.

    pip install phonenumbers && python backend/scripts/india_mobile_ranges.py

Run it every few months (new mobile series are opened from time to time), then commit both files.
"""
import pathlib

import phonenumbers

ROOT = pathlib.Path(__file__).resolve().parents[2]
PATTERN = phonenumbers.PhoneMetadata.metadata_for_region("IN").mobile.national_number_pattern
assert "$" not in PATTERN and '"""' not in PATTERN
NOTE = f"India's mobile number ranges (the 10 digits after +91), from libphonenumber {phonenumbers.__version__}."

(ROOT / "backend/foundwith/india_mobile.py").write_text(
    f'"""{NOTE}\nWritten by backend/scripts/india_mobile_ranges.py: don\'t edit by hand."""\n\n'
    f'PATTERN = (\n    r"{PATTERN}"\n)\n')
(ROOT / "android/app/src/main/java/app/foundwith/core/IndiaMobile.kt").write_text(
    "package app.foundwith.core\n\n"
    f"/** {NOTE}\n *  Written by backend/scripts/india_mobile_ranges.py: don't edit by hand. */\n"
    f'internal object IndiaMobile {{\n    const val PATTERN = """{PATTERN}"""\n}}\n')
print("libphonenumber", phonenumbers.__version__, "pattern", len(PATTERN), "chars")
