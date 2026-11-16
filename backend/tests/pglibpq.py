"""Test-only Postgres adapter over libpq via ctypes.

It implements the small Conn/Database interface from foundwith.db with the same $n-parameter SQL
the app sends through asyncpg in production, so the tests run the real queries against a real
Postgres. Values are sent and read in Postgres text format."""

from __future__ import annotations

import ctypes
import ctypes.util
import re
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from datetime import date, datetime
from decimal import Decimal
from typing import Any

_lib = ctypes.CDLL(ctypes.util.find_library("pq") or "libpq.so.5")
_P = ctypes.c_void_p
for _name, _args, _res in [
    ("PQconnectdb", [ctypes.c_char_p], _P),
    ("PQstatus", [_P], ctypes.c_int),
    ("PQerrorMessage", [_P], ctypes.c_char_p),
    ("PQfinish", [_P], None),
    ("PQexec", [_P, ctypes.c_char_p], _P),
    ("PQexecParams", [_P, ctypes.c_char_p, ctypes.c_int, _P, ctypes.POINTER(ctypes.c_char_p), _P, _P, ctypes.c_int], _P),
    ("PQresultStatus", [_P], ctypes.c_int),
    ("PQresultErrorMessage", [_P], ctypes.c_char_p),
    ("PQresultErrorField", [_P, ctypes.c_int], ctypes.c_char_p),
    ("PQntuples", [_P], ctypes.c_int),
    ("PQnfields", [_P], ctypes.c_int),
    ("PQfname", [_P, ctypes.c_int], ctypes.c_char_p),
    ("PQftype", [_P, ctypes.c_int], ctypes.c_uint),
    ("PQgetisnull", [_P, ctypes.c_int, ctypes.c_int], ctypes.c_int),
    ("PQgetvalue", [_P, ctypes.c_int, ctypes.c_int], _P),
    ("PQgetlength", [_P, ctypes.c_int, ctypes.c_int], ctypes.c_int),
    ("PQcmdStatus", [_P], ctypes.c_char_p),
    ("PQclear", [_P], None),
]:
    _fn = getattr(_lib, _name)
    _fn.argtypes = _args
    _fn.restype = _res

_COMMAND_OK, _TUPLES_OK = 1, 2
_SQLSTATE = ord("C")


class PgError(Exception):
    def __init__(self, sqlstate: str | None, message: str) -> None:
        super().__init__(f"[{sqlstate}] {message.strip()}")
        self.sqlstate = sqlstate


def _encode(value: Any) -> str | None:
    if value is None:
        return None
    if isinstance(value, bool):
        return "t" if value else "f"
    if isinstance(value, (bytes, bytearray, memoryview)):
        return "\\x" + bytes(value).hex()
    if isinstance(value, (int, float, Decimal)):
        return str(value)
    if isinstance(value, (datetime, date)):
        return value.isoformat()
    if isinstance(value, str):
        return value
    if isinstance(value, (list, tuple)):
        return "{" + ",".join(_array_item(v) for v in value) + "}"
    raise TypeError(f"can't send {type(value).__name__} to Postgres")


def _array_item(value: Any) -> str:
    if value is None:
        return "NULL"
    text = _encode(value)
    assert text is not None
    return '"' + text.replace("\\", "\\\\").replace('"', '\\"') + '"'


def _timestamp(text: str) -> datetime:
    text = text.replace(" ", "T", 1)
    if re.search(r"[+-]\d\d$", text):
        text += ":00"
    return datetime.fromisoformat(text)


def _decode(oid: int, raw: bytes) -> Any:
    text = raw.decode("utf-8")
    if oid == 16:
        return text == "t"
    if oid == 17:
        return bytes.fromhex(text[2:])
    if oid in (20, 21, 23, 26):
        return int(text)
    if oid in (700, 701):
        return float(text)
    if oid == 1700:
        return Decimal(text)
    if oid == 1082:
        return date.fromisoformat(text)
    if oid in (1114, 1184):
        return _timestamp(text)
    return text


