"""Gets the live server ready for the phone tests (android/app/src/androidTest): the fixed account
+91 90000 00009 is on WhoHas as "Test Caller", taking part, and has +91 90000 00077 in its contacts,
so a missed call from 90000 00077 is one the app must tell the phone about (since 22 Nov 2026 a
call is told only when someone on WhoHas has the number); and the phones' own account,
+91 90000 00010, starts afresh, so nothing an earlier run saved is still on the server. Safe to run
any number of times.

    python3 tests/live/phone_fixture.py config.json [--keep-phones-account]

--keep-phones-account: another phone-test run is using the phones' account right now (runs go side
by side since 23 Nov 2026), so it is left as it is; deleting it would fail that run's phones.
"""

from __future__ import annotations

import json
import sys

from e2e import Failed, Http, firebase_sign_in

CALLER = "+919000000009"
NAME = "Test Caller"
# The number the phones get a missed call from: no account, not in the phones' contacts, and had by
# Test Caller.
UNKNOWN_CALLER = "+919000000077"
PHONES = "+919000000010"


def main() -> int:
    config = json.load(open(sys.argv[1]))
    http = Http(config["api_url"])
    token = firebase_sign_in(config["web_api_key"], CALLER, config["test_numbers"][CALLER])
    status, body = http.call("GET", "/v1/optout", token)
    if status == 200 and body.get("opted_out"):
        http.call("DELETE", "/v1/optout", token)
    status, me = http.call("GET", "/v1/me", token)
    if status == 404:
        status, me = http.call("POST", "/v1/me", token, {"display_name": NAME})
    elif status == 200 and me.get("name") != NAME:
        status, me = http.call("PATCH", "/v1/me", token, {"display_name": NAME})
    if status != 200:
        raise Failed(f"caller account: {status} {me}")
    # Only people who accepted the current terms are named on cards and after calls, so the caller
    # takes part like anyone would: contacts on, current consent.
    sync = me.get("sync", {})
    if not sync.get("enabled") or not sync.get("consent_current"):
        status, me = http.call("PUT", "/v1/me/sync", token,
                               {"enabled": True, "consent_version": sync.get("current_consent_version")})
        if status != 200:
            raise Failed(f"caller consent: {status} {me}")
    status, body = http.call("PUT", "/v1/me/numbers", token, {"contacts": [UNKNOWN_CALLER], "call_log": []})
    if status != 200:
        raise Failed(f"caller's contacts: {status} {body}")
    print(f"Caller ready: {me.get('name')} (taking part: {me.get('sync', {}).get('enabled')}), has {UNKNOWN_CALLER}")

    if "--keep-phones-account" in sys.argv[2:]:
        print("Another phone-test run is using the phones' account: left as it is")
        return 0
    token = firebase_sign_in(config["web_api_key"], PHONES, config["test_numbers"][PHONES])
    status, _ = http.call("GET", "/v1/me", token)
    if status == 200:
        status, body = http.call("DELETE", "/v1/me", token)
        if status not in (200, 204):
            raise Failed(f"phones' account: {status} {body}")
        print("Phones' account deleted: it starts afresh")
    return 0


if __name__ == "__main__":
    sys.exit(main())
