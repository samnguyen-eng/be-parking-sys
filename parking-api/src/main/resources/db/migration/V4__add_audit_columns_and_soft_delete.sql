-- Add common audit/soft-delete columns to all business tables.
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS created_by VARCHAR(100) NOT NULL DEFAULT 'system',
    ADD COLUMN IF NOT EXISTS updated_by VARCHAR(100) NOT NULL DEFAULT 'system',
    ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE accounts
    ADD COLUMN IF NOT EXISTS created_by VARCHAR(100) NOT NULL DEFAULT 'system',
    ADD COLUMN IF NOT EXISTS updated_by VARCHAR(100) NOT NULL DEFAULT 'system',
    ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE parking_spaces
    ADD COLUMN IF NOT EXISTS created_by VARCHAR(100) NOT NULL DEFAULT 'system',
    ADD COLUMN IF NOT EXISTS updated_by VARCHAR(100) NOT NULL DEFAULT 'system',
    ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE reservations
    ADD COLUMN IF NOT EXISTS created_by VARCHAR(100) NOT NULL DEFAULT 'system',
    ADD COLUMN IF NOT EXISTS updated_by VARCHAR(100) NOT NULL DEFAULT 'system',
    ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE outbox_events
    ADD COLUMN IF NOT EXISTS created_by VARCHAR(100) NOT NULL DEFAULT 'system',
    ADD COLUMN IF NOT EXISTS updated_by VARCHAR(100) NOT NULL DEFAULT 'system',
    ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_users_is_deleted ON users(is_deleted);
CREATE INDEX IF NOT EXISTS idx_accounts_is_deleted ON accounts(is_deleted);
CREATE INDEX IF NOT EXISTS idx_parking_spaces_is_deleted ON parking_spaces(is_deleted);
CREATE INDEX IF NOT EXISTS idx_reservations_is_deleted ON reservations(is_deleted);
CREATE INDEX IF NOT EXISTS idx_outbox_events_is_deleted ON outbox_events(is_deleted);
