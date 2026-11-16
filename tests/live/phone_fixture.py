"""Gets the live server ready for the phone tests (android/app/src/androidTest): the fixed caller
+91 90000 00009 has a Found With account named "Test Caller", so a missed call from it is one the
app must tell the phone about. Safe to run any number of times.

    python3 tests/live/phone_fixture.py config.json
"""

from __future__ import annotations

import json
import sys

from e2e import Failed, Http, firebase_sign_in

CALLER = "+919000000009"
NAME = "Test Caller"


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
    print(f"Caller ready: {me.get('name')}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
