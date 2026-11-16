-- v0.5 (WhoHas): who tapped Tell for which number. When that number's owner joins, Home says
-- "Priya and 2 others invited you". One row per teller and number; the newest tap wins. Rows go
-- with the teller's account, and after 90 days. Additive, so the running code keeps working.

CREATE TABLE IF NOT EXISTS tells (
    teller_id   BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    number_hmac bytea       NOT NULL CHECK (octet_length(number_hmac) = 32),
    told_at     timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (teller_id, number_hmac)
);

CREATE INDEX IF NOT EXISTS tells_by_number ON tells (number_hmac, told_at DESC);
