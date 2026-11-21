"""v0.4: contact names and popular names, the evening note, wake-ups, phone health by brand,
live updates (push queue and dispatch) and "Your data"."""

import datetime as dt

import pytest
from starlette.testclient import TestClient

from foundwith import maintenance, push, repo
from foundwith.api import create_app
from foundwith.auth import DevPushCaller, DevVerifier
from foundwith.contact_names import NameCipher, counting_key, tidy_contact_name
from foundwith.errors import AccountSuspended, QuotaExceeded
from foundwith.numbers import to_ref
from tests.conftest import People
from tests.test_service import (ARJUN, CLINIC, KARTHIK, LETTERS, MEENA, PLUMBER, PLUMBER_E164, PRIYA, RAVI,
                                card_for, service_with)

pytestmark = pytest.mark.anyio

TOKEN = "fcm-token-" + "x" * 20
IST = dt.timezone(dt.timedelta(hours=5, minutes=30))


def token_for(name: str) -> str:
    return f"fcm-token-{name}-" + "x" * 20


def contact(number, name):
    return {"number": number, "name": name}


async def nightly(db, service):
    return await maintenance.run(db, service.identity, service.settings)


def age_accounts(db, days):
    db.conn.run(f"UPDATE users SET created_at = now() - interval '{days} days'")


# ---------------------------------------------------------------- contact names

def test_names_are_tidied_and_counted_loosely():
    assert tidy_contact_name("  Dr.  Anand \u200e") == "Dr. Anand"
    assert tidy_contact_name("98451 72046") is None and tidy_contact_name("😀") is None
    assert tidy_contact_name(None) is None and tidy_contact_name(42) is None
    assert tidy_contact_name("x" * 90) == "x" * 60
    assert counting_key("Dr. Anand") == counting_key("dr anand") == counting_key("DR ANAND")
    assert counting_key("Mahesh Electricals") != counting_key("Mahesh Electrical")


def test_names_are_encrypted_and_bound_to_their_number():
    cipher = NameCipher(b"k" * 32)
    a, b = b"\x01" * 32, b"\x02" * 32
    blob = cipher.encrypt("Lakshmi Aunty", a)
    assert b"Lakshmi" not in blob and cipher.decrypt(blob, a) == "Lakshmi Aunty"
    assert cipher.decrypt(blob, b) is None                    # moved next to another number: unreadable
    assert cipher.decrypt(b"short", a) is None
    assert cipher.encrypt("Lakshmi Aunty", a) != blob         # a new nonce every time
    assert cipher.name_hmac("Lakshmi Aunty") == cipher.name_hmac("lakshmi  aunty")
    assert NameCipher(b"j" * 32).name_hmac("Lakshmi Aunty") != cipher.name_hmac("Lakshmi Aunty")


async def test_popular_name_needs_three_established_people(db, identity):
    service = service_with(db, identity, popular_name_min_account_days=7)
    people = People(service)
    for i, name in enumerate(["Mahesh Electricals", "mahesh electricals", "MAHESH ELECTRICALS", "Mahesh"]):
        await people.add(f"+9197000000{i:02d}", f"Person {LETTERS[i]}", contacts=[contact(PLUMBER, name)])
    viewer, _, _ = await people.add(ARJUN, "Arjun Rao", contacts=[])
    await nightly(db, service)
    assert (await service.lookup(viewer, PLUMBER, "search"))["popular_name"] is None   # all accounts brand new

    age_accounts(db, 8)
    await nightly(db, service)
    found = await service.lookup(viewer, PLUMBER, "search")
    assert found["popular_name"] in ("Mahesh Electricals", "mahesh electricals", "MAHESH ELECTRICALS")
    assert (await service.lookup(viewer, PLUMBER, "call"))["popular_name"] == found["popular_name"]

    # Two people renaming it isn't enough to keep three: the name goes the next night.
    for i in (0, 1):
        user = await service.current_user(People.ident(f"+9197000000{i:02d}"))
        await service.sync_numbers(user, [contact(PLUMBER, "Plumber")], [])
    await nightly(db, service)
    assert (await service.lookup(viewer, PLUMBER, "search"))["popular_name"] is None


