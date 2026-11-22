"""HTTP layer: auth, error shapes and the main flows, through Starlette's TestClient."""

import time
from contextlib import asynccontextmanager

import pytest
from starlette.testclient import TestClient

from foundwith import admin
from foundwith.api import create_app
from foundwith.auth import AuthError, AuthUnavailable, DevVerifier, Identity
from tests.conftest import make_settings

ARJUN, RAVI, PRIYA = "+919845031207", "+919900148823", "+919741266019"
PLUMBER = "98451 72046"
PLUMBER_E164 = "+919845172046"


@pytest.fixture
def client(db, settings, identity):
    app = create_app(settings, db=db, verifier=DevVerifier(), identity=identity, configure_logging=False)
    with TestClient(app) as test_client:
        yield test_client


def as_user(phone):
    return {"Authorization": f"Bearer dev:{phone}"}


def join(client, phone, name, contacts=(), call_log=()):
    h = as_user(phone)
    assert client.post("/v1/me", json={"display_name": name}, headers=h).status_code == 200
    consent = client.get("/v1/me", headers=h).json()["sync"]["current_consent_version"]
    assert client.put("/v1/me/sync", json={"enabled": True, "consent_version": consent}, headers=h).status_code == 200
    r = client.put("/v1/me/numbers", json={"contacts": list(contacts), "call_log": list(call_log)}, headers=h)
    assert r.status_code == 200, r.text
    return h, r.json()


def error_code(response):
    return response.json()["error"]["code"]


def test_health_and_unknown_routes(client):
    assert client.get("/healthz").json() == {"status": "ok"}
    assert client.get("/readyz").json() == {"status": "ready"}
    r = client.get("/nope")
    assert r.status_code == 404 and error_code(r) == "not_found"
    assert client.post("/v1/cards").status_code == 405


def test_sign_in_is_required(client):
    r = client.get("/v1/cards")
    assert r.status_code == 401 and error_code(r) == "unauthenticated"
    r = client.get("/v1/cards", headers={"Authorization": "Bearer garbage"})
    assert r.status_code == 401


def test_profile_lifecycle(client):
    h = as_user(ARJUN)
    r = client.get("/v1/me", headers=h)
    assert r.status_code == 404 and error_code(r) == "not_registered"

    r = client.post("/v1/me", json={"display_name": "A"}, headers=h)
    assert r.status_code == 422 and error_code(r) == "name_not_allowed"
    r = client.post("/v1/me", json={"display_name": "HDFC Bank Support"}, headers=h)
    assert r.status_code == 422 and error_code(r) == "name_not_allowed" and "own name" in r.json()["error"]["message"]
    r = client.post("/v1/me", json={"display_name": "Arjun", "admin": True}, headers=h)
    assert r.status_code == 422 and error_code(r) == "invalid_request"
    assert r.json()["error"]["details"]["fields"][0]["field"] == "admin"
    r = client.post("/v1/me", content=b"{not json", headers=h)
    assert r.status_code == 400

    # Signing in creates the account straight away, with no name yet.
    r = client.post("/v1/me", json={}, headers=h)
    me = r.json()
    assert r.status_code == 200 and me["name"] is None and me["phone"] == ARJUN and me["sync"]["enabled"] is False
    assert me["suspended"] is False and me["number_opted_out"] is False and me["visibility"] == "everyone"
    assert me["quota"]["search"] == {"limit": 5, "left": 5} and me["evening_note"] is True
    assert r.headers["cache-control"] == "no-store"
    assert client.post("/v1/me", json={"display_name": "Someone Else"}, headers=h).json()["name"] is None
    # Choosing the name later doesn't start the wait before a change.
    assert client.patch("/v1/me", json={"display_name": "  Arjun   Rao "}, headers=h).json()["name"] == "Arjun Rao"

    r = client.put("/v1/me/numbers", json={"contacts": [PLUMBER]}, headers=h)
    assert r.status_code == 409 and error_code(r) == "sync_required"
    r = client.put("/v1/me/sync", json={"enabled": True}, headers=h)
    assert r.status_code == 409 and error_code(r) == "consent_required"
    assert r.json()["error"]["details"]["current_consent_version"]

    assert client.patch("/v1/me", json={"display_name": "Arjun R"}, headers=h).json()["name"] == "Arjun R"
    r = client.patch("/v1/me", json={"display_name": "Arjun"}, headers=h)
    assert r.status_code == 429 and error_code(r) == "name_change_too_soon"
    assert client.delete("/v1/me", headers=h).json()["deleted"] is True
    assert client.get("/v1/me", headers=h).status_code == 404


