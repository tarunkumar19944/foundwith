"""All SQL lives here. Every statement uses $n parameters and explicit casts so it runs the same
under asyncpg (production) and the libpq test adapter.

Vocabulary used below:
  holder    - a user who has a number in their contacts or call log (and hasn't hidden it).
              Holders are who other people see on a number's card.
  viewer    - the signed-in user asking for cards or a lookup.
  excluded  - user ids the viewer must never be shown (blocked in either direction).
"""

from __future__ import annotations

from collections.abc import Sequence
from typing import Any

from .db import Conn, Row

USER_COLUMNS = """id, public_id::text AS public_id, auth_uid, phone_hmac, display_name, sync_enabled,
    consent_version, consent_at, first_sync_at, created_at, suspended_at, suspended_reason, admit_tokens, evening_note,
    EXTRACT(EPOCH FROM (now() - admit_tokens_at))::float8 AS admit_age_s,
    EXTRACT(EPOCH FROM (now() - last_seen_at))::float8    AS seen_age_s,
    EXTRACT(EPOCH FROM (now() - name_changed_at))::float8 AS name_age_s,
    EXTRACT(EPOCH FROM (now() - created_at))::float8      AS created_age_s"""

MAX_BIGINT = 9_223_372_036_854_775_807


def _count(status: str) -> int:
    """Rows affected, from a command tag such as 'DELETE 3'."""
    return int(status.split()[-1])


# ---------------------------------------------------------------- users

async def user_by_auth_uid(conn: Conn, auth_uid: str) -> Row | None:
    return await conn.fetchrow(f"SELECT {USER_COLUMNS} FROM users WHERE auth_uid = $1", auth_uid)


async def user_by_public_id(conn: Conn, public_id: str) -> Row | None:
    return await conn.fetchrow(f"SELECT {USER_COLUMNS} FROM users WHERE public_id = $1::uuid", public_id)


async def user_by_phone(conn: Conn, phone_hmac: bytes) -> Row | None:
    return await conn.fetchrow(f"SELECT {USER_COLUMNS} FROM users WHERE phone_hmac = $1::bytea", phone_hmac)


async def lock_user(conn: Conn, user_id: int) -> Row | None:
    """Serialises writes for one user (syncs, sync on/off, renames, deletion)."""
    return await conn.fetchrow(f"SELECT {USER_COLUMNS} FROM users WHERE id = $1 FOR UPDATE", user_id)


async def other_owners_of_phone(conn: Conn, phone_hmac: bytes, auth_uid: str) -> Sequence[Row]:
    """Accounts that hold this verified number under a different sign-in."""
    return await conn.fetch(
        "SELECT id FROM users WHERE phone_hmac = $1::bytea AND auth_uid <> $2 FOR UPDATE", phone_hmac, auth_uid)


async def insert_user(conn: Conn, auth_uid: str, phone_hmac: bytes, display_name: str | None,
                      starting_tokens: float) -> Row | None:
    """Create the account. If this number had an account that was deleted recently, the new one
    starts with that account's allowance, name-change clock and suspension. Returns None when the
    account already exists."""
    return await conn.fetchrow(
        f"""INSERT INTO users (auth_uid, phone_hmac, display_name, admit_tokens, admit_tokens_at,
                               name_changed_at, suspended_at, suspended_reason)
            SELECT $1, $2::bytea, $3,
                   COALESCE(t.admit_tokens, $4::float8), COALESCE(t.admit_tokens_at, now()),
                   t.name_changed_at, t.suspended_at, t.suspended_reason
            FROM (SELECT 1) AS one
            LEFT JOIN account_tombstones t ON t.phone_hmac = $2::bytea
            ON CONFLICT (auth_uid) DO NOTHING
            RETURNING {USER_COLUMNS}""",
        auth_uid, phone_hmac, display_name, float(starting_tokens))


async def tombstone(conn: Conn, phone_hmac: bytes) -> Row | None:
    return await conn.fetchrow(
        """SELECT EXTRACT(EPOCH FROM deleted_at)::float8 AS deleted_epoch, suspended_at
           FROM account_tombstones WHERE phone_hmac = $1::bytea""", phone_hmac)


async def consume_tombstone(conn: Conn, user_id: int, phone_hmac: bytes) -> None:
    """Carry today's quota use over to the new account, then forget the tombstone."""
    await conn.execute(
        """INSERT INTO lookup_quota (user_id, kind, day, used)
           SELECT $1, x.kind, x.day, x.used
           FROM account_tombstones t, jsonb_to_recordset(t.quota) AS x(kind text, day date, used integer)
           WHERE t.phone_hmac = $2::bytea AND x.kind IN ('search', 'call')
           ON CONFLICT (user_id, kind) DO NOTHING""",
        user_id, phone_hmac)
    await conn.execute("DELETE FROM account_tombstones WHERE phone_hmac = $1::bytea", phone_hmac)


async def retire_user(conn: Conn, user_id: int) -> None:
    """Delete an account and everything it synced, leaving only a tombstone for its number."""
    await conn.execute(
        """INSERT INTO account_tombstones AS t (phone_hmac, deleted_at, admit_tokens, admit_tokens_at, quota,
                                               name_changed_at, suspended_at, suspended_reason)
           SELECT u.phone_hmac, now(), u.admit_tokens, u.admit_tokens_at,
                  COALESCE((SELECT jsonb_agg(jsonb_build_object('kind', q.kind, 'day', q.day, 'used', q.used))
                            FROM lookup_quota q WHERE q.user_id = u.id), '[]'::jsonb),
                  u.name_changed_at, u.suspended_at, u.suspended_reason
           FROM users u WHERE u.id = $1
           ON CONFLICT (phone_hmac) DO UPDATE SET
               deleted_at       = EXCLUDED.deleted_at,
               admit_tokens     = EXCLUDED.admit_tokens,
               admit_tokens_at  = EXCLUDED.admit_tokens_at,
               quota            = EXCLUDED.quota,
               name_changed_at  = EXCLUDED.name_changed_at,
               suspended_at     = COALESCE(EXCLUDED.suspended_at, t.suspended_at),
               suspended_reason = COALESCE(EXCLUDED.suspended_reason, t.suspended_reason)""",
        user_id)
    # Delete the address book explicitly rather than through the foreign-key cascade, so the
    # holder-count triggers see it as one ordinary statement.
    await conn.execute("DELETE FROM user_numbers WHERE user_id = $1", user_id)
    await conn.execute("DELETE FROM users WHERE id = $1", user_id)


async def queue_auth_deletion(conn: Conn, auth_uid: str) -> None:
    await conn.execute(
        """INSERT INTO auth_deletions (auth_uid) VALUES ($1)
           ON CONFLICT (auth_uid) DO UPDATE SET done_at = NULL, requested_at = now(), attempts = 0""", auth_uid)


async def cancel_auth_deletion(conn: Conn, auth_uid: str) -> None:
    """The person signed in again with the same sign-in account, so it must not be deleted any more."""
    await conn.execute("DELETE FROM auth_deletions WHERE auth_uid = $1 AND done_at IS NULL", auth_uid)


