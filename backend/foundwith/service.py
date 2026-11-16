"""Found With's product rules (v0.4, 16 Nov 2026).

  1. A user takes part (their numbers count for others) only after allowing it, with consent to the
     current terms. Before that they can still see their own number's count and search, by count.
  2. A person "has" a number when it is in their contacts or their call log.
  3. "Found with N": everyone taking part who has the number, never counting the viewer. With
     visibility "known" (the default) only people the viewer knows are named: people whose own
     number is in the viewer's contacts or call log. The rest are a count.
  4. Every number looks the same everywhere (Home, search, after calls). Its name, in order: the
     viewer's own contact name (on the phone), the owner's own Found With name, the popular name
     (what at least 3 established people saved), the number.
  5. Search (any Indian mobile) and after-call lookups follow rule 3, within daily quotas.
  6. "Hide this number" removes it from Found With for that user in both directions.
  7. Anyone can take their own number out of Found With for everybody (opt-out), account or not.
  8. People can block each other: neither sees the other anywhere.
"""

from __future__ import annotations

import base64
import binascii
import bisect
import json
import logging
import math
import re
import secrets
import uuid
from collections.abc import Sequence
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any

from . import repo
from .auth import Identity
from .config import Settings
from .db import Conn, Database, Row
from .errors import (AccountExists, AccountSuspended, ApiError, ConsentRequired, FullSyncRequired,  # noqa: F401
                     InvalidIdentity, InvalidNumber, InviteNotFound, InviteRejected, LimitReached,
                     NameChangeTooSoon, NameNotAllowed, NotFound, NotRegistered, QuotaExceeded, RateLimited,
                     ReauthRequired, SyncRequired)
from .identity import IdentityAdmin
from .contact_names import NameCipher, tidy_contact_name
from .names import clean_display_name
from .numbers import NumberHasher, from_ref, is_indian_mobile, normalise, to_ref

log = logging.getLogger("foundwith")

KNOWN_PER_CARD_MAX = 100
SAMPLE_KNOWN_SKIP_MAX = 200     # how many known holders we are prepared to skip while sampling the rest
SAMPLE_KNOWN_EXCLUDE_MAX = 2000  # above this many known holders, skip them in Python instead of in SQL
SEEN_REFRESH_S = 6 * 3600       # how often a signed-in request refreshes "last seen"
DAY_S = 86_400
NOT_REGISTERED = "Create your profile to start using Found With."
INVITE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"   # no 0/O or 1/I, so a code read aloud survives
INVITE_CODE = re.compile(r"^[A-HJ-NP-Z2-9]{8}$")
MAX_PUSH_NUMBERS = 2000   # numbers per sync whose holders get a live update


# ---------------------------------------------------------------- results

@dataclass
class Holder:
    id: str
    name: str | None   # their Found With name; None until they choose one
    known: bool
    contact_ref: str | None = None  # set when the viewer has this person saved, so the app can show its own label

    def as_json(self) -> dict[str, Any]:
        out: dict[str, Any] = {"id": self.id, "name": self.name, "known": self.known}
        if self.contact_ref:
            out["contact_ref"] = self.contact_ref
        return out


@dataclass
class Match:
    number_hmac: bytes
    total: int = 0
    total_is_lower_bound: bool = False
    known: list[Holder] = field(default_factory=list)
    others: list[Holder] = field(default_factory=list)
    known_ids: frozenset[int] = frozenset()   # every known holder, including any beyond the listed ones
    name: str | None = None                   # the owner's own Found With name (rule 4)
    popular_name: str | None = None           # what most people saved for it (rule 4)
    owner_id: str | None = None               # the owner's public id, when they take part (for Block)

    @property
    def ref(self) -> str:
        return to_ref(self.number_hmac)

    @property
    def more(self) -> int:
        return max(0, self.total - len(self.known) - len(self.others))

    def as_json(self) -> dict[str, Any]:
        return {
            "ref": self.ref,
            "total": self.total,
            "total_is_lower_bound": self.total_is_lower_bound,
            "known": [h.as_json() for h in self.known],
            "others": [h.as_json() for h in self.others],
            "more": self.more,
            "name": self.name,
            "popular_name": self.popular_name,
            "on_found_with": self.owner_id is not None,
            "owner_id": self.owner_id,
        }


def _iso(value: datetime | None) -> str | None:
    return value.isoformat() if value else None


def encode_cursor(value: Any) -> str:
    raw = json.dumps(value, separators=(",", ":")).encode("utf-8")
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def _decode_cursor(cursor: str | None) -> Any:
    if not cursor:
        return None
    try:
        if len(cursor) > 200:
            raise ValueError("too long")
        return json.loads(base64.urlsafe_b64decode(cursor + "=" * (-len(cursor) % 4)))
    except (binascii.Error, ValueError, UnicodeDecodeError) as exc:
        raise ApiError("That page link is not valid.") from exc


def _holders_cursor(cursor: str | None) -> int:
    value = _decode_cursor(cursor)
    if value is None:
        return 0
    if isinstance(value, bool) or not isinstance(value, int) or not 0 <= value <= repo.MAX_BIGINT:
        raise ApiError("That page link is not valid.")
    return value


def _cards_cursor(cursor: str | None) -> tuple[int, int, str] | None:
    value = _decode_cursor(cursor)
    if value is None:
        return None
    ok = (isinstance(value, list) and len(value) == 3
          and all(isinstance(v, int) and not isinstance(v, bool) and v <= 0 for v in value[:2])
          and isinstance(value[2], str) and len(value[2]) == 43)
    if not ok:
        raise ApiError("That page link is not valid.")
    return value[0], value[1], value[2]


def _public_id(value: str, what: str = "person") -> str:
    try:
        return str(uuid.UUID(str(value)))
    except ValueError as exc:
        raise NotFound(f"We couldn't find that {what}.") from exc


def _days(n: int) -> str:
    return "1 day" if n == 1 else f"{n} days"