def test_cards_lookup_hide_flow(client):
    arjun, arjun_sync = join(client, ARJUN, "Arjun Rao", contacts=[PLUMBER, RAVI])
    ravi, _ = join(client, RAVI, "Ravi Kumar", contacts=[PLUMBER])
    assert arjun_sync["stats"] == {"numbers": 2, "added": 2, "removed": 0, "skipped": 0, "deferred": 0}

    cards = client.get("/v1/cards", headers=arjun).json()
    assert cards["total_cards"] == 1 and cards["next"] is None
    card = cards["cards"][0]
    assert card["ref"] == arjun_sync["refs"][PLUMBER_E164]
    assert [h["name"] for h in card["known"]] == ["Ravi Kumar"]

    r = client.post("/v1/lookup", json={"number": "98451-72046", "context": "search"}, headers=ravi)
    body = r.json()
    assert r.status_code == 200 and [h["name"] for h in body["others"]] == ["Arjun Rao"]
    assert body["quota"]["left"] == 4

    r = client.post("/v1/lookup", json={"number": "080 4123 5566 99"}, headers=ravi)
    assert r.status_code == 422 and error_code(r) == "invalid_number"

    holders = client.get(f"/v1/numbers/{card['ref']}/holders?limit=10", headers=arjun).json()
    assert [h["name"] for h in holders["holders"]] == ["Ravi Kumar"] and holders["list_capped"] is False
    assert client.get("/v1/numbers/not-a-ref/holders", headers=arjun).status_code == 404
    assert client.get("/v1/cards?limit=0", headers=arjun).status_code == 422
    assert client.get("/v1/cards?cursor=junk", headers=arjun).status_code == 400

    assert client.put(f"/v1/hidden/{card['ref']}", headers=ravi).status_code == 204
    assert client.get("/v1/cards", headers=arjun).json()["cards"] == []
    assert client.get("/v1/hidden", headers=ravi).json() == {"refs": [card["ref"]]}
    assert client.delete(f"/v1/hidden/{card['ref']}", headers=ravi).status_code == 204
    assert client.get("/v1/cards", headers=arjun).json()["total_cards"] == 1


def test_a_borrowed_reference_is_worthless(client):
    arjun, sync = join(client, ARJUN, "Arjun Rao", contacts=[PLUMBER])
    join(client, RAVI, "Ravi Kumar", contacts=[PLUMBER])
    priya, _ = join(client, PRIYA, "Priya Sharma")
    ref = sync["refs"][PLUMBER_E164]
    r = client.get(f"/v1/numbers/{ref}/holders", headers=priya)
    assert r.status_code == 404 and error_code(r) == "not_found"
    client.post("/v1/lookup", json={"number": PLUMBER}, headers=priya)
    assert len(client.get(f"/v1/numbers/{ref}/holders", headers=priya).json()["holders"]) == 2


def test_search_quota_returns_429(client):
    arjun, _ = join(client, ARJUN, "Arjun Rao", contacts=[PLUMBER])
    for _ in range(5):
        assert client.post("/v1/lookup", json={"number": PLUMBER}, headers=arjun).status_code == 200
    r = client.post("/v1/lookup", json={"number": PLUMBER}, headers=arjun)
    assert r.status_code == 429 and error_code(r) == "quota_exceeded"
    assert client.post("/v1/lookup", json={"number": PLUMBER, "context": "call"}, headers=arjun).status_code == 200