async def update_phone(conn: Conn, user_id: int, phone_hmac: bytes) -> None:
    await conn.execute(
        "UPDATE users SET phone_hmac = $2::bytea, last_seen_at = now(), updated_at = now() WHERE id = $1",
        user_id, phone_hmac)


async def touch_user(conn: Conn, user_id: int) -> None:
    await conn.execute("UPDATE users SET last_seen_at = now() WHERE id = $1", user_id)


async def update_display_name(conn: Conn, user_id: int, display_name: str, *, first: bool = False) -> Row:
    """`first`: the person is choosing a name for the first time, which doesn't start the wait
    before the next change."""
    return await conn.fetchrow(
        f"""UPDATE users SET display_name = $2,
                             name_changed_at = CASE WHEN $3::boolean THEN name_changed_at ELSE now() END,
                             updated_at = now()
            WHERE id = $1 RETURNING {USER_COLUMNS}""",
        user_id, display_name, first)


async def set_evening_note(conn: Conn, user_id: int, on: bool) -> None:
    await conn.execute("UPDATE users SET evening_note = $2::boolean, updated_at = now() WHERE id = $1", user_id, on)


async def enable_sync(conn: Conn, user_id: int, consent_version: str) -> Row:
    return await conn.fetchrow(
        f"""UPDATE users SET sync_enabled = TRUE, consent_version = $2, consent_at = now(), updated_at = now()
            WHERE id = $1 RETURNING {USER_COLUMNS}""",
        user_id, consent_version)


async def disable_sync(conn: Conn, user_id: int) -> Row:
    """Turning sync off removes the user's numbers from matching straight away.
    Hidden-number choices are kept (with no address-book flags) so they survive a re-enable."""
    await conn.execute("DELETE FROM user_numbers WHERE user_id = $1 AND NOT hidden", user_id)
    await conn.execute(
        "UPDATE user_numbers SET in_contacts = FALSE, in_call_log = FALSE WHERE user_id = $1 AND hidden", user_id)
    return await conn.fetchrow(
        f"UPDATE users SET sync_enabled = FALSE, updated_at = now() WHERE id = $1 RETURNING {USER_COLUMNS}", user_id)


async def mark_synced(conn: Conn, user_id: int, admit_tokens: float) -> None:
    await conn.execute(
        """UPDATE users SET first_sync_at = COALESCE(first_sync_at, now()), last_seen_at = now(),
                            admit_tokens = $2::float8, admit_tokens_at = now(), updated_at = now()
           WHERE id = $1""",
        user_id, float(admit_tokens))


async def set_suspended(conn: Conn, user_id: int, reason: str | None) -> None:
    """reason=None lifts the suspension."""
    await conn.execute(
        """UPDATE users SET suspended_at = CASE WHEN $2::text IS NULL THEN NULL ELSE now() END,
                            suspended_reason = $2::text, updated_at = now()
           WHERE id = $1""",
        user_id, reason)


async def suspend_phone(conn: Conn, phone_hmac: bytes, reason: str) -> None:
    """Suspend a number that has no account right now, so signing up again doesn't clear it."""
    await conn.execute(
        """INSERT INTO account_tombstones AS t (phone_hmac, suspended_at, suspended_reason)
           VALUES ($1::bytea, now(), $2)
           ON CONFLICT (phone_hmac) DO UPDATE SET suspended_at = now(), suspended_reason = EXCLUDED.suspended_reason""",
        phone_hmac, reason)


# ---------------------------------------------------------------- the user's own numbers

async def own_numbers(conn: Conn, user_id: int) -> Sequence[Row]:
    return await conn.fetch(
        "SELECT number_hmac, in_contacts, in_call_log, hidden FROM user_numbers WHERE user_id = $1", user_id)


async def own_numbers_among(conn: Conn, user_id: int, hmacs: Sequence[bytes]) -> Sequence[Row]:
    if not hmacs:
        return []
    return await conn.fetch(
        """SELECT number_hmac, in_contacts, in_call_log, hidden FROM user_numbers
           WHERE user_id = $1 AND number_hmac = ANY($2::bytea[])""",
        user_id, list(hmacs))


async def count_stored(conn: Conn, user_id: int) -> int:
    """Numbers currently stored from the person's contacts or call log (hidden-only rows excluded)."""
    return await conn.fetchval(
        "SELECT count(*) FROM user_numbers WHERE user_id = $1 AND (in_contacts OR in_call_log)", user_id)


async def suppressed_among(conn: Conn, hmacs: Sequence[bytes]) -> Sequence[Row]:
    if not hmacs:
        return []
    return await conn.fetch(
        "SELECT number_hmac FROM suppressed_numbers WHERE number_hmac = ANY($1::bytea[])", list(hmacs))


async def upsert_numbers(conn: Conn, user_id: int, hmacs: Sequence[bytes],
                         in_contacts: Sequence[bool], in_call_log: Sequence[bool]) -> None:
    """Insert or update address-book flags. `hidden` is never touched here."""
    if not hmacs:
        return
    await conn.execute(
        """INSERT INTO user_numbers (user_id, number_hmac, in_contacts, in_call_log)
           SELECT $1, t.h, t.c, t.l FROM unnest($2::bytea[], $3::bool[], $4::bool[]) AS t(h, c, l)
           ON CONFLICT (user_id, number_hmac) DO UPDATE
              SET in_contacts = EXCLUDED.in_contacts, in_call_log = EXCLUDED.in_call_log""",
        user_id, list(hmacs), list(in_contacts), list(in_call_log))


async def lock_counts(conn: Conn, hmacs: Sequence[bytes]) -> None:
    """Lock the holder-count rows a sync is about to change, all in one statement and in number
    order, creating any that don't exist yet. The triggers then only touch rows this transaction
    already holds, so two syncs that share popular numbers queue behind each other instead of
    deadlocking (each trigger statement locking its own subset let them lock in crossing orders)."""
    if not hmacs:
        return
    await conn.execute(
        """INSERT INTO number_stats AS s (number_hmac, holders)
           SELECT t.h, 0 FROM unnest($1::bytea[]) AS t(h) ORDER BY t.h
           ON CONFLICT (number_hmac) DO UPDATE SET holders = s.holders""",
        sorted(set(hmacs)))


async def clear_flags_of_hidden(conn: Conn, user_id: int, hmacs: Sequence[bytes]) -> None:
    """A hidden number left the phone: keep the row (and the hide choice) without address-book flags."""
    if not hmacs:
        return
    await conn.execute(
        """UPDATE user_numbers SET in_contacts = FALSE, in_call_log = FALSE
           WHERE user_id = $1 AND number_hmac = ANY($2::bytea[]) AND hidden""",
        user_id, list(hmacs))


