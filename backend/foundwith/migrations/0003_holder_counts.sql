-- Found With 0003: stored holder counts, so Home cards and incoming-call lookups never count rows.
--
-- number_stats.holders = how many rows in user_numbers have that number with in_contacts AND NOT
-- hidden, across everyone. Call-log-only rows never count. Statement-level triggers keep it
-- current inside the same transaction as the change, so a count is never ahead of or behind the
-- data it describes. The maintenance job compares it with a recount and repairs any difference.
--
-- Safe on a live database: CREATE TRIGGER locks user_numbers against writes until this migration
-- commits, so the backfill below sees every row and nothing is counted twice.

CREATE TABLE number_stats (
    number_hmac  BYTEA   PRIMARY KEY,
    holders      INTEGER NOT NULL
);

-- Every function adds a signed delta per number, in number order, through the same upsert.
-- Taking row locks in one order keeps concurrent syncs from deadlocking on popular numbers.

CREATE FUNCTION number_stats_after_insert() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO number_stats AS s (number_hmac, holders)
    SELECT n.number_hmac, count(*)::int
    FROM new_rows n
    WHERE n.in_contacts AND NOT n.hidden
    GROUP BY n.number_hmac
    ORDER BY n.number_hmac
    ON CONFLICT (number_hmac) DO UPDATE SET holders = s.holders + EXCLUDED.holders;
    RETURN NULL;
END $$;

CREATE FUNCTION number_stats_after_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO number_stats AS s (number_hmac, holders)
    SELECT d.number_hmac, sum(d.delta)::int
    FROM (
        SELECT n.number_hmac, 1 AS delta FROM new_rows n WHERE n.in_contacts AND NOT n.hidden
        UNION ALL
        SELECT o.number_hmac, -1 AS delta FROM old_rows o WHERE o.in_contacts AND NOT o.hidden
    ) d
    GROUP BY d.number_hmac
    HAVING sum(d.delta) <> 0
    ORDER BY d.number_hmac
    ON CONFLICT (number_hmac) DO UPDATE SET holders = s.holders + EXCLUDED.holders;
    RETURN NULL;
END $$;

CREATE FUNCTION number_stats_after_delete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO number_stats AS s (number_hmac, holders)
    SELECT o.number_hmac, -count(*)::int
    FROM old_rows o
    WHERE o.in_contacts AND NOT o.hidden
    GROUP BY o.number_hmac
    ORDER BY o.number_hmac
    ON CONFLICT (number_hmac) DO UPDATE SET holders = s.holders + EXCLUDED.holders;
    RETURN NULL;
END $$;

CREATE TRIGGER user_numbers_count_insert AFTER INSERT ON user_numbers
    REFERENCING NEW TABLE AS new_rows
    FOR EACH STATEMENT EXECUTE FUNCTION number_stats_after_insert();

CREATE TRIGGER user_numbers_count_update AFTER UPDATE ON user_numbers
    REFERENCING OLD TABLE AS old_rows NEW TABLE AS new_rows
    FOR EACH STATEMENT EXECUTE FUNCTION number_stats_after_update();

CREATE TRIGGER user_numbers_count_delete AFTER DELETE ON user_numbers
    REFERENCING OLD TABLE AS old_rows
    FOR EACH STATEMENT EXECUTE FUNCTION number_stats_after_delete();

INSERT INTO number_stats (number_hmac, holders)
SELECT number_hmac, count(*)::int
FROM user_numbers
WHERE in_contacts AND NOT hidden
GROUP BY number_hmac;

-- Lookups and cards are read-mostly and latency-sensitive; keep these hot tables tidy.
ALTER TABLE number_stats SET (fillfactor = 80);
