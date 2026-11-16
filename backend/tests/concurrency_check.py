"""Many people syncing at once, all touching the same popular numbers.

    python -m tests.concurrency_check

Each worker thread has its own database connection, the way API instances do. They all add and
remove the same handful of popular numbers in different orders, so their holder-count updates
collide. The check passes if every sync succeeds (lock conflicts are retried inside the service)
and the stored counts still equal a recount afterwards."""

from __future__ import annotations

import random
import threading
import time

import anyio

from foundwith.auth import Identity
from foundwith.config import Settings
from foundwith.migrate import apply_migrations
from foundwith.numbers import NumberHasher
from foundwith.service import Service
from tests import perf_check
from tests.pglibpq import LibpqConnection, LibpqDatabase

WORKERS = 16
ROUNDS = 60
POPULAR = [f"98450{n:05d}" for n in range(12)]   # numbers everyone keeps adding and removing


class NoIdentity:
    async def delete_user(self, uid: str) -> None:
        return None


def worker(conninfo: str, index: int, errors: list[str], syncs: list[int]) -> None:
    database = LibpqDatabase(conninfo)
    settings = Settings(env="test", auth_mode="dev", number_pepper=perf_check.PEPPER, name_key=b"n" * 32,
                        new_numbers_burst=10**6, new_numbers_per_day=10**6)
    service = Service(database, NumberHasher(perf_check.PEPPER), settings, NoIdentity())
    rng = random.Random(index)

    async def run() -> None:
        ident = Identity(f"uid-{index}", f"+9170000{index:05d}", time.time())
        user = await service.register(ident, "Worker " + "ABCDEFGHIJKLMNOPQRSTUVWXYZ"[index])
        user = await service.set_sync(user, True, settings.consent_version)
        await service.sync_numbers(user, [], [])
        user = await service.current_user(ident)
        for _ in range(ROUNDS):
            book = rng.sample(POPULAR, rng.randint(4, len(POPULAR)))
            rng.shuffle(book)
            try:
                if rng.random() < 0.5:
                    await service.sync_numbers(user, book, [])
                else:
                    await service.apply_changes(user, book[:3], book[3:6], [], [])
                syncs[index] += 1
            except Exception as exc:  # noqa: BLE001 - collected and reported
                errors.append(f"worker {index}: {type(exc).__name__}: {exc}")

    try:
        anyio.run(run)
    finally:
        database.conn.close()


def main() -> None:
    conninfo, stop = perf_check.start_postgres()
    try:
        admin = LibpqConnection(conninfo)
        admin.run("CREATE DATABASE foundwith_concurrency")
        admin.close()
        target = conninfo + " dbname=foundwith_concurrency"
        setup = LibpqDatabase(target)
        anyio.run(apply_migrations, setup.conn)
        errors: list[str] = []
        syncs = [0] * WORKERS
        threads = [threading.Thread(target=worker, args=(target, i, errors, syncs)) for i in range(WORKERS)]
        started = time.perf_counter()
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        elapsed = time.perf_counter() - started
        stored = {bytes(r["number_hmac"]): r["holders"] for r in setup.conn.run(
            "SELECT number_hmac, holders FROM number_stats WHERE holders <> 0")[0]}
        actual = {bytes(r["number_hmac"]): r["n"] for r in setup.conn.run(
            "SELECT number_hmac, count(*)::int AS n FROM user_numbers WHERE in_contacts AND NOT hidden GROUP BY number_hmac")[0]}
        setup.conn.close()
        print(f"{sum(syncs)} of {WORKERS * ROUNDS} syncs succeeded in {elapsed:.1f} s; {len(errors)} failed")
        for line in errors[:10]:
            print("  " + line)
        print("stored counts match a recount" if stored == actual else f"COUNTS DIFFER: {stored} vs {actual}")
        if errors or stored != actual:
            raise SystemExit(1)
    finally:
        stop()


if __name__ == "__main__":
    main()