async def delete_numbers(conn: Conn, user_id: int, hmacs: Sequence[bytes]) -> None:
    if not hmacs:
        return
    await conn.execute(
        "DELETE FROM user_numbers WHERE user_id = $1 AND number_hmac = ANY($2::bytea[]) AND NOT hidden",
        user_id, list(hmacs))


async def card_candidates(conn: Conn, user_id: int, own_phone_hmac: bytes) -> Sequence[Row]:
    """Numbers that can produce a card for the viewer: in their contacts or call log, not hidden."""
    return await conn.fetch(
        """SELECT number_hmac, in_contacts, in_call_log FROM user_numbers
           WHERE user_id = $1 AND NOT hidden AND (in_contacts OR in_call_log) AND number_hmac <> $2::bytea""",
        user_id, own_phone_hmac)


async def is_hidden(conn: Conn, user_id: int, number_hmac: bytes) -> bool:
    return bool(await conn.fetchval(
        "SELECT hidden FROM user_numbers WHERE user_id = $1 AND number_hmac = $2::bytea", user_id, number_hmac))


# ---------------------------------------------------------------- matching

async def excluded_ids(conn: Conn, viewer_id: int, viewer_phone_hmac: bytes) -> list[int]:
    """People the viewer blocked, and people who blocked the viewer."""
    rows = await conn.fetch(
        """SELECT u.id FROM blocks b JOIN users u ON u.phone_hmac = b.blocked_phone_hmac WHERE b.user_id = $1
           UNION
           SELECT b.user_id AS id FROM blocks b WHERE b.blocked_phone_hmac = $2::bytea""",
        viewer_id, viewer_phone_hmac)
    return [r["id"] for r in rows]


async def holder_totals(conn: Conn, viewer_id: int, hmacs: Sequence[bytes],
                        excluded: Sequence[int]) -> Sequence[Row]:
    """How many holders each number has, other than the viewer and anyone blocked either way.
    Read from the stored counts (number_stats), so the cost doesn't grow with how popular a
    number is. Numbers with no holders, and numbers whose owners opted out, are left out."""
    if not hmacs:
        return []
    rows = await conn.fetch(
        """SELECT t.h AS number_hmac,
                  s.holders - (v.user_id IS NOT NULL)::int AS total
           FROM unnest($2::bytea[]) AS t(h)
           JOIN number_stats s ON s.number_hmac = t.h
           LEFT JOIN user_numbers v ON v.user_id = $1 AND v.number_hmac = t.h AND (v.in_contacts OR v.in_call_log) AND NOT v.hidden
           WHERE s.holders > 0
             AND NOT EXISTS (SELECT 1 FROM suppressed_numbers p WHERE p.number_hmac = t.h)""",
        viewer_id, list(hmacs))
    totals = {bytes(r["number_hmac"]): int(r["total"]) for r in rows}
    if excluded and totals:
        # Blocked people are few, so this probes the primary key (user_id, number_hmac) directly.
        for r in await conn.fetch(
                """SELECT number_hmac, count(*)::int AS n FROM user_numbers
                   WHERE user_id = ANY($1::bigint[]) AND number_hmac = ANY($2::bytea[])
                     AND (in_contacts OR in_call_log) AND NOT hidden
                   GROUP BY number_hmac""",
                list(excluded), list(totals)):
            totals[bytes(r["number_hmac"])] -= r["n"]
    return [{"number_hmac": h, "total": n} for h, n in totals.items() if n > 0]


async def known_holders(conn: Conn, viewer_id: int, hmacs: Sequence[bytes], excluded: Sequence[int],
                        consent_version: str) -> Sequence[Row]:
    """For each number, the holders the viewer knows: people whose own number is in the viewer's
    contacts or call log. Only people who accepted the current terms are named; anyone else
    still counts, but as a number.

    The LATERAL ... LIMIT 1 makes Postgres look each of the viewer's contacts up in the users
    index one by one. Left to itself the planner prefers a hash join that reads the whole users
    table, which is cheap at a few thousand users and grows with every sign-up; this plan grows
    only with the size of the viewer's own address book."""
    return await conn.fetch(
        """SELECT un.number_hmac, u.id, u.public_id, u.display_name, u.phone_hmac
           FROM user_numbers vc
           CROSS JOIN LATERAL (
               SELECT x.id, x.public_id::text AS public_id, x.display_name, x.phone_hmac
               FROM users x WHERE x.phone_hmac = vc.number_hmac AND x.consent_version = $4 LIMIT 1
           ) u
           JOIN user_numbers un ON un.user_id = u.id AND (un.in_contacts OR un.in_call_log) AND NOT un.hidden
           WHERE vc.user_id = $1 AND (vc.in_contacts OR vc.in_call_log) AND NOT vc.hidden
             AND un.number_hmac = ANY($2::bytea[])
             AND u.id <> $1 AND u.id <> ALL($3::bigint[])""",
        viewer_id, list(hmacs), list(excluded), consent_version)


async def owners(conn: Conn, viewer_id: int, hmacs: Sequence[bytes],
                 excluded: Sequence[int], consent_version: str) -> dict[bytes, Row]:
    """Each number's owner, for owners taking part in Found With (sync on, current consent, not
    suspended): their public id and their Found With name (None until they choose one).
    Left out: the viewer, anyone blocked either way, and numbers whose owner opted out."""
    if not hmacs:
        return {}
    rows = await conn.fetch(
        """SELECT u.phone_hmac, u.public_id::text AS public_id, u.display_name
           FROM users u
           WHERE u.phone_hmac = ANY($2::bytea[])
             AND u.id <> $1 AND u.id <> ALL($3::bigint[])
             AND u.sync_enabled AND u.suspended_at IS NULL AND u.consent_version = $4
             AND NOT EXISTS (SELECT 1 FROM suppressed_numbers s WHERE s.number_hmac = u.phone_hmac)""",
        viewer_id, list(hmacs), list(excluded), consent_version)
    return {bytes(r["phone_hmac"]): r for r in rows}


# ---------------------------------------------------------------- Tell ("I'm on WhoHas, and 7 more people there have your number")

async def record_tell(conn: Conn, teller_id: int, number_hmac: bytes) -> None:
    await conn.execute(
        """INSERT INTO tells (teller_id, number_hmac) VALUES ($1, $2::bytea)
           ON CONFLICT (teller_id, number_hmac) DO UPDATE SET told_at = now()""",
        teller_id, number_hmac)


async def tellers(conn: Conn, number_hmac: bytes, viewer_id: int, days: int = 90, limit: int = 3) -> tuple[list[str | None], int]:
    """Who told this number (the viewer's own) about WhoHas in the last [days], newest first: up
    to [limit] names (None for someone with no name yet) and how many in all. Only unsuspended
    people, and never anyone blocked either way."""
    rows = await conn.fetch(
        """SELECT u.display_name, count(*) OVER () AS n
           FROM tells t JOIN users u ON u.id = t.teller_id
           WHERE t.number_hmac = $1::bytea AND t.told_at > now() - make_interval(days => $2)
             AND u.suspended_at IS NULL AND u.id <> $3
             AND NOT EXISTS (SELECT 1 FROM blocks b
                             WHERE (b.user_id = $3 AND b.blocked_phone_hmac = u.phone_hmac)
                                OR (b.user_id = u.id AND b.blocked_phone_hmac = $1::bytea))
           ORDER BY t.told_at DESC LIMIT $4""",
        number_hmac, days, viewer_id, limit)
    return [r["display_name"] for r in rows], (int(rows[0]["n"]) if rows else 0)


