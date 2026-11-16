-- v0.4.1: moderators can stop a number from getting a popular name (for example one that
-- several people saved as an insult or as "Fraud"), from a complaint to the help address.
-- Additive, so the code that is already running keeps working.

CREATE TABLE IF NOT EXISTS popular_name_holds (
    number_hmac bytea PRIMARY KEY CHECK (octet_length(number_hmac) = 32),
    reason      text NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);