async def test_call_logs_and_suspended_people_dont_name_numbers(db, identity):
    service = service_with(db, identity, popular_name_min_account_days=0)
    people = People(service)
    await people.add("+919700000001", "Person A", contacts=[contact(CLINIC, "City Clinic")])
    await people.add("+919700000002", "Person B", contacts=[contact(CLINIC, "City Clinic")])
    await people.add("+919700000003", "Person C", call_log=[CLINIC])           # a call log has no names
    viewer, _, _ = await people.add(ARJUN, "Arjun Rao", contacts=[])
    await nightly(db, service)
    assert (await service.lookup(viewer, CLINIC, "search"))["popular_name"] is None
    third, _, _ = await people.add("+919700000004", "Person D", contacts=[contact(CLINIC, "City clinic")])
    await nightly(db, service)
    assert (await service.lookup(viewer, CLINIC, "search"))["popular_name"] is not None
    from foundwith import admin
    await admin.suspend_user(db, third["public_id"], "test")
    await nightly(db, service)
    assert (await service.lookup(viewer, CLINIC, "search"))["popular_name"] is None


async def test_names_follow_the_address_book(db, identity):
    service = service_with(db, identity, popular_name_min_account_days=0)
    people = People(service)
    a, _, _ = await people.add("+919700000001", "Person A", contacts=[contact(PLUMBER, "Raju")])
    b, _, _ = await people.add("+919700000002", "Person B", contacts=[contact(PLUMBER, "Raju")])
    c, _, _ = await people.add("+919700000003", "Person C", contacts=[PLUMBER])          # an older app: no names
    viewer, _, _ = await people.add(ARJUN, "Arjun Rao", contacts=[])
    stored = "SELECT count(*) AS n FROM user_numbers WHERE name_enc IS NOT NULL"
    assert db.conn.run(stored)[0][0]["n"] == 2
    await service.apply_changes(c, [contact(PLUMBER, "raju")], [], [], [])               # saved with a name
    await nightly(db, service)
    assert (await service.lookup(viewer, PLUMBER, "search"))["popular_name"] == "Raju"
    await service.sync_numbers(a, [], [PLUMBER])                                          # now only a call
    assert db.conn.run(stored)[0][0]["n"] == 2
    await nightly(db, service)
    assert (await service.lookup(viewer, PLUMBER, "search"))["popular_name"] is None
    # Nothing readable in the database.
    rows, _ = db.conn.run("SELECT name_enc FROM user_numbers WHERE name_enc IS NOT NULL")
    assert all(b"Raju" not in bytes(r["name_enc"]) and b"raju" not in bytes(r["name_enc"]) for r in rows)


async def test_opting_out_removes_the_popular_name_at_once(db, identity):
    service = service_with(db, identity, popular_name_min_account_days=0)
    people = People(service)
    for i in range(3):
        await people.add(f"+9197000000{i:02d}", f"Person {LETTERS[i]}", contacts=[contact(PLUMBER, "Raju Plumber")])
    viewer, _, _ = await people.add(ARJUN, "Arjun Rao", contacts=[])
    await nightly(db, service)
    assert (await service.lookup(viewer, PLUMBER, "search"))["popular_name"] == "Raju Plumber"
    assert (await service.lookup(viewer, PLUMBER, "search"))["taken_out"] is False
    await service.opt_out(People.ident(PLUMBER_E164))
    found = await service.lookup(viewer, PLUMBER, "search")
    assert found["popular_name"] is None and found["total"] == 0 and found["taken_out"] is True


async def test_cards_carry_names_and_whether_the_owner_joined(db, identity):
    service = service_with(db, identity, popular_name_min_account_days=0)
    people = People(service)
    for i in range(3):
        await people.add(f"+9197000000{i:02d}", f"Person {LETTERS[i]}", contacts=[contact(PLUMBER, "Raju")])
    await people.add(RAVI, "Ravi Kumar", contacts=[PLUMBER])
    arjun, _, sync = await people.add(ARJUN, "Arjun Rao", contacts=[PLUMBER, RAVI])
    await people.add(PRIYA, "Priya Sharma", contacts=[RAVI])
    await nightly(db, service)
    cards = await service.cards(arjun, None, None)
    plumber = card_for(cards, sync["refs"][PLUMBER_E164])
    assert plumber["popular_name"] == "Raju" and plumber["on_found_with"] is False and plumber["name"] is None
    ravi = card_for(cards, sync["refs"][RAVI])
    assert ravi["name"] == "Ravi Kumar" and ravi["on_found_with"] is True and ravi["owner_id"]
    assert cards["mine"]["name"] == "Arjun Rao" and cards["taking_part"] is True


# ---------------------------------------------------------------- wake-ups (the evening note was removed on 22 Nov 2026)