async def forget_old_tells(conn: Conn, days: int = 90) -> int:
    return _count(await conn.execute("DELETE FROM tells WHERE told_at < now() - make_interval(days => $1)", days))


async def popular_names(conn: Conn, hmacs: Sequence[bytes]) -> dict[bytes, tuple[bytes, int]]:
    """The encrypted popular name of each number that has one, with how many people have that
    name for it ("5 people know it as …")."""
    if not hmacs:
        return {}
    rows = await conn.fetch(
        """SELECT p.number_hmac, p.name_enc, p.people FROM popular_names p
           WHERE p.number_hmac = ANY($1::bytea[])
             AND NOT EXISTS (SELECT 1 FROM suppressed_numbers s WHERE s.number_hmac = p.number_hmac)""",
        list(hmacs))
    return {bytes(r["number_hmac"]): (bytes(r["name_enc"]), int(r["people"] or 0)) for r in rows}


async def set_contact_names(conn: Conn, user_id: int, hmacs: Sequence[bytes],
                            name_hmacs: Sequence[bytes | None], name_encs: Sequence[bytes | None]) -> None:
    """Store how the person saved each of these numbers (None: no name, or no longer a contact).
    Rows whose name hasn't changed are left alone."""
    if not hmacs:
        return
    await conn.execute(
        """UPDATE user_numbers un SET name_hmac = t.nh, name_enc = t.ne
           FROM unnest($2::bytea[], $3::bytea[], $4::bytea[]) AS t(h, nh, ne)
           WHERE un.user_id = $1 AND un.number_hmac = t.h AND un.name_hmac IS DISTINCT FROM t.nh""",
        user_id, list(hmacs), list(name_hmacs), list(name_encs))


async def holder_samples(conn: Conn, viewer_id: int, hmacs: Sequence[bytes], sample_size: int,
                         excluded: Sequence[int]) -> Sequence[Row]:
    """The first few holder ids of each number, in index order."""
    return await conn.fetch(
        """SELECT t.h AS number_hmac, s.user_id
           FROM unnest($2::bytea[]) AS t(h)
           CROSS JOIN LATERAL (
               SELECT un.user_id FROM user_numbers un
               WHERE un.number_hmac = t.h AND (un.in_contacts OR un.in_call_log) AND NOT un.hidden
                 AND un.user_id <> $1 AND un.user_id <> ALL($4::bigint[])
               ORDER BY un.user_id
               LIMIT $3
           ) s""",
        viewer_id, list(hmacs), sample_size, list(excluded))


async def users_by_ids(conn: Conn, ids: Sequence[int]) -> Sequence[Row]:
    if not ids:
        return []
    return await conn.fetch(
        "SELECT id, public_id::text AS public_id, display_name FROM users WHERE id = ANY($1::bigint[])",
        list(ids))


async def holders_cutoff(conn: Conn, viewer_id: int, number_hmac: bytes, cap: int,
                         excluded: Sequence[int]) -> tuple[int, bool]:
    """Only the first `cap` holders of a number can be paged. Returns the highest user id that
    may be listed and whether there are more holders beyond it."""
    rows = await conn.fetch(
        """SELECT un.user_id FROM user_numbers un
           WHERE un.number_hmac = $2::bytea AND (un.in_contacts OR un.in_call_log) AND NOT un.hidden
             AND un.user_id <> $1 AND un.user_id <> ALL($4::bigint[])
           ORDER BY un.user_id
           OFFSET $3 LIMIT 2""",
        viewer_id, number_hmac, cap - 1, list(excluded))
    if not rows:
        return MAX_BIGINT, False
    return rows[0]["user_id"], len(rows) > 1


async def holders_page(conn: Conn, viewer_id: int, number_hmac: bytes, after_id: int, limit: int,
                       up_to_id: int, excluded: Sequence[int]) -> Sequence[Row]:
    return await conn.fetch(
        """SELECT u.id, u.public_id::text AS public_id, u.display_name, u.phone_hmac,
                  EXISTS (SELECT 1 FROM user_numbers vc
                          WHERE vc.user_id = $1 AND vc.number_hmac = u.phone_hmac
                            AND (vc.in_contacts OR vc.in_call_log) AND NOT vc.hidden) AS known
           FROM user_numbers un
           JOIN users u ON u.id = un.user_id
           WHERE un.number_hmac = $2::bytea AND (un.in_contacts OR un.in_call_log) AND NOT un.hidden
             AND un.user_id <> $1 AND un.user_id <> ALL($6::bigint[])
             AND un.user_id > $3 AND un.user_id <= $5
           ORDER BY un.user_id
           LIMIT $4""",
        viewer_id, number_hmac, after_id, limit, up_to_id, list(excluded))


# ---------------------------------------------------------------- lookups: record and entitlement

async def log_lookup(conn: Conn, user_id: int, actor_phone_hmac: bytes, number_hmac: bytes,
                     kind: str, holders_found: int) -> None:
    await conn.execute(
        """INSERT INTO lookup_log (user_id, actor_phone_hmac, number_hmac, kind, holders_found)
           VALUES ($1, $2::bytea, $3::bytea, $4, $5)""",
        user_id, actor_phone_hmac, number_hmac, kind, holders_found)


async def may_list_holders(conn: Conn, user_id: int, number_hmac: bytes) -> bool:
    """A person may page a number's holders if it is one of their own cards, their own number, or
    a number they searched in the last 24 hours. A reference passed on by someone else is worth nothing."""
    return bool(await conn.fetchval(
        """SELECT EXISTS (SELECT 1 FROM user_numbers
                          WHERE user_id = $1 AND number_hmac = $2::bytea
                            AND NOT hidden AND (in_contacts OR in_call_log))
               OR EXISTS (SELECT 1 FROM users WHERE id = $1 AND phone_hmac = $2::bytea)
               OR EXISTS (SELECT 1 FROM lookup_log
                          WHERE user_id = $1 AND number_hmac = $2::bytea AND kind = 'search'
                            AND created_at > now() - interval '24 hours')""",
        user_id, number_hmac))


# ---------------------------------------------------------------- circle and invites

