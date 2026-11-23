"""Test fixtures. By default a throwaway Postgres cluster is started from the local server binaries.
Set TEST_DATABASE_URL (libpq key=value form, e.g. "host=localhost user=postgres password=postgres")
to use an existing server instead, as Cloud Build does."""

from __future__ import annotations

import glob
import os
import shutil
import subprocess
import tempfile
import time
from pathlib import Path

import anyio
import pytest

from foundwith.auth import Identity
from foundwith.config import Settings
from foundwith.migrate import apply_migrations
from foundwith.numbers import NumberHasher
from foundwith.service import Service
from tests.pglibpq import LibpqConnection, LibpqDatabase, PgError

TABLES = ("users, user_numbers, number_stats, lookup_quota, auth_deletions, suppressed_numbers, lookup_log, "
          "account_tombstones, reports, blocks, invite_redemptions, push_tokens, push_queue, popular_names, device_health")


def _pg_bindir() -> str | None:
    if os.environ.get("PG_BINDIR"):
        return os.environ["PG_BINDIR"]
    found = shutil.which("pg_ctl")
    if found:
        return str(Path(found).parent)
    candidates = sorted(glob.glob("/usr/lib/postgresql/*/bin/pg_ctl"))
    return str(Path(candidates[-1]).parent) if candidates else None


def _wait_for(conninfo: str, seconds: int = 60) -> None:
    """A fresh Postgres container restarts once during start-up; keep trying for a while."""
    deadline = time.monotonic() + seconds
    while True:
        try:
            LibpqConnection(conninfo).close()
            return
        except PgError:
            if time.monotonic() > deadline:
                raise
            time.sleep(1)


@pytest.fixture(scope="session")
def pg_conninfo():
    if os.environ.get("TEST_DATABASE_URL"):
        _wait_for(os.environ["TEST_DATABASE_URL"])
        yield os.environ["TEST_DATABASE_URL"]
        return
    bindir = _pg_bindir()
    if bindir is None:
        pytest.skip("No Postgres server found. Install Postgres or set TEST_DATABASE_URL.")
    base = Path(tempfile.mkdtemp(prefix="foundwith-pg-"))
    data, sock = base / "data", base / "sock"
    sock.mkdir()
    run_as: list[str] = []
    if os.geteuid() == 0:  # Postgres refuses to run as root
        for path in (base, sock):
            shutil.chown(path, "postgres")
        run_as = ["runuser", "-u", "postgres", "--"]
    subprocess.run(run_as + [f"{bindir}/initdb", "-D", str(data), "-U", "postgres", "-A", "trust",
                             "-E", "UTF8", "--locale=C", "--no-sync"], check=True, capture_output=True)
    opts = f"-k {sock} -c listen_addresses='' -c fsync=off -c full_page_writes=off -c synchronous_commit=off"
    subprocess.run(run_as + [f"{bindir}/pg_ctl", "-D", str(data), "-w", "-l", str(base / "server.log"),
                             "-o", opts, "start"], check=True, capture_output=True)
    try:
        yield f"host={sock} user=postgres dbname=postgres"
    finally:
        subprocess.run(run_as + [f"{bindir}/pg_ctl", "-D", str(data), "-m", "immediate", "stop"], capture_output=True)
        shutil.rmtree(base, ignore_errors=True)


@pytest.fixture(scope="session")
def migrated_db(pg_conninfo):
    admin = LibpqConnection(pg_conninfo)
    admin.run("DROP DATABASE IF EXISTS foundwith_test")
    admin.run("CREATE DATABASE foundwith_test")
    admin.close()
    database = LibpqDatabase(pg_conninfo + " dbname=foundwith_test")
    applied = anyio.run(apply_migrations, database.conn)
    assert applied, "expected migrations to run on a fresh database"
    yield database
    database.conn.close()


@pytest.fixture
def db(migrated_db):
    migrated_db.conn.run(f"TRUNCATE {TABLES} RESTART IDENTITY CASCADE")
    return migrated_db


@pytest.fixture
def anyio_backend():
    return "asyncio"


def make_settings(**overrides) -> Settings:
    base = dict(env="test", auth_mode="dev", number_pepper=b"test-pepper-0123456789-abcdefghijklmnopqrstuv",
                name_key=b"test-name-key-0123456789-abcdefghijklmnopqrs",
                search_daily_limit=5, call_daily_limit=10, max_numbers_per_user=200,
                new_numbers_burst=150, new_numbers_per_day=24, count_cap=1000, card_sample_size=3,
                holder_page_size=50, visibility="everyone", known_as_names=True)
    base.update(overrides)
    settings = Settings(**base)
    settings.validate()
    return settings


@pytest.fixture
def settings():
    return make_settings()


class FakeIdentity:
    def __init__(self) -> None:
        self.deleted: list[str] = []
        self.fail = False

    async def delete_user(self, uid: str) -> None:
        if self.fail:
            raise RuntimeError("identity service unavailable")
        self.deleted.append(uid)


@pytest.fixture
def identity():
    return FakeIdentity()


@pytest.fixture
def service(db, settings, identity):
    return Service(db, NumberHasher(settings.number_pepper), settings, identity)


class People:
    """Creates registered users with an address book in one call."""

    def __init__(self, service: Service) -> None:
        self.service = service

    @staticmethod
    def ident(phone: str, uid: str | None = None) -> Identity:
        """A sign-in for this number, made just now (as after a fresh OTP)."""
        return Identity(uid=uid or "uid-" + phone.lstrip("+"), phone=phone, auth_time=time.time())

    async def add(self, phone: str, name: str, contacts=(), call_log=(), sync: bool = True, uid: str | None = None):
        ident = self.ident(phone, uid)
        user = await self.service.register(ident, name)
        result = None
        if sync:
            user = await self.service.set_sync(user, True, self.service.settings.consent_version)
            result = await self.service.sync_numbers(user, list(contacts), list(call_log))
            user = await self.service.current_user(ident)
        return user, ident, result


@pytest.fixture
def people(service):
    return People(service)
