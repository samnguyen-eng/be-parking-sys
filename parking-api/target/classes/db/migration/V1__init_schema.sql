-- V1: Khởi tạo schema ban đầu

-- Bảng users: thông tin tài khoản đăng nhập
CREATE TABLE IF NOT EXISTS users (
    id          BIGSERIAL PRIMARY KEY,
    username    VARCHAR(50)  NOT NULL UNIQUE,
    email       VARCHAR(100) NOT NULL UNIQUE,
    password    VARCHAR(255) NOT NULL,
    plate_number VARCHAR(20) NOT NULL UNIQUE,  -- Biển số xe (duy nhất mỗi user)
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Bảng accounts: số dư tài khoản (tách riêng để dễ lock khi trừ tiền)
CREATE TABLE IF NOT EXISTS accounts (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL UNIQUE REFERENCES users(id),
    balance     NUMERIC(15, 2) NOT NULL DEFAULT 0.00,
    version     BIGINT NOT NULL DEFAULT 0,  -- Optimistic locking
    updated_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Bảng parking_spaces: 80 chỗ đậu xe
CREATE TABLE IF NOT EXISTS parking_spaces (
    id          BIGSERIAL PRIMARY KEY,
    space_number VARCHAR(10) NOT NULL UNIQUE,  -- Ví dụ: A01, A02, ..., H10
    status      VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',  -- AVAILABLE | RESERVED
    updated_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_space_status CHECK (status IN ('AVAILABLE', 'RESERVED'))
);

-- Bảng reservations: lịch sử đặt chỗ
CREATE TABLE IF NOT EXISTS reservations (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES users(id),
    space_id        BIGINT REFERENCES parking_spaces(id),
    reservation_date DATE NOT NULL DEFAULT CURRENT_DATE,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- PENDING | CONFIRMED | CANCELLED
    amount          NUMERIC(15, 2) NOT NULL DEFAULT 10.00,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_reservation_status CHECK (status IN ('PENDING', 'CONFIRMED', 'CANCELLED')),
    CONSTRAINT uq_user_date UNIQUE (user_id, reservation_date)  -- 1 user chỉ được đặt 1 chỗ/ngày
);

-- Bảng outbox: Transactional Outbox Pattern
CREATE TABLE IF NOT EXISTS outbox_events (
    id              BIGSERIAL PRIMARY KEY,
    aggregate_type  VARCHAR(50) NOT NULL DEFAULT 'RESERVATION',
    aggregate_id    BIGINT NOT NULL,  -- reservation_id
    event_type      VARCHAR(100) NOT NULL DEFAULT 'reservation.created',
    payload         JSONB NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- PENDING | PUBLISHED | DEAD
    retry_count     INT NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED', 'DEAD'))
);

-- Indexes
CREATE INDEX IF NOT EXISTS idx_reservations_user_id ON reservations(user_id);
CREATE INDEX IF NOT EXISTS idx_reservations_date ON reservations(reservation_date);
CREATE INDEX IF NOT EXISTS idx_reservations_status ON reservations(status);
CREATE INDEX IF NOT EXISTS idx_outbox_status ON outbox_events(status) WHERE status = 'PENDING';
CREATE INDEX IF NOT EXISTS idx_parking_spaces_status ON parking_spaces(status);
