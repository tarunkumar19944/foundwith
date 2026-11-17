"""Live multi-user test: seven test users use the real WhoHas server the way people do, and every
answer is checked. Runs from GitHub after each deploy and every night (.github/workflows/live-test.yml).

It signs in with Identity Platform's test numbers (+91 90000 00002 to 08: no SMS is sent), so it
needs the config JSON from Secret Manager (secret found-with-test-accounts):
    {"api_url": ..., "web_api_key": ..., "test_numbers": {"+919000000002": "123456", ...}}

    python3 tests/live/e2e.py config.json

Everything it creates is deleted at the end, and at the start in case an earlier run stopped
halfway. The shared numbers it saves (+91 90000 00090 and 91) belong to no test account, and the
phone tests never save them (they use 0007x), so the counts here are exact.
Standard library only.
"""

from __future__ import annotations

import json
import sys
import time
import traceback
import urllib.error
import urllib.request

PLUMBER = "+919000000090"
CLINIC = "+919000000091"
NAMES = {2: "Asha Test", 3: "Bala Test", 4: "Chitra Test", 5: "Deepak Test",
         6: "Esha Test", 7: "Farhan Test", 8: "Gita Test"}
# +91 90000 00009 and 10 belong to the phone tests (android/app/src/androidTest): never touched here.


class Failed(Exception):
    pass


class Skipped(Exception):
    pass


class Report:
    def __init__(self) -> None:
        self.passed: list[str] = []
        self.failed: list[tuple[str, str]] = []
        self.skipped: str | None = None

    def check(self, name: str, ok: bool, detail: object = "") -> bool:
        if ok:
            self.passed.append(name)
            print(f"  ok    {name}")
        else:
            self.failed.append((name, str(detail)[:400]))
            print(f"  FAIL  {name}: {str(detail)[:400]}")
        return ok


class Http:
    def __init__(self, base: str) -> None:
        self.base = base.rstrip("/")

    def call(self, method: str, path: str, token: str | None = None, body: object = None,
             raw: bool = False) -> tuple[int, object]:
        url = path if path.startswith("https://") else self.base + path
        headers = {"X-App-Version": "9999", "User-Agent": "found-with-live-test"}
        data = None
        if body is not None:
            data = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        if token:
            headers["Authorization"] = "Bearer " + token
        request = urllib.request.Request(url, data=data, headers=headers, method=method)
        for attempt in range(3):
            try:
                with urllib.request.urlopen(request, timeout=30) as r:
                    text = r.read().decode()
                    return r.status, (text if raw else (json.loads(text) if text else None))
            except urllib.error.HTTPError as e:
                text = e.read().decode()
                if e.code in (502, 503, 504) and attempt < 2:
                    time.sleep(2 * (attempt + 1))
                    continue
                try:
                    return e.code, (text if raw else json.loads(text))
                except ValueError:
                    return e.code, text
            except urllib.error.URLError:
                if attempt == 2:
                    raise
                time.sleep(2)
        raise Failed("unreachable")


def firebase_sign_in(key: str, phone: str, code: str) -> str:
    """Identity Platform's REST sign-in for a test number: no SMS, the fixed code."""
    api = "https://identitytoolkit.googleapis.com/v1/accounts"
    http = Http(api)
    status, sent = http.call("POST", f"{api}:sendVerificationCode?key={key}", body={"phoneNumber": phone})
    if status != 200:
        raise Failed(f"sendVerificationCode {phone}: {status} {sent}")
    status, signed = http.call("POST", f"{api}:signInWithPhoneNumber?key={key}",
                               body={"sessionInfo": sent["sessionInfo"], "code": code})
    if status != 200:
        raise Failed(f"signInWithPhoneNumber {phone}: {status} {signed}")
    return signed["idToken"]


