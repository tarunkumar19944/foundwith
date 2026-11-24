"""Push messages through Firebase Cloud Messaging (FCM). Two kinds, none with a name in it:

  * Silent refreshes ("cards", "joined", "invite"): the app fetches its cards again. Queued in
    push_queue, one row per person; Cloud Scheduler calls /internal/push every minute. Card
    changes wait at least cards_push_gap_s (a minute) after the person's last push; joins and
    invites are also sent at once, from the request that caused them.
  * Wake-ups ("wake"), a few times a day: the app checks the call log for calls it didn't get to
    check (some phones stop apps in the background). /internal/push/wake.
  There is no daily summary (removed 22 Nov 2026): the app tells about changes itself, when it refreshes.
"""

from __future__ import annotations

import json
import logging
import time
import urllib.error
import urllib.request
from collections.abc import Awaitable, Callable, Sequence
from typing import Any, Protocol

import anyio

from . import repo
from .db import Database

log = logging.getLogger("foundwith.push")

METADATA_TOKEN_URL = ("http://metadata.google.internal/computeMetadata/v1/instance/"
                      "service-accounts/default/token")

OK, UNREGISTERED, RETRY, FAILED = "ok", "unregistered", "retry", "failed"


def reasons(why: int) -> str:
    names = [name for bit, name in ((repo.PUSH_CARDS, "cards"), (repo.PUSH_JOINED, "joined"),
                                    (repo.PUSH_INVITE, "invite")) if why & bit]
    return ",".join(names) or "cards"

class Sender(Protocol):
    async def send_all(self, items: Sequence[tuple[str, int]]) -> list[tuple[str, str]]: ...

    async def send_messages(self, items: Sequence[tuple[str, dict[str, Any]]]) -> list[tuple[str, str]]: ...


class LogSender:
    """For local runs: logs what would be sent and keeps it, for tests."""

    def __init__(self) -> None:
        self.sent: list[tuple[str, str]] = []
        self.messages: list[tuple[str, dict[str, Any]]] = []

    async def send_all(self, items: Sequence[tuple[str, int]]) -> list[tuple[str, str]]:
        for token, why in items:
            self.sent.append((token, reasons(why)))
            log.info("push (not sent: PUSH_MODE=log)", extra={"event": "push_logged", "why": reasons(why)})
        return [(token, OK) for token, _ in items]

    async def send_messages(self, items: Sequence[tuple[str, dict[str, Any]]]) -> list[tuple[str, str]]:
        for token, message in items:
            self.messages.append((token, message))
            log.info("push (not sent: PUSH_MODE=log)", extra={"event": "push_logged", "kind": message.get("kind")})
        return [(token, OK) for token, _ in items]


Poster = Callable[[str, dict[str, str], dict[str, Any]], tuple[int, dict[str, Any]]]


