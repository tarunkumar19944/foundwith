"""Applies SQL migrations in order. Runs as a Cloud Run job before each deploy:
    python -m foundwith.migrate
A Postgres advisory lock makes concurrent runs safe; each file runs in its own transaction."""

from __future__ import annotations

import json
from pathlib import Path

import anyio

from .config import Settings
from .db import AsyncpgDatabase, Conn

MIGRATIONS_DIR = Path(__file__).parent / "migrations"
LOCK_KEY = 7_274_276_100


async def apply_migrations(conn: Conn, directory: Path = MIGRATIONS_DIR) -> list[str]:
    await conn.execute(
        "CREATE TABLE IF NOT EXISTS schema_migrations (version TEXT PRIMARY KEY, applied_at TIMESTAMPTZ NOT NULL DEFAULT now())")
    await conn.execute("SELECT pg_advisory_lock($1::bigint)", LOCK_KEY)
    try:
        done = {r["version"] for r in await conn.fetch("SELECT version FROM schema_migrations")}
        applied = []
        for path in sorted(directory.glob("*.sql")):
            if path.stem in done:
                continue
            async with conn.transaction():
                await conn.execute(path.read_text(encoding="utf-8"))
                await conn.execute("INSERT INTO schema_migrations (version) VALUES ($1)", path.stem)
            applied.append(path.stem)
        return applied
    finally:
        await conn.execute("SELECT pg_advisory_unlock($1::bigint)", LOCK_KEY)


async def main() -> None:
    settings = Settings.from_env(for_api=False)
    # No statement timeout: a backfill on a large table can take minutes. The lock timeout stays,
    # so a migration that can't get its locks fails fast instead of stalling live traffic.
    db = await AsyncpgDatabase.connect(settings, application_name="found-with-migrate", pool_size=1,
                                       statement_timeout_ms=0)
    try:
        async with db.acquire() as conn:
            applied = await apply_migrations(conn)
    finally:
        await db.close()
    print(json.dumps({"severity": "INFO", "message": "migrations finished", "applied": applied}))


if __name__ == "__main__":
    anyio.run(main)
