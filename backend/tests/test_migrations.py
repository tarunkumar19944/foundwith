"""Upgrading a live v0.1 database: the 0002 migration must leave existing accounts working."""

import shutil
import time

import pytest

from foundwith.auth import Identity
from foundwith.migrate import MIGRATIONS_DIR, apply_migrations
from foundwith.numbers import NumberHasher
from foundwith.service import Service
from tests.conftest import make_settings
from tests.pglibpq import LibpqConnection, LibpqDatabase

pytestmark = pytest.mark.anyio

ARJUN, RAVI = "+919845031207", "+919900148823"


@pytest.fixture
def v01_database(pg_conninfo, tmp_path):
    """A database with only the first migration applied, as a v0.1 deployment has."""
    admin = LibpqConnection(pg_conninfo)
    admin.run("DROP DATABASE IF EXISTS foundwith_upgrade")
    admin.run("CREATE DATABASE foundwith_upgrade")
    admin.close()
    shutil.copy(MIGRATIONS_DIR / "0001_init.sql", tmp_path / "0001_init.sql")
    database = LibpqDatabase(pg_conninfo + " dbname=foundwith_upgrade")
    yield database, tmp_path
    database.conn.close()


async def test_upgrade_from_v01_keeps_existing_accounts_working(v01_database, identity):
    database, only_first = v01_database
    conn = database.conn
    assert await apply_migrations(conn, only_first) == ["0001_init"]

    settings = make_settings()
    hasher = NumberHasher(settings.number_pepper)
    plumber = hasher.digest("+919845172046")
    # Two accounts exactly as v0.1 wrote them, both holding the plumber's number.
    for uid, phone, name in [("uid-arjun", ARJUN, "Arjun Rao"), ("uid-ravi", RAVI, "Ravi Kumar")]:
        await conn.execute(
            """INSERT INTO users (auth_uid, phone_hmac, display_name, sync_enabled, consent_version, consent_at, first_sync_at)
               VALUES ($1, $2::bytea, $3, TRUE, $4, now(), now())""",
            uid, hasher.digest(phone), name, settings.consent_version)
        await conn.execute(
            """INSERT INTO user_numbers (user_id, number_hmac, in_contacts)
               SELECT id, $2::bytea, TRUE FROM users WHERE auth_uid = $1""", uid, plumber)
    await conn.execute(
        "INSERT INTO lookup_quota (user_id, kind, day, used) SELECT id, 'search', (now() AT TIME ZONE 'Asia/Kolkata')::date, 2 FROM users WHERE auth_uid = 'uid-arjun'")

    assert await apply_migrations(conn) == ["0002_safety_and_lifecycle", "0003_holder_counts", "0004_invites", "0005_names_calls_and_push", "0006_name_holds", "0007_tells"]
    assert await apply_migrations(conn) == []                              # running again is a no-op
    # The holder counts are backfilled from the address books that were already there.
    assert (await conn.fetchrow("SELECT holders FROM number_stats WHERE number_hmac = $1::bytea", plumber))["holders"] == 2

    service = Service(database, hasher, settings, identity)
    arjun_ident = Identity("uid-arjun", ARJUN, time.time())
    arjun = await service.current_user(arjun_ident)
    assert arjun["suspended_at"] is None and arjun["admit_tokens"] == 3000 and arjun["seen_age_s"] < 60
    cards = await service.cards(arjun, None, None)
    assert [h["name"] for h in cards["cards"][0]["others"]] == ["Ravi Kumar"]
    synced = await service.sync_numbers(arjun, ["98451 72046", "99860 31412"], [])
    assert synced["stats"] == {"numbers": 2, "added": 1, "removed": 0, "skipped": 0, "deferred": 0}
    assert (await service.lookup(arjun, "98451 72046", "search"))["quota"]["left"] == settings.search_daily_limit - 3

    from foundwith import maintenance
    stats = await maintenance.run(database, identity, settings)
    assert stats["inactive_deleted"] == 0                                  # nobody is purged just for upgrading