async def test_wakeups_reach_recent_users_and_drop_dead_tokens(db, people, service):
    ravi, _, _ = await people.add(RAVI, "Ravi Kumar", contacts=[])
    arjun, _, _ = await people.add(ARJUN, "Arjun Rao", contacts=[])
    await service.save_device(ravi, token_for("ravi"))
    await service.save_device(arjun, token_for("old"))
    db.conn.run(f"UPDATE users SET last_seen_at = now() - interval '45 days' WHERE id = {arjun['id']}")
    sender = push.LogSender()
    assert await push.send_wakeups(db, sender) == {"sent": 1, "tokens_removed": 0}
    assert sender.messages == [(token_for("ravi"), {"kind": "wake"})]

    class Gone(push.LogSender):
        async def send_messages(self, items):
            return [(t, push.UNREGISTERED) for t, _ in items]
    assert (await push.send_wakeups(db, Gone(), active_days=60))["tokens_removed"] == 2


def test_fcm_wakeup_message():
    wake = push.FcmSender.build("tok", {"kind": "wake"})["message"]
    assert wake["data"] == {"why": "wake"} and "notification" not in wake and wake["android"]["priority"] == "normal"


def test_scheduler_endpoints_need_the_scheduler(db, settings, identity):
    from tests.conftest import make_settings
    sender = push.LogSender()
    app = create_app(make_settings(push_mode="log"), db=db, verifier=DevVerifier(), identity=identity, configure_logging=False,
                     push_sender=sender, push_caller=DevPushCaller())
    with TestClient(app) as client:
        for path in ("/internal/push", "/internal/push/wake"):
            assert client.post(path).status_code == 401
            assert client.post(path, headers={"Authorization": "Bearer someone"}).status_code == 401
            assert client.post(path, headers={"Authorization": "Bearer dev:push"}).status_code == 200, path
    plain = create_app(settings, db=db, verifier=DevVerifier(), identity=identity, configure_logging=False)
    with TestClient(plain) as client:
        assert client.post("/internal/push/wake").status_code == 404        # pushes off: no such endpoint
        assert client.post("/internal/push/evening").status_code == 404     # the evening note no longer exists


# ---------------------------------------------------------------- phone health, settings, app version

def test_health_is_kept_by_brand_only(db, settings, identity):
    app = create_app(settings, db=db, verifier=DevVerifier(), identity=identity, configure_logging=False)
    with TestClient(app) as client:
        h = {"Authorization": f"Bearer dev:{ARJUN}"}
        client.post("/v1/me", json={}, headers=h)
        day = {"day": "2026-11-16", "brand": "xiaomi", "android": 14, "calls_seen": 10, "calls_checked": 6}
        assert client.post("/v1/me/health", json=day, headers=h).status_code == 204
        assert client.post("/v1/me/health", json={**day, "calls_seen": 4, "calls_checked": 9}, headers=h).status_code == 204
        assert client.post("/v1/me/health", json={**day, "brand": "Xiaomi; DROP"}, headers=h).status_code == 422
        rows, _ = db.conn.run("SELECT * FROM device_health")
        assert [(r["brand"], r["phones"], r["calls_seen"], r["calls_checked"]) for r in rows] == [("xiaomi", 2, 14, 10)]
        assert set(rows[0]) == {"day", "brand", "android", "phones", "calls_seen", "calls_checked"}   # nobody in it

        import anyio
        from foundwith import admin
        report = anyio.run(admin.run, db, ["health"])
        assert report == [{"brand": "xiaomi", "phone_days": 2, "calls_seen": 14, "calls_checked": 10, "checked_pct": 71.4}]

        r = client.put("/v1/me/settings", json={"evening_note": False}, headers=h)
        assert r.status_code == 200 and r.json()["evening_note"] is False


def test_old_apps_are_asked_to_update(db, identity):
    from tests.conftest import make_settings
    app = create_app(make_settings(min_app_version=20), db=db, verifier=DevVerifier(), identity=identity,
                     configure_logging=False)
    with TestClient(app) as client:
        h = {"Authorization": f"Bearer dev:{ARJUN}"}
        r = client.post("/v1/me", json={}, headers={**h, "X-App-Version": "19"})
        assert r.status_code == 426 and r.json()["error"]["code"] == "update_required"
        assert client.post("/v1/me", json={}, headers={**h, "X-App-Version": "20"}).status_code == 200
        assert client.get("/v1/me", headers=h).status_code == 200                # the web page sends none


