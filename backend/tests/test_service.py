"""Product rules, run against a real Postgres."""

import time

import pytest

from foundwith.auth import Identity
from foundwith.errors import (AccountExists, AccountSuspended, ApiError, ConsentRequired, InvalidNumber, LimitReached,
                              NameChangeTooSoon, NameNotAllowed, NotFound, NotRegistered, QuotaExceeded,
                              RateLimited, ReauthRequired, SyncRequired)
from foundwith.numbers import NumberHasher, to_ref
from foundwith.service import Service, retry_sign_in_deletions
from tests.conftest import People, make_settings

pytestmark = pytest.mark.anyio

ARJUN, RAVI, PRIYA, MEENA, KARTHIK = "+919845031207", "+919900148823", "+919741266019", "+919886120457", "+919008073314"
PLUMBER = "98451 72046"          # how people actually save numbers
PLUMBER_E164 = "+919845172046"
UNKNOWN = "7619155380"           # a caller nobody saved except Priya
CLINIC = "99860 31412"
LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"


def card_for(cards, ref):
    return next((c for c in cards["cards"] if c["ref"] == ref), None)


def names(holders):
    return [h["name"] for h in holders]


def service_with(db, identity, **overrides) -> Service:
    settings = make_settings(**overrides)
    return Service(db, NumberHasher(settings.number_pepper), settings, identity)


def one(db, sql):
    return db.conn.run(sql)[0][0]


# ---------------------------------------------------------------- matching

async def test_card_lists_everyone_who_saved_the_number(people, service):
    arjun, _, sync = await people.add(ARJUN, "Arjun Rao", contacts=[PLUMBER, RAVI], call_log=[UNKNOWN])
    await people.add(RAVI, "Ravi Kumar", contacts=[PLUMBER, ARJUN])
    await people.add(PRIYA, "Priya Sharma", contacts=[PLUMBER, UNKNOWN])        # no link to Arjun at all
    await people.add(MEENA, "Meena Iyer", contacts=[PLUMBER], sync=False)       # never turned on sync

    cards = await service.cards(arjun, None, None)
    plumber = card_for(cards, sync["refs"][PLUMBER_E164])
    assert plumber["in_contacts"] and not plumber["in_call_log"]
    assert plumber["total"] == 2 and plumber["more"] == 0
    assert names(plumber["known"]) == ["Ravi Kumar"]          # Arjun has Ravi saved
    assert names(plumber["others"]) == ["Priya Sharma"]       # a stranger, still shown by name
    assert plumber["known"][0]["contact_ref"] == sync["refs"][RAVI]

    unknown = card_for(cards, sync["refs"]["+917619155380"])
    assert unknown["in_call_log"] and not unknown["in_contacts"]
    assert names(unknown["others"]) == ["Priya Sharma"]

    # Ravi's number: nobody except Arjun has it, so no card
    assert card_for(cards, sync["refs"][RAVI]) is None
    assert cards["cards"][0]["ref"] == plumber["ref"]           # cards with people you know come first


async def test_call_logs_count_and_name_people(people, service):
    """v0.4: a number in someone's call log counts the same as one in their contacts."""
    await people.add(RAVI, "Ravi Kumar", call_log=[PLUMBER])                   # only called the plumber
    arjun, _, sync = await people.add(ARJUN, "Arjun Rao", contacts=[PLUMBER])
    plumber = card_for(await service.cards(arjun, None, None), sync["refs"][PLUMBER_E164])
    assert plumber["total"] == 1 and names(plumber["others"]) == ["Ravi Kumar"]
    priya, _, _ = await people.add(PRIYA, "Priya Sharma", contacts=[])
    result = await service.lookup(priya, PLUMBER, "search")
    assert sorted(names(result["others"])) == ["Arjun Rao", "Ravi Kumar"] and result["total"] == 2


async def test_with_visibility_known_call_log_holders_are_named_only_to_people_who_know_them(db, identity):
    service = service_with(db, identity, visibility="known")
    people = People(service)
    await people.add(RAVI, "Ravi Kumar", contacts=[ARJUN], call_log=[PLUMBER, CLINIC])
    await people.add(PRIYA, "Priya Sharma", contacts=[CLINIC])
    arjun, _, sync = await people.add(ARJUN, "Arjun Rao", contacts=[RAVI, PLUMBER, CLINIC])   # knows Ravi
    karthik, _, _ = await people.add(KARTHIK, "Karthik Reddy", contacts=[CLINIC])            # knows nobody

    cards = await service.cards(arjun, None, None)
    plumber = card_for(cards, sync["refs"][PLUMBER_E164])
    assert plumber["total"] == 1 and names(plumber["known"]) == ["Ravi Kumar"] and plumber["others"] == []
    clinic = card_for(cards, sync["refs"]["+919986031412"])
    assert clinic["total"] == 3 and names(clinic["known"]) == ["Ravi Kumar"] and clinic["more"] == 2
    found = await service.lookup(karthik, CLINIC, "search")
    assert found["total"] == 3 and found["known"] == [] and found["others"] == [] and found["more"] == 3


async def test_numbers_saved_in_indian_scripts_match(people, service):
    """A contact saved with Devanagari or Kannada digits made the whole sync fail in v0.1."""
    arjun, _, sync = await people.add(ARJUN, "Arjun Rao", contacts=["९८४५१ ७२०४६", "Mom", None, 121])
    assert sync["stats"]["numbers"] == 1 and PLUMBER_E164 in sync["refs"]
    await people.add(RAVI, "Ravi Kumar", contacts=["೯೮೪೫೧೭೨೦೪೬"])
    cards = await service.cards(arjun, None, None)
    assert names(cards["cards"][0]["others"]) == ["Ravi Kumar"]


async def test_stranger_can_search_any_number(people, service):
    await people.add(ARJUN, "Arjun Rao", contacts=[PLUMBER])
    await people.add(RAVI, "Ravi Kumar", contacts=[PLUMBER])
    karthik, _, _ = await people.add(KARTHIK, "Karthik Reddy", contacts=[])
    result = await service.lookup(karthik, "+91 98451-72046", "search")
    assert result["number"] == PLUMBER_E164
    assert sorted(names(result["others"])) == ["Arjun Rao", "Ravi Kumar"]
    assert result["quota"] == {"limit": 5, "left": 4}


