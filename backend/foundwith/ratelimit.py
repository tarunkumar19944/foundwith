"""A small in-memory rate limiter, one bucket per signed-in person and kind of request.

Each API instance keeps its own buckets, so with several instances running the real ceiling is
this limit times the instance count. That is fine for its job: stopping one misbehaving client
(a sync loop, a script paging cards) from saturating the database for everyone. The daily quotas
that protect people's data live in the database, not here."""

from __future__ import annotations

import math
import time
from collections.abc import Callable


class RateLimiter:
    def __init__(self, clock: Callable[[], float] = time.monotonic, max_keys: int = 50_000) -> None:
        self._clock = clock
        self._max_keys = max_keys
        self._buckets: dict[tuple[str, str], tuple[float, float]] = {}  # key -> (tokens, updated_at)

    def check(self, who: str, kind: str, per_minute: int) -> int | None:
        """Spend one request. Returns None when allowed, or the seconds to wait when not.
        A bucket holds up to half a minute's worth, so short bursts are fine."""
        now = self._clock()
        rate = per_minute / 60.0
        burst = max(1.0, per_minute / 2.0)
        key = (who, kind)
        tokens, updated = self._buckets.get(key, (burst, now))
        tokens = min(burst, tokens + (now - updated) * rate)
        if tokens < 1.0:
            self._buckets[key] = (tokens, now)
            return max(1, math.ceil((1.0 - tokens) / rate))
        self._buckets[key] = (tokens - 1.0, now)
        if len(self._buckets) > self._max_keys:
            self._evict(now)
        return None

    def _evict(self, now: float) -> None:
        """Forget buckets idle for over two minutes (by then they are full again anyway)."""
        self._buckets = {k: v for k, v in self._buckets.items() if now - v[1] < 120.0}
        if len(self._buckets) > self._max_keys:  # still too many: a flood of distinct keys
            self._buckets.clear()
