# Parking Space Reservation System — Mô tả toàn bộ project

## 1. Tổng quan

**be-parking-sys** là hệ thống đặt chỗ đậu xe trực tuyến chạy trên Google Cloud Platform (GCP). Hệ thống cho phép người dùng đăng ký tài khoản, nạp tiền, và tham gia tranh giành chỗ đậu xe mỗi ngày lúc 20:00. Bãi đậu xe cung cấp **80 chỗ**, mỗi chỗ có giá **10 USD/ngày**. Khi cửa đặt chỗ mở, có thể có **~1.000 người** gửi yêu cầu đồng thời — hệ thống xử lý theo nguyên tắc **first-come, first-served** và không để xảy ra đặt trùng.

---

## 2. Kiến trúc tổng thể

```
[Client / Frontend]
        │
        ▼
[Cloud Run] parking-api  (port 8080 — autoscale)
        │
        ├─── Cloud SQL (PostgreSQL, Private IP, SSL only)
        ├─── Cloud Memorystore (Redis + TLS) — distributed lock, idempotency
        ├─── Cloud KMS — encrypt password (AES-256) + sign JWT (RSA-2048)
        └─── Secret Manager — DB/Redis credentials

        │ (writes Outbox event to DB)
        ▼
[Cloud Run] parking-outbox  (port 8081 — always-on, min=1 max=1)
        │   polls DB mỗi 500ms, publish lên Pub/Sub
        ▼
[Cloud Pub/Sub]  topic: reservation.created
        │   push subscription → HTTP POST
        ▼
[Cloud Run] parking-worker  (port 8082 — autoscale min=1 max=5)
        │   nhận message, kiểm tra idempotency (Redis), ghi chỗ xuống DB
        └─── Cloud SQL (cùng DB)
```

---

## 3. Cấu trúc project (Multi-module Maven)

```
be-parking-sys/                  ← Parent POM
├── pom.xml
├── parking-api/                 ← Module 1: REST API
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/parking/api/
│       │   ├── controller/      ← REST endpoints
│       │   ├── service/         ← Business logic
│       │   ├── repository/      ← Spring Data JPA
│       │   ├── entity/          ← JPA entities
│       │   ├── dto/             ← Request/Response DTOs
│       │   ├── config/          ← Spring config (Security, Redis, KMS...)
│       │   └── security/        ← JWT filter, auth handler
│       └── resources/
│           ├── application.yml
│           └── db/migration/    ← Flyway SQL scripts
│
├── parking-outbox/              ← Module 2: Outbox Poller
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/parking/outbox/
│       │   ├── service/         ← Scheduler: poll DB → publish Pub/Sub
│       │   ├── repository/      ← Query outbox table
│       │   └── config/          ← Pub/Sub config
│       └── resources/
│           └── application.yml
│
└── parking-worker/              ← Module 3: Reservation Worker
    ├── Dockerfile
    ├── pom.xml
    └── src/main/
        ├── java/com/parking/worker/
        │   ├── controller/      ← HTTP endpoint nhận Pub/Sub push
        │   ├── service/         ← Xử lý đặt chỗ idempotent
        │   └── config/          ← Redis, JPA config
        └── resources/
            └── application.yml
```

---

## 4. Chi tiết từng module

### 4.1 parking-api — REST API Service

**Trách nhiệm:** Tiếp nhận toàn bộ request từ client, xác thực người dùng, quản lý số dư, tiếp nhận yêu cầu đặt chỗ và ghi vào Outbox.

**Các chức năng chính:**

| Nhóm | Endpoint (dự kiến) | Mô tả |
|------|-------------------|-------|
| Auth | `POST /api/auth/register` | Đăng ký tài khoản, mã hoá mật khẩu qua KMS (AES-256) |
| Auth | `POST /api/auth/login` | Đăng nhập, cấp JWT token (ký bằng KMS RSA-2048) |
| Deposit | `POST /api/deposit` | Nạp tiền vào tài khoản |
| Parking | `GET /api/parking/spaces` | Xem trạng thái 80 chỗ (available / reserved + 3 số cuối biển số) |
| Parking | `POST /api/parking/reserve` | Đặt chỗ (chỉ mở từ 20:00) |

**Cơ chế xử lý đặt chỗ đồng thời:**
1. Kiểm tra giờ mở cửa (20:00).
2. Kiểm tra xe đã có chỗ chưa (tránh đặt trùng).
3. Kiểm tra và trừ số dư (atomic).
4. Acquire **Redisson distributed lock** (TTL 10s) để chọn chỗ trống.
5. Ghi bản ghi vào bảng **outbox** (trạng thái `PENDING`).
6. Trả về response ngay — worker xử lý bất đồng bộ phía sau.