async def test_lookup_of_your_own_number_shows_who_has_it(people, service):
    arjun, _, _ = await people.add(ARJUN, "Arjun Rao", contacts=[RAVI])
    await people.add(RAVI, "Ravi Kumar", contacts=[ARJUN])
    await people.add(PRIYA, "Priya Sharma", contacts=[ARJUN])
    result = await service.lookup(arjun, ARJUN, "search")
    assert names(result["known"]) == ["Ravi Kumar"]
    assert names(result["others"]) == ["Priya Sharma"]


async def test_total_is_capped_for_very_popular_numbers(db, identity):
    service = service_with(db, identity, count_cap=3, card_sample_size=2)
    people = People(service)
    for i in range(5):
        await people.add(f"+9198000000{i:02d}", f"Holder {LETTERS[i]}", contacts=[CLINIC])
    viewer, _, _ = await people.add(ARJUN, "Arjun Rao", contacts=[CLINIC])
    card = (await service.cards(viewer, None, None))["cards"][0]
    assert card["total"] == 3 and card["total_is_lower_bound"] is True
    assert len(card["others"]) == 2 and card["more"] == 1


async def test_sample_skips_people_you_know_and_holders_page(people, service):
    arjun, _, sync = await people.add(ARJUN, "Arjun Rao", contacts=[CLINIC, RAVI])
    await people.add(RAVI, "Ravi Kumar", contacts=[CLINIC])
    for i in range(6):
        await people.add(f"+9197000000{i:02d}", f"Person {LETTERS[i]}", contacts=[CLINIC])
    card = (await service.cards(arjun, None, None))["cards"][0]
    assert names(card["known"]) == ["Ravi Kumar"]
    assert "Ravi Kumar" not in names(card["others"]) and len(card["others"]) == 3
    assert card["total"] == 7 and card["more"] == 3

    seen, cursor, pages = [], None, 0
    while True:
        page = await service.holders(arjun, card["ref"], cursor, 3)
        assert page["list_capped"] is False
        seen += page["holders"]
        pages += 1
        cursor = page["next"]
        if cursor is None:
            break
    assert pages == 3 and len(seen) == 7
    assert [h for h in seen if h["known"]] == [{"id": card["known"][0]["id"], "name": "Ravi Kumar",
                                               "known": True, "contact_ref": sync["refs"][RAVI]}]


async def test_cards_page_in_a_stable_order(db, identity):
    service = service_with(db, identity, cards_page_default=2)
    people = People(service)
    numbers = [f"98450000{i:02d}" for i in range(7)]
    arjun, _, _ = await people.add(ARJUN, "Arjun Rao", contacts=numbers + [RAVI])
    await people.add(RAVI, "Ravi Kumar", contacts=numbers[:2])            # known to Arjun: these two come first
    await people.add(PRIYA, "Priya Sharma", contacts=numbers)
    await people.add(MEENA, "Meena Iyer", contacts=numbers[2:4])

    everything = (await service.cards(arjun, None, 100))["cards"]
    assert len(everything) == 7 and [len(c["known"]) for c in everything][:3] == [1, 1, 0]
    assert [c["total"] for c in everything] == [2, 2, 2, 2, 1, 1, 1]

    paged, cursor, pages = [], None, 0
    while True:
        page = await service.cards(arjun, cursor, None)
        assert page["total_cards"] == 7
        paged += page["cards"]
        pages += 1
        cursor = page["next"]
        if cursor is None:
            break
    assert pages == 4 and [c["ref"] for c in paged] == [c["ref"] for c in everything]
    for bad in ["x", "e30", "WzEsMiwzXQ"]:                               # junk, {}, [1,2,3]
        with pytest.raises(ApiError):
            await service.cards(arjun, bad, None)


# ---------------------------------------------------------------- who may see what

async def test_a_reference_alone_does_not_open_the_holder_list(people, service, db):
    arjun, _, sync = await people.add(ARJUN, "Arjun Rao", contacts=[PLUMBER])
    await people.add(RAVI, "Ravi Kumar", contacts=[PLUMBER])
    ref = sync["refs"][PLUMBER_E164]
    karthik, _, _ = await people.add(KARTHIK, "Karthik Reddy", contacts=[])

    with pytest.raises(NotFound):                                # a reference handed over by someone else
        await service.holders(karthik, ref, None, None)
    await service.lookup(karthik, PLUMBER, "call")               # a claimed incoming call is not enough either
    with pytest.raises(NotFound):
        await service.holders(karthik, ref, None, None)
    await service.lookup(karthik, PLUMBER, "search")             # a metered search is
    assert sorted(names((await service.holders(karthik, ref, None, None))["holders"])) == ["Arjun Rao", "Ravi Kumar"]

    db.conn.run("UPDATE lookup_log SET created_at = created_at - interval '25 hours'")
    with pytest.raises(NotFound):                                # and only for a day
        await service.holders(karthik, ref, None, None)
    assert (await service.holders(arjun, ref, None, None))["holders"]   # your own card always works

    log = db.conn.run("SELECT kind, holders_found FROM lookup_log ORDER BY id")[0]
    assert [(r["kind"], r["holders_found"]) for r in log] == [("call", 2), ("search", 2)]


async def test_only_the_first_names_of_a_very_popular_number_can_be_listed(db, identity):
    service = service_with(db, identity, holders_list_cap=4)
    people = People(service)
    for i in range(6):
        await people.add(f"+9198000000{i:02d}", f"Holder {LETTERS[i]}", contacts=[CLINIC])
    viewer, _, sync = await people.add(ARJUN, "Arjun Rao", contacts=[CLINIC])
    ref = sync["refs"]["+919986031412"]
    seen, cursor = [], None
    while True:
        page = await service.holders(viewer, ref, cursor, 3)
        assert page["list_capped"] is True
        seen += names(page["holders"])
        cursor = page["next"]
        if cursor is None:
            break
    assert seen == ["Holder A", "Holder B", "Holder C", "Holder D"]
    assert (await service.cards(viewer, None, None))["cards"][0]["total"] == 6   # the count is still honest


