-- Found With 0005: call logs count, contact names and popular names, sign-in before a name,
-- the evening note, push messages and phone health by brand.
--
-- Safe on a live database: every statement is additive or rebuilds a derived table (number_stats).
-- The new partial index on user_numbers is built before the old one is dropped.

-- 1. Call logs count. A person "has" a number when it is in their contacts or their call log,
--    and they haven't hidden it. The stored counts and the holders index follow that rule.
CREATE INDEX user_numbers_holds_idx
    ON user_numbers (number_hmac, user_id)
    WHERE (in_contacts OR in_call_log) AND NOT hidden;
DROP INDEX user_numbers_holders_idx;

CREATE OR REPLACE FUNCTION number_stats_after_insert() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO number_stats AS s (number_hmac, holders)
    SELECT n.number_hmac, count(*)::int
    FROM new_rows n
    WHERE (n.in_contacts OR n.in_call_log) AND NOT n.hidden
    GROUP BY n.number_hmac
    ORDER BY n.number_hmac
    ON CONFLICT (number_hmac) DO UPDATE SET holders = s.holders + EXCLUDED.holders;
    RETURN NULL;
END $$;

CREATE OR REPLACE FUNCTION number_stats_after_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO number_stats AS s (number_hmac, holders)
    SELECT d.number_hmac, sum(d.delta)::int
    FROM (
        SELECT n.number_hmac, 1 AS delta FROM new_rows n WHERE (n.in_contacts OR n.in_call_log) AND NOT n.hidden
        UNION ALL
        SELECT o.number_hmac, -1 AS delta FROM old_rows o WHERE (o.in_contacts OR o.in_call_log) AND NOT o.hidden
    ) d
    GROUP BY d.number_hmac
    HAVING sum(d.delta) <> 0
    ORDER BY d.number_hmac
    ON CONFLICT (number_hmac) DO UPDATE SET holders = s.holders + EXCLUDED.holders;
    RETURN NULL;
END $$;

CREATE OR REPLACE FUNCTION number_stats_after_delete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO number_stats AS s (number_hmac, holders)
    SELECT o.number_hmac, -count(*)::int
    FROM old_rows o
    WHERE (o.in_contacts OR o.in_call_log) AND NOT o.hidden
    GROUP BY o.number_hmac
    ORDER BY o.number_hmac
    ON CONFLICT (number_hmac) DO UPDATE SET holders = s.holders + EXCLUDED.holders;
    RETURN NULL;
END $$;

-- holders_day_start: the count at the start of the day (reset by the nightly job), so the
-- evening note can say what changed today.
ALTER TABLE number_stats ADD COLUMN holders_day_start INTEGER NOT NULL DEFAULT 0;

LOCK TABLE user_numbers IN SHARE MODE;
DELETE FROM number_stats;
INSERT INTO number_stats (number_hmac, holders, holders_day_start)
SELECT number_hmac, count(*)::int, count(*)::int
FROM user_numbers
WHERE (in_contacts OR in_call_log) AND NOT hidden
GROUP BY number_hmac;

-- 2. Contact names. How the person saved a number: an encrypted copy (AES-GCM, key in Secret
--    Manager) and a keyed hash of the tidied name, so identical names can be counted without
--    reading them. Only for numbers in contacts.
ALTER TABLE user_numbers
    ADD COLUMN name_hmac BYTEA,
    ADD COLUMN name_enc  BYTEA;

-- The name at least 3 established people saved for a number, worked out nightly.
CREATE TABLE popular_names (
    number_hmac  BYTEA       PRIMARY KEY,
    name_enc     BYTEA       NOT NULL,
    people       INTEGER     NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 3. People sign in first and choose a name afterwards.
ALTER TABLE users ALTER COLUMN display_name DROP NOT NULL;
ALTER TABLE users
    ADD COLUMN evening_note    BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN evening_sent_on DATE,
    ADD COLUMN last_push_at    TIMESTAMPTZ;

-- 4. Firebase Cloud Messaging tokens, one per installed app.
CREATE TABLE push_tokens (
    token       TEXT        PRIMARY KEY,
    user_id     BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX push_tokens_user_idx ON push_tokens (user_id);

-- Silent refreshes waiting to go out (bits: 1 cards changed, 2 someone they know joined,
-- 4 someone joined with their invite). One row per person, so a burst becomes one push.
CREATE TABLE push_queue (
    user_id  BIGINT      PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    why      SMALLINT    NOT NULL,
    due_at   TIMESTAMPTZ NOT NULL
);
CREATE INDEX push_queue_due_idx ON push_queue (due_at);

-- 5. Phone health by brand, with no person or phone in it: calls the phone saw, and how many of
--    them Found With got to check. Shows which brands stop the app after calls.
CREATE TABLE device_health (
    day            DATE    NOT NULL,
    brand          TEXT    NOT NULL,
    android        INTEGER NOT NULL,
    phones         INTEGER NOT NULL DEFAULT 0,
    calls_seen     INTEGER NOT NULL DEFAULT 0,
    calls_checked  INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (day, brand, android)
);