**Công nghệ:**
- Spring Boot 3.3.5 + Spring Security + Spring Data JPA
- Java 21 Virtual Threads (Tomcat thread pool phụ trợ bởi `--enable-preview`)
- ZGC (Generational) garbage collector
- Redisson để distributed lock
- Google Cloud KMS cho mã hoá mật khẩu + ký JWT
- Google Cloud Secret Manager để quản lý secrets
- Flyway cho DB migration
- MapStruct cho DTO mapping
- HikariCP pool: min=5, max=20

---

### 4.2 parking-outbox — Outbox Poller

**Trách nhiệm:** Đảm bảo **at-least-once delivery** — liên tục poll bảng `outbox` trong DB và publish event lên Google Cloud Pub/Sub.

**Luồng xử lý:**
```
[Scheduler mỗi 500ms]
    → Query outbox WHERE status = 'PENDING' LIMIT 50
    → Publish từng message lên Pub/Sub topic "reservation.created"
    → Cập nhật status = 'PUBLISHED' (hoặc tăng retry_count nếu lỗi)
    → Nếu retry_count > 5 → status = 'DEAD'
```

**Thiết kế:**
- **Always-on**: min=1 max=1 instance (không cần scale).
- Nhẹ tài nguyên: Xms128m, Xmx512m.
- Batch size 50 events/lần poll để kiểm soát throughput.
- Max 5 lần retry trước khi đánh dấu dead letter.

**Công nghệ:**
- Spring Scheduling (`@Scheduled`)
- Spring Cloud GCP Pub/Sub starter
- Spring Data JPA + HikariCP (pool nhỏ: min=2, max=5)

---

### 4.3 parking-worker — Reservation Worker

**Trách nhiệm:** Nhận message từ Pub/Sub (qua **push subscription** HTTP POST), xử lý đặt chỗ thực sự vào DB theo cơ chế **idempotent**.

**Luồng xử lý:**
```
[Pub/Sub push → POST /internal/pubsub/push]
    → Parse message, lấy messageId
    → Kiểm tra Redis: key "processed:msg:{messageId}" đã tồn tại?
        ├─ CÓ → bỏ qua (đã xử lý), trả 200 OK
        └─ KHÔNG:
            → Tìm chỗ trống còn available trong DB
            → Ghi reservation vào DB (trạng thái CONFIRMED)
            → Set Redis key TTL 24h (idempotency guard)
            → Trả 200 OK → Pub/Sub xoá message khỏi queue
```

**Thiết kế:**
- **Autoscale**: min=1, max=5 instances theo áp lực Pub/Sub.
- Idempotency bằng Redis ngăn xử lý trùng khi Pub/Sub retry.
- Nhẹ tài nguyên: Xms128m, Xmx768m.

**Công nghệ:**
- Spring Web (nhận HTTP push từ Pub/Sub)
- Spring Data JPA
- Redisson (Redis idempotency store, TTL 24h)
- Spring Cloud GCP Pub/Sub (deserialize message)

---

## 5. Tech Stack tổng hợp

| Lớp | Công nghệ | Mục đích |
|-----|-----------|----------|
| Language | Java 21 | Virtual Threads, modern syntax |
| Framework | Spring Boot 3.3.5 | Core framework |
| Security | Spring Security + JJWT 0.12.6 | Auth filter, JWT build/parse |
| Database | Cloud SQL PostgreSQL (Private IP, SSL) | Lưu trữ chính |
| ORM | Spring Data JPA + Hibernate | DB access layer |
| Migration | Flyway 10.15 | Schema versioning |
| Cache / Lock | Redisson 3.37 + Cloud Memorystore Redis (TLS) | Distributed lock, idempotency |
| Messaging | Google Cloud Pub/Sub | Event bus outbox→worker |
| Encryption | Google Cloud KMS | AES-256 password, RSA-2048 JWT signing |
| Secrets | Google Cloud Secret Manager | DB/Redis credentials |
| Mapping | MapStruct 1.6.2 | DTO ↔ Entity conversion |
| Container | Docker (multi-stage, non-root user) | Packaging & deploy |
| Runtime | Cloud Run | Serverless containers, autoscale |
| GC | ZGC (Generational) | Low-latency GC cho high-concurrency |
| Build | Maven 3.9 (multi-module) | Build system |

