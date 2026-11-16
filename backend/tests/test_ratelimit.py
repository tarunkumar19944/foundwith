from foundwith.ratelimit import RateLimiter


class Clock:
    now = 1000.0

    def __call__(self):
        return self.now


def test_burst_then_steady_rate():
    clock = Clock()
    limiter = RateLimiter(clock)
    assert [limiter.check("u1", "heavy", 20) for _ in range(10)] == [None] * 10     # half a minute's worth at once
    assert limiter.check("u1", "heavy", 20) == 3                                    # then one every 3 seconds
    assert limiter.check("u2", "heavy", 20) is None                                 # per person
    assert limiter.check("u1", "any", 120) is None                                  # per kind of request
    clock.now += 3
    assert limiter.check("u1", "heavy", 20) is None
    assert limiter.check("u1", "heavy", 20) == 3
    clock.now += 600                                                                # idle: refills to the burst, not beyond
    assert [limiter.check("u1", "heavy", 20) for _ in range(11)].count(None) == 10


def test_memory_stays_bounded():
    clock = Clock()
    limiter = RateLimiter(clock, max_keys=100)
    for i in range(500):
        limiter.check(f"user-{i}", "any", 120)
        clock.now += 1
    assert len(limiter._buckets) <= 121
    for i in range(500):                                   # a flood of distinct keys within one moment
        limiter.check(f"flood-{i}", "any", 120)
    assert len(limiter._buckets) <= 101