async def circle_members(conn: Conn, user_id: int, excluded: Sequence[int], consent_version: str) -> list[bytes]:
    """Which of the person's contacts and call-log numbers belong to people taking part in Found
    With: sync on, current terms, not suspended, not blocked either way. One index lookup per
    number in their own address book."""
    rows = await conn.fetch(
        """SELECT vc.number_hmac
           FROM user_numbers vc
           CROSS JOIN LATERAL (
               SELECT 1 FROM users x
               WHERE x.phone_hmac = vc.number_hmac AND x.sync_enabled AND x.consent_version = $3
                 AND x.suspended_at IS NULL AND x.id <> $1 AND x.id <> ALL($2::bigint[])
               LIMIT 1
           ) member
           WHERE vc.user_id = $1 AND (vc.in_contacts OR vc.in_call_log) AND NOT vc.hidden""",
        user_id, list(excluded), consent_version)
    return [bytes(r["number_hmac"]) for r in rows]


async def invite_code(conn: Conn, user_id: int) -> str | None:
    return await conn.fetchval("SELECT invite_code FROM users WHERE id = $1", user_id)


async def set_invite_code(conn: Conn, user_id: int, code: str) -> str | None:
    """Give the person this code unless they already have one, or someone else has it."""
    return await conn.fetchval(
        """UPDATE users SET invite_code = $2
           WHERE id = $1 AND invite_code IS NULL
             AND NOT EXISTS (SELECT 1 FROM users WHERE invite_code = $2)
           RETURNING invite_code""",
        user_id, code)


async def user_by_invite_code(conn: Conn, code: str) -> Row | None:
    return await conn.fetchrow(
        "SELECT id, display_name, suspended_at FROM users WHERE invite_code = $1", code)


async def redeem_invite(conn: Conn, phone_hmac: bytes, inviter_id: int) -> bool:
    """Record that this number joined through an invite. False if it already did, ever."""
    return bool(await conn.fetchval(
        """INSERT INTO invite_redemptions (phone_hmac, inviter_id) VALUES ($1::bytea, $2)
           ON CONFLICT (phone_hmac) DO NOTHING RETURNING TRUE""",
        phone_hmac, inviter_id))


async def invite_joined(conn: Conn, user_id: int, limit: int = 200) -> list[bytes]:
    """Numbers that joined through this person's invite, newest first."""
    rows = await conn.fetch(
        """SELECT phone_hmac FROM invite_redemptions WHERE inviter_id = $1
           ORDER BY redeemed_at DESC LIMIT $2""", user_id, limit)
    return [bytes(r["phone_hmac"]) for r in rows]


# ---------------------------------------------------------------- opt-out (suppressed numbers)

async def is_suppressed(conn: Conn, number_hmac: bytes) -> bool:
    return bool(await conn.fetchval(
        "SELECT EXISTS (SELECT 1 FROM suppressed_numbers WHERE number_hmac = $1::bytea)", number_hmac))


async def suppress(conn: Conn, number_hmac: bytes) -> int:
    """Record the opt-out and drop the number from every address book that holds it.
    Returns how many holder rows were removed."""
    await conn.execute(
        "INSERT INTO suppressed_numbers (number_hmac) VALUES ($1::bytea) ON CONFLICT DO NOTHING", number_hmac)
    await conn.execute("DELETE FROM popular_names WHERE number_hmac = $1::bytea", number_hmac)
    return _count(await conn.execute(
        "DELETE FROM user_numbers WHERE number_hmac = $1::bytea AND (in_contacts OR in_call_log) AND NOT hidden", number_hmac))


async def unsuppress(conn: Conn, number_hmac: bytes) -> None:
    await conn.execute("DELETE FROM suppressed_numbers WHERE number_hmac = $1::bytea", number_hmac)


# ---------------------------------------------------------------- hidden numbers

async def count_hidden(conn: Conn, user_id: int) -> int:
    return await conn.fetchval("SELECT count(*) FROM user_numbers WHERE user_id = $1 AND hidden", user_id)


async def hide_number(conn: Conn, user_id: int, number_hmac: bytes) -> None:
    await conn.execute(
        """INSERT INTO user_numbers (user_id, number_hmac, hidden) VALUES ($1, $2::bytea, TRUE)
           ON CONFLICT (user_id, number_hmac) DO UPDATE SET hidden = TRUE""",
        user_id, number_hmac)


async def unhide_number(conn: Conn, user_id: int, number_hmac: bytes) -> None:
    await conn.execute(
        """DELETE FROM user_numbers
           WHERE user_id = $1 AND number_hmac = $2::bytea AND hidden AND NOT in_contacts AND NOT in_call_log""",
        user_id, number_hmac)
    await conn.execute(
        "UPDATE user_numbers SET hidden = FALSE WHERE user_id = $1 AND number_hmac = $2::bytea", user_id, number_hmac)


async def hidden_numbers(conn: Conn, user_id: int) -> Sequence[Row]:
    return await conn.fetch(
        "SELECT number_hmac FROM user_numbers WHERE user_id = $1 AND hidden ORDER BY first_seen_at, number_hmac",
        user_id)


# ---------------------------------------------------------------- quotas

async def take_quota(conn: Conn, user_id: int, kind: str, tz: str, limit: int) -> int | None:
    """Count one lookup against today's quota. Returns how many were used today, or None when the
    quota is already used up. Atomic, so concurrent requests can't overspend it."""
    return await conn.fetchval(
        """INSERT INTO lookup_quota AS q (user_id, kind, day, used)
           VALUES ($1, $2, (now() AT TIME ZONE $3)::date, 1)
           ON CONFLICT (user_id, kind) DO UPDATE
              SET used = CASE WHEN q.day = EXCLUDED.day THEN q.used + 1 ELSE 1 END,
                  day  = EXCLUDED.day
              WHERE q.day <> EXCLUDED.day OR q.used < $4
           RETURNING used""",
        user_id, kind, tz, limit)


async def quota_used_today(conn: Conn, user_id: int, tz: str) -> dict[str, int]:
    rows = await conn.fetch(
        """SELECT kind, CASE WHEN day = (now() AT TIME ZONE $2)::date THEN used ELSE 0 END AS used
           FROM lookup_quota WHERE user_id = $1""",
        user_id, tz)
    return {r["kind"]: r["used"] for r in rows}


# ---------------------------------------------------------------- reports and blocks

async def reports_last_day(conn: Conn, reporter_id: int) -> int:
    return await conn.fetchval(
        "SELECT count(*) FROM reports WHERE reporter_id = $1 AND created_at > now() - interval '24 hours'",
        reporter_id)


async def insert_report(conn: Conn, reporter_id: int, reported: Row, reason: str, note: str | None) -> int:
    return await conn.fetchval(
        """INSERT INTO reports (reporter_id, reported_id, reported_phone_hmac, reported_name, reason, note)
           VALUES ($1, $2, $3::bytea, $4, $5, $6) RETURNING id""",
        reporter_id, reported["id"], bytes(reported["phone_hmac"]), reported["display_name"], reason, note)


async def count_blocks(conn: Conn, user_id: int) -> int:
    return await conn.fetchval("SELECT count(*) FROM blocks WHERE user_id = $1", user_id)