def test_one_bad_contact_does_not_fail_the_sync(client):
    odd = [PLUMBER, None, 9900148823, "x" * 500, {"a": 1}, "९७४१२ ६६०१९", ""]
    _, sync = join(client, ARJUN, "Arjun Rao", contacts=odd)
    assert sorted(sync["refs"]) == [PRIYA, PLUMBER_E164, RAVI]
    assert sync["stats"]["skipped"] == 4


def test_opt_out_needs_only_a_verified_number(client):
    arjun, _ = join(client, ARJUN, "Arjun Rao", contacts=[PLUMBER])
    ravi, _ = join(client, RAVI, "Ravi Kumar", contacts=[PLUMBER])
    plumber = as_user(PLUMBER_E164)                                   # never created a profile
    assert client.get("/v1/me", headers=plumber).status_code == 404
    assert client.get("/v1/optout", headers=plumber).json() == {"opted_out": False, "has_account": False}
    assert client.put("/v1/optout", headers=plumber).json() == {"opted_out": True, "account_deleted": False}
    assert client.get("/v1/cards", headers=arjun).json()["cards"] == []
    assert client.post("/v1/lookup", json={"number": PLUMBER}, headers=ravi).json()["total"] == 0
    assert client.delete("/v1/optout", headers=plumber).json() == {"opted_out": False}
    assert client.get("/v1/optout").status_code == 401

    # A number with an account: the page has to warn first, then the account goes too.
    assert client.get("/v1/optout", headers=ravi).json() == {"opted_out": False, "has_account": True}
    r = client.put("/v1/optout", headers=ravi)
    assert r.status_code == 409 and error_code(r) == "account_exists"
    r = client.put("/v1/optout", json={"delete_account": True}, headers=ravi)
    assert r.json() == {"opted_out": True, "account_deleted": True}
    assert client.get("/v1/me", headers=ravi).status_code == 404
    assert client.get("/v1/optout", headers=ravi).json() == {"opted_out": True, "has_account": False}


def test_the_web_page_deletes_the_account_of_the_verified_number(client):
    arjun, _ = join(client, ARJUN, "Arjun Rao", contacts=[PLUMBER])
    ravi, _ = join(client, RAVI, "Ravi Kumar", contacts=[PLUMBER])
    assert client.post("/v1/lookup", json={"number": PLUMBER}, headers=arjun).json()["total"] == 1   # Ravi
    assert client.delete("/v1/account").status_code == 401
    r = client.delete("/v1/account", headers=ravi)
    assert r.status_code == 200 and r.json()["deleted"] is True and r.json()["had_account"] is True
    assert client.get("/v1/me", headers=ravi).status_code == 404
    assert client.post("/v1/lookup", json={"number": PLUMBER}, headers=arjun).json()["total"] == 0   # counts go down by one
    # Not the opt-out list: Ravi can sign up again, and his number still shows.
    assert client.get("/v1/optout", headers=ravi).json() == {"opted_out": False, "has_account": False}
    join(client, RAVI, "Ravi Kumar", contacts=[])
    # A number with no account: nothing to delete, and nothing is changed.
    r = client.delete("/v1/account", headers=as_user(PLUMBER_E164))
    assert r.status_code == 200 and r.json() == {"deleted": False, "had_account": False}


def test_reports_and_blocks(client):
    arjun, _ = join(client, ARJUN, "Arjun Rao", contacts=[PLUMBER])
    ravi, _ = join(client, RAVI, "Ravi Kumar", contacts=[PLUMBER])
    ravi_id = client.get("/v1/cards", headers=arjun).json()["cards"][0]["others"][0]["id"]

    r = client.post("/v1/reports", json={"user_id": ravi_id, "reason": "impersonation", "note": "fake"}, headers=arjun)
    assert r.status_code == 204
    r = client.post("/v1/reports", json={"user_id": ravi_id, "reason": "because"}, headers=arjun)
    assert r.status_code == 422 and error_code(r) == "invalid_request"
    r = client.post("/v1/reports", json={"user_id": "nobody", "reason": "other"}, headers=arjun)
    assert r.status_code == 404

    r = client.post("/v1/blocks", json={"user_id": ravi_id}, headers=arjun)
    assert r.status_code == 201 and r.json()["name"] == "Ravi Kumar"
    block_id = r.json()["id"]
    assert client.get("/v1/cards", headers=arjun).json()["cards"] == []
    assert client.get("/v1/cards", headers=ravi).json()["cards"] == []
    blocks = client.get("/v1/blocks", headers=arjun).json()["blocks"]
    assert [(b["id"], b["name"]) for b in blocks] == [(block_id, "Ravi Kumar")] and len(blocks[0]["ref"]) == 43
    assert client.delete(f"/v1/blocks/{block_id}", headers=ravi).status_code == 404
    assert client.delete(f"/v1/blocks/{block_id}", headers=arjun).status_code == 204
    assert client.delete("/v1/blocks/abc", headers=arjun).status_code == 404
    assert client.get("/v1/cards", headers=arjun).json()["total_cards"] == 1


