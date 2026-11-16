"""Moderation commands for the person running Found With. They run as a one-off execution of the
maintenance Cloud Run job, so nothing here is reachable from the internet:

    gcloud run jobs execute found-with-maintenance --region=asia-south1 --wait \\
        --args=-m,foundwith.admin,reports

Complaints mostly arrive at the help address and name a phone number. The job never gets the
number pepper, so turn the number into its hash on your own machine first (nothing is sent anywhere):

    NUMBER_PEPPER="$(gcloud secrets versions access latest --secret=found-with-number-pepper)" \\
        python -m foundwith.admin hash "+91 98450 31207"

and pass that hash to the commands below that take <hash>.

Commands:
    hash <number>                        (on your machine) the hash of a phone number
    number <hash>                        the account on that number, its holder count and popular name state
    suspend-number <hash> <reason>       suspend the account on that number (or the number, if it has none)
    hold-name <hash> <reason>            take away the number's popular name and never give it one again
    release-name <hash>                  undo hold-name; it can get one again from the next night
    reports                              list open reports (newest last)
    suspend-report <report id> <reason>  suspend the reported person and close every open report about them
    dismiss-report <report id> <reason>  close one report without action
    suspend <user id> <reason>           suspend by the id shown in reports
    unsuspend <user id>                  lift a suspension

A suspended person is taken out of every card and can no longer sync, search or look up numbers.
They can still delete their account; a suspension is remembered against the number for a while
after that, so signing up again doesn't clear it. Output goes to the job's logs as JSON."""

from __future__ import annotations

import json
import os
import sys
import uuid
from typing import Any

import anyio

from . import repo
from .config import Settings
from .db import AsyncpgDatabase, Conn, Database, Row


class AdminError(Exception):
    pass


async def _suspend_row(conn: Conn, user: Row, reason: str) -> None:
    await repo.set_suspended(conn, user["id"], reason)
    await repo.disable_sync(conn, user["id"])  # removes their numbers, so they vanish from every card


async def list_reports(db: Database, limit: int = 200) -> list[dict[str, Any]]:
    async with db.acquire() as conn:
        rows = await repo.open_reports(conn, limit)
    return [{
        "report_id": r["id"], "reason": r["reason"], "note": r["note"],
        "name_when_reported": r["reported_name"], "name_now": r["current_name"],
        "user_id": r["reported_public_id"], "already_suspended": bool(r["suspended"]),
        "open_reports_about_this_person": r["open_against_same_number"],
        "created_at": r["created_at"].isoformat(),
    } for r in rows]


async def suspend_user(db: Database, public_id: str, reason: str) -> dict[str, Any]:
    async with db.acquire() as conn, conn.transaction():
        user = await repo.user_by_public_id(conn, public_id)
        if user is None:
            raise AdminError(f"no account with id {public_id}")
        await _suspend_row(conn, user, reason)
        closed = await repo.resolve_reports_for_phone(conn, bytes(user["phone_hmac"]), f"suspended: {reason}")
    return {"suspended": public_id, "reports_closed": closed}


async def unsuspend_user(db: Database, public_id: str) -> dict[str, Any]:
    async with db.acquire() as conn, conn.transaction():
        user = await repo.user_by_public_id(conn, public_id)
        if user is None:
            raise AdminError(f"no account with id {public_id}")
        await repo.set_suspended(conn, user["id"], None)
    return {"unsuspended": public_id, "note": "they need to turn contact sync back on themselves"}


async def suspend_from_report(db: Database, report_id: int, reason: str) -> dict[str, Any]:
    async with db.acquire() as conn, conn.transaction():
        report = await repo.report_by_id(conn, report_id)
        if report is None:
            raise AdminError(f"no report with id {report_id}")
        phone_hmac = bytes(report["reported_phone_hmac"])
        user = await repo.user_by_phone(conn, phone_hmac)
        if user is not None:
            await _suspend_row(conn, user, reason)
        else:
            # The account is gone. Remember the suspension against the number instead.
            await repo.suspend_phone(conn, phone_hmac, reason)
        closed = await repo.resolve_reports_for_phone(conn, phone_hmac, f"suspended: {reason}")
    return {"suspended_from_report": report_id, "account_existed": user is not None, "reports_closed": closed}


async def dismiss_report(db: Database, report_id: int, reason: str) -> dict[str, Any]:
    async with db.acquire() as conn:
        if not await repo.resolve_report(conn, report_id, f"dismissed: {reason}"):
            raise AdminError(f"no open report with id {report_id}")
    return {"dismissed": report_id}


def _hash(value: str) -> bytes:
    try:
        digest = bytes.fromhex(value)
    except ValueError as exc:
        raise AdminError("expected the 64-character hash from the hash command") from exc
    if len(digest) != 32:
        raise AdminError("expected the 64-character hash from the hash command")
    return digest


def hash_number(raw: str, pepper: str) -> dict[str, Any]:
    """Runs on the moderator's own machine. Never print or log the pepper."""
    from .numbers import NumberHasher, normalise

    e164 = normalise(raw)
    if e164 is None:
        raise AdminError("not a phone number")
    try:
        hasher = NumberHasher(pepper.encode("utf-8"))
    except ValueError as exc:
        raise AdminError(str(exc)) from exc
    return {"number": e164, "hash": hasher.digest(e164).hex()}