def test_opt_out_page_is_served_with_a_strict_policy(db, settings, identity):
    app = create_app(settings, db=db, verifier=DevVerifier(), identity=identity, configure_logging=False)
    with TestClient(app) as client:
        r = client.get("/out")
        assert r.status_code == 200 and "Remove your number from WhoHas" in r.text
        policy = r.headers["content-security-policy"]
        nonce = policy.split("'nonce-")[1].split("'")[0]
        assert f'nonce="{nonce}"' in r.text and "unsafe-eval" not in policy and "frame-ancestors 'none'" in policy
        assert '"apiKey"' not in r.text                                   # not configured: the page says so
    from tests.conftest import make_settings
    configured = make_settings(firebase_web_api_key="web-key", firebase_auth_domain="x.firebaseapp.com",
                               firebase_project_id="proj")
    app = create_app(configured, db=db, verifier=DevVerifier(), identity=identity, configure_logging=False)
    with TestClient(app) as client:
        assert '"apiKey": "web-key"' in client.get("/out").text


def test_delete_page_deletes_the_account_and_says_what_goes(db, settings, identity):
    app = create_app(settings, db=db, verifier=DevVerifier(), identity=identity, configure_logging=False)
    with TestClient(app) as client:
        r = client.get("/delete")
        assert r.status_code == 200 and "Delete your WhoHas account" in r.text
        assert "Delete my account" in r.text and "privacy policy" in r.text
        assert "30 days" not in r.text                                    # nothing promised that isn't checked
        assert '"/v1/account", { method: "DELETE"' in r.text              # the account of the verified number
        assert "delete_account" not in r.text and 'fetch("/v1/optout"' not in r.text   # never the opt-out list
        assert "What is deleted:" in r.text and "sign up again later" in r.text


# ---------------------------------------------------------------- live updates

def queue(db):
    rows, _ = db.conn.run("SELECT u.display_name AS name, q.why FROM push_queue q JOIN users u ON u.id = q.user_id")
    return {row["name"]: int(row["why"]) for row in rows}


def scalar(db, sql):
    rows, _ = db.conn.run(sql)
    return next(iter(rows[0].values()))


async def test_pushes_follow_syncs_joins_and_invites(db, identity):
    service = service_with(db, identity, push_mode="log")
    people = People(service)
    ravi, _, _ = await people.add(RAVI, "Ravi Kumar", contacts=[PLUMBER, ARJUN])
    priya, _, _ = await people.add(PRIYA, "Priya Sharma", contacts=[PLUMBER])
    meena, _, _ = await people.add(MEENA, "Meena Iyer", contacts=[PLUMBER])     # no app token: never queued
    await service.save_device(ravi, token_for("ravi"))
    await service.save_device(priya, token_for("priya"))
    db.conn.run("DELETE FROM push_queue")

    # Arjun joins and saves the plumber: Ravi and Priya's plumber cards changed, and Ravi has
    # Arjun saved, so he also hears that Arjun joined.
    arjun, _, _ = await people.add(ARJUN, "Arjun Rao", contacts=[PLUMBER])
    assert queue(db) == {"Ravi Kumar": repo.PUSH_CARDS | repo.PUSH_JOINED, "Priya Sharma": repo.PUSH_CARDS}

    sender = push.LogSender()
    sent = await push.dispatch(db, sender)
    assert sent == {"people": 2, "sent": 2, "tokens_removed": 0, "retrying": 0}
    assert sorted(sender.sent) == [(token_for("priya"), "cards"), (token_for("ravi"), "cards,joined")]
    assert queue(db) == {}

    # A second change soon after waits for the gap, so the same phone isn't woken every minute.
    await service.apply_changes(arjun, [], [PLUMBER], [], [])
    assert set(queue(db)) == {"Ravi Kumar", "Priya Sharma"}
    assert (await push.dispatch(db, push.LogSender()))["people"] == 0

    # Someone joining with Priya's link reaches Priya straight away.
    code = (await service.circle(priya, "https://x"))["invite"]["code"]
    karthik, _, _ = await people.add(KARTHIK, "Karthik Reddy", contacts=[])
    await service.redeem_invite(karthik, code)
    assert queue(db)["Priya Sharma"] == repo.PUSH_CARDS | repo.PUSH_INVITE
    sent = await push.dispatch(db, push.LogSender())
    assert sent["people"] == 1


async def test_no_queue_when_pushes_are_off(db, people, service):
    ravi, _, _ = await people.add(RAVI, "Ravi Kumar", contacts=[PLUMBER])
    await service.save_device(ravi, TOKEN)
    await people.add(ARJUN, "Arjun Rao", contacts=[PLUMBER])
    assert int(scalar(db, "SELECT count(*) FROM push_queue")) == 0


