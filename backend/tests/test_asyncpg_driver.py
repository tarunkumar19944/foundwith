"""Runs the main flows through the production driver (an asyncpg pool). Skipped where asyncpg isn't
installed; Cloud Build installs it, so every deploy exercises every kind of statement the app sends
through the real driver before anything is deployed."""

import time

import pytest

from foundwith import admin, maintenance
from foundwith.auth import Identity
from foundwith.db import AsyncpgDatabase
from foundwith.numbers import NumberHasher
from foundwith.service import Service

asyncpg = pytest.importorskip("asyncpg")
pytestmark = pytest.mark.anyio

ARJUN, RAVI, PRIYA, PLUMBER = "+919845031207", "+919900148823", "+919741266019", "+919845172046"


def _kwargs(conninfo: str) -> dict:
    parts = dict(item.split("=", 1) for item in conninfo.split())
    return {"host": parts.get("host"), "port": int(parts.get("port", 5432)), "user": parts.get("user"),
            "password": parts.get("password"), "database": parts.get("dbname")}


async def test_main_flows_through_asyncpg(db, pg_conninfo, settings, identity):
    pool = await asyncpg.create_pool(**{**_kwargs(pg_conninfo), "database": "foundwith_test"}, min_size=1, max_size=2)
    database = AsyncpgDatabase(pool)
    try:
        service = Service(database, NumberHasher(settings.number_pepper), settings, identity)
        users, idents = {}, {}
        for phone, name, contacts, calls in [(ARJUN, "Arjun Rao", ["98451 72046", RAVI], ["7619155380"]),
                                             (RAVI, "Ravi Kumar", ["98451 72046"], []),
                                             (PRIYA, "Priya Sharma", ["98451 72046", "7619155380"], [])]:
            ident = idents[name] = Identity("uid-" + phone, phone, time.time())
            user = await service.register(ident, name)
            user = await service.set_sync(user, True, settings.consent_version)
            result = await service.sync_numbers(user, contacts, calls)
            assert result["stats"]["deferred"] == 0 and len(result["refs"]) == len(contacts) + len(calls)
            users[name] = await service.current_user(ident)
        arjun, ravi, priya = users["Arjun Rao"], users["Ravi Kumar"], users["Priya Sharma"]

        # cards, lookups, the paged list, profile
        cards = await service.cards(arjun, None, 1)
        assert cards["total_cards"] == 2 and cards["next"] is not None
        assert [h["name"] for h in cards["cards"][0]["known"]] == ["Ravi Kumar"]
        assert len((await service.cards(arjun, cards["next"], 1))["cards"]) == 1
        found = await service.lookup(ravi, "9845172046", "search")
        assert sorted(h["name"] for h in found["others"]) == ["Arjun Rao", "Priya Sharma"]
        assert (await service.lookup(ravi, "011 2345 6789", "call"))["total"] == 0
        listed = await service.holders(ravi, found["ref"], None, 1)
        assert len(listed["holders"]) == 1 and listed["next"] is not None
        assert len((await service.holders(ravi, found["ref"], listed["next"], 1))["holders"]) == 1
        profile = await service.profile(ravi, idents["Ravi Kumar"])
        assert profile["quota"]["search"]["left"] == settings.search_daily_limit - 1

        # hide, rename, report, block
        await service.hide(ravi, found["ref"])
        assert await service.hidden(ravi) == [found["ref"]]
        await service.unhide(ravi, found["ref"])
        assert (await service.rename(ravi, "Ravi K"))["display_name"] == "Ravi K"
        await service.report(arjun, priya["public_id"], "other", "driver test")
        block = await service.block(arjun, priya["public_id"])
        assert await service.blocks(arjun) == [{"id": block["id"], "name": "Priya Sharma"}]
        assert (await service.cards(arjun, None, None))["total_cards"] == 1
        await service.unblock(arjun, block["id"])

        # opt-out by someone with no account
        plumber = Identity("uid-plumber", PLUMBER, time.time())
        assert await service.opt_out(plumber) == {"opted_out": True}
        assert (await service.lookup(arjun, PLUMBER, "search"))["total"] == 0
        assert (await service.sync_numbers(arjun, ["98451 72046", RAVI], ["7619155380"]))["stats"]["skipped"] == 1
        assert await service.opt_in(plumber) == {"opted_out": False}

        # moderation and the nightly job
        reports = await admin.list_reports(database)
        assert len(reports) == 1 and reports[0]["name_now"] == "Priya Sharma"
        done = await admin.suspend_from_report(database, reports[0]["report_id"], "driver test")
        assert done["account_existed"] is True
        await admin.unsuspend_user(database, priya["public_id"])
        stats = await maintenance.run(database, identity, settings)
        assert stats["inactive_deleted"] == 0 and stats["lookup_log_purged"] == 0

        # deletion and coming back
        await service.delete_account(ravi)
        again = Identity("uid-ravi-2", RAVI, time.time() + 5)
        assert (await service.register(again, "Ravi Kumar"))["display_name"] == "Ravi Kumar"
        await service.delete_account(await service.current_user(again))
    finally:
        await database.close()
