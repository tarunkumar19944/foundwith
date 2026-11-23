"""The real-time paths: stored holder counts, sending changes instead of the whole address book,
and retries when two syncs collide."""

import pytest

from foundwith import admin, maintenance
from foundwith.errors import FullSyncRequired, SyncRequired
from tests.conftest import People
from tests.pglibpq import PgError
from tests.test_service import (ARJUN, CLINIC, KARTHIK, LETTERS, MEENA, PLUMBER, PLUMBER_E164, PRIYA, RAVI,
                                card_for, names, service_with)

pytestmark = pytest.mark.anyio

DRIVER = "70221 34567"
DRIVER_E164 = "+917022134567"


def assert_counts_exact(db):
    """The stored counts must equal a recount of the address books, number by number."""
    stored = {bytes(r["number_hmac"]): r["holders"] for r in db.conn.run(
        "SELECT number_hmac, holders FROM number_stats WHERE holders <> 0")[0]}
    actual = {bytes(r["number_hmac"]): r["n"] for r in db.conn.run(
        """SELECT number_hmac, count(*)::int AS n FROM user_numbers
           WHERE (in_contacts OR in_call_log) AND NOT hidden GROUP BY number_hmac""")[0]}
    assert stored == actual


def holders_of(db, service, e164):
    digest = service.hasher.digest(e164)
    rows = db.conn.run(f"SELECT holders FROM number_stats WHERE number_hmac = '\\x{digest.hex()}'::bytea")[0]
    return rows[0]["holders"] if rows else 0


async def test_counts_follow_every_way_a_number_can_come_and_go(people, service, db, identity):
    arjun, arjun_id, _ = await people.add(ARJUN, "Arjun Rao", contacts=[PLUMBER, CLINIC, RAVI])
    ravi, _, ravi_sync = await people.add(RAVI, "Ravi Kumar", contacts=[PLUMBER, CLINIC], call_log=[DRIVER])
    priya, priya_id, _ = await people.add(PRIYA, "Priya Sharma", contacts=[PLUMBER], call_log=[CLINIC])
    meena, _, _ = await people.add(MEENA, "Meena Iyer", contacts=[PLUMBER, DRIVER])
    assert_counts_exact(db)
    assert holders_of(db, service, PLUMBER_E164) == 4
    assert holders_of(db, service, DRIVER_E164) == 2           # Ravi only called the driver: still counts

    await service.sync_numbers(ravi, [CLINIC], [PLUMBER])      # plumber moves to Ravi's call log: still his
    assert holders_of(db, service, PLUMBER_E164) == 4
    await service.sync_numbers(ravi, [CLINIC], [])             # and now gone from Ravi's phone
    assert holders_of(db, service, PLUMBER_E164) == 3
    await service.hide(arjun, ravi_sync["refs"][PLUMBER_E164])
    assert holders_of(db, service, PLUMBER_E164) == 2
    await service.unhide(arjun, ravi_sync["refs"][PLUMBER_E164])
    await service.apply_changes(priya, [CLINIC], [PLUMBER], [], [CLINIC])
    assert_counts_exact(db)

    await service.set_sync(meena, False, None)                 # sync off
    await service.delete_account(priya)                        # account deleted
    await people.add(ARJUN, "New Owner", contacts=[], uid="someone-else")   # Arjun's number recycled
    assert_counts_exact(db)

    karthik, _, _ = await people.add(KARTHIK, "Karthik Reddy", contacts=[PLUMBER, CLINIC])
    await admin.suspend_user(db, karthik["public_id"], "test")
    await service.opt_out(people.ident("+919986031412"))       # the clinic opts out
    assert_counts_exact(db)
    assert holders_of(db, service, "+919986031412") == 0


async def test_lookup_totals_come_from_the_stored_counts(people, service, db):
    for i in range(6):
        await people.add(f"+9197000000{i:02d}", f"Person {LETTERS[i]}", contacts=[CLINIC])
    viewer, _, _ = await people.add(ARJUN, "Arjun Rao", contacts=[CLINIC])
    found = await service.lookup(viewer, CLINIC, "call")
    assert found["total"] == 6                                  # the viewer is not counted on their own card
    stranger, _, _ = await people.add(RAVI, "Ravi Kumar", contacts=[])
    assert (await service.lookup(stranger, CLINIC, "search"))["total"] == 7

    # A blocked person is left out of the total too.
    blocked = (await service.holders(viewer, found["ref"], None, 10))["holders"][0]
    await service.block(viewer, blocked["id"])
    assert (await service.lookup(viewer, CLINIC, "call"))["total"] == 5


