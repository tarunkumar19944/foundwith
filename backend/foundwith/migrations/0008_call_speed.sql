-- v0.6 (23 Nov 2026): how fast phones tell people about a call, by brand. Of the calls checked,
-- how many were checked within 10 seconds and within a minute of the call ending. Counts only,
-- like the rest of device_health. Additive, so the running code keeps working.

ALTER TABLE device_health ADD COLUMN IF NOT EXISTS calls_within_10s INTEGER NOT NULL DEFAULT 0;
ALTER TABLE device_health ADD COLUMN IF NOT EXISTS calls_within_60s INTEGER NOT NULL DEFAULT 0;
