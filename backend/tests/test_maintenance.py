"""The nightly job: inactive accounts, retention, and its safety limit."""

import time

import pytest

from foundwith import maintenance
from foundwith.auth import Identity
from foundwith.errors import NotRegistered

pytestmark = pytest.mark.anyio

ARJUN, RAVI, PRIYA = "+919845031207", "+919900148823", "+919741266019"
PLUMBER = "98451 72046"


def count(db, table, where="TRUE"):
    return db.conn.run(f"SELECT count(*) AS n FROM {table} WHERE {where}")[0][0]["n"]


async def test_accounts_not_seen_for_ninety_days_are_deleted(people, service, db, identity, settings):
    arjun, _, _ = await people.add(ARJUN, "Arjun Rao", contacts=[PLUMBER])
    ravi, ravi_ident, _ = await people.add(RAVI, "Ravi Kumar", contacts=[PLUMBER])
    priya, _, _ = await people.add(PRIYA, "Priya Sharma", contacts=[PLUMBER])
    db.conn.run(f"UPDATE users SET last_seen_at = now() - interval '91 days' WHERE id = {ravi['id']}")
    db.conn.run(f"UPDATE users SET last_seen_at = now() - interval '89 days' WHERE id = {priya['id']}")

    stats = await maintenance.run(db, identity, settings)
    assert stats["inactive_deleted"] == 1 and stats["inactive_cap_hit"] == 0
    assert stats["sign_in_deletions_done"] == 1 and identity.deleted == [ravi_ident.uid]
    with pytest.raises(NotRegistered):
        await service.current_user(ravi_ident)
    card = (await service.cards(arjun, None, None))["cards"][0]
    assert [h["name"] for h in card["others"]] == ["Priya Sharma"]      # Ravi is gone from every card
    assert count(db, "account_tombstones") == 1

    # Whoever gets Ravi's number next starts from a clean account, not Ravi's.
    newcomer = Identity(uid=ravi_ident.uid, phone=RAVI, auth_time=time.time() + 2)
    fresh = await service.register(newcomer, "Someone New")
    assert fresh["display_name"] == "Someone New" and fresh["sync_enabled"] is False
    assert count(db, "user_numbers", f"user_id = {fresh['id']}") == 0


async def test_someone_who_comes_back_mid_run_is_not_deleted(people, db):
    from foundwith import repo

    arjun, _, _ = await people.add(ARJUN, "Arjun Rao")
    db.conn.run("UPDATE users SET last_seen_at = now() - interval '91 days'")
    assert len(await repo.inactive_users(db.conn, 90, 10)) == 1
    db.conn.run("UPDATE users SET last_seen_at = now()")                # they opened the app
    async with db.conn.transaction():
        assert await repo.still_inactive(db.conn, arjun["id"], 90) is False


async def test_one_run_never_deletes_more_than_its_cap(people, db, monkeypatch):
    """If "last seen" ever stopped being recorded, every account would look inactive.
    The cap turns that from an instant wipe into a slow leak with a warning in the logs."""
    monkeypatch.setattr(maintenance, "INACTIVE_MIN_PER_RUN", 3)
    for i in range(8):
        await people.add(f"+9198000000{i:02d}", "Test Person", sync=False)
    db.conn.run("UPDATE users SET last_seen_at = now() - interval '200 days'")
    assert await maintenance.delete_inactive_accounts(db, 90) == {"inactive_deleted": 3, "inactive_cap_hit": 1}
    assert count(db, "users") == 5
    assert await maintenance.delete_inactive_accounts(db, 90) == {"inactive_deleted": 3, "inactive_cap_hit": 1}
    assert await maintenance.delete_inactive_accounts(db, 90) == {"inactive_deleted": 2, "inactive_cap_hit": 0}


async def test_old_records_are_purged_on_schedule(people, service, db, identity, settings):
    arjun, _, _ = await people.add(ARJUN, "Arjun Rao", contacts=[PLUMBER])
    ravi, _, _ = await people.add(RAVI, "Ravi Kumar")
    priya, _, _ = await people.add(PRIYA, "Priya Sharma")
    await service.lookup(arjun, PLUMBER, "search")
    await service.lookup(arjun, PLUMBER, "call")
    db.conn.run("UPDATE lookup_log SET created_at = now() - interval '181 days' WHERE kind = 'search'")

    await service.delete_account(ravi)                                   # ordinary deletion: remembered 30 days
    from foundwith import admin
    await admin.suspend_user(db, priya["public_id"], "spam")
    await service.delete_account(priya)                                  # suspended: remembered 90 days
    db.conn.run("UPDATE account_tombstones SET deleted_at = now() - interval '31 days'")
    db.conn.run("UPDATE auth_deletions SET done_at = now() - interval '31 days'")

    stats = await maintenance.run(db, identity, settings)
    assert stats["lookup_log_purged"] == 1 and count(db, "lookup_log") == 1
    assert stats["tombstones_purged"] == 1 and count(db, "account_tombstones", "suspended_at IS NOT NULL") == 1
    assert stats["deletion_records_purged"] == 2

    db.conn.run("UPDATE account_tombstones SET deleted_at = now() - interval '91 days'")
    assert (await maintenance.run(db, identity, settings))["tombstones_purged"] == 1
    assert count(db, "account_tombstones") == 0
