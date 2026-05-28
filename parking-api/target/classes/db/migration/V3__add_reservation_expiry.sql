-- Add reservation expiry timestamp to auto-release occupied slots after hold period.
ALTER TABLE reservations
    ADD COLUMN IF NOT EXISTS expires_at TIMESTAMP;

UPDATE reservations
SET expires_at = COALESCE(expires_at, created_at + INTERVAL '24 hours')
WHERE expires_at IS NULL;

ALTER TABLE reservations
    ALTER COLUMN expires_at SET NOT NULL;

ALTER TABLE reservations
    DROP CONSTRAINT IF EXISTS chk_reservation_status;

ALTER TABLE reservations
    ADD CONSTRAINT chk_reservation_status
        CHECK (status IN ('PENDING', 'CONFIRMED', 'CANCELLED', 'EXPIRED'));

CREATE INDEX IF NOT EXISTS idx_reservations_expires_at ON reservations(expires_at);