async def test_known_only_visibility_names_nobody_you_have_not_saved(db, identity):
    service = service_with(db, identity, visibility="known")
    people = People(service)
    arjun, _, sync = await people.add(ARJUN, "Arjun Rao", contacts=[PLUMBER, RAVI])
    await people.add(RAVI, "Ravi Kumar", contacts=[PLUMBER])
    await people.add(PRIYA, "Priya Sharma", contacts=[PLUMBER])
    card = (await service.cards(arjun, None, None))["cards"][0]
    assert names(card["known"]) == ["Ravi Kumar"] and card["others"] == []
    assert card["total"] == 2 and card["more"] == 1
    listed = await service.holders(arjun, card["ref"], None, None)
    assert names(listed["holders"]) == ["Ravi Kumar"] and listed["next"] is None

    karthik, _, _ = await people.add(KARTHIK, "Karthik Reddy", contacts=[])
    found = await service.lookup(karthik, PLUMBER, "search")
    assert found["total"] == 3 and found["known"] == [] and found["others"] == [] and found["more"] == 3
    assert (await service.holders(karthik, found["ref"], None, None))["holders"] == []


async def test_default_is_known_and_your_call_log_counts_as_knowing_someone(db, identity):
    from foundwith.config import Settings
    assert Settings.visibility == "known"
    service = service_with(db, identity, visibility="known")
    people = People(service)
    # Arjun never saved Ravi, but Ravi called him once. Priya is a stranger to Arjun.
    arjun, _, _ = await people.add(ARJUN, "Arjun Rao", contacts=[PLUMBER], call_log=[RAVI])
    await people.add(RAVI, "Ravi Kumar", contacts=[PLUMBER])
    await people.add(PRIYA, "Priya Sharma", contacts=[PLUMBER, RAVI])
    cards = await service.cards(arjun, None, None)
    plumber = next(c for c in cards["cards"] if c["name"] is None)
    assert names(plumber["known"]) == ["Ravi Kumar"] and plumber["others"] == []
    assert plumber["total"] == 2 and plumber["more"] == 1          # Priya is only a number

    # Ravi's own number is on Arjun's call log and Priya has it saved: a card, titled with
    # Ravi's Found With name because Arjun has no name saved for it. Priya stays a count.
    ravi_card = next(c for c in cards["cards"] if c["name"] == "Ravi Kumar")
    assert ravi_card["total"] == 1 and ravi_card["known"] == [] and ravi_card["more"] == 1


async def test_every_number_looks_the_same_everywhere(people, service):
    """v0.4: the owner's own name shows on calls, search and cards alike."""
    meena, _, _ = await people.add(MEENA, "Meena Iyer", contacts=[])
    await people.add(RAVI, "Ravi Kumar", contacts=[PLUMBER])
    call = await service.lookup(meena, RAVI, "call")
    assert call["name"] == "Ravi Kumar" and call["total"] == 0     # nobody else has Ravi's number
    assert call["on_found_with"] is True and call["owner_id"]
    search = await service.lookup(meena, RAVI, "search")
    assert search["name"] == "Ravi Kumar" and search["owner_id"] == call["owner_id"]
    plumber = await service.lookup(meena, PLUMBER, "call")
    assert plumber["name"] is None and plumber["on_found_with"] is False and plumber["owner_id"] is None


async def test_someone_without_a_name_yet_is_on_found_with_but_unnamed(people, service):
    meena, _, _ = await people.add(MEENA, "Meena Iyer", contacts=[])
    await people.add(RAVI, None, contacts=[PLUMBER])
    found = await service.lookup(meena, RAVI, "search")
    assert found["name"] is None and found["on_found_with"] is True


async def test_owner_name_left_out_when_blocked_opted_out_or_not_taking_part(people, service):
    meena, meena_ident, _ = await people.add(MEENA, "Meena Iyer", contacts=[])
    ravi, ravi_ident, _ = await people.add(RAVI, "Ravi Kumar", contacts=[])
    await people.add(PRIYA, "Priya Sharma", contacts=[], sync=False)
    assert (await service.lookup(meena, PRIYA, "call"))["name"] is None     # sync off: never agreed

    await service.block(ravi, str(meena["public_id"]))                     # blocked either way
    assert (await service.lookup(meena, RAVI, "call"))["name"] is None
    await service.unblock(ravi, (await service.blocks(ravi))[0]["id"])
    assert (await service.lookup(meena, RAVI, "call"))["name"] == "Ravi Kumar"

    await service.opt_out(ravi_ident, delete_account=True)                  # Ravi took his number out
    after = await service.lookup(meena, RAVI, "call")
    assert after["name"] is None and after["on_found_with"] is False


async def test_people_on_old_terms_are_counted_but_not_named(db, identity):
    service = service_with(db, identity, visibility="known")
    people = People(service)
    arjun, _, _ = await people.add(ARJUN, "Arjun Rao", contacts=[PLUMBER, RAVI])
    await people.add(RAVI, "Ravi Kumar", contacts=[PLUMBER])
    db.conn.run("UPDATE users SET consent_version = '2000-01-01' WHERE display_name = 'Ravi Kumar'")
    plumber = (await service.cards(arjun, None, None))["cards"][0]
    assert plumber["total"] == 1 and plumber["known"] == []          # still counted, no longer named
    assert (await service.lookup(arjun, RAVI, "call"))["name"] is None


# ---------------------------------------------------------------- your number, circle and invites

async def test_your_own_number_card_names_the_people_you_know(people, service):
    arjun, _, _ = await people.add(ARJUN, "Arjun Rao", contacts=[RAVI], call_log=[PRIYA])
    await people.add(RAVI, "Ravi Kumar", contacts=[ARJUN])
    await people.add(PRIYA, "Priya Sharma", contacts=[ARJUN])
    await people.add(MEENA, "Meena Iyer", contacts=[ARJUN])                 # a stranger to Arjun
    cards = await service.cards(arjun, None, None)
    mine = cards["mine"]
    assert mine["total"] == 3
    assert sorted(names(mine["known"])) == ["Priya Sharma", "Ravi Kumar"]   # contacts and call log
    listed = await service.holders(arjun, mine["ref"], None, None)          # and the list opens
    assert "Ravi Kumar" in names(listed["holders"])
    assert (await service.cards(arjun, cards["next"], None) if cards["next"] else {"mine": None})["mine"] is None


