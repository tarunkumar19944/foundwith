-- Found With 0004: invite links and the reward for using them.
--
-- Every person gets a short invite code on first use. When someone new joins through it, both get
-- extra searches a day. Redemptions are kept by phone number (hashed), not by account, so deleting
-- and re-creating an account can't earn the reward twice.
--
-- Safe on a live database: adding a nullable column is instant, and the users table is small
-- enough at launch that building its unique index takes a moment.

ALTER TABLE users ADD COLUMN invite_code TEXT;
CREATE UNIQUE INDEX users_invite_code_key ON users (invite_code) WHERE invite_code IS NOT NULL;

CREATE TABLE invite_redemptions (
    phone_hmac   BYTEA       PRIMARY KEY,
    inviter_id   BIGINT      REFERENCES users (id) ON DELETE SET NULL,
    redeemed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX invite_redemptions_inviter_idx ON invite_redemptions (inviter_id);