async def insert_block(conn: Conn, user_id: int, blocked_phone_hmac: bytes) -> int:
    return await conn.fetchval(
        """INSERT INTO blocks (user_id, blocked_phone_hmac) VALUES ($1, $2::bytea)
           ON CONFLICT (user_id, blocked_phone_hmac) DO UPDATE SET user_id = EXCLUDED.user_id
           RETURNING id""",
        user_id, blocked_phone_hmac)


async def delete_block(conn: Conn, user_id: int, block_id: int) -> bool:
    return _count(await conn.execute("DELETE FROM blocks WHERE user_id = $1 AND id = $2", user_id, block_id)) > 0


async def list_blocks(conn: Conn, user_id: int) -> Sequence[Row]:
    return await conn.fetch(
        """SELECT b.id, b.blocked_phone_hmac, u.display_name FROM blocks b
           LEFT JOIN users u ON u.phone_hmac = b.blocked_phone_hmac
           WHERE b.user_id = $1 ORDER BY b.id""",
        user_id)


# ---------------------------------------------------------------- maintenance and admin

async def pending_auth_deletions(conn: Conn, max_attempts: int, limit: int) -> Sequence[Row]:
    return await conn.fetch(
        """SELECT auth_uid, attempts FROM auth_deletions
           WHERE done_at IS NULL AND attempts < $1 ORDER BY requested_at LIMIT $2""",
        max_attempts, limit)


async def auth_deletion_still_wanted(conn: Conn, auth_uid: str) -> bool:
    """False once the person has signed in again with the same sign-in account."""
    return bool(await conn.fetchval(
        """SELECT EXISTS (SELECT 1 FROM auth_deletions WHERE auth_uid = $1 AND done_at IS NULL)
              AND NOT EXISTS (SELECT 1 FROM users WHERE auth_uid = $1)""",
        auth_uid))


async def record_auth_deletion(conn: Conn, auth_uid: str, error: str | None) -> None:
    if error is None:
        await conn.execute(
            "UPDATE auth_deletions SET done_at = now(), attempts = attempts + 1, last_error = NULL WHERE auth_uid = $1",
            auth_uid)
    else:
        await conn.execute(
            "UPDATE auth_deletions SET attempts = attempts + 1, last_error = $2 WHERE auth_uid = $1",
            auth_uid, error[:500])


async def count_users(conn: Conn) -> int:
    return await conn.fetchval("SELECT count(*) FROM users")


async def inactive_users(conn: Conn, days: int, limit: int) -> Sequence[Row]:
    return await conn.fetch(
        """SELECT id, auth_uid FROM users
           WHERE last_seen_at < now() - ($1::int * interval '1 day')
           ORDER BY last_seen_at LIMIT $2""",
        days, limit)


async def still_inactive(conn: Conn, user_id: int, days: int) -> bool:
    """Re-check under a row lock, in case the person came back while the job was running."""
    return bool(await conn.fetchval(
        "SELECT last_seen_at < now() - ($2::int * interval '1 day') FROM users WHERE id = $1 FOR UPDATE",
        user_id, days))


async def recount_holders(conn: Conn) -> tuple[int, int]:
    """Compare the stored holder counts with a full recount and repair any difference, then drop
    counts that have reached zero. Run inside a REPEATABLE READ transaction: if a sync changes a
    count while this runs, the transaction fails with a serialization error and is retried,
    instead of overwriting the newer count. Returns (counts repaired, zero counts dropped)."""
    repaired = await conn.fetchval(
        """WITH actual AS (
               SELECT number_hmac, count(*)::int AS holders
               FROM user_numbers WHERE (in_contacts OR in_call_log) AND NOT hidden GROUP BY number_hmac
           ), drift AS (
               SELECT COALESCE(a.number_hmac, s.number_hmac) AS number_hmac, COALESCE(a.holders, 0) AS holders
               FROM actual a FULL JOIN number_stats s ON s.number_hmac = a.number_hmac
               WHERE COALESCE(a.holders, 0) <> COALESCE(s.holders, 0)
           ), repaired AS (
               INSERT INTO number_stats AS s (number_hmac, holders, holders_day_start)
               SELECT number_hmac, holders, holders FROM drift ORDER BY number_hmac
               ON CONFLICT (number_hmac) DO UPDATE SET holders = EXCLUDED.holders
               RETURNING 1
           )
           SELECT count(*) FROM repaired""")
    dropped = _count(await conn.execute("DELETE FROM number_stats WHERE holders = 0"))
    return int(repaired), dropped


async def purge_lookup_log(conn: Conn, days: int) -> int:
    return _count(await conn.execute(
        "DELETE FROM lookup_log WHERE created_at < now() - ($1::int * interval '1 day')", days))


async def purge_tombstones(conn: Conn, days: int, suspended_days: int) -> int:
    return _count(await conn.execute(
        """DELETE FROM account_tombstones
           WHERE (suspended_at IS NULL AND deleted_at < now() - ($1::int * interval '1 day'))
              OR (suspended_at IS NOT NULL AND deleted_at < now() - ($2::int * interval '1 day'))""",
        days, suspended_days))


async def purge_auth_deletions(conn: Conn, days: int) -> int:
    return _count(await conn.execute(
        "DELETE FROM auth_deletions WHERE done_at < now() - ($1::int * interval '1 day')", days))


async def open_reports(conn: Conn, limit: int) -> Sequence[Row]:
    return await conn.fetch(
        """SELECT r.id, r.reason, r.note, r.reported_name, r.created_at,
                  u.public_id::text AS reported_public_id, u.display_name AS current_name,
                  u.suspended_at IS NOT NULL AS suspended,
                  (SELECT count(*) FROM reports r2
                   WHERE r2.reported_phone_hmac = r.reported_phone_hmac AND r2.resolved_at IS NULL) AS open_against_same_number
           FROM reports r LEFT JOIN users u ON u.phone_hmac = r.reported_phone_hmac
           WHERE r.resolved_at IS NULL ORDER BY r.created_at LIMIT $1""",
        limit)


async def report_by_id(conn: Conn, report_id: int) -> Row | None:
    return await conn.fetchrow("SELECT id, reported_phone_hmac FROM reports WHERE id = $1", report_id)


async def resolve_reports_for_phone(conn: Conn, phone_hmac: bytes, resolution: str) -> int:
    return _count(await conn.execute(
        """UPDATE reports SET resolved_at = now(), resolution = $2
           WHERE reported_phone_hmac = $1::bytea AND resolved_at IS NULL""",
        phone_hmac, resolution))


async def resolve_report(conn: Conn, report_id: int, resolution: str) -> int:
    return _count(await conn.execute(
        "UPDATE reports SET resolved_at = now(), resolution = $2 WHERE id = $1 AND resolved_at IS NULL",
        report_id, resolution))


def as_list(rows: Any) -> list[Row]:
    return list(rows or [])


# ---------------------------------------------------------------- popular names and the day's changes

