CREATE TABLE IF NOT EXISTS worker_message_retries (
    id BIGSERIAL PRIMARY KEY,
    retry_key VARCHAR(255) NOT NULL,
    message_id VARCHAR(255) NOT NULL,
    payload TEXT,
    retry_count INT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL,
    last_error TEXT,
    next_retry_at TIMESTAMP NOT NULL,
    first_failed_at TIMESTAMP NOT NULL,
    last_failed_at TIMESTAMP NOT NULL,
    moved_to_dlq_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100) NOT NULL DEFAULT 'system',
    updated_by VARCHAR(100) NOT NULL DEFAULT 'system',
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT uk_worker_message_retries_retry_key UNIQUE (retry_key)
);

CREATE INDEX IF NOT EXISTS idx_worker_message_retries_message_id ON worker_message_retries(message_id);
CREATE INDEX IF NOT EXISTS idx_worker_message_retries_status ON worker_message_retries(status);
CREATE INDEX IF NOT EXISTS idx_worker_message_retries_last_failed_at ON worker_message_retries(last_failed_at);
CREATE INDEX IF NOT EXISTS idx_worker_message_retries_is_deleted ON worker_message_retries(is_deleted);