async def test_circle_counts_contacts_on_found_with(people, service):
    arjun, _, _ = await people.add(ARJUN, "Arjun Rao", contacts=[RAVI, PRIYA, PLUMBER], call_log=[MEENA])
    await people.add(RAVI, "Ravi Kumar", contacts=[])
    await people.add(PRIYA, "Priya Sharma", contacts=[], sync=False)        # not taking part
    await people.add(MEENA, "Meena Iyer", contacts=[])
    circle = await service.circle(arjun, "https://api.example/")
    assert circle["members"] == 2 and len(circle["member_refs"]) == 2       # Ravi, and Meena from calls
    assert circle["goal"] == 20
    code = circle["invite"]["code"]
    assert len(code) == 8 and circle["invite"]["link"] == f"https://api.example/i/{code}"
    assert (await service.circle(arjun, "https://api.example"))["invite"]["code"] == code   # stable


async def test_invite_is_recorded_once_per_number(db, people, service):
    arjun, _, _ = await people.add(ARJUN, "Arjun Rao", contacts=[])
    code = (await service.circle(arjun, "https://x"))["invite"]["code"]
    ravi, ravi_ident, _ = await people.add(RAVI, "Ravi Kumar", contacts=[])
    base = service.settings.search_daily_limit

    with pytest.raises(ApiError) as bad:
        await service.redeem_invite(ravi, "ZZZZZZZZ")
    assert bad.value.code == "invite_not_found"
    done = await service.redeem_invite(ravi, code.lower())                  # codes ignore case
    assert done == {"redeemed": True}
    invite = (await service.circle(arjun, "https://x"))["invite"]
    assert invite["joined"] == 1 and invite["joined_refs"] == [to_ref(service.hasher.digest(RAVI))]
    # No reward: limits stay as they are.
    assert (await service.profile(ravi, ravi_ident))["quota"]["search"]["limit"] == base
    assert (await service.lookup(arjun, PLUMBER, "search"))["quota"]["limit"] == base

    with pytest.raises(ApiError) as again:
        await service.redeem_invite(ravi, code)
    assert again.value.code == "invite_rejected" and again.value.details["reason"] == "already_used"
    with pytest.raises(ApiError) as own:
        await service.redeem_invite(arjun, code)
    assert own.value.details["reason"] == "own_invite"

    # Deleting and re-creating the account doesn't earn it again: it's kept by number.
    await service.delete_account(ravi)
    ravi2 = await service.register(People.ident(RAVI, "uid-ravi-2"), "Ravi Kumar")
    with pytest.raises(ApiError) as recreated:
        await service.redeem_invite(ravi2, code)
    assert recreated.value.details["reason"] == "already_used"


async def test_invite_only_counts_in_the_first_week(db, people, service):
    arjun, _, _ = await people.add(ARJUN, "Arjun Rao", contacts=[])
    code = (await service.circle(arjun, "https://x"))["invite"]["code"]
    await people.add(MEENA, "Meena Iyer", contacts=[])
    db.conn.run("UPDATE users SET created_at = now() - interval '8 days' WHERE display_name = 'Meena Iyer'")
    meena = await service.current_user(People.ident(MEENA))
    with pytest.raises(ApiError) as late:
        await service.redeem_invite(meena, code)
    assert late.value.details["reason"] == "too_late"


async def test_inviter_sees_everyone_who_joined(db, people, service):
    arjun, _, _ = await people.add(ARJUN, "Arjun Rao", contacts=[])
    code = (await service.circle(arjun, "https://x"))["invite"]["code"]
    for i in range(7):
        friend = await service.register(People.ident(f"+91900000{i:04d}"), f"Friend {LETTERS[i]}")
        await service.redeem_invite(friend, code)
    invite = (await service.circle(arjun, "https://x"))["invite"]
    assert invite["joined"] == 7 and len(set(invite["joined_refs"])) == 7


# ---------------------------------------------------------------- hiding, sync on and off

async def test_hidden_number_is_out_of_found_with_both_ways(people, service):
    arjun, _, _ = await people.add(ARJUN, "Arjun Rao", contacts=[PLUMBER])
    ravi, ravi_id, ravi_sync = await people.add(RAVI, "Ravi Kumar", contacts=[PLUMBER])
    ref = ravi_sync["refs"][PLUMBER_E164]

    await service.hide(ravi, ref)
    assert (await service.cards(arjun, None, None))["cards"] == []   # Ravi no longer counts as a holder
    assert (await service.cards(ravi, None, None))["cards"] == []    # and Ravi gets no card for it
    assert await service.hidden(ravi) == [ref]
    with pytest.raises(NotFound):
        await service.holders(ravi, ref, None, None)

    await service.sync_numbers(ravi, [PLUMBER], [])                   # the choice survives a re-sync
    assert (await service.cards(arjun, None, None))["cards"] == []
    call = await service.lookup(ravi, PLUMBER, "call")
    assert call["hidden"] is True and call["total"] == 0

    await service.unhide(ravi, ref)
    assert names((await service.cards(arjun, None, None))["cards"][0]["others"]) == ["Ravi Kumar"]
    assert await service.hidden(ravi) == []


async def test_hidden_choice_kept_after_number_leaves_the_phone(people, service):
    ravi, _, sync = await people.add(RAVI, "Ravi Kumar", contacts=[PLUMBER])
    ref = sync["refs"][PLUMBER_E164]
    await service.hide(ravi, ref)
    await service.sync_numbers(ravi, [], [])
    assert await service.hidden(ravi) == [ref]
    await service.sync_numbers(ravi, [PLUMBER], [])
    arjun, _, _ = await people.add(ARJUN, "Arjun Rao", contacts=[PLUMBER])
    assert (await service.cards(arjun, None, None))["cards"] == []


async def test_hidden_numbers_are_capped(db, identity):
    service = service_with(db, identity, max_hidden_numbers=2)
    people = People(service)
    arjun, _, sync = await people.add(ARJUN, "Arjun Rao", contacts=[PLUMBER, CLINIC, UNKNOWN])
    refs = list(sync["refs"].values())
    await service.hide(arjun, refs[0])
    await service.hide(arjun, refs[1])
    await service.hide(arjun, refs[1])                                # hiding again is fine
    with pytest.raises(LimitReached):
        await service.hide(arjun, refs[2])