class User:
    def __init__(self, n: int, phone: str, token: str, http: Http) -> None:
        self.n, self.phone, self.token, self.http = n, phone, token, http
        self.name = NAMES[n]
        self.id: str | None = None

    def call(self, method: str, path: str, body: object = None, raw: bool = False):
        return self.http.call(method, path, self.token, body, raw)

    def lookup(self, number: str, context: str = "call") -> dict:
        """Call lookups by default: their daily limit is higher, and these numbers are kept across
        runs (the limit carries over a deleted account), so several runs a day still fit."""
        status, body = self.call("POST", "/v1/lookup", {"number": number, "context": context})
        if status != 200:
            raise Failed(f"{self.name} lookup {number}: {status} {body}")
        return body

    def join(self, contacts: list, call_log: list = (), named: bool = True) -> None:
        status, body = self.call("POST", "/v1/me", {"display_name": self.name} if named else {})
        if status != 200:
            raise Failed(f"{self.name} create profile: {status} {body}")
        self.id = body.get("id") if isinstance(body, dict) else None
        status, me = self.call("GET", "/v1/me")
        self.id = me["id"]
        consent = me["sync"]["current_consent_version"]
        status, body = self.call("PUT", "/v1/me/sync", {"enabled": True, "consent_version": consent})
        if status != 200:
            raise Failed(f"{self.name} sync on: {status} {body}")
        status, body = self.call("PUT", "/v1/me/numbers", {"contacts": list(contacts), "call_log": list(call_log)})
        if status != 200:
            raise Failed(f"{self.name} full sync: {status} {body}")

    def clean_up(self) -> None:
        """Back to nothing: no account, number not taken out."""
        status, body = self.call("GET", "/v1/optout")
        if status == 200 and body.get("opted_out"):
            self.call("DELETE", "/v1/optout")
        status, _ = self.call("GET", "/v1/me")
        if status == 200:
            self.call("DELETE", "/v1/me")


def known_names(result: dict) -> list[str]:
    return sorted(h["name"] or "(no name)" for h in result.get("known", []))