def test_suspended_accounts_are_locked_out_but_can_still_leave(client, db):
    import anyio

    arjun, _ = join(client, ARJUN, "Arjun Rao", contacts=[PLUMBER])
    ravi, _ = join(client, RAVI, "Ravi Kumar", contacts=[PLUMBER])
    ravi_id = client.get("/v1/me", headers=ravi).json()["id"]
    anyio.run(admin.suspend_user, db, ravi_id, "impersonation")

    for method, path in [("get", "/v1/cards"), ("put", "/v1/me/sync"), ("get", "/v1/hidden"), ("get", "/v1/blocks")]:
        r = getattr(client, method)(path, headers=ravi)
        assert r.status_code == 403 and error_code(r) == "account_suspended", path
    me = client.get("/v1/me", headers=ravi).json()
    assert me["suspended"] is True and me["sync"]["enabled"] is False
    assert client.get("/v1/cards", headers=arjun).json()["cards"] == []
    assert client.delete("/v1/me", headers=ravi).json()["deleted"] is True


def test_a_runaway_client_is_slowed_down(db, identity):
    settings = make_settings(rate_limit_heavy_per_minute=4)          # a burst of 2, then one every 15 seconds
    app = create_app(settings, db=db, verifier=DevVerifier(), identity=identity, configure_logging=False)
    with TestClient(app) as client:
        arjun = as_user(ARJUN)
        client.post("/v1/me", json={"display_name": "Arjun Rao"}, headers=arjun)
        consent = client.get("/v1/me", headers=arjun).json()["sync"]["current_consent_version"]
        client.put("/v1/me/sync", json={"enabled": True, "consent_version": consent}, headers=arjun)
        assert client.get("/v1/cards", headers=arjun).status_code == 200
        assert client.get("/v1/cards", headers=arjun).status_code == 200
        r = client.get("/v1/cards", headers=arjun)
        assert r.status_code == 429 and error_code(r) == "rate_limited" and int(r.headers["retry-after"]) >= 1
        assert client.get("/v1/me", headers=arjun).status_code == 200      # light requests are unaffected
        other = as_user(RAVI)                                              # and so is everybody else
        client.post("/v1/me", json={"display_name": "Ravi Kumar"}, headers=other)
        assert client.get("/v1/cards", headers=other).status_code == 200


def test_test_numbers_are_not_slowed_down(db, identity):
    settings = make_settings(rate_limit_heavy_per_minute=4, quota_exempt_numbers=(ARJUN,))
    app = create_app(settings, db=db, verifier=DevVerifier(), identity=identity, configure_logging=False)
    with TestClient(app) as client:
        arjun = as_user(ARJUN)
        client.post("/v1/me", json={"display_name": "Arjun Rao"}, headers=arjun)
        assert all(client.get("/v1/cards", headers=arjun).status_code == 200 for _ in range(6))


class FixedSignIn:
    """A verifier whose sign-in time we control (the dev verifier always says "just now")."""

    def __init__(self):
        self.auth_time = time.time()
        self.error = None

    async def verify(self, token):
        if self.error is not None:
            raise self.error
        return Identity(uid="uid-" + token, phone=token, auth_time=self.auth_time)