async def test_turning_sync_off_removes_you_from_matching(people, service):
    arjun, _, _ = await people.add(ARJUN, "Arjun Rao", contacts=[PLUMBER])
    ravi, ravi_id, _ = await people.add(RAVI, "Ravi Kumar", contacts=[PLUMBER, CLINIC])
    ravi = await service.set_sync(ravi, False, None)
    assert (await service.cards(arjun, None, None))["cards"] == []
    # Ravi can still use the app, by counts: his own number, and search.
    page = await service.cards(ravi, None, None)
    assert page["cards"] == [] and page["taking_part"] is False and page["mine"]["ref"]
    assert (await service.lookup(ravi, PLUMBER, "search"))["total"] == 1
    with pytest.raises(SyncRequired):
        await service.sync_numbers(ravi, [PLUMBER], [])


async def test_before_allowing_anything_you_see_your_own_count(people, service):
    """The first Home screen: "Your number · Found with 2 people", before any contact is sent."""
    arjun, _, _ = await people.add(ARJUN, None, sync=False)
    await people.add(RAVI, "Ravi Kumar", contacts=[ARJUN])
    await people.add(PRIYA, "Priya Sharma", call_log=[ARJUN])
    page = await service.cards(arjun, None, None)
    assert page["taking_part"] is False and page["cards"] == []
    assert page["mine"]["total"] == 2 and page["mine"]["known"] == []     # counts only: Arjun has sent nothing
    found = await service.lookup(arjun, RAVI, "search")
    assert found["total"] == 0 and found["name"] == "Ravi Kumar"


async def test_consent_is_required_to_turn_on_sync(people, service):
    arjun, _, _ = await people.add(ARJUN, "Arjun Rao", sync=False)
    with pytest.raises(ConsentRequired):
        await service.set_sync(arjun, True, None)
    with pytest.raises(ConsentRequired):
        await service.set_sync(arjun, True, "2020-01-01")
    user = await service.set_sync(arjun, True, service.settings.consent_version)
    assert user["sync_enabled"] and user["consent_at"] is not None


async def test_changed_terms_must_be_accepted_before_the_next_sync(db, identity):
    old = service_with(db, identity)
    arjun, ident, _ = await People(old).add(ARJUN, "Arjun Rao", contacts=[PLUMBER])
    new = service_with(db, identity, consent_version="2027-01-01")
    with pytest.raises(ConsentRequired) as err:
        await new.sync_numbers(arjun, [PLUMBER, CLINIC], [])
    assert err.value.details == {"current_consent_version": "2027-01-01"}
    assert (await new.profile(arjun, ident))["sync"]["consent_current"] is False
    arjun = await new.set_sync(arjun, True, "2027-01-01")
    assert (await new.sync_numbers(arjun, [PLUMBER, CLINIC], []))["stats"]["added"] == 1


# ---------------------------------------------------------------- the address book and its allowance

async def test_each_sync_replaces_the_previous_snapshot(people, service):
    arjun, ident, _ = await people.add(ARJUN, "Arjun Rao", contacts=[PLUMBER, CLINIC])
    await people.add(RAVI, "Ravi Kumar", contacts=[PLUMBER, CLINIC])
    assert (await service.cards(arjun, None, None))["total_cards"] == 2
    result = await service.sync_numbers(arjun, [PLUMBER], [])
    assert result["stats"]["removed"] == 1
    assert (await service.cards(arjun, None, None))["total_cards"] == 1


async def test_sync_skips_unmatchable_entries_and_own_number(people, service):
    arjun, _, result = await people.add(ARJUN, "Arjun Rao", contacts=[PLUMBER, "121", "Mom", ARJUN, "0" + PLUMBER])
    assert result["stats"] == {"numbers": 1, "added": 1, "removed": 0, "skipped": 2, "deferred": 0}
    assert set(result["refs"]) == {PLUMBER_E164}


async def test_new_numbers_come_out_of_an_allowance_that_refills(db, identity):
    """Putting numbers into contacts reveals who else has them, so it can't be unlimited.
    Here: 100 at once, then 24 a day (one an hour), 150 stored at most."""
    service = service_with(db, identity, new_numbers_burst=100, new_numbers_per_day=24, max_numbers_per_user=150)
    people = People(service)
    book = [f"9{n:09d}" for n in range(1, 201)]

    arjun, _, first = await people.add(ARJUN, "Arjun Rao", contacts=book[:120])
    assert first["stats"] == {"numbers": 100, "added": 100, "removed": 0, "skipped": 0, "deferred": 20}
    assert len(first["refs"]) == 100 and "+91" + book[0] in first["refs"]
    assert "+91" + book[119] not in first["refs"]                      # deferred numbers get no reference

    again = await service.sync_numbers(arjun, book[:120], [])          # retrying changes nothing and costs nothing
    assert again["stats"] == {"numbers": 100, "added": 0, "removed": 0, "skipped": 0, "deferred": 20}

    db.conn.run("UPDATE users SET admit_tokens_at = admit_tokens_at - interval '12 hours'")
    later = await service.sync_numbers(arjun, book[:120], [])          # half a day refills 12
    assert later["stats"]["added"] == 12 and later["stats"]["deferred"] == 8

    db.conn.run("UPDATE users SET admit_tokens = 100")
    full = await service.sync_numbers(arjun, book, [])                 # 112 kept, room for 38 more
    assert full["stats"]["numbers"] == 150 and full["stats"]["added"] == 38 and full["stats"]["deferred"] == 50

    db.conn.run("UPDATE users SET admit_tokens = 0, admit_tokens_at = now()")
    kept = await service.sync_numbers(arjun, book[:150], [])           # numbers already stored are free
    assert kept["stats"]["numbers"] == 150 and kept["stats"]["removed"] == 0 and kept["stats"]["added"] == 0

    db.conn.run("UPDATE users SET admit_tokens_at = now() - interval '400 days'")
    refilled = await service.sync_numbers(arjun, [], [])               # the allowance never exceeds its size
    assert refilled["stats"]["removed"] == 150
    assert one(db, "SELECT admit_tokens FROM users")["admit_tokens"] == 100