class LibpqConnection:
    def __init__(self, conninfo: str) -> None:
        self._conn = _lib.PQconnectdb(conninfo.encode("utf-8"))
        if _lib.PQstatus(self._conn) != 0:
            message = (_lib.PQerrorMessage(self._conn) or b"").decode()
            _lib.PQfinish(self._conn)
            raise PgError(None, message)
        self._depth = 0
        self.run("SET TIME ZONE 'UTC'")

    def run(self, sql: str, args: tuple[Any, ...] = ()) -> tuple[list[dict[str, Any]], str]:
        if args:
            values = [_encode(a) for a in args]
            array = (ctypes.c_char_p * len(values))(*[v.encode("utf-8") if v is not None else None for v in values])
            res = _lib.PQexecParams(self._conn, sql.encode("utf-8"), len(values), None, array, None, None, 0)
        else:
            res = _lib.PQexec(self._conn, sql.encode("utf-8"))
        if not res:
            raise PgError(None, (_lib.PQerrorMessage(self._conn) or b"").decode())
        try:
            status = _lib.PQresultStatus(res)
            if status not in (_COMMAND_OK, _TUPLES_OK):
                state = _lib.PQresultErrorField(res, _SQLSTATE)
                message = _lib.PQresultErrorMessage(res) or b"query failed"
                raise PgError(state.decode() if state else None, message.decode())
            rows: list[dict[str, Any]] = []
            if status == _TUPLES_OK:
                nfields = _lib.PQnfields(res)
                names = [_lib.PQfname(res, i).decode() for i in range(nfields)]
                oids = [_lib.PQftype(res, i) for i in range(nfields)]
                for r in range(_lib.PQntuples(res)):
                    row: dict[str, Any] = {}
                    for c in range(nfields):
                        if _lib.PQgetisnull(res, r, c):
                            row[names[c]] = None
                        else:
                            raw = ctypes.string_at(_lib.PQgetvalue(res, r, c), _lib.PQgetlength(res, r, c))
                            row[names[c]] = _decode(oids[c], raw)
                    rows.append(row)
            return rows, (_lib.PQcmdStatus(res) or b"").decode()
        finally:
            _lib.PQclear(res)

    # -- the Conn interface used by the app
    async def fetch(self, sql: str, *args: Any) -> list[dict[str, Any]]:
        return self.run(sql, args)[0]

    async def fetchrow(self, sql: str, *args: Any) -> dict[str, Any] | None:
        rows = self.run(sql, args)[0]
        return rows[0] if rows else None

    async def fetchval(self, sql: str, *args: Any) -> Any:
        row = await self.fetchrow(sql, *args)
        return None if row is None else next(iter(row.values()))

    async def execute(self, sql: str, *args: Any) -> str:
        return self.run(sql, args)[1]

    @asynccontextmanager
    async def transaction(self) -> AsyncIterator[None]:
        self.run("BEGIN" if self._depth == 0 else f"SAVEPOINT sp{self._depth}")
        self._depth += 1
        try:
            yield
        except BaseException:
            self._depth -= 1
            self.run("ROLLBACK" if self._depth == 0 else f"ROLLBACK TO SAVEPOINT sp{self._depth}")
            raise
        else:
            self._depth -= 1
            self.run("COMMIT" if self._depth == 0 else f"RELEASE SAVEPOINT sp{self._depth}")

    def close(self) -> None:
        if self._conn:
            _lib.PQfinish(self._conn)
            self._conn = None


class LibpqDatabase:
    """One connection, used serially. Enough for tests; production uses the asyncpg pool."""

    def __init__(self, conninfo: str) -> None:
        self.conn = LibpqConnection(conninfo)

    @asynccontextmanager
    async def acquire(self) -> AsyncIterator[LibpqConnection]:
        yield self.conn

    async def close(self) -> None:
        self.conn.close()
