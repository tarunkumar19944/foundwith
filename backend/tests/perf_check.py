"""Times the real-time paths on a realistic amount of data.

    python -m tests.perf_check              # 10,000 people, about 2.3 million stored numbers
    python -m tests.perf_check --users 30000

It starts a throwaway Postgres (the server binaries must be installed), fills it with a synthetic
population, and times the same service calls the API makes: the incoming-call lookup, search,
the Home cards, the paged holder list and a contact sync. It also prints the query plans for the
two statements every lookup depends on, so a missing index shows up as a sequential scan.

The population is built to look like real address books: every person has about 60 other members
saved, 20 numbers from a pool of locally popular ones (each held by roughly 1% of everyone), a few
from a handful of numbers almost everybody has, about 115 that hardly anyone else has, and 30
numbers that are only in their call log."""

from __future__ import annotations

import argparse
import glob
import os
import shutil
import statistics
import subprocess
import tempfile
import time
from pathlib import Path

import anyio

from foundwith.auth import Identity
from foundwith.config import Settings
from foundwith.migrate import apply_migrations
from foundwith.numbers import NumberHasher, to_ref
from foundwith.service import Service
from tests.pglibpq import LibpqConnection, LibpqDatabase

PEPPER = b"perf-check-pepper-0123456789-abcdefghijklmnop"


class NoIdentity:
    async def delete_user(self, uid: str) -> None:
        return None


def start_postgres() -> tuple[str, callable]:
    found = shutil.which("pg_ctl")
    candidates = sorted(glob.glob("/usr/lib/postgresql/*/bin/pg_ctl"))
    bindir = os.environ.get("PG_BINDIR") or (str(Path(found).parent) if found else
                                             str(Path(candidates[-1]).parent) if candidates else None)
    if bindir is None:
        raise SystemExit("No Postgres server binaries found. Install Postgres or set PG_BINDIR.")
    base = Path(tempfile.mkdtemp(prefix="foundwith-perf-"))
    data, sock = base / "data", base / "sock"
    sock.mkdir()
    run_as: list[str] = []
    if os.geteuid() == 0:
        for path in (base, sock):
            shutil.chown(path, "postgres")
        run_as = ["runuser", "-u", "postgres", "--"]
    subprocess.run(run_as + [f"{bindir}/initdb", "-D", str(data), "-U", "postgres", "-A", "trust",
                             "-E", "UTF8", "--locale=C", "--no-sync"], check=True, capture_output=True)
    # Roughly what a 3.75 GB Cloud SQL instance runs with.
    opts = (f"-k {sock} -c listen_addresses='' -c fsync=off -c synchronous_commit=off "
            "-c shared_buffers=1GB -c effective_cache_size=2500MB -c work_mem=4MB -c max_wal_size=4GB")
    subprocess.run(run_as + [f"{bindir}/pg_ctl", "-D", str(data), "-w", "-l", str(base / "server.log"),
                             "-o", opts, "start"], check=True, capture_output=True)

    def stop() -> None:
        subprocess.run(run_as + [f"{bindir}/pg_ctl", "-D", str(data), "-m", "immediate", "stop"], capture_output=True)
        shutil.rmtree(base, ignore_errors=True)

    return f"host={sock} user=postgres dbname=postgres", stop