async def test_switching_sync_off_and_on_does_not_reset_the_allowance(db, identity):
    service = service_with(db, identity, new_numbers_burst=10, new_numbers_per_day=24)
    people = People(service)
    arjun, _, first = await people.add(ARJUN, "Arjun Rao", contacts=[f"9{n:09d}" for n in range(1, 11)])
    assert first["stats"]["added"] == 10
    arjun = await service.set_sync(arjun, False, None)
    arjun = await service.set_sync(arjun, True, service.settings.consent_version)
    fresh = await service.sync_numbers(arjun, [f"8{n:09d}" for n in range(1, 11)], [])
    assert fresh["stats"] == {"numbers": 0, "added": 0, "removed": 0, "skipped": 0, "deferred": 10}


# ---------------------------------------------------------------- quotas

async def test_search_needs_a_mobile_number_and_has_a_daily_quota(people, service, db):
    arjun, _, _ = await people.add(ARJUN, "Arjun Rao", contacts=[PLUMBER])
    with pytest.raises(InvalidNumber):
        await service.lookup(arjun, "080 4123 556", "search")
    with pytest.raises(InvalidNumber):
        await service.lookup(arjun, "011 2345 6789", "search")        # landline: calls only
    landline = await service.lookup(arjun, "011 2345 6789", "call")
    assert landline["number"] == "+911123456789"

    for left in range(4, -1, -1):
        assert (await service.lookup(arjun, PLUMBER, "search"))["quota"]["left"] == left
    with pytest.raises(QuotaExceeded):
        await service.lookup(arjun, PLUMBER, "search")
    assert (await service.lookup(arjun, PLUMBER, "call"))["quota"]["left"] == 8   # separate quota

    db.conn.run("UPDATE lookup_quota SET day = day - 1")                          # a new day
    assert (await service.lookup(arjun, PLUMBER, "search"))["quota"]["left"] == 4


# ---------------------------------------------------------------- opt-out

async def test_anyone_can_take_their_own_number_out_of_found_with(people, service, db):
    """The plumber never installed the app. He verifies his number once and is gone for everybody."""
    arjun, _, sync = await people.add(ARJUN, "Arjun Rao", contacts=[PLUMBER, CLINIC])
    ravi, _, _ = await people.add(RAVI, "Ravi Kumar", contacts=[PLUMBER, CLINIC])
    priya, _, _ = await people.add(PRIYA, "Priya Sharma", call_log=[PLUMBER])    # only ever called him
    ref = sync["refs"][PLUMBER_E164]
    plumber = People.ident(PLUMBER_E164)
    stored = f"SELECT count(*) AS n FROM user_numbers WHERE number_hmac = decode('{_hex(service, PLUMBER_E164)}', 'hex')"
    assert one(db, stored)["n"] == 3

    assert await service.opt_out_status(plumber) == {"opted_out": False, "has_account": False}
    assert await service.opt_out(plumber) == {"opted_out": True, "account_deleted": False}
    assert await service.opt_out(plumber) == {"opted_out": True, "account_deleted": False}   # twice is fine
    assert one(db, stored)["n"] == 0                                         # every copy, call logs too

    cards = await service.cards(arjun, None, None)
    assert [c["ref"] for c in cards["cards"]] == [sync["refs"]["+919986031412"]]   # only the clinic is left
    assert (await service.cards(priya, None, None))["cards"] == []
    found = await service.lookup(ravi, PLUMBER, "search")
    assert found["total"] == 0 and found["known"] == [] and found["others"] == []
    assert (await service.holders(ravi, found["ref"], None, None))["holders"] == []
    assert (await service.lookup(arjun, PLUMBER, "call"))["total"] == 0
    with pytest.raises(NotFound):
        await service.holders(arjun, ref, None, None)

    resync = await service.sync_numbers(arjun, [PLUMBER, CLINIC], [])         # the next sync can't bring it back
    assert PLUMBER_E164 not in resync["refs"]
    assert resync["stats"] == {"numbers": 1, "added": 0, "removed": 0, "skipped": 1, "deferred": 0}
    again = await service.sync_numbers(priya, [], [PLUMBER])                  # nor a call log
    assert again["stats"] == {"numbers": 0, "added": 0, "removed": 0, "skipped": 1, "deferred": 0}
    assert one(db, stored)["n"] == 0

    assert await service.opt_in(plumber) == {"opted_out": False}              # he can change his mind
    await service.sync_numbers(arjun, [PLUMBER, CLINIC], [])
    await service.sync_numbers(ravi, [PLUMBER, CLINIC], [])
    assert (await service.cards(arjun, None, None))["total_cards"] == 2


def _hex(service, e164):
    return service.hasher.digest(e164).hex()


async def test_taking_out_a_members_number_deletes_the_account_after_a_warning(people, service, identity):
    arjun, arjun_ident, _ = await people.add(ARJUN, "Arjun Rao", contacts=[RAVI])
    ravi, _, _ = await people.add(RAVI, "Ravi Kumar", contacts=[ARJUN])
    await people.add(PRIYA, "Priya Sharma", contacts=[ARJUN])
    assert (await service.lookup(ravi, ARJUN, "search"))["total"] == 1
    assert (await service.opt_out_status(arjun_ident))["has_account"] is True
    with pytest.raises(AccountExists):
        await service.opt_out(arjun_ident)                                   # the page must warn first
    assert await service.opt_out(arjun_ident, delete_account=True) == {"opted_out": True, "account_deleted": True}
    with pytest.raises(NotRegistered):
        await service.current_user(arjun_ident)
    assert identity.deleted == [arjun["auth_uid"]]
    assert (await service.lookup(ravi, ARJUN, "search"))["total"] == 0


# ---------------------------------------------------------------- accounts

async def test_registering_twice_changes_nothing(people, service):
    arjun, ident, _ = await people.add(ARJUN, "Arjun Rao", sync=False)
    again = await service.register(ident, "Somebody Else")
    assert again["id"] == arjun["id"] and again["display_name"] == "Arjun Rao"