async def number_info(db: Database, value: str) -> dict[str, Any]:
    async with db.acquire() as conn:
        row = await repo.number_overview(conn, _hash(value))
    return {
        "user_id": row["public_id"], "name_they_gave": row["display_name"],
        "joined_at": row["joined_at"], "suspended_at": row["suspended_at"] or row["number_suspended_at"],
        "people_who_have_it": row["holders"], "popular_name_from_people": row["popular_name_people"],
        "popular_name_held": row["popular_name_held"], "taken_out": bool(row["taken_out"]),
    }


async def suspend_number(db: Database, value: str, reason: str) -> dict[str, Any]:
    phone_hmac = _hash(value)
    async with db.acquire() as conn, conn.transaction():
        user = await repo.user_by_phone(conn, phone_hmac)
        if user is not None:
            await _suspend_row(conn, user, reason)
        else:
            await repo.suspend_phone(conn, phone_hmac, reason)
        closed = await repo.resolve_reports_for_phone(conn, phone_hmac, f"suspended: {reason}")
    return {"suspended_number": value, "account_existed": user is not None, "reports_closed": closed}


async def hold_name(db: Database, value: str, reason: str) -> dict[str, Any]:
    async with db.acquire() as conn:
        removed = await repo.hold_popular_name(conn, _hash(value), reason)
    return {"name_held": value, "had_a_popular_name": removed}


async def release_name(db: Database, value: str) -> dict[str, Any]:
    async with db.acquire() as conn:
        if not await repo.release_popular_name(conn, _hash(value)):
            raise AdminError("that number's name isn't held")
    return {"name_released": value, "note": "it can get a popular name again from the next nightly run"}


async def health_report(db: Database, days: int = 14) -> list[dict[str, Any]]:
    """After-call health by phone brand: of the calls phones saw, how many Found With checked."""
    async with db.acquire() as conn:
        rows = await repo.health_by_brand(conn, days)
    return [{"brand": r["brand"], "phone_days": r["phone_days"], "calls_seen": r["calls_seen"],
             "calls_checked": r["calls_checked"],
             "checked_pct": round(100 * r["calls_checked"] / r["calls_seen"], 1) if r["calls_seen"] else None}
            for r in rows]


async def run(db: Database, argv: list[str]) -> Any:
    command, args = (argv[0] if argv else ""), argv[1:]
    try:
        if command == "reports" and not args:
            return await list_reports(db)
        if command == "suspend-report" and len(args) >= 2:
            return await suspend_from_report(db, int(args[0]), " ".join(args[1:]))
        if command == "dismiss-report" and len(args) >= 2:
            return await dismiss_report(db, int(args[0]), " ".join(args[1:]))
        if command == "suspend" and len(args) >= 2:
            return await suspend_user(db, str(uuid.UUID(args[0])), " ".join(args[1:]))
        if command == "unsuspend" and len(args) == 1:
            return await unsuspend_user(db, str(uuid.UUID(args[0])))
        if command == "number" and len(args) == 1:
            return await number_info(db, args[0])
        if command == "suspend-number" and len(args) >= 2:
            return await suspend_number(db, args[0], " ".join(args[1:]))
        if command == "hold-name" and len(args) >= 2:
            return await hold_name(db, args[0], " ".join(args[1:]))
        if command == "release-name" and len(args) == 1:
            return await release_name(db, args[0])
        if command == "health" and len(args) <= 1:
            return await health_report(db, int(args[0]) if args else 14)
    except ValueError as exc:
        raise AdminError(f"bad argument: {exc}") from exc
    raise AdminError("usage: hash <number> | number <hash> | suspend-number <hash> <reason> | "
                     "hold-name <hash> <reason> | release-name <hash> | reports | suspend-report <id> <reason> | "
                     "dismiss-report <id> <reason> | suspend <user id> <reason> | unsuspend <user id> | health [days]")


async def main(argv: list[str]) -> int:
    if argv[:1] == ["hash"]:
        # No database: this runs on the moderator's machine, with the pepper in their shell only.
        try:
            if len(argv) < 2:
                raise AdminError("usage: hash <number>")
            print(json.dumps(hash_number(" ".join(argv[1:]), os.environ.get("NUMBER_PEPPER", ""))))
        except AdminError as exc:
            print(json.dumps({"severity": "ERROR", "message": str(exc)}))
            return 2
        return 0
    settings = Settings.from_env(for_api=False)
    db = await AsyncpgDatabase.connect(settings, application_name="found-with-admin", pool_size=1)
    try:
        result = await run(db, argv)
    except AdminError as exc:
        print(json.dumps({"severity": "ERROR", "message": str(exc)}))
        return 2
    finally:
        await db.close()
    for item in (result if isinstance(result, list) else [result]):
        print(json.dumps({"severity": "NOTICE", "message": "admin", **item}, default=str))
    if isinstance(result, list) and not result:
        print(json.dumps({"severity": "NOTICE", "message": "nothing to show"}))
    return 0


if __name__ == "__main__":
    sys.exit(anyio.run(main, sys.argv[1:]))