# ---------------------------------------------------------------- sign-in account deletion

async def delete_sign_in_account(db: Database, identity: IdentityAdmin, auth_uid: str) -> bool:
    """Delete the Identity Platform account. Failures are recorded and retried by the maintenance job.
    Nothing is deleted if the person has signed in again with the same sign-in account."""
    async with db.acquire() as conn:
        if not await repo.auth_deletion_still_wanted(conn, auth_uid):
            await repo.cancel_auth_deletion(conn, auth_uid)
            return True
    error: str | None = None
    try:
        await identity.delete_user(auth_uid)
    except Exception as exc:  # noqa: BLE001 - recorded for retry
        error = f"{type(exc).__name__}: {exc}"
        log.warning("sign-in account deletion failed; will retry", extra={"event": "auth_delete_failed"})
    async with db.acquire() as conn:
        await repo.record_auth_deletion(conn, auth_uid, error)
    return error is None


async def retry_sign_in_deletions(db: Database, identity: IdentityAdmin,
                                  max_attempts: int = 10, batch: int = 200) -> tuple[int, int]:
    async with db.acquire() as conn:
        pending = list(await repo.pending_auth_deletions(conn, max_attempts, batch))
    done = failed = 0
    for row in pending:
        if await delete_sign_in_account(db, identity, row["auth_uid"]):
            done += 1
        else:
            failed += 1
    return done, failed


# ---------------------------------------------------------------- service