async def test_names_must_look_like_names(people, service):
    for bad in ["A", "SBI Bank KYC", "Customer Care", "Police Inspector Rao", "Arjun 9845", "Arjun 😀",
                "s.b.i", "www.loans.in", "cheaploans.com", "Found With Team", "x" * 41, "पुलिस अधिकारी"]:
        with pytest.raises(NameNotAllowed):
            await service.register(People.ident(ARJUN), bad)
    fine_names = ["Arjun Rao", "D'Souza", "Bankim Chandra", "Dr. Kavitha S.", "K.S.Rao", "S.Kumar",
                  "ಅರ್ಜುನ್ ರಾವ್", "अर्जुन राव", "Anne-Marie"]
    for i, fine in enumerate(fine_names):
        ident = People.ident(f"+91980000{i:04d}")
        assert (await service.register(ident, f"  {fine} "))["display_name"] == fine


async def test_name_changes_are_spaced_out(people, service, db):
    arjun, _, _ = await people.add(ARJUN, "Arjun Rao", sync=False)
    arjun = await service.rename(arjun, "Arjun Rao")                          # no change, no clock
    arjun = await service.rename(arjun, "Arjun R Rao")                        # the first change is free
    assert arjun["display_name"] == "Arjun R Rao"
    with pytest.raises(NameChangeTooSoon) as err:
        await service.rename(arjun, "Someone Else")
    assert err.value.details == {"days_left": 14}
    with pytest.raises(NameNotAllowed):
        await service.rename(arjun, "Bank Manager")
    db.conn.run("UPDATE users SET name_changed_at = now() - interval '15 days'")
    assert (await service.rename(arjun, "Arjun Rao"))["display_name"] == "Arjun Rao"


async def test_recycled_number_replaces_the_old_account(people, service):
    old, old_ident, _ = await people.add(ARJUN, "Old Owner", contacts=[PLUMBER], uid="old-uid")
    new_ident = Identity(uid="new-uid", phone=ARJUN, auth_time=time.time() - 5)
    await service.register(new_ident, "New Owner")
    with pytest.raises(NotRegistered):
        await service.current_user(old_ident)
    priya, _, _ = await people.add(PRIYA, "Priya Sharma", contacts=[])
    assert (await service.lookup(priya, PLUMBER, "search"))["total"] == 0  # the old address book is gone


async def test_deleting_an_account_removes_it_everywhere(people, service, identity, db):
    arjun, arjun_ident, _ = await people.add(ARJUN, "Arjun Rao", contacts=[PLUMBER])
    ravi, _, _ = await people.add(RAVI, "Ravi Kumar", contacts=[PLUMBER])
    identity.fail = True
    result = await service.delete_account(ravi)
    assert result == {"deleted": True, "sign_in_account_deleted": False}
    assert (await service.cards(arjun, None, None))["cards"] == []
    pending = db.conn.run("SELECT auth_uid, attempts, done_at FROM auth_deletions")[0]
    assert pending[0]["attempts"] == 1 and pending[0]["done_at"] is None

    identity.fail = False
    assert await retry_sign_in_deletions(db, identity) == (1, 0)
    assert identity.deleted == ["uid-919900148823"]
    assert one(db, "SELECT count(*) AS n FROM user_numbers")["n"] == 1   # only Arjun's row left


async def test_deleting_and_signing_up_again_does_not_reset_limits(db, identity):
    service = service_with(db, identity, new_numbers_burst=10, new_numbers_per_day=24)
    people = People(service)
    await people.add(RAVI, "Ravi Kumar", contacts=[PLUMBER])
    arjun, ident, _ = await people.add(ARJUN, "Arjun Rao", contacts=[f"9{n:09d}" for n in range(1, 11)])
    for _ in range(3):
        await service.lookup(arjun, PLUMBER, "search")
    await service.delete_account(arjun)
    assert one(db, "SELECT count(*) AS n FROM users")["n"] == 1

    with pytest.raises(ReauthRequired):                       # the old sign-in can't re-create the account
        await service.register(ident, "Arjun Rao")
    assert one(db, "SELECT count(*) AS n FROM users")["n"] == 1

    fresh = Identity(uid="uid-after-new-otp", phone=ARJUN, auth_time=time.time() + 2)
    arjun = await service.register(fresh, "Arjun Rao")
    assert (await service.profile(arjun, fresh))["quota"]["search"] == {"limit": 5, "left": 2}
    arjun = await service.set_sync(arjun, True, service.settings.consent_version)
    again = await service.sync_numbers(arjun, [f"8{n:09d}" for n in range(1, 11)], [])
    assert again["stats"]["added"] == 0 and again["stats"]["deferred"] == 10
    assert one(db, "SELECT count(*) AS n FROM account_tombstones")["n"] == 0


async def test_signing_in_again_cancels_a_pending_sign_in_deletion(people, service, identity, db):
    """If deleting the sign-in account failed and the person comes back with the same sign-in account,
    the nightly retry must not delete it from under them."""
    arjun, ident, _ = await people.add(ARJUN, "Arjun Rao", contacts=[PLUMBER])
    identity.fail = True
    await service.delete_account(arjun)
    back = Identity(uid=ident.uid, phone=ARJUN, auth_time=time.time() + 2)
    await service.register(back, "Arjun Rao")
    identity.fail = False
    assert await retry_sign_in_deletions(db, identity) == (0, 0)
    assert identity.deleted == []
    assert (await service.current_user(back))["display_name"] == "Arjun Rao"


async def test_last_seen_is_refreshed_by_use(people, service, db):
    arjun, ident, _ = await people.add(ARJUN, "Arjun Rao", sync=False)
    db.conn.run("UPDATE users SET last_seen_at = now() - interval '40 days'")
    await service.current_user(ident)
    assert one(db, "SELECT now() - last_seen_at < interval '1 minute' AS fresh FROM users")["fresh"] is True


# ---------------------------------------------------------------- reports, blocks, suspension

async def test_reporting_a_name(people, service, db):
    arjun, _, _ = await people.add(ARJUN, "Arjun Rao", contacts=[PLUMBER])
    ravi, _, _ = await people.add(RAVI, "Ravi Kumar", contacts=[PLUMBER])
    await service.report(arjun, ravi["public_id"], "impersonation", "  Says he is from my bank ")
    row = one(db, "SELECT reporter_id, reported_id, reported_name, reason, note FROM reports")
    assert row == {"reporter_id": arjun["id"], "reported_id": ravi["id"], "reported_name": "Ravi Kumar",
                   "reason": "impersonation", "note": "Says he is from my bank"}
    for missing in [arjun["public_id"], "00000000-0000-4000-8000-000000000000", "not-an-id"]:
        with pytest.raises(NotFound):
            await service.report(arjun, missing, "other", None)
    await service.delete_account(ravi)                                   # the report outlives the account
    assert one(db, "SELECT reported_id, reported_name FROM reports") == {"reported_id": None, "reported_name": "Ravi Kumar"}