async def compute_popular_names(conn: Conn, min_people: int, min_account_days: int, active_days: int) -> int:
    """For every number, the name at least `min_people` established people saved for it (most
    people wins; ties go to the same name every night). Established: accounts at least
    min_account_days old, used in the last active_days, syncing, not suspended. Opted-out numbers
    get none. Returns how many numbers have a popular name."""
    await conn.execute(
        """WITH counted AS (
               SELECT un.number_hmac, un.name_hmac, count(*)::int AS people,
                      (array_agg(un.name_enc ORDER BY un.user_id))[1] AS name_enc
               FROM user_numbers un
               JOIN users u ON u.id = un.user_id
               WHERE un.in_contacts AND NOT un.hidden AND un.name_hmac IS NOT NULL
                 AND u.sync_enabled AND u.suspended_at IS NULL
                 AND u.created_at <= now() - make_interval(days => $2)
                 AND u.last_seen_at >= now() - make_interval(days => $3)
               GROUP BY un.number_hmac, un.name_hmac
               HAVING count(*) >= $1),
           best AS (
               SELECT DISTINCT ON (number_hmac) number_hmac, name_enc, people
               FROM counted
               WHERE NOT EXISTS (SELECT 1 FROM suppressed_numbers s WHERE s.number_hmac = counted.number_hmac)
                 AND NOT EXISTS (SELECT 1 FROM popular_name_holds h WHERE h.number_hmac = counted.number_hmac)
               ORDER BY number_hmac, people DESC, name_hmac),
           gone AS (
               DELETE FROM popular_names p WHERE NOT EXISTS (SELECT 1 FROM best b WHERE b.number_hmac = p.number_hmac))
           INSERT INTO popular_names AS p (number_hmac, name_enc, people)
           SELECT number_hmac, name_enc, people FROM best
           ON CONFLICT (number_hmac) DO UPDATE
               SET name_enc = EXCLUDED.name_enc, people = EXCLUDED.people, updated_at = now()
               WHERE p.people <> EXCLUDED.people OR p.name_enc <> EXCLUDED.name_enc""",
        min_people, min_account_days, active_days)
    return int(await conn.fetchval("SELECT count(*)::int FROM popular_names"))


async def hold_popular_name(conn: Conn, number_hmac: bytes, reason: str) -> bool:
    """Stops the number getting a popular name, and removes the one it has now. True if it had one."""
    await conn.execute(
        """INSERT INTO popular_name_holds (number_hmac, reason) VALUES ($1::bytea, $2)
           ON CONFLICT (number_hmac) DO UPDATE SET reason = EXCLUDED.reason""",
        number_hmac, reason)
    return _count(await conn.execute("DELETE FROM popular_names WHERE number_hmac = $1::bytea", number_hmac)) > 0


async def release_popular_name(conn: Conn, number_hmac: bytes) -> bool:
    """The number can get a popular name again, from the next nightly run."""
    return _count(await conn.execute("DELETE FROM popular_name_holds WHERE number_hmac = $1::bytea", number_hmac)) > 0


async def number_overview(conn: Conn, number_hmac: bytes) -> Row:
    """What moderators need to see about one number, without any names people saved for it."""
    return await conn.fetchrow(
        """SELECT u.public_id, u.display_name, u.suspended_at, u.created_at AS joined_at,
                  (SELECT t.suspended_at FROM account_tombstones t WHERE t.phone_hmac = $1::bytea) AS number_suspended_at,
                  coalesce((SELECT s.holders FROM number_stats s WHERE s.number_hmac = $1::bytea), 0) AS holders,
                  (SELECT p.people FROM popular_names p WHERE p.number_hmac = $1::bytea) AS popular_name_people,
                  (SELECT h.reason FROM popular_name_holds h WHERE h.number_hmac = $1::bytea) AS popular_name_held,
                  EXISTS (SELECT 1 FROM suppressed_numbers x WHERE x.number_hmac = $1::bytea) AS taken_out
           FROM (SELECT 1) one
           LEFT JOIN users u ON u.phone_hmac = $1::bytea""",
        number_hmac)


async def start_new_day(conn: Conn) -> int:
    """The nightly job: today's changes are counted from here."""
    return _count(await conn.execute(
        "UPDATE number_stats SET holders_day_start = holders WHERE holders_day_start <> holders"))


async def wake_tokens(conn: Conn, after: str, limit: int, active_days: int) -> list[str]:
    """Push tokens of people who used Found With recently, for the check-for-missed-calls wake-up."""
    rows = await conn.fetch(
        """SELECT t.token FROM push_tokens t JOIN users u ON u.id = t.user_id
           WHERE t.token > $1 AND u.suspended_at IS NULL
             AND u.last_seen_at >= now() - make_interval(days => $3)
           ORDER BY t.token LIMIT $2""",
        after, limit, active_days)
    return [r["token"] for r in rows]


# ---------------------------------------------------------------- phone health by brand

async def add_health(conn: Conn, day: Any, brand: str, android: int, calls_seen: int, calls_checked: int) -> None:
    await conn.execute(
        """INSERT INTO device_health AS h (day, brand, android, phones, calls_seen, calls_checked)
           VALUES ($1::date, $2, $3, 1, $4, $5)
           ON CONFLICT (day, brand, android) DO UPDATE
               SET phones = h.phones + 1, calls_seen = h.calls_seen + EXCLUDED.calls_seen,
                   calls_checked = h.calls_checked + EXCLUDED.calls_checked""",
        day, brand, android, calls_seen, calls_checked)


async def health_by_brand(conn: Conn, days: int) -> Sequence[Row]:
    return await conn.fetch(
        """SELECT brand, sum(phones)::int AS phone_days, sum(calls_seen)::int AS calls_seen,
                  sum(calls_checked)::int AS calls_checked
           FROM device_health WHERE day >= current_date - $1::int
           GROUP BY brand ORDER BY sum(calls_seen) DESC""", days)


async def purge_health(conn: Conn, days: int) -> int:
    return _count(await conn.execute("DELETE FROM device_health WHERE day < current_date - $1::int", days))


# ---------------------------------------------------------------- push messages

PUSH_CARDS, PUSH_JOINED, PUSH_INVITE = 1, 2, 4


async def save_push_token(conn: Conn, user_id: int, token: str, keep: int = 5) -> None:
    """Remember an app's push token. A token moves with its app, so it is reassigned if another
    account signs in on that phone. Only the newest `keep` tokens per person are kept."""
    await conn.execute(
        """INSERT INTO push_tokens (token, user_id) VALUES ($1, $2)
           ON CONFLICT (token) DO UPDATE SET user_id = EXCLUDED.user_id, updated_at = now()""",
        token, user_id)
    await conn.execute(
        """DELETE FROM push_tokens WHERE user_id = $1 AND token NOT IN (
               SELECT token FROM push_tokens WHERE user_id = $1 ORDER BY updated_at DESC LIMIT $2)""",
        user_id, keep)


async def delete_push_token(conn: Conn, user_id: int, token: str) -> None:
    await conn.execute("DELETE FROM push_tokens WHERE token = $1 AND user_id = $2", token, user_id)