async def test_changes_put_a_new_contact_on_cards_straight_away(people, service, db):
    arjun, _, _ = await people.add(ARJUN, "Arjun Rao", contacts=[PLUMBER])
    ravi, _, _ = await people.add(RAVI, "Ravi Kumar", contacts=[CLINIC])
    assert (await service.cards(arjun, None, None))["cards"] == []

    result = await service.apply_changes(ravi, [PLUMBER], [], [], [])   # Ravi saves the plumber
    assert result["stats"] == {"added": 1, "removed": 0, "skipped": 0, "deferred": 0}
    assert set(result["refs"]) == {PLUMBER_E164}
    card = (await service.cards(arjun, None, None))["cards"][0]
    assert names(card["others"]) == ["Ravi Kumar"]

    removed = await service.apply_changes(ravi, [], [PLUMBER], [], [])
    assert removed["stats"]["removed"] == 1
    assert (await service.cards(arjun, None, None))["cards"] == []
    assert_counts_exact(db)


async def test_changes_keep_the_other_source_and_respect_hidden_numbers(people, service, db):
    arjun, _, sync = await people.add(ARJUN, "Arjun Rao", contacts=[PLUMBER], call_log=[PLUMBER])
    await people.add(RAVI, "Ravi Kumar", contacts=[PLUMBER])
    ref = sync["refs"][PLUMBER_E164]

    await service.apply_changes(arjun, [], [PLUMBER], [], [])           # deleted the contact, call stays
    card = card_for(await service.cards(arjun, None, None), ref)
    assert card["in_call_log"] and not card["in_contacts"]

    await service.hide(arjun, ref)
    await service.apply_changes(arjun, [], [], [], [PLUMBER])           # call log cleared too
    assert await service.hidden(arjun) == [ref]                          # the hide choice survives
    await service.apply_changes(arjun, [PLUMBER], [], [], [])           # saved again: still hidden
    assert (await service.cards(arjun, None, None))["cards"] == []
    assert_counts_exact(db)


async def test_changes_need_a_full_sync_first_and_use_the_same_allowance(db, identity):
    service = service_with(db, identity, new_numbers_burst=3, new_numbers_per_day=1)
    people = People(service)
    user, ident, _ = await people.add(ARJUN, "Arjun Rao", sync=False)
    user = await service.set_sync(user, True, service.settings.consent_version)
    with pytest.raises(FullSyncRequired):
        await service.apply_changes(user, [PLUMBER], [], [], [])
    await service.sync_numbers(user, [PLUMBER], [])
    user = await service.current_user(ident)
    result = await service.apply_changes(user, [CLINIC, "70221 00001", "70221 00002"], [], ["*121#"], [])
    assert result["stats"] == {"added": 2, "removed": 0, "skipped": 1, "deferred": 1}

    await service.set_sync(user, False, None)
    with pytest.raises(SyncRequired):
        await service.apply_changes(user, [CLINIC], [], [], [])


async def test_opted_out_numbers_are_skipped_in_changes(people, service, db):
    arjun, _, _ = await people.add(ARJUN, "Arjun Rao", contacts=[PLUMBER])
    await service.opt_out(people.ident(PLUMBER_E164))
    result = await service.apply_changes(arjun, [CLINIC, PLUMBER], [], [], [])
    assert result["stats"]["skipped"] == 1 and PLUMBER_E164 not in result["refs"]


async def test_a_sync_is_retried_when_postgres_breaks_a_deadlock(people, service, monkeypatch):
    arjun, _, _ = await people.add(ARJUN, "Arjun Rao", contacts=[PLUMBER])
    real = service._write_numbers
    calls = {"n": 0}

    async def first_call_deadlocks(*args, **kwargs):
        calls["n"] += 1
        if calls["n"] == 1:
            raise PgError("40P01", "deadlock detected")
        return await real(*args, **kwargs)

    monkeypatch.setattr(service, "_write_numbers", first_call_deadlocks)
    result = await service.sync_numbers(arjun, [PLUMBER, CLINIC], [])
    assert calls["n"] == 2 and result["stats"]["added"] == 1


async def test_maintenance_repairs_counts_that_drifted(people, service, db, identity, settings):
    await people.add(ARJUN, "Arjun Rao", contacts=[PLUMBER, CLINIC])
    await people.add(RAVI, "Ravi Kumar", contacts=[PLUMBER])
    db.conn.run("UPDATE number_stats SET holders = holders + 5")              # simulate drift
    db.conn.run("INSERT INTO number_stats VALUES ('\\x00'::bytea, 0)")         # and a stale zero row
    stats = await maintenance.run(db, identity, settings)
    assert stats["holder_counts_repaired"] == 2 and stats["zero_counts_dropped"] == 1
    assert_counts_exact(db)
    again = await maintenance.run(db, identity, settings)
    assert again["holder_counts_repaired"] == 0