class GoneSender:
    async def send_all(self, items):
        return [(token, push.UNREGISTERED if "old" in token else push.RETRY) for token, _ in items]


async def test_dispatch_drops_dead_tokens_and_retries_the_rest(db, people, service):
    ravi, _, _ = await people.add(RAVI, "Ravi Kumar", contacts=[])
    await service.save_device(ravi, token_for("old"))
    await service.save_device(ravi, token_for("new"))
    async with db.acquire() as conn:
        await repo.queue_push(conn, ravi["id"], repo.PUSH_INVITE)
    assert await push.dispatch(db, GoneSender()) == {"people": 1, "sent": 0, "tokens_removed": 1, "retrying": 1}
    assert int(scalar(db, "SELECT count(*) FROM push_tokens")) == 1
    assert int(scalar(db, "SELECT why FROM push_queue")) == repo.PUSH_INVITE      # back in the queue, a minute later


async def test_tokens_move_with_the_phone_and_are_capped(db, people, service):
    ravi, _, _ = await people.add(RAVI, "Ravi Kumar", contacts=[])
    arjun, _, _ = await people.add(ARJUN, "Arjun Rao", contacts=[])
    await service.save_device(ravi, TOKEN)
    await service.save_device(arjun, TOKEN)            # Arjun signed in on Ravi's old phone
    assert scalar(db, "SELECT display_name FROM users u JOIN push_tokens t ON t.user_id = u.id") == "Arjun Rao"
    for i in range(7):
        await service.save_device(ravi, token_for(str(i)))
    assert int(scalar(db, f"SELECT count(*) FROM push_tokens WHERE user_id = {ravi['id']}")) == 5
    await service.forget_device(ravi, token_for("6"))
    assert int(scalar(db, f"SELECT count(*) FROM push_tokens WHERE user_id = {ravi['id']}")) == 4


def test_fcm_message_has_no_personal_data():
    msg = push.FcmSender.message("tok", repo.PUSH_JOINED)["message"]
    assert msg["data"] == {"why": "joined"} and msg["android"]["priority"] == "high"
    assert push.FcmSender.message("tok", repo.PUSH_CARDS)["message"]["android"]["priority"] == "normal"


async def test_fcm_sender_sorts_out_replies():
    replies = {"a": (200, {}), "b": (404, {}), "c": (503, {}), "d": (400, {"error": {"details": [{"errorCode": "UNREGISTERED"}]}}),
               "e": (400, {"error": {"details": [{"errorCode": "INVALID_ARGUMENT"}]}})}
    calls = []

    def post(url, headers, body):
        calls.append(headers["Authorization"])
        return replies[body["message"]["token"]]

    async def token():
        return "access", 3600.0

    sender = push.FcmSender("proj", token=token, post=post)
    got = dict(await sender.send_all([(t, 1) for t in replies]))
    assert got == {"a": push.OK, "b": push.UNREGISTERED, "c": push.RETRY, "d": push.UNREGISTERED, "e": push.FAILED}
    assert set(calls) == {"Bearer access"}


# ---------------------------------------------------------------- your data

async def test_your_data_and_export(db, people, service):
    arjun, arjun_ident, sync = await people.add(ARJUN, "Arjun Rao", contacts=[contact(PLUMBER, "Raju"), RAVI],
                                                call_log=[PRIYA])
    ravi, _, _ = await people.add(RAVI, "Ravi Kumar", contacts=[PLUMBER])
    await service.lookup(arjun, RAVI, "search")
    await service.hide(arjun, sync["refs"][PLUMBER_E164])
    data = await service.my_data(arjun, arjun_ident)
    assert data["name"] == "Arjun Rao" and data["phone"] == ARJUN
    assert data["numbers"] == {"contacts": 2, "call_log": 1, "hidden": 1}
    assert data["searches_made"] == 1 and data["consent_version"] == service.settings.consent_version
    assert data["evening_note"] is True

    full = await service.export(arjun, arjun_ident)
    assert {n["ref"] for n in full["numbers"]} == set(sync["refs"].values())
    assert full["lookups"][0]["ref"] == to_ref(service.hasher.digest(RAVI)) and full["lookups"][0]["kind"] == "search"
    saved = {n["ref"]: n["saved_as"] for n in full["numbers"]}
    assert saved[sync["refs"][PLUMBER_E164]] == "Raju" and saved[sync["refs"][RAVI]] is None