async def delete_push_tokens(conn: Conn, tokens: Sequence[str]) -> int:
    if not tokens:
        return 0
    return _count(await conn.execute("DELETE FROM push_tokens WHERE token = ANY($1::text[])", list(tokens)))


async def purge_push_tokens(conn: Conn, days: int) -> int:
    """Apps refresh their token at every start; one not seen for this long is gone."""
    return _count(await conn.execute(
        "DELETE FROM push_tokens WHERE updated_at < now() - make_interval(days => $1)", days))


async def queue_card_pushes(conn: Conn, actor_id: int, numbers: Sequence[bytes], gap_s: int,
                            limit: int = 5000) -> int:
    """Everyone else who has one of these numbers saved gets a push, at most every gap_s.
    Only people with the app installed (a push token) are queued."""
    if not numbers:
        return 0
    return _count(await conn.execute(
        """INSERT INTO push_queue AS q (user_id, why, due_at)
           SELECT x.user_id, 1::smallint, x.due FROM (
               SELECT DISTINCT un.user_id,
                      GREATEST(now(), COALESCE(u.last_push_at, '-infinity'::timestamptz) + make_interval(secs => $3)) AS due
               FROM user_numbers un
               JOIN users u ON u.id = un.user_id AND u.sync_enabled
               WHERE un.number_hmac = ANY($2::bytea[]) AND (un.in_contacts OR un.in_call_log) AND NOT un.hidden AND un.user_id <> $1
                 AND EXISTS (SELECT 1 FROM push_tokens t WHERE t.user_id = un.user_id)
               ORDER BY un.user_id
               LIMIT $4) x
           ON CONFLICT (user_id) DO UPDATE SET why = q.why | EXCLUDED.why, due_at = LEAST(q.due_at, EXCLUDED.due_at)""",
        actor_id, list(numbers), float(gap_s), limit))


async def queue_joined_pushes(conn: Conn, new_user_id: int, new_phone_hmac: bytes, excluded: Sequence[int],
                              limit: int = 2000) -> int:
    """Someone started taking part: everyone who has their number saved gets a push now."""
    return _count(await conn.execute(
        """INSERT INTO push_queue AS q (user_id, why, due_at)
           SELECT un.user_id, 2::smallint, now()
           FROM user_numbers un
           JOIN users u ON u.id = un.user_id AND u.sync_enabled
           WHERE un.number_hmac = $2::bytea AND (un.in_contacts OR un.in_call_log) AND NOT un.hidden AND un.user_id <> $1
             AND un.user_id <> ALL($3::bigint[])
             AND EXISTS (SELECT 1 FROM push_tokens t WHERE t.user_id = un.user_id)
           ORDER BY un.user_id
           LIMIT $4
           ON CONFLICT (user_id) DO UPDATE SET why = q.why | EXCLUDED.why, due_at = now()""",
        new_user_id, new_phone_hmac, list(excluded), limit))


async def queue_push(conn: Conn, user_id: int, why: int) -> None:
    await conn.execute(
        """INSERT INTO push_queue AS q (user_id, why, due_at) VALUES ($1, $2::smallint, now())
           ON CONFLICT (user_id) DO UPDATE SET why = q.why | EXCLUDED.why, due_at = now()""",
        user_id, why)


async def take_due_pushes(conn: Conn, batch: int) -> list[tuple[int, int, list[str]]]:
    """Remove up to `batch` due entries and return (user id, why, tokens) for each. Run inside a
    transaction; SKIP LOCKED lets two dispatchers share the queue without sending twice."""
    rows = await conn.fetch(
        """DELETE FROM push_queue WHERE user_id IN (
               SELECT user_id FROM push_queue WHERE due_at <= now()
               ORDER BY due_at LIMIT $1 FOR UPDATE SKIP LOCKED)
           RETURNING user_id, why""", batch)
    if not rows:
        return []
    ids = [r["user_id"] for r in rows]
    tokens: dict[int, list[str]] = {}
    for r in await conn.fetch("SELECT user_id, token FROM push_tokens WHERE user_id = ANY($1::bigint[])", ids):
        tokens.setdefault(r["user_id"], []).append(r["token"])
    await conn.execute("UPDATE users SET last_push_at = now() WHERE id = ANY($1::bigint[])", ids)
    return [(r["user_id"], int(r["why"]), tokens.get(r["user_id"], [])) for r in rows]


async def requeue_pushes(conn: Conn, entries: Sequence[tuple[int, int]], delay_s: int = 60) -> None:
    for user_id, why in entries:
        await conn.execute(
            """INSERT INTO push_queue AS q (user_id, why, due_at)
               VALUES ($1, $2::smallint, now() + make_interval(secs => $3))
               ON CONFLICT (user_id) DO UPDATE SET why = q.why | EXCLUDED.why""",
            user_id, why, float(delay_s))


# ---------------------------------------------------------------- your data

async def data_summary(conn: Conn, user_id: int) -> Row:
    return await conn.fetchrow(
        """SELECT
             (SELECT count(*) FROM user_numbers WHERE user_id = $1 AND in_contacts)::int AS contacts,
             (SELECT count(*) FROM user_numbers WHERE user_id = $1 AND in_call_log)::int AS call_log,
             (SELECT count(*) FROM user_numbers WHERE user_id = $1 AND hidden)::int AS hidden,
             (SELECT count(*) FROM blocks WHERE user_id = $1)::int AS blocks,
             (SELECT count(*) FROM reports WHERE reporter_id = $1)::int AS reports,
             (SELECT count(*) FROM lookup_log WHERE user_id = $1 AND kind = 'search')::int AS searches,
             (SELECT count(*) FROM lookup_log WHERE user_id = $1 AND kind = 'call')::int AS call_lookups,
             (SELECT count(*) FROM invite_redemptions WHERE inviter_id = $1)::int AS invited,
             (SELECT count(*) FROM push_tokens WHERE user_id = $1)::int AS devices""",
        user_id)


async def export_numbers(conn: Conn, user_id: int) -> Sequence[Row]:
    return await conn.fetch(
        """SELECT number_hmac, in_contacts, in_call_log, hidden, name_enc FROM user_numbers
           WHERE user_id = $1 ORDER BY number_hmac""", user_id)


async def export_lookups(conn: Conn, user_id: int) -> Sequence[Row]:
    return await conn.fetch(
        """SELECT number_hmac, kind, holders_found, created_at FROM lookup_log
           WHERE user_id = $1 ORDER BY created_at DESC LIMIT 5000""", user_id)


async def export_reports(conn: Conn, user_id: int) -> Sequence[Row]:
    return await conn.fetch(
        """SELECT reported_name, reason, note, created_at, resolved_at FROM reports
           WHERE reporter_id = $1 ORDER BY created_at DESC""", user_id)


async def export_blocks(conn: Conn, user_id: int) -> Sequence[Row]:
    return await conn.fetch(
        "SELECT blocked_phone_hmac, created_at FROM blocks WHERE user_id = $1 ORDER BY created_at DESC", user_id)