def _post_json(url: str, headers: dict[str, str], body: dict[str, Any]) -> tuple[int, dict[str, Any]]:
    req = urllib.request.Request(url, data=json.dumps(body).encode("utf-8"), method="POST",
                                 headers={**headers, "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:  # noqa: S310 (fixed https URL)
            return resp.status, json.loads(resp.read() or b"{}")
    except urllib.error.HTTPError as exc:
        try:
            payload = json.loads(exc.read() or b"{}")
        except ValueError:
            payload = {}
        return exc.code, payload


async def metadata_token() -> tuple[str, float]:
    """An access token for the service account Cloud Run runs as, from the metadata server."""

    def _get() -> tuple[str, float]:
        req = urllib.request.Request(METADATA_TOKEN_URL, headers={"Metadata-Flavor": "Google"})
        with urllib.request.urlopen(req, timeout=5) as resp:  # noqa: S310 (fixed metadata URL)
            data = json.loads(resp.read())
        return str(data["access_token"]), float(data.get("expires_in", 300))

    return await anyio.to_thread.run_sync(_get)


class FcmSender:
    """Sends data-only messages through the FCM HTTP v1 API, 16 at a time."""

    def __init__(self, project_id: str, *, token: Callable[[], Awaitable[tuple[str, float]]] = metadata_token,
                 post: Poster = _post_json, clock: Callable[[], float] = time.time) -> None:
        self.url = f"https://fcm.googleapis.com/v1/projects/{project_id}/messages:send"
        self._token_fn = token
        self._post = post
        self._clock = clock
        self._token: str | None = None
        self._token_expires = 0.0
        self._lock = anyio.Lock()

    async def _access_token(self) -> str:
        async with self._lock:
            if self._token is None or self._clock() > self._token_expires - 60:
                self._token, lifetime = await self._token_fn()
                self._token_expires = self._clock() + lifetime
            return self._token

    @staticmethod
    def message(token: str, why: int) -> dict[str, Any]:
        urgent = bool(why & (repo.PUSH_JOINED | repo.PUSH_INVITE))
        return {"message": {
            "token": token,
            "data": {"why": reasons(why)},
            "android": {
                # Every refresh is high priority since 24 Nov 2026, so changes are told within about
                # a minute even when the phone is dozing. Joins and invites live longer.
                "priority": "high",
                "ttl": "86400s" if urgent else "3600s",
                "collapse_key": "refresh",
            },
        }}

    @staticmethod
    def build(token: str, message: dict[str, Any]) -> dict[str, Any]:
        """A wake-up, from the neutral form used by LogSender."""
        return {"message": {
            "token": token,
            "data": {"why": "wake"},
            "android": {"priority": "normal", "ttl": "14400s", "collapse_key": "wake"},
        }}

    async def _send(self, token: str, why: int) -> str:
        return await self._post_message(self.message(token, why))

    async def _post_message(self, body: dict[str, Any]) -> str:
        try:
            access = await self._access_token()
            status, payload = await anyio.to_thread.run_sync(
                self._post, self.url, {"Authorization": f"Bearer {access}"}, body)
        except Exception:  # noqa: BLE001 - network trouble: try again on the next run
            log.warning("push failed", exc_info=True, extra={"event": "push_error"})
            return RETRY
        if status == 200:
            return OK
        codes = {d.get("errorCode") for d in (payload.get("error") or {}).get("details") or [] if isinstance(d, dict)}
        if status == 404 or "UNREGISTERED" in codes:
            return UNREGISTERED          # the app was uninstalled or the token replaced
        if status in (429, 500, 502, 503, 504):
            return RETRY
        log.warning("push rejected", extra={"event": "push_rejected", "status": status})
        return FAILED

    async def send_all(self, items: Sequence[tuple[str, int]]) -> list[tuple[str, str]]:
        results: list[tuple[str, str]] = []
        limiter = anyio.CapacityLimiter(16)

        async def one(token: str, why: int) -> None:
            async with limiter:
                results.append((token, await self._send(token, why)))

        async with anyio.create_task_group() as tg:
            for token, why in items:
                tg.start_soon(one, token, why)
        return results

    async def send_messages(self, items: Sequence[tuple[str, dict[str, Any]]]) -> list[tuple[str, str]]:
        results: list[tuple[str, str]] = []
        limiter = anyio.CapacityLimiter(16)

        async def one(token: str, message: dict[str, Any]) -> None:
            async with limiter:
                results.append((token, await self._post_message(self.build(token, message))))

        async with anyio.create_task_group() as tg:
            for token, message in items:
                tg.start_soon(one, token, message)
        return results


async def dispatch(db: Database, sender: Sender, batch: int = 500) -> dict[str, int]:
    """Send every push that is due (up to `batch` people). Tokens FCM says are gone are deleted;
    people whose push couldn't be sent are queued again for a minute later."""
    async with db.acquire() as conn, conn.transaction():
        due = await repo.take_due_pushes(conn, batch)
    if not due:
        return {"people": 0, "sent": 0, "tokens_removed": 0, "retrying": 0}
    owner = {token: (user_id, why) for user_id, why, tokens in due for token in tokens}
    results = await sender.send_all([(token, why) for token, (_, why) in owner.items()])
    gone = [token for token, outcome in results if outcome == UNREGISTERED]
    retry = {owner[token] for token, outcome in results if outcome == RETRY}
    async with db.acquire() as conn:
        removed = await repo.delete_push_tokens(conn, gone)
        await repo.requeue_pushes(conn, sorted(retry))
    sent = sum(1 for _, outcome in results if outcome == OK)
    log.info("pushes sent", extra={"event": "push_dispatch", "people": len(due), "sent": sent})
    return {"people": len(due), "sent": sent, "tokens_removed": removed, "retrying": len(retry)}


async def send_wakeups(db: Database, sender: Sender, active_days: int = 30, batch: int = 1000) -> dict[str, int]:
    """Ask every recently used app to check its call log for calls it missed."""
    after, sent, gone_total = "", 0, 0
    while True:
        async with db.acquire() as conn:
            tokens = await repo.wake_tokens(conn, after, batch, active_days)
        if not tokens:
            break
        after = tokens[-1]
        results = await sender.send_messages([(t, {"kind": "wake"}) for t in tokens])
        sent += sum(1 for _, outcome in results if outcome == OK)
        gone = [token for token, outcome in results if outcome == UNREGISTERED]
        async with db.acquire() as conn:
            gone_total += await repo.delete_push_tokens(conn, gone)
    log.info("wake-ups sent", extra={"event": "wakeups", "sent": sent})
    return {"sent": sent, "tokens_removed": gone_total}
