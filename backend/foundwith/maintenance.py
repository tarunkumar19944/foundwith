"""Daily housekeeping, run by Cloud Scheduler as a Cloud Run job:
    python -m foundwith.maintenance

  * deletes accounts that have not been seen for INACTIVE_DAYS (so holder lists only show people
    who still use Found With, and a recycled number never inherits the previous owner's account)
  * retries sign-in account deletions that failed earlier
  * purges the lookup record, account tombstones and finished deletion records past their retention
  * recounts holders, works out popular names and starts a new day for the evening note
"""

from __future__ import annotations

import json
import logging
from typing import Any

import anyio

from . import repo
from .config import Settings
from .db import AsyncpgDatabase, Database
from .identity import GoogleIdentityAdmin, IdentityAdmin, NoopIdentityAdmin
from .service import retry_sign_in_deletions

log = logging.getLogger("foundwith.maintenance")

# Deleting inactive accounts is the one destructive step here, so each run is limited to this many
# accounts or this share of all accounts, whichever is larger. If "last seen" ever stopped being
# recorded, the damage would be slow and the warning below would show up in the logs first.
INACTIVE_MIN_PER_RUN = 100
INACTIVE_MAX_SHARE = 0.05
FINISHED_DELETIONS_KEEP_DAYS = 30
PUSH_TOKEN_KEEP_DAYS = 60   # the app refreshes its token at every start
HEALTH_KEEP_DAYS = 400      # a little over a year of brand reports


async def delete_inactive_accounts(db: Database, days: int) -> dict[str, int]:
    async with db.acquire() as conn:
        total = await repo.count_users(conn)
        per_run = max(INACTIVE_MIN_PER_RUN, int(total * INACTIVE_MAX_SHARE))
        candidates = list(await repo.inactive_users(conn, days, per_run + 1))
    hit_cap = len(candidates) > per_run
    deleted = 0
    for row in candidates[:per_run]:
        async with db.acquire() as conn, conn.transaction():
            if not await repo.still_inactive(conn, row["id"], days):
                continue  # came back, or already gone
            await repo.retire_user(conn, row["id"])
            await repo.queue_auth_deletion(conn, row["auth_uid"])
            deleted += 1
    if hit_cap:
        log.warning("more inactive accounts than one run deletes; the rest wait for the next run",
                    extra={"event": "inactive_cap_hit"})
    return {"inactive_deleted": deleted, "inactive_cap_hit": int(hit_cap)}


async def check_holder_counts(db: Database, attempts: int = 3) -> dict[str, int]:
    """Recount every number's holders and repair the stored counts if they have drifted. They are
    kept by database triggers, so any repair here means something bypassed them: it is logged as a
    warning so it gets looked at."""
    for attempt in range(attempts):
        try:
            async with db.acquire() as conn, conn.transaction():
                await conn.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ")
                repaired, dropped = await repo.recount_holders(conn)
            break
        except Exception as exc:
            if attempt == attempts - 1 or getattr(exc, "sqlstate", None) not in ("40001", "40P01"):
                raise
    if repaired:
        log.warning("holder counts had drifted and were repaired", extra={"event": "holder_count_drift"})
    return {"holder_counts_repaired": repaired, "zero_counts_dropped": dropped}


async def run(db: Database, identity: IdentityAdmin, settings: Settings) -> dict[str, Any]:
    stats: dict[str, Any] = await delete_inactive_accounts(db, settings.inactive_days)
    done, failed = await retry_sign_in_deletions(db, identity)
    stats.update({"sign_in_deletions_done": done, "sign_in_deletions_failed": failed})
    async with db.acquire() as conn:
        stats["lookup_log_purged"] = await repo.purge_lookup_log(conn, settings.lookup_log_days)
        stats["tombstones_purged"] = await repo.purge_tombstones(
            conn, settings.tombstone_days, settings.suspension_memory_days)
        stats["deletion_records_purged"] = await repo.purge_auth_deletions(conn, FINISHED_DELETIONS_KEEP_DAYS)
    stats.update(await check_holder_counts(db))
    async with db.acquire() as conn:
        stats["popular_names"] = await repo.compute_popular_names(
            conn, settings.popular_name_min_people, settings.popular_name_min_account_days,
            settings.popular_name_active_days)
        # After the recount, so "more people today" is counted from correct totals.
        stats["day_started_numbers"] = await repo.start_new_day(conn)
        stats["push_tokens_purged"] = await repo.purge_push_tokens(conn, PUSH_TOKEN_KEEP_DAYS)
        stats["health_rows_purged"] = await repo.purge_health(conn, HEALTH_KEEP_DAYS)
    return stats


async def main() -> None:
    settings = Settings.from_env(for_api=False)
    identity: IdentityAdmin = (GoogleIdentityAdmin(settings.firebase_project_id)
                               if settings.auth_mode == "firebase" else NoopIdentityAdmin())
    db = await AsyncpgDatabase.connect(settings, application_name="found-with-maintenance", pool_size=2,
                                       statement_timeout_ms=30 * 60 * 1000)
    try:
        stats = await run(db, identity, settings)
    finally:
        await db.close()
    severity = "WARNING" if stats["sign_in_deletions_failed"] or stats["inactive_cap_hit"] else "INFO"
    print(json.dumps({"severity": severity, "message": "maintenance finished", **stats}))


if __name__ == "__main__":
    anyio.run(main)