def test_a_deleted_account_needs_a_fresh_sign_in_to_come_back(db, settings, identity):
    verifier = FixedSignIn()
    app = create_app(settings, db=db, verifier=verifier, identity=identity, configure_logging=False)
    with TestClient(app) as client:
        h = {"Authorization": f"Bearer {ARJUN}"}
        assert client.post("/v1/me", json={"display_name": "Arjun Rao"}, headers=h).status_code == 200
        assert client.delete("/v1/me", headers=h).status_code == 200
        r = client.post("/v1/me", json={"display_name": "Arjun Rao"}, headers=h)      # same sign-in, replayed
        assert r.status_code == 401 and error_code(r) == "reauth_required"
        verifier.auth_time = time.time() + 2                                           # a new OTP sign-in
        assert client.post("/v1/me", json={"display_name": "Arjun Rao"}, headers=h).status_code == 200


def test_temporary_failures_answer_503_not_500(db, settings, identity):
    verifier = FixedSignIn()

    class BusyDatabase:
        def __init__(self):
            self.busy = False

        @asynccontextmanager
        async def acquire(self):
            if self.busy:
                raise TimeoutError("no free connection")
            async with db.acquire() as conn:
                yield conn

    database = BusyDatabase()
    app = create_app(settings, db=database, verifier=verifier, identity=identity, configure_logging=False)
    with TestClient(app, raise_server_exceptions=False) as client:
        h = {"Authorization": f"Bearer {ARJUN}"}
        assert client.post("/v1/me", json={"display_name": "Arjun Rao"}, headers=h).status_code == 200
        database.busy = True
        r = client.get("/v1/me", headers=h)
        assert r.status_code == 503 and error_code(r) == "unavailable" and r.headers["retry-after"] == "3"
        assert client.get("/readyz").status_code == 503
        database.busy = False

        verifier.error = AuthUnavailable("no certificates")
        r = client.get("/v1/me", headers=h)
        assert r.status_code == 503 and error_code(r) == "unavailable"
        verifier.error = AuthError("expired")
        assert client.get("/v1/me", headers=h).status_code == 401


def test_oversized_bodies_are_refused(db, identity):
    settings = make_settings(max_body_bytes=2_000)
    app = create_app(settings, db=db, verifier=DevVerifier(), identity=identity, configure_logging=False)
    with TestClient(app, raise_server_exceptions=False) as client:
        arjun, _ = join(client, ARJUN, "Arjun Rao", contacts=[PLUMBER])
        r = client.put("/v1/me/numbers", json={"contacts": ["9845000000"] * 400}, headers=arjun)
        assert r.status_code == 413          # refused by the framework before the app reads it
        assert client.get("/v1/cards", headers=arjun).status_code == 200


def test_changes_endpoint_puts_a_new_contact_on_cards(client):
    arjun, _ = join(client, ARJUN, "Arjun Rao", contacts=[PLUMBER])
    h = as_user(RAVI)
    assert client.post("/v1/me", json={"display_name": "Ravi Kumar"}, headers=h).status_code == 200
    consent = client.get("/v1/me", headers=h).json()["sync"]["current_consent_version"]
    client.put("/v1/me/sync", json={"enabled": True, "consent_version": consent}, headers=h)
    r = client.post("/v1/me/numbers/changes", json={"contacts_added": [PLUMBER]}, headers=h)
    assert r.status_code == 409 and error_code(r) == "full_sync_required"

    client.put("/v1/me/numbers", json={"contacts": [], "call_log": []}, headers=h)
    r = client.post("/v1/me/numbers/changes", json={"contacts_added": [PLUMBER]}, headers=h)
    assert r.status_code == 200 and r.json()["stats"]["added"] == 1
    card = client.get("/v1/cards", headers=arjun).json()["cards"][0]
    assert [p["name"] for p in card["others"]] == ["Ravi Kumar"]

    r = client.post("/v1/me/numbers/changes", json={"contacts_added": [PLUMBER] * 501}, headers=h)
    assert r.status_code == 422