def populate(conn: LibpqConnection, users: int) -> dict[str, int]:
    """Numbers here are keyed hashes of made-up labels ('u17' is person 17's own number,
    'p5' a locally popular number, 'v2' one almost everyone has, 'n123' an ordinary one)."""
    pepper_hex = PEPPER.hex()
    conn.run(f"""
        CREATE FUNCTION fake_number(label text) RETURNS bytea LANGUAGE sql IMMUTABLE AS
        $$ SELECT hmac(convert_to('+' || label, 'UTF8'), decode('{pepper_hex}', 'hex'), 'sha256') $$""")
    conn.run(f"""
        INSERT INTO users (auth_uid, phone_hmac, display_name, sync_enabled, consent_version, consent_at, first_sync_at)
        SELECT 'uid-' || i, fake_number('u' || i), 'Member ' || i, TRUE, '2026-11-13', now(), now()
        FROM generate_series(1, {users}) AS i""")
    for label, pool, per_person, contacts, call_log in [
        ("u", users, 60, "TRUE", "(random() < 0.3)"),
        ("p", max(200, users // 5), 20, "TRUE", "(random() < 0.3)"),
        ("v", 10, 5, "TRUE", "FALSE"),
        ("n", users * 30, 115, "TRUE", "(random() < 0.1)"),
        ("c", users * 30, 30, "FALSE", "TRUE"),
    ]:
        conn.run(f"""
            INSERT INTO user_numbers (user_id, number_hmac, in_contacts, in_call_log)
            SELECT u.id, fake_number(k.label), {contacts}, {call_log}
            FROM users u
            CROSS JOIN LATERAL (
                SELECT DISTINCT '{label}' || (1 + floor(random() * {pool}))::int AS label
                FROM generate_series(1, {per_person}) WHERE u.id = u.id
            ) k
            WHERE k.label <> 'u' || u.id
            ON CONFLICT (user_id, number_hmac) DO NOTHING""")
    conn.run("VACUUM ANALYZE")
    rows = conn.run("SELECT count(*) AS n FROM user_numbers")[0][0]["n"]
    size = conn.run("SELECT pg_database_size(current_database()) / 1048576 AS mb")[0][0]["mb"]
    return {"stored_numbers": rows, "database_mb": size}


def summarise(samples: list[float]) -> str:
    samples = sorted(samples)
    p50 = statistics.median(samples)
    p95 = samples[min(len(samples) - 1, int(len(samples) * 0.95))]
    return f"median {p50:7.1f} ms   95th {p95:7.1f} ms   slowest {samples[-1]:7.1f} ms   ({len(samples)} runs)"


async def measure(database: LibpqDatabase, users: int, rounds: int) -> None:
    settings = Settings(env="test", auth_mode="dev", number_pepper=PEPPER, name_key=b"n" * 32, search_daily_limit=10**6,
                        call_daily_limit=10**6, new_numbers_burst=10**6, new_numbers_per_day=10**6)
    service = Service(database, NumberHasher(PEPPER), settings, NoIdentity())
    conn = database.conn
    await conn.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto")

    timings: dict[str, list[float]] = {}

    async def timed(label: str, call) -> object:
        start = time.perf_counter()
        result = await call()
        timings.setdefault(label, []).append((time.perf_counter() - start) * 1000)
        return result

    step = max(1, users // rounds)
    for i in range(1, users + 1, step):
        ident = Identity(f"uid-{i}", f"+u{i}", time.time())
        # The service hashes real numbers; here we look people up by the fake labels directly.
        user = await conn.fetchrow("SELECT * FROM users WHERE auth_uid = $1", ident.uid)
        user = await service.db.conn.fetchrow(
            f"SELECT id, public_id::text AS public_id, auth_uid, phone_hmac, display_name, sync_enabled, "
            f"first_sync_at FROM users WHERE id = {user['id']}")

        async def lookup(label: str, kind: str):
            digest = bytes((await conn.fetchrow("SELECT fake_number($1) AS h", label))["h"])
            original = service.hasher.digest
            service.hasher.digest = lambda _e164, d=digest: d          # look up this exact stored number
            try:
                return await service.lookup(user, "9845031207", kind)
            finally:
                service.hasher.digest = original

        await timed("incoming call, ordinary number", lambda: lookup(f"n{1 + i % (users * 30)}", "call"))
        await timed("incoming call, locally popular number", lambda: lookup(f"p{1 + i % 200}", "call"))
        await timed("incoming call, number almost everyone has", lambda: lookup(f"v{1 + i % 10}", "call"))
        found = await timed("search, locally popular number", lambda: lookup(f"p{1 + (i * 7) % 200}", "search"))
        await timed("holder list, one page of 50", lambda: service.holders(user, found["ref"], None, 50))
        await timed("Home cards, first 100", lambda: service.cards(user, None, 100))
        await timed("change sent: one contact saved", lambda: service.apply_changes(
            user, [f"9{(i * 7919) % 10**9:09d}"], [], [], []))
        if i % (step * 10) == 1:
            book = [f"8{(i * 104729 + k * 7907) % 10**9:09d}" for k in range(2000)]
            await timed("full sync, 2,000 new numbers", lambda: service.sync_numbers(user, book, []))
            await timed("full sync again, nothing changed", lambda: service.sync_numbers(user, book, []))

    for label, samples in timings.items():
        print(f"  {label:44s} {summarise(samples)}")

    print("\nQuery plans (look for 'Index Scan' / 'Index Only Scan'; a 'Seq Scan' on users or user_numbers would be a problem):")
    viewer = users // 2
    popular = bytes((await conn.fetchrow("SELECT fake_number('v1') AS h"))["h"])
    plans = {
        "holder count of a number almost everyone has (stored)": (
            """EXPLAIN (ANALYZE, COSTS OFF, TIMING OFF, SUMMARY OFF)
               SELECT s.holders FROM number_stats s WHERE s.number_hmac = $1::bytea""", (popular,)),
        "holders the viewer knows": (
            """EXPLAIN (ANALYZE, COSTS OFF, TIMING OFF, SUMMARY OFF)
               SELECT un.number_hmac, u.id FROM user_numbers vc
               CROSS JOIN LATERAL (SELECT x.id FROM users x WHERE x.phone_hmac = vc.number_hmac LIMIT 1) u
               JOIN user_numbers un ON un.user_id = u.id AND un.in_contacts AND NOT un.hidden
               WHERE vc.user_id = $2 AND vc.in_contacts AND NOT vc.hidden AND un.number_hmac = ANY(ARRAY[$1::bytea])
                 AND u.id <> $2""",
            (popular, viewer)),
    }
    for label, (sql, args) in plans.items():
        print(f"\n  -- {label}")
        for row in await conn.fetch(sql, *args):
            print("    " + row["QUERY PLAN"])
    _ = to_ref


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--users", type=int, default=10_000)
    parser.add_argument("--rounds", type=int, default=200, help="how many different people to measure as")
    args = parser.parse_args()

    conninfo, stop = start_postgres()
    try:
        admin = LibpqConnection(conninfo)
        admin.run("CREATE DATABASE foundwith_perf")
        admin.close()
        database = LibpqDatabase(conninfo + " dbname=foundwith_perf")
        anyio.run(apply_migrations, database.conn)
        database.conn.run("CREATE EXTENSION IF NOT EXISTS pgcrypto")
        started = time.perf_counter()
        facts = populate(database.conn, args.users)
        print(f"{args.users:,} people, {facts['stored_numbers']:,} stored numbers, database {facts['database_mb']:,} MB "
              f"(built in {time.perf_counter() - started:.0f} s)\n")
        anyio.run(measure, database, args.users, args.rounds)
        database.conn.close()
    finally:
        stop()


if __name__ == "__main__":
    main()