---

## 6. Các design pattern quan trọng

### 6.1 Transactional Outbox Pattern
Thay vì gọi Pub/Sub trực tiếp trong transaction (dễ mất message khi lỗi), parking-api ghi event vào bảng `outbox` cùng transaction với reservation. parking-outbox sau đó poll và publish — đảm bảo **không mất event** dù Pub/Sub tạm thời lỗi.

### 6.2 Distributed Lock (Redisson)
Khi 1.000 request đến cùng lúc lúc 20:00, Redis distributed lock đảm bảo chỉ một goroutine/thread tại một thời điểm được chọn chỗ trống — tránh race condition và đặt trùng.

### 6.3 Idempotency Guard (Redis)
Worker lưu messageId đã xử lý vào Redis (TTL 24h). Nếu Pub/Sub retry message (at-least-once delivery), worker phát hiện và bỏ qua — không ghi reservation trùng.

### 6.4 Virtual Threads (Java 21)
parking-api sử dụng Java 21 virtual threads, cho phép xử lý hàng nghìn concurrent request mà không tốn nhiều OS thread, giúp đáp ứng 1.500 TPS stress test.

---

## 7. Bảo mật

| Điểm bảo mật | Triển khai |
|--------------|-----------|
| Mật khẩu | Mã hoá AES-256 qua Google Cloud KMS trước khi lưu vào DB |
| JWT | Ký bằng RSA-2048 key trên Google Cloud KMS (private key không bao giờ rời KMS) |
| Database | Cloud SQL Private IP (không expose internet) + SSL required |
| Redis | Cloud Memorystore + TLS + AUTH password |
| Secrets | Tất cả credentials lưu trong Secret Manager, không hardcode |
| Container | Non-root user trong Docker image |
| API | Spring Security filter chain + JWT Bearer token |

---

## 8. Khả năng chịu tải

| Yêu cầu | Giải pháp |
|---------|-----------|
| 1.000 user đồng thời lúc 20:00 | Redisson distributed lock + Virtual Threads |
| 1.500 TPS trong 3 phút | Cloud Run autoscale + ZGC + HikariCP pool |
| Không đặt trùng | Distributed lock khi chọn chỗ + idempotency Redis ở worker |
| Không mất reservation | Outbox pattern (DB-level durability trước khi lên Pub/Sub) |
| Worker failover | Pub/Sub retry + idempotency guard chống xử lý 2 lần |

---

## 9. Biến môi trường chính

| Biến | Module | Mô tả |
|------|--------|-------|
| `GCP_PROJECT_ID` | tất cả | GCP project |
| `DB_HOST` | tất cả | Cloud SQL Private IP (default: 10.20.0.10) |
| `DB_PASSWORD` | tất cả | Lấy từ Secret Manager |
| `REDIS_HOST` | api, worker | Memorystore IP (default: 10.20.0.20) |
| `REDIS_PASSWORD` | api, worker | Redis AUTH password |
| `KMS_LOCATION` | api | asia-southeast1 |
| `KMS_KEY_RING` | api | parking-keyring |
| `KMS_JWT_KEY` | api | parking-jwt-key (RSA sign) |
| `KMS_PASSWORD_KEY` | api | parking-password-key (AES encrypt) |
| `PUBSUB_TOPIC` | outbox | reservation.created |

---

## 10. Luồng người dùng end-to-end

```
1. Đăng ký: POST /api/auth/register
   → Mật khẩu mã hoá AES-256 (KMS) → lưu DB

2. Đăng nhập: POST /api/auth/login
   → Kiểm tra mật khẩu → cấp JWT (ký RSA-2048 qua KMS)

3. Nạp tiền: POST /api/deposit
   → Bearer JWT → cộng số dư vào account

4. [Lúc 20:00] Đặt chỗ: POST /api/parking/reserve
   → Kiểm tra giờ → kiểm tra xe chưa có chỗ
   → Trừ 10 USD số dư (atomic)
   → Acquire distributed lock (Redis, TTL 10s)
   → Ghi outbox event (DB transaction)
   → Trả response: "reservation đang xử lý"

5. parking-outbox (500ms sau):
   → Poll outbox → publish lên Pub/Sub

6. parking-worker (vài ms sau):
   → Nhận push từ Pub/Sub
   → Idempotency check (Redis)
   → Ghi reservation CONFIRMED vào DB

7. Xem bãi xe: GET /api/parking/spaces
   → Hiển thị 80 chỗ: available hoặc XXX (3 số cuối biển số)
```