async def test_config_checks_push_settings():
    from foundwith.config import ConfigError, Settings
    base = {"AUTH_MODE": "dev", "NUMBER_PEPPER": "p" * 32, "NAME_KEY": "n" * 32}
    for bad in ({"PUSH_MODE": "fcm"}, {"PUSH_MODE": "maybe"}):
        with pytest.raises(ConfigError):
            Settings.from_env({**base, **bad})
    ok = Settings.from_env({**base, "PUSH_MODE": "fcm", "PUSH_CALLER": "push@x.iam.gserviceaccount.com",
                            "PUSH_AUDIENCE": "https://api.example/internal/push", "FIREBASE_PROJECT_ID": "proj"})
    assert ok.fcm_project_id == "proj" and ok.push_mode == "fcm"


# ---------------------------------------------------------------- moderation by number

def test_moderators_hash_numbers_on_their_own_machine():
    from foundwith import admin
    from foundwith.numbers import NumberHasher

    pepper = "p" * 40
    done = admin.hash_number("098450 31207", pepper)
    assert done == {"number": "+919845031207", "hash": NumberHasher(pepper.encode()).digest("+919845031207").hex()}
    with pytest.raises(admin.AdminError):
        admin.hash_number("hello", pepper)
    with pytest.raises(admin.AdminError):
        admin.hash_number("098450 31207", "short")


async def test_moderators_act_on_a_number_from_a_complaint(db, identity):
    from foundwith import admin

    service = service_with(db, identity, popular_name_min_account_days=0)
    people = People(service)
    for i in range(3):
        await people.add(f"+9197000000{i:02d}", f"Person {LETTERS[i]}", contacts=[contact(PLUMBER, "Fraud")])
    viewer, _, _ = await people.add(ARJUN, "Arjun Rao", contacts=[])
    owner, owner_ident, _ = await people.add(PLUMBER_E164, "Raju Sharma", contacts=[])
    await nightly(db, service)
    assert (await service.lookup(viewer, PLUMBER, "search"))["popular_name"] == "Fraud"

    hashed = service.hasher.digest(PLUMBER_E164).hex()
    info = await admin.run(db, ["number", hashed])
    assert info["user_id"] == owner["public_id"] and info["name_they_gave"] == "Raju Sharma"
    assert info["people_who_have_it"] == 3 and info["popular_name_from_people"] == 3 and info["popular_name_held"] is None

    held = await admin.run(db, ["hold-name", hashed, "insulting", "name"])
    assert held["had_a_popular_name"] is True
    assert (await service.lookup(viewer, PLUMBER, "search"))["popular_name"] is None      # gone at once
    await nightly(db, service)
    assert (await service.lookup(viewer, PLUMBER, "search"))["popular_name"] is None      # and stays gone
    assert (await admin.run(db, ["number", hashed]))["popular_name_held"] == "insulting name"

    done = await admin.run(db, ["suspend-number", hashed, "pretending", "to", "be", "a", "bank"])
    assert done["account_existed"] is True
    with pytest.raises(AccountSuspended):
        await service.current_user(owner_ident)

    await admin.run(db, ["release-name", hashed])
    await nightly(db, service)
    assert (await service.lookup(viewer, PLUMBER, "search"))["popular_name"] == "Fraud"
    with pytest.raises(admin.AdminError):
        await admin.run(db, ["release-name", hashed])
    with pytest.raises(admin.AdminError):
        await admin.run(db, ["number", "not-a-hash"])

    nobody = service.hasher.digest("+919811111111").hex()                                 # no account on it
    assert (await admin.run(db, ["suspend-number", nobody, "spam"]))["account_existed"] is False
    assert (await admin.run(db, ["number", nobody]))["suspended_at"] is not None


async def test_test_numbers_are_not_held_to_lookup_limits(db, identity):
    service = service_with(db, identity, search_daily_limit=1, quota_exempt_numbers=("+91 98450 31207",))
    people = People(service)
    arjun, _, _ = await people.add(ARJUN, "Arjun Rao", contacts=[])
    ravi, _, _ = await people.add(RAVI, "Ravi Kumar", contacts=[])
    for _ in range(3):
        result = await service.lookup(arjun, PLUMBER, "search")       # Arjun's number is a test number
    assert result["quota"]["left"] == 1
    await service.lookup(ravi, PLUMBER, "search")
    with pytest.raises(QuotaExceeded):
        await service.lookup(ravi, PLUMBER, "search")                 # everyone else keeps the limit
