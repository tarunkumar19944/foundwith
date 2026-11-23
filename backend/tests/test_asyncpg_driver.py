"""Runs the main flows through the production driver (an asyncpg pool). Skipped where asyncpg isn't
installed; Cloud Build and the GitHub workflow install it, so every deploy exercises every kind of
statement the app sends through the real driver (bytea, arrays, dates, timestamps) before anything
is deployed."""

import datetime as dt
import time

import pytest

from foundwith import admin, maintenance, push
from foundwith.auth import Identity
from foundwith.db import AsyncpgDatabase
from foundwith.errors import AccountExists, AccountSuspended
from foundwith.numbers import NumberHasher
from foundwith.service import Service
from tests.conftest import make_settings

asyncpg = pytest.importorskip("asyncpg")
pytestmark = pytest.mark.anyio

ARJUN, RAVI, PRIYA, PLUMBER = "+919845031207", "+919900148823", "+919741266019", "+919845172046"
IST = dt.timezone(dt.timedelta(hours=5, minutes=30))


def _kwargs(conninfo: str) -> dict:
    parts = dict(item.split("=", 1) for item in conninfo.split())
    return {"host": parts.get("host"), "port": int(parts.get("port", 5432)), "user": parts.get("user"),
            "password": parts.get("password"), "database": parts.get("dbname")}


def entry(number: str, name: str) -> dict:
    return {"number": number, "name": name}


