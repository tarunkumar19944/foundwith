"""Database access. The app talks to a tiny interface so the tests can run the same SQL against a
throwaway Postgres without a network-installed driver; production uses an asyncpg pool."""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from contextlib import AbstractAsyncContextManager
from typing import Any, Protocol

from .config import Settings

Row = Mapping[str, Any]


class Conn(Protocol):
    """The subset of asyncpg.Connection the app uses. SQL uses $1-style parameters."""

    async def fetch(self, sql: str, *args: Any) -> Sequence[Row]: ...
    async def fetchrow(self, sql: str, *args: Any) -> Row | None: ...
    async def fetchval(self, sql: str, *args: Any) -> Any: ...
    async def execute(self, sql: str, *args: Any) -> str: ...
    def transaction(self) -> AbstractAsyncContextManager[Any]: ...


class Database(Protocol):
    def acquire(self) -> AbstractAsyncContextManager[Conn]: ...
    async def close(self) -> None: ...


class AsyncpgDatabase:
    """Connection pool for Cloud SQL. With DB_HOST=/cloudsql/<instance> asyncpg uses the Unix
    socket that Cloud Run mounts, so traffic never leaves Google's network and needs no IP allowlist."""

    def __init__(self, pool: Any, acquire_timeout_s: float = 5.0) -> None:
        self._pool = pool
        self._acquire_timeout_s = acquire_timeout_s

    @classmethod
    async def connect(cls, settings: Settings, *, application_name: str = "found-with-api",
                      pool_size: int | None = None, statement_timeout_ms: int = 10_000) -> AsyncpgDatabase:
        import asyncpg  # imported here so tests and tooling don't need the driver installed

        pool = await asyncpg.create_pool(
            host=settings.db_host,
            port=settings.db_port,
            database=settings.db_name,
            user=settings.db_user,
            password=settings.db_password or None,
            min_size=1,
            max_size=pool_size or settings.db_pool_size,
            timeout=10,                 # connecting
            command_timeout=15,         # any single statement, measured by the client
            max_inactive_connection_lifetime=300,
            server_settings={
                "application_name": application_name,
                # Enforced by Postgres itself, so a runaway query is stopped even if the client
                # has gone away. Lookups take milliseconds; a full sync of 10,000 numbers about a second.
                "statement_timeout": str(statement_timeout_ms),
                "idle_in_transaction_session_timeout": "15000",
                "lock_timeout": "5000",
            },
        )
        return cls(pool, settings.db_acquire_timeout_s)

    def acquire(self) -> AbstractAsyncContextManager[Conn]:
        # When every connection is busy, wait a few seconds and then fail with a timeout
        # (the API answers 503) rather than queueing requests until Cloud Run cuts them off.
        return self._pool.acquire(timeout=self._acquire_timeout_s)

    async def close(self) -> None:
        await self._pool.close()
