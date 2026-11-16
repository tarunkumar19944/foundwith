-- Found With: initial schema.
--
-- Privacy model: the server never stores a phone number in plain text. Every number,
-- including a user's own, is stored as HMAC-SHA256(pepper, E.164). Contact names never
-- leave the phone. Registered users' display names are the only names stored.

CREATE TABLE users (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id        UUID        NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    auth_uid         TEXT        NOT NULL UNIQUE,          -- Firebase / Identity Platform UID
    phone_hmac       BYTEA       NOT NULL UNIQUE,          -- the user's own verified number
    display_name     TEXT        NOT NULL CHECK (char_length(display_name) BETWEEN 2 AND 40),
    sync_enabled     BOOLEAN     NOT NULL DEFAULT FALSE,   -- contact sync = taking part in matching
    consent_version  TEXT,                                 -- version of the sync consent text accepted
    consent_at       TIMESTAMPTZ,
    first_sync_at    TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One row per number a user has in their contacts and/or call log.
--   in_contacts : the number is saved in the user's contacts. Only this makes the user a "holder"
--                 that other people see on the number's card.
--   in_call_log : the number is in the user's call log. Only ever used for the user's own cards.
--   hidden      : the user chose "Hide this number". The row is kept (even after the number
--                 leaves the phone) so the choice survives the next sync.
CREATE TABLE user_numbers (
    user_id        BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    number_hmac    BYTEA       NOT NULL,
    in_contacts    BOOLEAN     NOT NULL DEFAULT FALSE,
    in_call_log    BOOLEAN     NOT NULL DEFAULT FALSE,
    hidden         BOOLEAN     NOT NULL DEFAULT FALSE,
    first_seen_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, number_hmac),
    CHECK (in_contacts OR in_call_log OR hidden)
);

-- The hot path: "who has this number saved?" Only rows that count as a holder are indexed,
-- ordered by user id so pages and samples come straight off the index.
CREATE INDEX user_numbers_holders_idx
    ON user_numbers (number_hmac, user_id)
    WHERE in_contacts AND NOT hidden;

-- Daily lookup quotas (search and incoming-call lookups). One row per user and kind;
-- the counter resets when the day changes (days are counted in IST).
CREATE TABLE lookup_quota (
    user_id  BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    kind     TEXT   NOT NULL CHECK (kind IN ('search', 'call')),
    day      DATE   NOT NULL,
    used     INTEGER NOT NULL CHECK (used >= 0),
    PRIMARY KEY (user_id, kind)
);

-- Sign-in accounts still to delete from Identity Platform after an account deletion.
-- A row is removed from the retry list once the deletion succeeds.
CREATE TABLE auth_deletions (
    auth_uid      TEXT        PRIMARY KEY,
    requested_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    done_at       TIMESTAMPTZ,
    attempts      INTEGER     NOT NULL DEFAULT 0,
    last_error    TEXT
);
CREATE INDEX auth_deletions_pending_idx ON auth_deletions (requested_at) WHERE done_at IS NULL;