async def test_main_flows_through_asyncpg(db, pg_conninfo, identity):
    settings = make_settings(popular_name_min_account_days=0, push_mode="log")
    pool = await asyncpg.create_pool(**{**_kwargs(pg_conninfo), "database": "foundwith_test"}, min_size=1, max_size=2)
    database = AsyncpgDatabase(pool)
    try:
        service = Service(database, NumberHasher(settings.number_pepper), settings, identity)
        users, idents = {}, {}
        for phone, name, contacts, calls in [
                (ARJUN, "Arjun Rao", [entry("98451 72046", "Raju Plumber"), entry(RAVI, "Ravi")], ["7619155380"]),
                (RAVI, "Ravi Kumar", [entry("98451 72046", "RAJU PLUMBER")], []),
                (PRIYA, "Priya Sharma", [entry("98451 72046", "raju plumber"), "7619155380"], [])]:
            ident = idents[name] = Identity("uid-" + phone, phone, time.time())
            user = await service.register(ident, name)
            user = await service.set_sync(user, True, settings.consent_version)
            result = await service.sync_numbers(user, contacts, calls)
            assert result["stats"]["deferred"] == 0 and len(result["refs"]) == len(contacts) + len(calls)
            users[name] = await service.current_user(ident)
        arjun, ravi, priya = users["Arjun Rao"], users["Ravi Kumar"], users["Priya Sharma"]

        # a first sign-in has no name yet, and can still look numbers up
        nameless = Identity("uid-nameless", "+919811100001", time.time())
        newcomer = await service.register(nameless)
        assert newcomer["display_name"] is None
        assert (await service.lookup(newcomer, PLUMBER, "search"))["total"] == 3
        assert (await service.rename(newcomer, "Kiran"))["display_name"] == "Kiran"

        # cards, lookups (call logs count too), the paged list, profile
        cards = await service.cards(arjun, None, 1)
        assert cards["total_cards"] == 2 and cards["next"] is not None        # numbers someone else has
        assert [h["name"] for h in cards["cards"][0]["known"]] == ["Ravi Kumar"]
        found = await service.lookup(ravi, "9845172046", "search")
        assert found["total"] == 2 and sorted(h["name"] for h in found["others"]) == ["Arjun Rao", "Priya Sharma"]
        assert (await service.lookup(ravi, "7619155380", "call"))["total"] == 2          # contact + call log
        assert (await service.lookup(ravi, "011 2345 6789", "call"))["total"] == 0
        listed = await service.holders(ravi, found["ref"], None, 1)
        assert len(listed["holders"]) == 1 and listed["next"] is not None
        assert len((await service.holders(ravi, found["ref"], listed["next"], 1))["holders"]) == 1
        profile = await service.profile(ravi, idents["Ravi Kumar"])
        assert profile["quota"]["search"]["left"] == settings.search_daily_limit - 1

        # popular names: three people saved the plumber under the same name, give or take case
        stats = await maintenance.run(database, identity, settings)
        assert stats["inactive_deleted"] == 0
        assert (await service.lookup(newcomer, PLUMBER, "search"))["popular_name"] in {"Raju Plumber", "raju plumber"}

        # export and my data carry the encrypted names back out
        exported = await service.export(arjun, idents["Arjun Rao"])
        assert "Raju Plumber" in str(exported)
        assert (await service.my_data(arjun, idents["Arjun Rao"]))

        # hide, rename, block
        await service.hide(ravi, found["ref"])
        assert await service.hidden(ravi) == [found["ref"]]
        await service.unhide(ravi, found["ref"])
        assert (await service.rename(ravi, "Ravi K"))["display_name"] == "Ravi K"
        await service.report(arjun, priya["public_id"], "other", "driver test")
        block = await service.block(arjun, priya["public_id"])
        [listed_block] = await service.blocks(arjun)
        assert listed_block["id"] == block["id"] and listed_block["name"] == "Priya Sharma" and listed_block["ref"]
        await service.unblock(arjun, block["id"])

        # push: devices, the queue, wake-ups, health
        await service.save_device(arjun, "fcm-token-arjun-" + "x" * 20)
        await service.save_device(ravi, "fcm-token-ravi-" + "x" * 20)
        assert (await push.dispatch(database, push.LogSender()))["people"] >= 0
        assert (await push.send_wakeups(database, push.LogSender()))["sent"] == 2
        await service.record_health("xiaomi", 14, dt.datetime.now(dt.timezone.utc).date(), 10, 7)
        assert await admin.run(database, ["health", "3650"])
        await service.forget_device(ravi, "fcm-token-ravi-" + "x" * 20)

        # opt-out by someone with no account, then by someone with one
        plumber = Identity("uid-plumber", PLUMBER, time.time())
        assert (await service.opt_out_status(plumber))["has_account"] is False
        assert (await service.opt_out(plumber))["opted_out"] is True
        assert (await service.lookup(arjun, PLUMBER, "search"))["total"] == 0
        assert (await service.opt_in(plumber))["opted_out"] is False
        with pytest.raises(AccountExists):
            await service.opt_out(nameless)                                   # has an account: warn first

        # moderation: from a report, and by number from a complaint
        reports = await admin.list_reports(database)
        assert len(reports) == 1 and reports[0]["name_now"] == "Priya Sharma"
        done = await admin.suspend_from_report(database, reports[0]["report_id"], "driver test")
        assert done["account_existed"] is True
        await admin.unsuspend_user(database, priya["public_id"])
        hashed = service.hasher.digest(PLUMBER).hex()
        info = await admin.run(database, ["number", hashed])
        assert info["people_who_have_it"] == 0 and info["taken_out"] is False     # opting out removed every copy
        assert (await admin.run(database, ["hold-name", hashed, "test"]))["had_a_popular_name"] is False
        await admin.run(database, ["release-name", hashed])
        await admin.run(database, ["suspend-number", service.hasher.digest(RAVI).hex(), "driver", "test"])
        with pytest.raises(AccountSuspended):
            await service.current_user(idents["Ravi Kumar"])
        await admin.unsuspend_user(database, ravi["public_id"])

        # deletion and coming back
        await service.delete_account(await service.current_user(idents["Ravi Kumar"]))
        again = Identity("uid-ravi-2", RAVI, time.time() + 5)
        assert (await service.register(again, "Ravi Kumar"))["display_name"] == "Ravi Kumar"
        assert (await service.opt_out(again, delete_account=True))["opted_out"] is True
        assert (await service.lookup(arjun, RAVI, "search"))["total"] == 0
    finally:
        await database.close()