def run(config: dict) -> Report:
    report = Report()
    http = Http(config["api_url"])
    numbers = {int(p[-2:]): (p, c) for p, c in config["test_numbers"].items() if int(p[-2:]) in NAMES}
    missing = [n for n in NAMES if n not in numbers]
    if missing:
        raise Failed(f"test numbers missing for users {missing}")

    print("Server")
    # /healthz is only for Cloud Run's own probes: run.app reserves paths like it from outside.
    status, body = http.call("GET", "/readyz")
    report.check("database is ready", status == 200, body)
    status, body = http.call("GET", "/v1/me")
    report.check("signed-out requests are refused", status == 401, (status, body))

    print("Signing in seven test users")
    users: dict[int, User] = {}
    for n in sorted(NAMES):
        phone, code = numbers[n]
        users[n] = User(n, phone, firebase_sign_in(config["web_api_key"], phone, code), http)
    report.check("all seven sign in with Identity Platform", len(users) == len(NAMES))
    for u in users.values():
        u.clean_up()
    # After an account is deleted the server wants a sign-in newer than the deletion before the
    # number can join again (whole seconds), so sign everyone in afresh.
    time.sleep(2)
    for u in users.values():
        u.token = firebase_sign_in(config["web_api_key"], u.phone, numbers[u.n][1])
    a, b, c, d, e, f, g = (users[n] for n in sorted(users))

    try:
        print("First steps")
        status, body = a.call("GET", "/v1/me")
        report.check("a new sign-in has no profile yet", status == 404, (status, body))
        status, body = a.call("POST", "/v1/me", {})
        report.check("the account is created without a name", status == 200 and body.get("name") is None, body)
        status, me = a.call("GET", "/v1/me")
        left = me.get("quota", {}) if status == 200 else {}
        if left.get("call", {}).get("left", 0) < 40 or left.get("search", {}).get("left", 0) < 5:
            raise Skipped(f"today's lookup limit for the test numbers is nearly used ({left}); try after midnight IST")
        status, body = a.call("GET", "/v1/cards")
        report.check("Home works before allowing contacts", status == 200 and body.get("taking_part") is False
                     and body.get("mine") is not None, body)
        report.check("searching works before allowing contacts", a.lookup(PLUMBER, "search")["total"] == 0)
        status, body = a.call("PATCH", "/v1/me", {"display_name": a.name})
        report.check("the name can be given later", status == 200, (status, body))
        status, body = a.call("PATCH", "/v1/me", {"display_name": "SBI Customer Care"})
        report.check("bank-like names are refused", status == 422, (status, body))
        status, body = a.call("POST", "/v1/lookup", {"number": "hello", "context": "search"})
        report.check("a non-number search is refused", status == 422, (status, body))
        status, body = a.call("POST", "/v1/lookup", {"number": "044 2345 6789", "context": "search"})
        report.check("a landline search is refused", status == 422, (status, body))

        print("Five people join and save each other")
        a_contacts = [{"number": b.phone, "name": "Bala"}, {"number": c.phone, "name": "Chitra"},
                      {"number": PLUMBER, "name": "Raju Plumber"}]
        status, me = a.call("GET", "/v1/me")
        status, body = a.call("PUT", "/v1/me/sync", {"enabled": True, "consent_version": me["sync"]["current_consent_version"]})
        status, body = a.call("PUT", "/v1/me/numbers", {"contacts": a_contacts, "call_log": []})
        report.check("Asha's contacts sync with their names", status == 200, (status, body))
        a.id = me["id"]
        b.join([{"number": a.phone, "name": "Asha"}, {"number": PLUMBER, "name": "Raju Plumber"}], [c.phone])
        c.join([{"number": PLUMBER, "name": "raju plumber"}, {"number": a.phone, "name": "Asha Akka"}], named=False)
        d.join([{"number": PLUMBER, "name": "Plumber"}, CLINIC])
        e.join([{"number": CLINIC, "name": "Dr. Anand Clinic"}])

        print("What each person sees")
        seen = a.lookup(PLUMBER)
        nameless = [h for h in seen.get("known", []) if not h.get("name")]
        report.check("someone you know who has no name yet comes with your contact's reference (the app shows your name for them)",
                     len(nameless) == 1 and nameless[0].get("contact_ref"), seen)
        status, body = c.call("PATCH", "/v1/me", {"display_name": c.name})
        report.check("Chitra picks her name later", status == 200, (status, body))
        seen = a.lookup(PLUMBER)
        report.check("Asha: the plumber is found with 3 others, not counting her", seen["total"] == 3, seen)
        report.check("Asha: Bala and Chitra are named (she knows them)",
                     known_names(seen) == ["Bala Test", "Chitra Test"], seen)
        report.check("Asha: Deepak is a count, not a name", seen["more"] == 1, seen)
        seen = e.lookup(PLUMBER)
        report.check("Esha knows nobody who has it: all four are a count",
                     seen["total"] == 4 and known_names(seen) == [] and seen["more"] == 4, seen)
        seen = d.lookup(a.phone)
        report.check("searching a member's number shows their own name and that they're on Found With",
                     seen.get("name") == a.name and seen.get("on_found_with") is True and seen.get("owner_id") == a.id, seen)
        status, cards = a.call("GET", "/v1/cards")
        report.check("Asha's Home: her own number is found with Bala and Chitra",
                     status == 200 and cards["mine"]["total"] == 2
                     and known_names(cards["mine"]) == ["Bala Test", "Chitra Test"], cards.get("mine"))
        plumber_card = [x for x in cards.get("cards", []) if x["ref"] == a.lookup(PLUMBER)["ref"]]
        report.check("Asha's Home has a card for the plumber", len(plumber_card) == 1, cards)
        seen = a.lookup(c.phone, "call")
        report.check("call-log numbers count: Chitra's number is in Bala's calls only, and Asha sees him",
                     seen["context"] == "call" and seen["total"] == 1 and known_names(seen) == ["Bala Test"], seen)
        seen = b.lookup(a.phone)
        report.check("people in your call log are people you know: Bala sees Chitra has Asha",
                     known_names(seen) == ["Chitra Test"], seen)

        print("Hide, block and rename")
        ref_a = d.lookup(a.phone)["ref"]
        status, _ = b.call("PUT", f"/v1/hidden/{ref_a}")
        report.check("Bala hides Asha's number", status == 204, status)
        status, cards = a.call("GET", "/v1/cards")
        report.check("Asha is now found with 1 (Bala's copy is hidden)", cards["mine"]["total"] == 1, cards["mine"])
        b.call("DELETE", f"/v1/hidden/{ref_a}")
        status, blocked = a.call("POST", "/v1/blocks", {"user_id": c.id})
        report.check("Asha blocks Chitra", status == 201, (status, blocked))
        seen = a.lookup(PLUMBER)
        report.check("blocked people disappear from Asha's view", "Chitra Test" not in known_names(seen), seen)
        status, blocks = a.call("GET", "/v1/blocks")
        report.check("the blocked list shows Chitra", status == 200 and any(x.get("name") == "Chitra Test" for x in blocks.get("blocks", blocks if isinstance(blocks, list) else [])), blocks)
        if isinstance(blocked, dict) and "id" in blocked:
            status, _ = a.call("DELETE", f"/v1/blocks/{blocked['id']}")
            report.check("Asha unblocks Chitra", status == 204, status)
            report.check("Chitra is back", "Chitra Test" in known_names(a.lookup(PLUMBER)))
        # Name changes are limited to one per 14 days per number, kept even across deleted accounts,
        # so on later runs this number is still waiting: either outcome is right, the rule isn't.
        status, body = c.call("PATCH", "/v1/me", {"display_name": "Chitra R Test"})
        if status == 200:
            report.check("others see Chitra's new name", "Chitra R Test" in known_names(a.lookup(PLUMBER)))
            status, body = c.call("PATCH", "/v1/me", {"display_name": "Chitra Test"})
        report.check("a second name change within 14 days is refused",
                     status == 429 and body.get("error", {}).get("code") == "name_change_too_soon", (status, body))

        print("Changes after the first sync")
        status, body = a.call("POST", "/v1/me/numbers/changes",
                              {"contacts_added": [{"number": d.phone, "name": "Deepak"}], "contacts_removed": [],
                               "call_log_added": [], "call_log_removed": []})
        report.check("Asha saves Deepak (a change, not a full sync)", status == 200, (status, body))
        report.check("Deepak is now named on the plumber for Asha", "Deepak Test" in known_names(a.lookup(PLUMBER)))
        status, body = a.call("POST", "/v1/me/numbers/changes",
                              {"contacts_added": [], "contacts_removed": [d.phone], "call_log_added": [], "call_log_removed": []})
        report.check("Asha deletes Deepak's contact", status == 200 and "Deepak Test" not in known_names(a.lookup(PLUMBER)), body)

        print("Invites")
        status, circle = a.call("GET", "/v1/me/circle")
        code = circle.get("invite", {}).get("code") if status == 200 else None
        report.check("Asha has an invite link", bool(code), circle)
        status, page = http.call("GET", f"/i/{code}", raw=True)
        report.check("the invite page names Asha", status == 200 and "Asha" in page, status)
        status, body = e.call("POST", "/v1/me/invite/redeem", {"code": "ZZZZZZZZ"})
        report.check("a made-up invite code is refused", status == 404, (status, body))
        status, body = a.call("POST", "/v1/me/invite/redeem", {"code": code})
        report.check("your own invite doesn't count", status == 409, (status, body))
        # A number counts for an invite once, ever, so only the first run ever sees it count.
        status, body = e.call("POST", "/v1/me/invite/redeem", {"code": code})
        if status == 200:
            status, circle = a.call("GET", "/v1/me/circle")
            report.check("Esha joined with Asha's link and Asha sees it", circle["invite"]["joined"] == 1, circle)
            status, body = e.call("POST", "/v1/me/invite/redeem", {"code": code})
        report.check("the same number can't count twice",
                     status == 409 and body.get("error", {}).get("details", {}).get("reason") == "already_used", (status, body))

        print("Taking a number out")
        status, body = f.call("PUT", "/v1/optout", {})
        report.check("Farhan (no account) takes his number out", status == 200 and body.get("opted_out") is True, (status, body))
        b.call("POST", "/v1/me/numbers/changes", {"contacts_added": [{"number": f.phone, "name": "Farhan"}],
                                                   "contacts_removed": [], "call_log_added": [], "call_log_removed": []})
        seen = a.lookup(f.phone)
        report.check("nobody sees who has Farhan's number", seen["total"] == 0 and seen.get("taken_out") is True, seen)
        status, body = f.call("DELETE", "/v1/optout")
        report.check("Farhan can undo it", status == 200 and body.get("opted_out") is False, (status, body))
        g.join([{"number": a.phone, "name": "Asha"}])
        status, body = g.call("PUT", "/v1/optout", {"delete_account": False})
        report.check("Gita has an account: she's warned first", status == 409 and body.get("error", {}).get("code") == "account_exists", (status, body))
        status, body = g.call("PUT", "/v1/optout", {"delete_account": True})
        report.check("Gita takes her number out and deletes her account", status == 200, (status, body))
        status, _ = g.call("GET", "/v1/me")
        report.check("Gita's account is gone", status in (401, 404), status)
        g.call("DELETE", "/v1/optout")

        print("Tell (WhoHas)")
        status, _ = a.call("POST", "/v1/tells", {"number": b.phone})
        report.check("Asha taps Tell for Bala", status == 204, status)
        status, body = b.call("GET", "/v1/me")
        report.check("Bala's profile says Asha told him", status == 200 and body.get("invited_by_count", 0) >= 1
                     and any(n.startswith("Asha") or n == "A friend" for n in body.get("invited_by", [])), body)
        cards = a.call("GET", "/v1/cards")[1]
        report.check("cards say how many of the people you know have each number",
                     all("known_count" in c and c["known_count"] >= len(c["known"]) for c in cards.get("cards", [])), cards.get("cards", [])[:2])

        print("Settings and your data")
        status, body = a.call("PUT", "/v1/me/settings", {"evening_note": False})
        report.check("the evening note can be turned off", status == 200 and body.get("evening_note") is False, (status, body))
        status, _ = a.call("PUT", "/v1/me/device", {"token": "live-test-device-token-" + "x" * 20})
        report.check("a phone registers for notifications", status == 204, status)
        status, _ = a.call("DELETE", "/v1/me/device", {"token": "live-test-device-token-" + "x" * 20})
        report.check("and unregisters", status in (200, 204), status)
        status, _ = a.call("POST", "/v1/me/health", {"day": time.strftime("%Y-%m-%d"), "brand": "livetest",
                                                       "android": 14, "calls_seen": 3, "calls_checked": 3})
        report.check("after-call health is accepted", status == 204, status)
        status, body = a.call("GET", "/v1/me/data")
        report.check("'Your data' summary loads", status == 200, (status, body))
        status, body = a.call("GET", "/v1/me/export")
        report.check("the download has the names Asha saved", status == 200 and "Raju Plumber" in json.dumps(body), status)

        print("Deleting accounts")
        status, _ = d.call("DELETE", "/v1/me")
        report.check("Deepak deletes his account", status in (200, 204), status)
        seen = e.lookup(PLUMBER)
        report.check("his copy of the plumber is gone at once", seen["total"] == 3, seen)
    except Skipped as exc:
        report.skipped = str(exc)
    except Failed as exc:
        report.check("scenario could continue", False, exc)
    except Exception:
        report.check("scenario could continue", False, traceback.format_exc())
    finally:
        print("Cleaning up")
        for u in users.values():
            try:
                u.clean_up()
            except Exception as exc:  # keep cleaning the others
                print(f"  cleanup {u.name}: {exc}")
        seen = None
        time.sleep(2)
        try:
            fresh = User(8, g.phone, firebase_sign_in(config["web_api_key"], g.phone, numbers[8][1]), http)
            fresh.call("POST", "/v1/me", {})
            seen = fresh.lookup(PLUMBER)
            fresh.clean_up()
        except Exception as exc:
            print(f"  final check: {exc}")
        report.check("nothing is left behind", seen is not None and seen["total"] == 0, seen)
    return report


def main() -> int:
    config = json.load(open(sys.argv[1]))
    started = time.time()
    try:
        report = run(config)
    except Failed as exc:
        print(f"Could not run: {exc}")
        return 2
    took = time.time() - started
    if report.skipped:
        print(f"::warning::Skipped: {report.skipped}")
    print(f"\n{len(report.passed)} passed, {len(report.failed)} failed in {took:.0f}s")
    for name, detail in report.failed:
        print(f"::error::{name}: {detail}")
    return 1 if report.failed else 0


if __name__ == "__main__":
    sys.exit(main())