def test_tell_names_who_invited_you_and_cards_count_who_you_know(client):
    join(client, ARJUN, "Arjun Rao", contacts=[RAVI, PRIYA])
    join(client, PRIYA, "Priya Sharma", contacts=[RAVI, ARJUN])
    assert client.post("/v1/tells", json={"number": "99001 48823"}, headers=as_user(ARJUN)).status_code == 204
    assert client.post("/v1/tells", json={"number": RAVI}, headers=as_user(PRIYA)).status_code == 204
    assert client.post("/v1/tells", json={"number": RAVI}, headers=as_user(PRIYA)).status_code == 204   # again: one row
    assert client.post("/v1/tells", json={"number": "044 2222 3333"}, headers=as_user(ARJUN)).status_code == 422
    assert client.post("/v1/tells", json={"number": ARJUN}, headers=as_user(ARJUN)).status_code == 204   # own: ignored

    join(client, RAVI, "Ravi Kumar", contacts=[ARJUN])
    me = client.get("/v1/me", headers=as_user(RAVI)).json()
    assert me["invited_by_count"] == 2 and sorted(me["invited_by"]) == ["Arjun", "Priya"]
    assert client.get("/v1/me", headers=as_user(ARJUN)).json()["invited_by"] == []

    # Arjun's card for Ravi: 2 people have it (Priya and Ravi's own contact list doesn't count), Arjun knows Priya.
    cards = {c["ref"]: c for c in client.get("/v1/cards", headers=as_user(ARJUN)).json()["cards"]}
    ravi = next(c for c in cards.values() if c["owner_id"] is not None and c["name"] == "Ravi Kumar")
    assert ravi["known_count"] == len(ravi["known"]) and "popular_name_people" in ravi


def test_invite_link_circle_and_redeem(client):
    join(client, ARJUN, "Arjun Rao", contacts=[RAVI])
    circle = client.get("/v1/me/circle", headers=as_user(ARJUN)).json()
    code, link = circle["invite"]["code"], circle["invite"]["link"]
    assert link.endswith(f"/i/{code}") and circle["members"] == 0

    # The link is a page: on Android a Play Store button with the code for the app to pick up,
    # on an iPhone "Android only for now". It names who invited.
    android = {"user-agent": "Mozilla/5.0 (Linux; Android 14; 23021RAA2Y) Mobile"}
    r = client.get(f"/i/{code}", headers=android)
    assert r.status_code == 200 and "Arjun invited you to WhoHas" in r.text
    assert f"https://play.google.com/store/apps/details?id=app.foundwith&amp;referrer=invite%3D{code}" in r.text
    assert "default-src 'none'" in r.headers["content-security-policy"]
    iphone = client.get(f"/i/{code}", headers={"user-agent": "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0)"})
    assert "Android for now" in iphone.text and "play.google.com" not in iphone.text
    bad = client.get("/i/not-a-code!", headers=android)
    assert bad.status_code == 200 and "referrer" not in bad.text and "You're invited" in bad.text

    join(client, RAVI, "Ravi Kumar", contacts=[ARJUN])
    r = client.post("/v1/me/invite/redeem", json={"code": code}, headers=as_user(RAVI))
    assert r.status_code == 200 and r.json() == {"redeemed": True}
    again = client.post("/v1/me/invite/redeem", json={"code": code}, headers=as_user(RAVI))
    assert again.status_code == 409 and error_code(again) == "invite_rejected"
    assert client.get("/v1/me/circle", headers=as_user(ARJUN)).json()["members"] == 1


def test_public_address_for_links_and_a_home_page(db, settings, identity):
    """Invite links use the public address (whohas-app.web.app) when one is set; / is a home page."""
    import dataclasses
    public = dataclasses.replace(settings, public_url="https://whohas-app.web.app")
    app = create_app(public, db=db, verifier=DevVerifier(), identity=identity, configure_logging=False)
    with TestClient(app) as c:
        join(c, ARJUN, "Arjun Rao", contacts=[RAVI])
        link = c.get("/v1/me/circle", headers=as_user(ARJUN)).json()["invite"]["link"]
        assert link.startswith("https://whohas-app.web.app/i/")
        home = c.get("/")
        assert home.status_code == 200 and "Unknown number calling?" in home.text
        assert 'href="/privacy"' in home.text and "found-with" not in home.text
