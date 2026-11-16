"""Several people syncing the same popular numbers at the same moment, each on their own database
connection. Every sync must succeed and the stored holder counts must still match a recount.
(A longer version runs with: python -m tests.concurrency_check)"""

import random
import threading
import time

import anyio

from foundwith.auth import Identity
from foundwith.numbers import NumberHasher
from foundwith.service import Service
from tests.conftest import make_settings
from tests.pglibpq import LibpqDatabase
from tests.test_realtime import assert_counts_exact

POPULAR = [f"98450{n:05d}" for n in range(10)]
WORKERS, ROUNDS = 6, 15


class NoIdentity:
    async def delete_user(self, uid: str) -> None:
        return None


def test_concurrent_syncs_on_shared_numbers_never_fail(db, pg_conninfo):
    settings = make_settings(new_numbers_burst=10_000, new_numbers_per_day=10_000)
    errors: list[str] = []
    done = [0] * WORKERS

    def worker(index: int) -> None:
        database = LibpqDatabase(pg_conninfo + " dbname=foundwith_test")
        service = Service(database, NumberHasher(settings.number_pepper), settings, NoIdentity())
        rng = random.Random(index)

        async def run() -> None:
            ident = Identity(f"uid-c{index}", f"+9170000{index:05d}", time.time())
            user = await service.register(ident, "Worker " + "ABCDEFGHIJ"[index])
            user = await service.set_sync(user, True, settings.consent_version)
            await service.sync_numbers(user, [], [])
            user = await service.current_user(ident)
            for _ in range(ROUNDS):
                book = rng.sample(POPULAR, rng.randint(3, len(POPULAR)))
                try:
                    if rng.random() < 0.5:
                        await service.sync_numbers(user, book, [])
                    else:
                        await service.apply_changes(user, book[:3], book[3:6], [], [])
                    done[index] += 1
                except Exception as exc:  # noqa: BLE001 - reported below
                    errors.append(f"{type(exc).__name__}: {exc}")

        try:
            anyio.run(run)
        finally:
            database.conn.close()

    threads = [threading.Thread(target=worker, args=(i,)) for i in range(WORKERS)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert errors == []
    assert sum(done) == WORKERS * ROUNDS
    assert_counts_exact(db)
