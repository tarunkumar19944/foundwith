-- Found With 0002: abuse controls, the public opt-out, moderation, and data lifecycle.
-- Safe to apply on a live v0.1 database: every statement is additive, and the new columns
-- get defaults that leave existing accounts exactly as they were.

ALTER TABLE users
    ADD COLUMN last_seen_at     TIMESTAMPTZ      NOT NULL DEFAULT now(),  -- any signed-in request, updated at most daily
    ADD COLUMN name_changed_at  TIMESTAMPTZ,                              -- NULL until the first change of display name
    ADD COLUMN suspended_at     TIMESTAMPTZ,
    ADD COLUMN suspended_reason TEXT,
    -- Allowance for adding new numbers (a token bucket: see Settings.new_numbers_burst).
    ADD COLUMN admit_tokens     DOUBLE PRECISION NOT NULL DEFAULT 3000,
    ADD COLUMN admit_tokens_at  TIMESTAMPTZ      NOT NULL DEFAULT now();

CREATE INDEX users_last_seen_idx ON users (last_seen_at);

-- Numbers whose owners asked to be left out of Found With. The owner proves the number is theirs
-- with an OTP; no account is needed. A suppressed number is never stored in anyone's address book,
-- never appears on a card and never returns holders in a lookup. The entry is a keyed hash and it
-- has to be kept for as long as the opt-out stands, otherwise the next contact sync would bring
-- the number straight back.
CREATE TABLE suppressed_numbers (
    number_hmac  BYTEA       PRIMARY KEY,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Every search and incoming-call lookup: who looked up which number, and how many holders came back.
-- Used to decide whether a person may page through a number's full holder list (they must have
-- searched it in the last 24 hours) and to investigate abuse. There is deliberately no foreign key
-- to users: rows outlive a deleted account until the retention period ends.
CREATE TABLE lookup_log (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id           BIGINT      NOT NULL,
    actor_phone_hmac  BYTEA       NOT NULL,
    number_hmac       BYTEA       NOT NULL,
    kind              TEXT        NOT NULL CHECK (kind IN ('search', 'call')),
    holders_found     INTEGER     NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX lookup_log_user_number_idx ON lookup_log (user_id, number_hmac, created_at DESC);
CREATE INDEX lookup_log_created_idx ON lookup_log USING brin (created_at);

-- What is remembered about a phone number after its account is deleted, so that limits and
-- suspensions can't be reset by deleting the account and signing up again. Holds no address book,
-- no name and no plain number. Purged by the maintenance job.
CREATE TABLE account_tombstones (
    phone_hmac        BYTEA            PRIMARY KEY,
    deleted_at        TIMESTAMPTZ      NOT NULL DEFAULT now(),
    admit_tokens      DOUBLE PRECISION NOT NULL DEFAULT 0,
    admit_tokens_at   TIMESTAMPTZ      NOT NULL DEFAULT now(),
    quota             JSONB            NOT NULL DEFAULT '[]',   -- [{"kind": "search", "day": "2026-11-12", "used": 37}]
    name_changed_at   TIMESTAMPTZ,
    suspended_at      TIMESTAMPTZ,
    suspended_reason  TEXT
);
CREATE INDEX account_tombstones_deleted_idx ON account_tombstones (deleted_at);

-- Reports about a profile name (fake, impersonating, offensive) or about harassment.
-- The reported person's number hash is kept so a report still means something after they
-- delete their account.
CREATE TABLE reports (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    reporter_id          BIGINT REFERENCES users (id) ON DELETE SET NULL,
    reported_id          BIGINT REFERENCES users (id) ON DELETE SET NULL,
    reported_phone_hmac  BYTEA       NOT NULL,
    reported_name        TEXT        NOT NULL,
    reason               TEXT        NOT NULL CHECK (reason IN ('fake_name', 'impersonation', 'offensive', 'harassment', 'other')),
    note                 TEXT        CHECK (char_length(note) <= 500),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at          TIMESTAMPTZ,
    resolution           TEXT
);
CREATE INDEX reports_open_idx ON reports (created_at) WHERE resolved_at IS NULL;
CREATE INDEX reports_reporter_idx ON reports (reporter_id, created_at);

-- "Don't show me this person, and don't show me to them." Stored against the other person's number
-- hash rather than their account, so it survives them deleting and re-creating the account.
CREATE TABLE blocks (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id             BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    blocked_phone_hmac  BYTEA       NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, blocked_phone_hmac)
);
CREATE INDEX blocks_blocked_idx ON blocks (blocked_phone_hmac);