class Service:
    def __init__(self, db: Database, hasher: NumberHasher, settings: Settings, identity: IdentityAdmin) -> None:
        self.db = db
        self.hasher = hasher
        self.settings = settings
        self.identity = identity
        self.names = NameCipher(settings.name_key)
        self.quota_exempt = frozenset(
            hasher.digest(e164) for e164 in (normalise(n, settings.country_code) for n in settings.quota_exempt_numbers) if e164)

    # ---------- accounts

    def _own_phone_hmac(self, ident: Identity) -> bytes:
        e164 = normalise(ident.phone, self.settings.country_code)
        if e164 is None:
            raise InvalidIdentity("Your sign-in doesn't carry a valid phone number. Sign in again.")
        return self.hasher.digest(e164)

    async def _retire_other_owners(self, conn: Conn, phone_hmac: bytes, keep_uid: str) -> bool:
        """A verified number now belongs to a different sign-in account. Whatever the earlier account
        synced describes someone else's address book, so that account is removed. Its sign-in
        account is left alone: it may by now belong to a different number."""
        others = list(await repo.other_owners_of_phone(conn, phone_hmac, keep_uid))
        for other in others:
            await repo.retire_user(conn, other["id"])
        if others:
            log.info("removed an earlier account for this number", extra={"event": "number_reassigned"})
        return bool(others)

    async def current_user(self, ident: Identity, *, allow_suspended: bool = False) -> Row:
        phone_hmac = self._own_phone_hmac(ident)
        async with self.db.acquire() as conn:
            user = await repo.user_by_auth_uid(conn, ident.uid)
            if user is None:
                raise NotRegistered(NOT_REGISTERED)
            if bytes(user["phone_hmac"]) != phone_hmac:
                # The sign-in account was moved to a new verified number.
                async with conn.transaction():
                    await self._retire_other_owners(conn, phone_hmac, ident.uid)
                    await repo.update_phone(conn, user["id"], phone_hmac)
                user = await repo.user_by_auth_uid(conn, ident.uid)
                if user is None:
                    raise NotRegistered(NOT_REGISTERED)
            elif user["seen_age_s"] >= SEEN_REFRESH_S:
                await repo.touch_user(conn, user["id"])
        if user["suspended_at"] is not None and not allow_suspended:
            raise AccountSuspended("This account is suspended. Contact support if you think this is a mistake.")
        return user

    async def register(self, ident: Identity, display_name: str | None = None) -> Row:
        """Create the account for the signed-in number, with or without a name: the app signs
        people in first and asks for a name afterwards. Calling it again changes nothing:
        names are set and changed with rename()."""
        phone_hmac = self._own_phone_hmac(ident)
        async with self.db.acquire() as conn:
            existing = await repo.user_by_auth_uid(conn, ident.uid)
        if existing is not None:
            return existing
        name = clean_display_name(display_name) if display_name and display_name.strip() else None
        async with self.db.acquire() as conn, conn.transaction():
            retired = await self._retire_other_owners(conn, phone_hmac, ident.uid)
            tomb = await repo.tombstone(conn, phone_hmac)
            if tomb is not None and not retired and ident.auth_time <= tomb["deleted_epoch"]:
                # The account for this number was deleted after this sign-in happened. Without this
                # check, a still-valid token could re-create the account in a loop to reset its limits.
                raise ReauthRequired("Sign in again to set up Found With.")
            user = await repo.insert_user(conn, ident.uid, phone_hmac, name, self.settings.new_numbers_burst)
            if user is None:  # a concurrent request created it first
                user = await repo.user_by_auth_uid(conn, ident.uid)
                if user is None:
                    raise NotRegistered(NOT_REGISTERED)
                return user
            if tomb is not None:
                await repo.consume_tombstone(conn, user["id"], phone_hmac)
            await repo.cancel_auth_deletion(conn, ident.uid)
            return user

    async def rename(self, user: Row, display_name: str) -> Row:
        name = clean_display_name(display_name)
        wait_s = self.settings.name_change_days * DAY_S
        async with self.db.acquire() as conn, conn.transaction():
            locked = await repo.lock_user(conn, user["id"])
            if locked is None:
                raise NotRegistered(NOT_REGISTERED)
            if locked["display_name"] == name:
                return locked
            if locked["display_name"] is None:
                # Choosing a name for the first time isn't a change, so it doesn't start the wait.
                return await repo.update_display_name(conn, user["id"], name, first=True)
            age = locked["name_age_s"]
            if age is not None and age < wait_s:
                days_left = max(1, math.ceil((wait_s - age) / DAY_S))
                raise NameChangeTooSoon(
                    f"You changed your name recently. You can change it again in {_days(days_left)}.",
                    details={"days_left": days_left})
            return await repo.update_display_name(conn, user["id"], name)

    async def profile(self, user: Row, ident: Identity) -> dict[str, Any]:
        s = self.settings
        async with self.db.acquire() as conn:
            used = await repo.quota_used_today(conn, user["id"], s.quota_timezone)
            opted_out = await repo.is_suppressed(conn, bytes(user["phone_hmac"]))
            search_limit = s.search_daily_limit
        return {
            "id": user["public_id"],
            "name": user["display_name"],
            "phone": normalise(ident.phone, s.country_code),
            "suspended": user["suspended_at"] is not None,
            "number_opted_out": opted_out,
            "visibility": s.visibility,
            "evening_note": user["evening_note"],
            "sync": {
                "enabled": user["sync_enabled"],
                "consent_version": user["consent_version"],
                "current_consent_version": s.consent_version,
                "consent_current": user["consent_version"] == s.consent_version,
                "first_sync_at": _iso(user["first_sync_at"]),
            },
            "quota": {
                "search": {"limit": search_limit, "left": max(0, search_limit - used.get("search", 0))},
                "call": {"limit": s.call_daily_limit, "left": max(0, s.call_daily_limit - used.get("call", 0))},
            },
            "limits": {
                "numbers": s.max_numbers_per_user,
                "new_numbers_per_day": s.new_numbers_per_day,
                "holders_list": s.holders_list_cap,
            },
            "created_at": _iso(user["created_at"]),
        }

    async def set_sync(self, user: Row, enabled: bool, consent_version: str | None) -> Row:
        async with self.db.acquire() as conn, conn.transaction():
            if await repo.lock_user(conn, user["id"]) is None:
                raise NotRegistered(NOT_REGISTERED)
            if not enabled:
                return await repo.disable_sync(conn, user["id"])
            if consent_version != self.settings.consent_version:
                raise ConsentRequired(
                    "Accept the current contact sync terms to turn on sync.",
                    details={"current_consent_version": self.settings.consent_version})
            return await repo.enable_sync(conn, user["id"], consent_version)

    async def set_evening_note(self, user: Row, on: bool) -> None:
        async with self.db.acquire() as conn:
            await repo.set_evening_note(conn, user["id"], on)

    async def delete_account(self, user: Row) -> dict[str, Any]:
        async with self.db.acquire() as conn, conn.transaction():
            await repo.retire_user(conn, user["id"])
            await repo.queue_auth_deletion(conn, user["auth_uid"])
        sign_in_deleted = await delete_sign_in_account(self.db, self.identity, user["auth_uid"])
        return {"deleted": True, "sign_in_account_deleted": sign_in_deleted}

    # ---------- address book sync

    def _digests(self, values: Sequence[Any], own: bytes, into: dict[bytes, str], cache: dict[str, bytes],
                 names: dict[bytes, str] | None = None) -> tuple[list[bytes], int]:
        """Normalise and hash raw entries. An entry is the number as saved, or
        {"number": ..., "name": ...} with the name it was saved under. Returns the digests in the
        order sent (duplicates and the person's own number dropped) and how many entries could not
        be matched. Names go into `names` (the first name sent for a number wins)."""
        out: list[bytes] = []
        seen: set[bytes] = set()
        skipped = 0
        for entry in values:
            raw, name = entry, None
            if isinstance(entry, dict):
                raw, name = entry.get("number"), tidy_contact_name(entry.get("name"))
            e164 = normalise(raw, self.settings.country_code)
            if e164 is None:
                skipped += 1
                continue
            digest = cache.get(e164)
            if digest is None:
                digest = cache[e164] = self.hasher.digest(e164)
            if digest == own or digest in seen:
                continue
            seen.add(digest)
            out.append(digest)
            into[digest] = e164
            if names is not None and name:
                names.setdefault(digest, name)
        return out, skipped

    async def _store_names(self, conn: Conn, user_id: int, numbers: Sequence[bytes],
                           target: dict[bytes, tuple[bool, bool]], names: dict[bytes, str]) -> None:
        """Keep the saved name of each stored contact (encrypted), and drop names of numbers that
        are no longer contacts."""
        hmacs, name_hmacs, name_encs = [], [], []
        for d in sorted(numbers):
            in_c = target.get(d, (False, False))[0]
            name = names.get(d) if in_c else None
            hmacs.append(d)
            name_hmacs.append(self.names.name_hmac(name) if name else None)
            name_encs.append(self.names.encrypt(name, d) if name else None)
        await repo.set_contact_names(conn, user_id, hmacs, name_hmacs, name_encs)

    async def _locked_for_sync(self, conn: Conn, user: Row) -> Row:
        s = self.settings
        locked = await repo.lock_user(conn, user["id"])
        if locked is None:
            raise NotRegistered(NOT_REGISTERED)
        if not locked["sync_enabled"]:
            raise SyncRequired("Turn on contact sync to see your cards.")
        if locked["consent_version"] != s.consent_version:
            raise ConsentRequired(
                "The contact sync terms have changed. Accept the current terms to keep syncing.",
                details={"current_consent_version": s.consent_version})
        return locked

    async def _write_numbers(self, conn: Conn, locked: Row, target: dict[bytes, tuple[bool, bool]],
                             dropping: Sequence[bytes], existing: dict[bytes, tuple[bool, bool, bool]],
                             stored_total_before: int) -> tuple[list[bytes], int, int, list[bytes]]:
        """Store `target` (number -> flags, in admission order) and drop the address-book flags of
        `dropping`. Numbers already stored are kept for free. New numbers are admitted from the
        person's allowance; what doesn't fit is deferred, never rejected.
        Returns (numbers stored now from `target`, how many were deferred, how many were removed,
        numbers whose holder counts changed)."""
        s = self.settings
        user_id = locked["id"]
        stored_before = {d for d, (in_c, in_l, _) in existing.items() if in_c or in_l}
        kept = [d for d in target if d in stored_before]
        new = [d for d in target if d not in stored_before]
        dropped_stored = sum(1 for d in dropping if d in stored_before)

        available = min(float(s.new_numbers_burst),
                        float(locked["admit_tokens"]) + s.new_numbers_per_day * float(locked["admit_age_s"]) / DAY_S)
        room = max(0, s.max_numbers_per_user - (stored_total_before - dropped_stored))
        n_admit = max(0, min(len(new), room, int(available)))
        admitted = new[:n_admit]

        changes: list[tuple[bytes, bool, bool]] = []
        for digest in kept + admitted:
            in_c, in_l = target[digest]
            old = existing.get(digest)
            if old is None or old[0] != in_c or old[1] != in_l:
                changes.append((digest, in_c, in_l))
        removed: list[bytes] = []
        cleared: list[bytes] = []
        for digest in dropping:
            old = existing.get(digest)
            if old is None:
                continue
            in_c, in_l, hidden = old
            if not hidden:
                removed.append(digest)
            elif in_c or in_l:
                cleared.append(digest)  # keep the hide choice, drop the address-book flags

        # Lock every holder count this write will change before changing anything, in one
        # statement and in number order (see repo.lock_counts).
        def counted(flags: tuple[bool, bool, bool] | None) -> bool:
            return bool(flags) and flags[0] and not flags[2]

        affected = [d for d, in_c, _ in changes
                    if counted(existing.get(d)) != (in_c and not (existing.get(d) or (False, False, False))[2])]
        affected += [d for d in removed if counted(existing.get(d))]
        await repo.lock_counts(conn, affected)
        changes.sort()
        await repo.upsert_numbers(conn, user_id, [c[0] for c in changes],
                                  [c[1] for c in changes], [c[2] for c in changes])
        await repo.clear_flags_of_hidden(conn, user_id, sorted(cleared))
        await repo.delete_numbers(conn, user_id, sorted(removed))
        await repo.mark_synced(conn, user_id, available - n_admit)
        return kept + admitted, len(new) - n_admit, len(removed), affected

    async def _in_transaction(self, work: Any) -> Any:
        """Run `work(conn)` in a transaction, retrying a couple of times if Postgres aborts it to
        break a deadlock or a serialization conflict (two syncs updating the same holder counts)."""
        for attempt in range(3):
            try:
                async with self.db.acquire() as conn, conn.transaction():
                    return await work(conn)
            except Exception as exc:
                if attempt == 2 or getattr(exc, "sqlstate", None) not in ("40P01", "40001"):
                    raise
                log.info("retrying a transaction after a lock conflict", extra={"event": "txn_retry"})
        raise AssertionError("unreachable")

    async def sync_numbers(self, user: Row, contacts: Sequence[str], call_log: Sequence[str]) -> dict[str, Any]:
        """Replace the user's synced numbers with this snapshot of their phone.

        Numbers already stored are kept for free. New numbers are admitted, contacts first, from the
        person's allowance; what doesn't fit is deferred to a later sync, never rejected."""
        e164_of: dict[bytes, str] = {}
        cache: dict[str, bytes] = {}
        names: dict[bytes, str] = {}
        own = bytes(user["phone_hmac"])
        in_contacts, skipped_c = self._digests(contacts, own, e164_of, cache, names)
        in_log, skipped_l = self._digests(call_log, own, e164_of, cache)
        contact_set, log_set = set(in_contacts), set(in_log)
        wanted = {d: (d in contact_set, d in log_set) for d in in_contacts + in_log}  # contacts first
        news: dict[str, Any] = {}

        async def work(conn: Conn) -> dict[str, Any]:
            locked = await self._locked_for_sync(conn, user)
            # Numbers whose owners opted out are never stored.
            suppressed = {bytes(r["number_hmac"]) for r in await repo.suppressed_among(conn, list(wanted))}
            target = {d: f for d, f in wanted.items() if d not in suppressed}
            existing = {bytes(r["number_hmac"]): (r["in_contacts"], r["in_call_log"], r["hidden"])
                        for r in await repo.own_numbers(conn, user["id"])}
            stored_total = sum(1 for in_c, in_l, _ in existing.values() if in_c or in_l)
            dropping = [d for d in existing if d not in target]
            stored, deferred, removed, affected = await self._write_numbers(conn, locked, target, dropping, existing, stored_total)
            await self._store_names(conn, user["id"], stored, target, names)
            stored_before = {d for d, (in_c, in_l, _) in existing.items() if in_c or in_l}
            news.update(affected=affected, joined=locked["first_sync_at"] is None and bool(stored))
            return {
                # Only numbers that are now stored get a reference.
                "refs": {e164_of[d]: to_ref(d) for d in stored},
                "stats": {
                    "numbers": len(stored),
                    "added": sum(1 for d in stored if d not in stored_before),
                    "removed": removed,
                    "skipped": skipped_c + skipped_l + len(suppressed),
                    "deferred": deferred,
                },
            }

        result = await self._in_transaction(work)
        await self._queue_after_sync(user, news.get("affected", []), news.get("joined", False))
        return result

    async def apply_changes(self, user: Row, contacts_added: Sequence[Any], contacts_removed: Sequence[Any],
                            call_log_added: Sequence[Any], call_log_removed: Sequence[Any]) -> dict[str, Any]:
        """Apply what changed on the phone since the last sync, so a newly saved contact gets its
        card in seconds instead of waiting for the next full sync. The full sync still runs now and
        then and is the source of truth. A number on both an added and a removed list for the same
        source counts as added."""
        e164_of: dict[bytes, str] = {}
        cache: dict[str, bytes] = {}
        names: dict[bytes, str] = {}
        own = bytes(user["phone_hmac"])
        c_add, sk1 = self._digests(contacts_added, own, e164_of, cache, names)
        c_rem, sk2 = self._digests(contacts_removed, own, {}, cache)
        l_add, sk3 = self._digests(call_log_added, own, e164_of, cache)
        l_rem, sk4 = self._digests(call_log_removed, own, {}, cache)
        touched = list(dict.fromkeys(c_add + l_add + c_rem + l_rem))
        news: dict[str, Any] = {}

        async def work(conn: Conn) -> dict[str, Any]:
            locked = await self._locked_for_sync(conn, user)
            if locked["first_sync_at"] is None:
                raise FullSyncRequired("Send your full address book once before sending changes.")
            suppressed = {bytes(r["number_hmac"]) for r in await repo.suppressed_among(conn, touched)}
            existing = {bytes(r["number_hmac"]): (r["in_contacts"], r["in_call_log"], r["hidden"])
                        for r in await repo.own_numbers_among(conn, user["id"], touched)}
            flags = {d: list(existing.get(d, (False, False, False))[:2]) for d in touched}
            for d in c_rem:
                flags[d][0] = False
            for d in l_rem:
                flags[d][1] = False
            for d in c_add:
                flags[d][0] = True
            for d in l_add:
                flags[d][1] = True
            # Admission order: new contacts first, then new call-log numbers.
            order = list(dict.fromkeys(c_add + l_add + [d for d in touched if d in existing]))
            target = {d: (flags[d][0], flags[d][1]) for d in order
                      if (flags[d][0] or flags[d][1]) and d not in suppressed}
            dropping = [d for d in touched if d not in target and d in existing]
            stored_total = await repo.count_stored(conn, user["id"])
            stored, deferred, removed, affected = await self._write_numbers(conn, locked, target, dropping, existing, stored_total)
            # Names: new contacts get theirs; a number that stopped being a contact loses its name.
            await self._store_names(conn, user["id"], [d for d in stored if d in set(c_add) or d in set(c_rem)],
                                    target, names)
            stored_before = {d for d, (in_c, in_l, _) in existing.items() if in_c or in_l}
            news["affected"] = affected
            return {
                "refs": {e164_of[d]: to_ref(d) for d in stored if d in e164_of},
                "stats": {
                    "added": sum(1 for d in stored if d not in stored_before),
                    "removed": removed,
                    "skipped": sk1 + sk2 + sk3 + sk4 + len(suppressed & set(c_add + l_add)),
                    "deferred": deferred,
                },
            }

        result = await self._in_transaction(work)
        await self._queue_after_sync(user, news.get("affected", []), False)
        return result

    async def _queue_after_sync(self, user: Row, affected: Sequence[bytes], joined: bool) -> None:
        """After a sync: the people whose cards it changed get a live update, and when someone
        takes part for the first time, everyone who has their number hears it now. Best effort:
        a failure here never fails the sync."""
        s = self.settings
        if s.push_mode == "off" or not (affected or joined):
            return
        try:
            async with self.db.acquire() as conn:
                if affected:
                    await repo.queue_card_pushes(conn, user["id"], sorted(affected)[:MAX_PUSH_NUMBERS], s.cards_push_gap_s)
                if joined:
                    own = bytes(user["phone_hmac"])
                    await repo.queue_joined_pushes(conn, user["id"], own, await repo.excluded_ids(conn, user["id"], own))
        except Exception:  # noqa: BLE001
            log.warning("could not queue live updates", exc_info=True, extra={"event": "push_queue_failed"})

    # ---------- matching

    async def _summaries(self, conn: Conn, viewer_id: int, hmacs: Sequence[bytes],
                         excluded: Sequence[int]) -> dict[bytes, Match]:
        """Totals and known holders for every number that has at least one holder."""
        if not hmacs:
            return {}
        s = self.settings
        totals = await repo.holder_totals(conn, viewer_id, hmacs, excluded)
        if not totals:
            return {}
        present = [bytes(r["number_hmac"]) for r in totals]
        known: dict[bytes, list[tuple[int, Holder]]] = {}
        for r in await repo.known_holders(conn, viewer_id, present, excluded, self.settings.consent_version):
            holder = Holder(r["public_id"], r["display_name"], True, to_ref(bytes(r["phone_hmac"])))
            known.setdefault(bytes(r["number_hmac"]), []).append((r["id"], holder))
        out: dict[bytes, Match] = {}
        for r in totals:
            digest = bytes(r["number_hmac"])
            total = int(r["total"])
            mine = sorted(known.get(digest, []), key=lambda item: ((item[1].name or "").casefold(), item[0]))
            out[digest] = Match(
                number_hmac=digest,
                total=min(total, s.count_cap),
                total_is_lower_bound=total > s.count_cap,
                known=[h for _, h in mine[:KNOWN_PER_CARD_MAX]],
                known_ids=frozenset(uid for uid, _ in mine))
        return out

    async def _add_names(self, conn: Conn, viewer_id: int, matches: Sequence[Match], excluded: Sequence[int]) -> None:
        """Rule 4: the owner's own name, the popular name, and whether the owner takes part."""
        if not matches:
            return
        hmacs = [m.number_hmac for m in matches]
        owners = await repo.owners(conn, viewer_id, hmacs, excluded, self.settings.consent_version)
        popular = await repo.popular_names(conn, hmacs)
        for m in matches:
            owner = owners.get(m.number_hmac)
            if owner is not None:
                m.owner_id, m.name = owner["public_id"], owner["display_name"]
            if m.number_hmac in popular:
                m.popular_name = self.names.decrypt(popular[m.number_hmac], m.number_hmac)

    async def _add_samples(self, conn: Conn, viewer_id: int, matches: Sequence[Match],
                           excluded: Sequence[int]) -> None:
        """Name a few holders the viewer doesn't know. Skipped entirely when visibility is "known"."""
        s = self.settings
        if s.visibility != "everyone":
            return
        wanted = [m for m in matches if m.total > len(m.known_ids)]
        if not wanted:
            return
        # Leave out everyone the viewer knows, in the query itself, so each number reads only the
        # few index entries it shows. (Someone the viewer knows who holds a number is already on
        # that card's "known" list, so excluding all of them never hides anyone who belongs here.)
        known_anywhere = sorted({uid for m in wanted for uid in m.known_ids})
        if len(known_anywhere) <= SAMPLE_KNOWN_EXCLUDE_MAX:
            leave_out, fetch = list(excluded) + known_anywhere, s.card_sample_size
        else:
            leave_out = list(excluded)
            fetch = s.card_sample_size + min(SAMPLE_KNOWN_SKIP_MAX, max(len(m.known_ids) for m in wanted))
        by_number: dict[bytes, list[int]] = {}
        for r in await repo.holder_samples(conn, viewer_id, [m.number_hmac for m in wanted], fetch, leave_out):
            by_number.setdefault(bytes(r["number_hmac"]), []).append(r["user_id"])
        picks = {m.number_hmac: [uid for uid in by_number.get(m.number_hmac, [])
                                 if uid not in m.known_ids][:s.card_sample_size] for m in wanted}
        ids = sorted({uid for chosen in picks.values() for uid in chosen})
        people = {r["id"]: r for r in await repo.users_by_ids(conn, ids)}
        for m in wanted:
            m.others = [Holder(people[uid]["public_id"], people[uid]["display_name"], False)
                        for uid in picks[m.number_hmac] if uid in people]

    async def cards(self, user: Row, cursor: str | None, limit: int | None) -> dict[str, Any]:
        """The person's own number first, then a card for every number on their phone that someone
        else has. Before they allow Found With to use their phone, only their own number: the count
        of people who have it is the first thing they see."""
        s = self.settings
        after = _cards_cursor(cursor)
        limit = max(1, min(limit or s.cards_page_default, s.cards_page_max))
        own = bytes(user["phone_hmac"])
        async with self.db.acquire() as conn:
            excluded = await repo.excluded_ids(conn, user["id"], own)
            candidates = await repo.card_candidates(conn, user["id"], own) if user["sync_enabled"] else []
            flags = {bytes(r["number_hmac"]): (r["in_contacts"], r["in_call_log"]) for r in candidates}
            matches = await self._summaries(conn, user["id"], list(flags), excluded)
            # People you know first, then the most widely saved. The reference breaks ties, which
            # also makes the order (and so the paging) stable.
            keyed = sorted((((-len(m.known), -m.total, m.ref), m) for m in matches.values()),
                           key=lambda item: item[0])
            keys = [key for key, _ in keyed]
            start = bisect.bisect_right(keys, after) if after is not None else 0
            page = [m for _, m in keyed[start:start + limit]]
            await self._add_samples(conn, user["id"], page, excluded)
            # Their own number: who they know has it. First page only.
            mine = None
            if after is None:
                own_match = (await self._summaries(conn, user["id"], [own], excluded)).get(own) or Match(own)
                mine = {**own_match.as_json(), "name": user["display_name"], "popular_name": None,
                        "in_contacts": False, "in_call_log": False}
            await self._add_names(conn, user["id"], page, excluded)
        cards = []
        for m in page:
            in_c, in_l = flags[m.number_hmac]
            cards.append({**m.as_json(), "in_contacts": in_c, "in_call_log": in_l})
        has_more = start + limit < len(keyed)
        page_one = {"mine": mine, "taking_part": bool(user["sync_enabled"])} if after is None else {}
        return {
            "cards": cards,
            **page_one,
            "total_cards": len(keyed),
            "next": encode_cursor(list(keys[start + limit - 1])) if has_more else None,
        }

    async def lookup(self, user: Row, raw_number: str, context: str) -> dict[str, Any]:
        s = self.settings
        e164 = normalise(raw_number, s.country_code)
        if context == "search":
            if not is_indian_mobile(e164):
                raise InvalidNumber("Enter a 10-digit Indian mobile number starting with 6, 7, 8 or 9.")
            limit, noun = s.search_daily_limit, "searches"
        else:
            if e164 is None:
                raise InvalidNumber("That doesn't look like a phone number.")
            limit, noun = s.call_daily_limit, "call lookups"
        digest = self.hasher.digest(e164)
        own = bytes(user["phone_hmac"])
        async with self.db.acquire() as conn, conn.transaction():
            if own in self.quota_exempt:
                used = 0
            else:
                used = await repo.take_quota(conn, user["id"], context, s.quota_timezone, limit)
            if used is None:
                raise QuotaExceeded(
                    f"You've used all {limit} {noun} for today. The limit resets at midnight IST.",
                    details={"limit": limit})
            hidden = context == "call" and await repo.is_hidden(conn, user["id"], digest)
            match = Match(digest)
            if not hidden:
                excluded = await repo.excluded_ids(conn, user["id"], own)
                match = (await self._summaries(conn, user["id"], [digest], excluded)).get(digest) or Match(digest)
                if match.total:
                    await self._add_samples(conn, user["id"], [match], excluded)
                await self._add_names(conn, user["id"], [match], excluded)
            taken_out = await repo.is_suppressed(conn, digest)
            await repo.log_lookup(conn, user["id"], own, digest, context, match.total)
        result = match.as_json()
        result["taken_out"] = taken_out   # the owner took it out: the app says "Not shown on Found With"
        result.update({"number": e164, "context": context, "hidden": hidden,
                       "quota": {"limit": limit, "left": max(0, limit - used)}})
        return result

    # ---------- circle and invites

    async def _invite_code(self, conn: Conn, user_id: int) -> str:
        code = await repo.invite_code(conn, user_id)
        while code is None:
            candidate = "".join(secrets.choice(INVITE_ALPHABET) for _ in range(8))
            code = await repo.set_invite_code(conn, user_id, candidate) or await repo.invite_code(conn, user_id)
        return code

    async def circle(self, user: Row, base_url: str) -> dict[str, Any]:
        """How many of the person's contacts are on Found With, which ones (so the phone can leave
        them out of the invite list), and their invite link with who joined through it."""
        s = self.settings
        own = bytes(user["phone_hmac"])
        async with self.db.acquire() as conn:
            excluded = await repo.excluded_ids(conn, user["id"], own)
            members = await repo.circle_members(conn, user["id"], excluded, s.consent_version) if user["sync_enabled"] else []
            code = await self._invite_code(conn, user["id"])
            joined = await repo.invite_joined(conn, user["id"])
        return {
            "members": len(members),
            "member_refs": [to_ref(h) for h in members],
            "goal": s.circle_goal,
            "invite": {
                "code": code,
                "link": f"{base_url.rstrip('/')}/i/{code}",
                "joined": len(joined),
                # So the inviter's phone can say "Ravi joined with your link" under its own name for Ravi.
                "joined_refs": [to_ref(h) for h in joined],
            },
        }

    async def redeem_invite(self, user: Row, code: str) -> dict[str, Any]:
        """A new person joined through someone's link: recorded once per number, ever, so the inviter
        hears about it once. There is no reward."""
        s = self.settings
        code = code.strip().upper()
        own = bytes(user["phone_hmac"])
        async with self.db.acquire() as conn, conn.transaction():
            inviter = await repo.user_by_invite_code(conn, code) if INVITE_CODE.match(code) else None
            if inviter is None:
                raise InviteNotFound("That invite link isn't valid.")
            if inviter["id"] == user["id"]:
                raise InviteRejected("That's your own invite.", details={"reason": "own_invite"})
            if (user["created_age_s"] or 0) > s.invite_window_days * DAY_S:
                raise InviteRejected(f"Invites only count in your first {_days(s.invite_window_days)} on Found With.",
                                     details={"reason": "too_late"})
            if not await repo.redeem_invite(conn, own, inviter["id"]):
                raise InviteRejected("This number has already joined through an invite.", details={"reason": "already_used"})
            if s.push_mode != "off":
                await repo.queue_push(conn, inviter["id"], repo.PUSH_INVITE)
        log.info("invite redeemed", extra={"event": "invite_redeemed"})
        return {"redeemed": True}

    # ---------- push messages, phone health, your data

    async def save_device(self, user: Row, token: str) -> None:
        async with self.db.acquire() as conn:
            await repo.save_push_token(conn, user["id"], token)

    async def forget_device(self, user: Row, token: str) -> None:
        async with self.db.acquire() as conn:
            await repo.delete_push_token(conn, user["id"], token)

    async def my_data(self, user: Row, ident: Identity) -> dict[str, Any]:
        """What Found With holds about this person, in counts, for the "Your data" page."""
        s = self.settings
        async with self.db.acquire() as conn:
            counts = await repo.data_summary(conn, user["id"])
            opted_out = await repo.is_suppressed(conn, bytes(user["phone_hmac"]))
        return {
            "name": user["display_name"],
            "phone": normalise(ident.phone, s.country_code),
            "created_at": _iso(user["created_at"]),
            "consent_version": user["consent_version"],
            "consent_at": _iso(user["consent_at"]),
            "sync_enabled": user["sync_enabled"],
            "evening_note": user["evening_note"],
            "number_opted_out": opted_out,
            "numbers": {"contacts": counts["contacts"], "call_log": counts["call_log"], "hidden": counts["hidden"]},
            "blocks": counts["blocks"],
            "reports_made": counts["reports"],
            "searches_made": counts["searches"],
            "call_lookups": counts["call_lookups"],
            "invited": counts["invited"],
            "devices": counts["devices"],
            "kept_days": {"lookups": s.lookup_log_days, "inactive_account": s.inactive_days},
        }

    async def export(self, user: Row, ident: Identity) -> dict[str, Any]:
        """Everything stored about this person. Numbers are only stored scrambled, so they come
        back as references; the phone turns its own back into numbers and names."""
        async with self.db.acquire() as conn:
            numbers = await repo.export_numbers(conn, user["id"])
            lookups = await repo.export_lookups(conn, user["id"])
            reports = await repo.export_reports(conn, user["id"])
            blocks = await repo.export_blocks(conn, user["id"])
            joined = await repo.invite_joined(conn, user["id"], limit=1000)
        return {
            "summary": await self.my_data(user, ident),
            "numbers": [{"ref": to_ref(bytes(r["number_hmac"])), "in_contacts": r["in_contacts"],
                         "in_call_log": r["in_call_log"], "hidden": r["hidden"],
                         "saved_as": self.names.decrypt(r["name_enc"], bytes(r["number_hmac"])) if r["name_enc"] else None}
                        for r in numbers],
            "lookups": [{"ref": to_ref(bytes(r["number_hmac"])), "kind": r["kind"],
                         "found_with": r["holders_found"], "at": _iso(r["created_at"])} for r in lookups],
            "reports_made": [{"name": r["reported_name"], "reason": r["reason"], "note": r["note"],
                              "at": _iso(r["created_at"]), "resolved_at": _iso(r["resolved_at"])} for r in reports],
            "blocks": [{"ref": to_ref(bytes(r["blocked_phone_hmac"])), "at": _iso(r["created_at"])} for r in blocks],
            "joined_with_your_invite": [to_ref(h) for h in joined],
        }

    async def holders(self, user: Row, ref: str, cursor: str | None, limit: int | None) -> dict[str, Any]:
        """Page through the people who have a number saved. Allowed for the viewer's own cards and
        for numbers they searched in the last 24 hours; at most holders_list_cap names per number."""
        if not user["sync_enabled"]:
            raise SyncRequired("Turn on contact sync to see who has a number.")
        s = self.settings
        digest = self._ref(ref)
        after = _holders_cursor(cursor)
        limit = max(1, min(limit or s.holder_page_size, 100))
        empty = {"ref": ref, "holders": [], "next": None, "list_capped": False}
        async with self.db.acquire() as conn:
            if not await repo.may_list_holders(conn, user["id"], digest):
                raise NotFound("We couldn't find that number.")
            if await repo.is_suppressed(conn, digest):
                return empty
            excluded = await repo.excluded_ids(conn, user["id"], bytes(user["phone_hmac"]))
            if s.visibility != "everyone":
                match = (await self._summaries(conn, user["id"], [digest], excluded)).get(digest)
                return {**empty, "holders": [h.as_json() for h in (match.known if match else [])]}
            up_to, capped = await repo.holders_cutoff(conn, user["id"], digest, s.holders_list_cap, excluded)
            rows = list(await repo.holders_page(conn, user["id"], digest, after, limit + 1, up_to, excluded))
        has_more = len(rows) > limit
        rows = rows[:limit]
        return {
            "ref": ref,
            "holders": [Holder(r["public_id"], r["display_name"], bool(r["known"]),
                               to_ref(bytes(r["phone_hmac"])) if r["known"] else None).as_json() for r in rows],
            "next": encode_cursor(rows[-1]["id"]) if has_more else None,
            "list_capped": capped,
        }

    # ---------- hidden numbers

    async def hide(self, user: Row, ref: str) -> None:
        digest = self._ref(ref)
        cap = self.settings.max_hidden_numbers
        async with self.db.acquire() as conn, conn.transaction():
            if await repo.lock_user(conn, user["id"]) is None:
                raise NotRegistered(NOT_REGISTERED)
            if not await repo.is_hidden(conn, user["id"], digest) and await repo.count_hidden(conn, user["id"]) >= cap:
                raise LimitReached(f"You can hide up to {cap:,} numbers. Unhide one to hide another.")
            await repo.hide_number(conn, user["id"], digest)

    async def unhide(self, user: Row, ref: str) -> None:
        digest = self._ref(ref)
        async with self.db.acquire() as conn, conn.transaction():
            await repo.unhide_number(conn, user["id"], digest)

    async def hidden(self, user: Row) -> list[str]:
        async with self.db.acquire() as conn:
            return [to_ref(bytes(r["number_hmac"])) for r in await repo.hidden_numbers(conn, user["id"])]

    def _ref(self, ref: str) -> bytes:
        try:
            return from_ref(ref)
        except ValueError as exc:
            raise NotFound("We couldn't find that number.") from exc

    # ---------- opt-out: "take my number out of Found With"
    # Needs only a verified sign-in for the number. No profile, no contact sync.

    async def opt_out_status(self, ident: Identity) -> dict[str, Any]:
        phone_hmac = self._own_phone_hmac(ident)
        async with self.db.acquire() as conn:
            return {"opted_out": await repo.is_suppressed(conn, phone_hmac),
                    "has_account": await repo.user_by_phone(conn, phone_hmac) is not None}

    async def opt_out(self, ident: Identity, delete_account: bool = False) -> dict[str, Any]:
        """Take the number out for everybody. If the number has a Found With account, that is
        deleted too, but only when the person said so (the page warns first)."""
        phone_hmac = self._own_phone_hmac(ident)
        async with self.db.acquire() as conn:
            account = await repo.user_by_phone(conn, phone_hmac)
        if account is not None and not delete_account:
            raise AccountExists("This number has a Found With account. Taking it out also deletes the account.")
        deleted = False
        if account is not None:
            await self.delete_account(account)
            deleted = True
        async with self.db.acquire() as conn, conn.transaction():
            removed = await repo.suppress(conn, phone_hmac)
        log.info("number opted out", extra={"event": "opt_out", "rows": removed, "account_deleted": deleted})
        return {"opted_out": True, "account_deleted": deleted}

    async def opt_in(self, ident: Identity) -> dict[str, Any]:
        async with self.db.acquire() as conn:
            await repo.unsuppress(conn, self._own_phone_hmac(ident))
        return {"opted_out": False}

    # ---------- reports and blocks (about people's profile names, never about calls)

    async def _other_user(self, conn: Conn, user: Row, public_id: str) -> Row:
        target = await repo.user_by_public_id(conn, _public_id(public_id))
        if target is None or target["id"] == user["id"]:
            raise NotFound("We couldn't find that person.")
        return target

    async def report(self, user: Row, public_id: str, reason: str, note: str | None) -> None:
        async with self.db.acquire() as conn, conn.transaction():
            target = await self._other_user(conn, user, public_id)
            if await repo.reports_last_day(conn, user["id"]) >= self.settings.reports_daily_limit:
                raise RateLimited("You've sent a lot of reports today. Try again tomorrow.")
            await repo.insert_report(conn, user["id"], target, reason, (note or "").strip() or None)

    async def block(self, user: Row, public_id: str) -> dict[str, Any]:
        cap = self.settings.max_blocks
        async with self.db.acquire() as conn, conn.transaction():
            target = await self._other_user(conn, user, public_id)
            if await repo.lock_user(conn, user["id"]) is None:
                raise NotRegistered(NOT_REGISTERED)
            if await repo.count_blocks(conn, user["id"]) >= cap:
                raise LimitReached(f"You can block up to {cap:,} people. Unblock someone to block another.")
            block_id = await repo.insert_block(conn, user["id"], bytes(target["phone_hmac"]))
        return {"id": block_id, "name": target["display_name"]}

    async def unblock(self, user: Row, block_id: int) -> None:
        async with self.db.acquire() as conn:
            if not await repo.delete_block(conn, user["id"], block_id):
                raise NotFound("We couldn't find that block.")

    async def blocks(self, user: Row) -> list[dict[str, Any]]:
        async with self.db.acquire() as conn:
            return [{"id": r["id"], "name": r["display_name"], "ref": to_ref(bytes(r["blocked_phone_hmac"]))}
                    for r in await repo.list_blocks(conn, user["id"])]

    # ---------- phone health by brand

    async def record_health(self, brand: str, android: int, day: Any, calls_seen: int, calls_checked: int) -> None:
        """One phone's day: calls it saw and how many Found With checked. Stored by brand only."""
        async with self.db.acquire() as conn:
            await repo.add_health(conn, day, brand, android, calls_seen, min(calls_checked, calls_seen))