async def test_reports_are_rate_limited(db, identity):
    service = service_with(db, identity, reports_daily_limit=2)
    people = People(service)
    arjun, _, _ = await people.add(ARJUN, "Arjun Rao")
    ravi, _, _ = await people.add(RAVI, "Ravi Kumar")
    await service.report(arjun, ravi["public_id"], "other", None)
    await service.report(arjun, ravi["public_id"], "other", None)
    with pytest.raises(RateLimited):
        await service.report(arjun, ravi["public_id"], "other", None)


async def test_blocking_hides_two_people_from_each_other(people, service):
    arjun, _, _ = await people.add(ARJUN, "Arjun Rao", contacts=[PLUMBER, RAVI])
    ravi, _, _ = await people.add(RAVI, "Ravi Kumar", contacts=[PLUMBER, ARJUN])
    priya, _, _ = await people.add(PRIYA, "Priya Sharma", contacts=[PLUMBER])
    assert (await service.cards(arjun, None, None))["cards"][0]["total"] == 2

    block = await service.block(arjun, ravi["public_id"])
    assert block["name"] == "Ravi Kumar"
    assert await service.block(arjun, ravi["public_id"]) == block            # blocking twice is one block
    mine = (await service.cards(arjun, None, None))["cards"][0]
    assert mine["total"] == 1 and mine["known"] == [] and names(mine["others"]) == ["Priya Sharma"]
    theirs = (await service.cards(ravi, None, None))["cards"][0]              # and Ravi no longer sees Arjun
    assert theirs["total"] == 1 and names(theirs["others"]) == ["Priya Sharma"]
    found = await service.lookup(ravi, PLUMBER, "search")
    assert names(found["known"]) + names(found["others"]) == ["Priya Sharma"]
    assert names((await service.holders(ravi, found["ref"], None, None))["holders"]) == ["Priya Sharma"]
    assert (await service.cards(priya, None, None))["cards"][0]["total"] == 2  # nobody else is affected

    assert await service.blocks(arjun) == [{"id": block["id"], "name": "Ravi Kumar", "ref": to_ref(ravi["phone_hmac"])}]
    with pytest.raises(NotFound):
        await service.unblock(ravi, block["id"])                             # only the blocker can undo it
    await service.unblock(arjun, block["id"])
    assert (await service.cards(arjun, None, None))["cards"][0]["total"] == 2


async def test_a_block_survives_the_other_person_starting_over(people, service):
    arjun, _, _ = await people.add(ARJUN, "Arjun Rao", contacts=[PLUMBER])
    ravi, _, _ = await people.add(RAVI, "Ravi Kumar", contacts=[PLUMBER])
    await service.block(arjun, ravi["public_id"])
    await service.delete_account(ravi)
    assert [(b["id"], b["name"]) for b in await service.blocks(arjun)] == [(1, None)]
    back = Identity(uid="ravi-again", phone=RAVI, auth_time=time.time() + 2)
    ravi = await service.register(back, "Ravi K")
    ravi = await service.set_sync(ravi, True, service.settings.consent_version)
    await service.sync_numbers(ravi, [PLUMBER], [])
    assert (await service.cards(arjun, None, None))["cards"] == []
    assert (await service.cards(ravi, None, None))["cards"] == []


async def test_suspension(people, service, db):
    from foundwith import admin

    arjun, _, _ = await people.add(ARJUN, "Arjun Rao", contacts=[PLUMBER])
    ravi, ravi_ident, _ = await people.add(RAVI, "Ravi Kumar", contacts=[PLUMBER])
    await service.report(arjun, ravi["public_id"], "impersonation", None)
    reports = await admin.list_reports(db)
    assert len(reports) == 1 and reports[0]["user_id"] == ravi["public_id"] and reports[0]["name_now"] == "Ravi Kumar"

    done = await admin.run(db, ["suspend-report", str(reports[0]["report_id"]), "pretending", "to", "be", "a", "bank"])
    assert done == {"suspended_from_report": reports[0]["report_id"], "account_existed": True, "reports_closed": 1}
    assert await admin.list_reports(db) == []
    assert (await service.cards(arjun, None, None))["cards"] == []        # gone from every card at once
    with pytest.raises(AccountSuspended):
        await service.current_user(ravi_ident)
    suspended = await service.current_user(ravi_ident, allow_suspended=True)   # can still see status and delete
    assert (await service.profile(suspended, ravi_ident))["suspended"] is True

    await service.delete_account(suspended)                               # starting over doesn't clear it
    back = Identity(uid="ravi-again", phone=RAVI, auth_time=time.time() + 2)
    again = await service.register(back, "Ravi Kumar")
    assert again["suspended_at"] is not None
    with pytest.raises(AccountSuspended):
        await service.current_user(back)

    await admin.run(db, ["unsuspend", again["public_id"]])
    assert (await service.current_user(back))["suspended_at"] is None
    with pytest.raises(admin.AdminError):
        await admin.run(db, ["suspend", "not-an-id", "x"])
    with pytest.raises(admin.AdminError):
        await admin.run(db, ["frobnicate"])


async def test_suspending_from_a_report_after_the_account_was_deleted(people, service, db):
    from foundwith import admin

    arjun, _, _ = await people.add(ARJUN, "Arjun Rao")
    ravi, _, _ = await people.add(RAVI, "Ravi Kumar")
    await service.report(arjun, ravi["public_id"], "harassment", None)
    await service.delete_account(ravi)
    report_id = one(db, "SELECT id FROM reports")["id"]
    done = await admin.suspend_from_report(db, report_id, "harassment")
    assert done["account_existed"] is False and done["reports_closed"] == 1
    back = Identity(uid="ravi-again", phone=RAVI, auth_time=time.time() + 2)
    assert (await service.register(back, "Ravi Kumar"))["suspended_at"] is not None
