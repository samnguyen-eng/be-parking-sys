# Parking System — Code Flow Guide

## Mục lục
1. [Kiến trúc tổng thể](#1-kiến-trúc-tổng-thể)
2. [Flow đặt chỗ (Reserve)](#2-flow-đặt-chỗ-reserve)
3. [ReservationCacheGuard — Redis gate](#3-reservationcacheguard--redis-gate)
4. [ReservationCommandService — DB transaction](#4-reservationcommandservice--db-transaction)
5. [Worker — Xử lý bất đồng bộ](#5-worker--xử-lý-bất-đồng-bộ)
6. [FCFS — First Come First Served](#6-fcfs--first-come-first-served)
7. [Concurrency protection](#7-concurrency-protection)

---

## 1. Kiến trúc tổng thể

```
User browser
    │
    ▼
parking-api (Cloud Run) ──── Redis (Redisson) ────────────────┐
    │                                                          │
    │ INSERT reservation (PENDING)                             │
    │                                                          │
    ▼                                                          │
Cloud SQL (PostgreSQL)                                         │
    │                                                          │
    │ afterCommit()                                            │
    ▼                                                          │
Pub/Sub topic: reservation.created                             │
    │                                                          │
    │ PUSH                                                     │
    ▼                                                          │
parking-worker (Cloud Run) ── Redis (Redisson) ───────────────┘
    │
    │ CONFIRM/CANCEL reservation
    │ Deduct balance from DB
    ▼
Cloud SQL (PostgreSQL)
```

---

## 2. Flow đặt chỗ (Reserve)

### Entry point: `ParkingController.reserve()`

```java
@PostMapping("/reserve")
public ResponseEntity<ApiResponse<ReserveResponse>> reserve(
        @Valid @RequestBody ReserveRequest request,
        @AuthenticationPrincipal UserDetails userDetails) {

    Long userId = resolveUserId(userDetails); // Lấy userId từ JWT token
    ReserveResponse response = parkingService.reserve(userId, request);
    return ResponseEntity.ok(ApiResponse.success("Reservation request accepted", response));
}
```

**Giải thích:**
- `@AuthenticationPrincipal` — Spring Security tự inject user từ JWT token đã verify
- `@Valid` — validate request body (spaceId > 0, date không null)
- Trả về 200 ngay lập tức — không chờ slot được confirm

---

### `ParkingService.reserve()`

```java
public ReserveResponse reserve(Long userId, ReserveRequest request) {
    LocalDate reservationDate = request.getReservationDate() != null
            ? request.getReservationDate()
            : LocalDate.now();

    // Bước 1: Resolve plate number TRƯỚC khi vào Redis/DB
    // (tránh giữ DB connection trong khi fetch plate)
    String plateNumber = reservationCommandService.resolvePlateNumber(userId, request);

    // Bước 2: Redis gate — kiểm tra và claim trước khi tốn DB
    reservationCacheGuard.tryReserveClaims(userId, spaceId, reservationDate, RESERVE_PRICE);

    // Bước 3: DB transaction — INSERT reservation + publish Pub/Sub
    ReservationCreatedResult result;
    try {
        result = reservationCommandService.createReservation(userId, request, reservationDate, plateNumber);
    } catch (RuntimeException ex) {
        // Nếu DB fail → release Redis claims
        reservationCacheGuard.releaseReserveClaims(userId, spaceId, reservationDate);
        throw ex;
    }

    return ReserveResponse.builder()
            .reservationId(result.reservationId())
            .status("PENDING")
            .message("Reservation request accepted. Waiting for confirmation.")
            .build();
}
```

**Giải thích:**
- `resolvePlateNumber` được gọi NGOÀI transaction → tránh giữ DB connection khi fetch
- `releaseReserveClaims` trong catch → đảm bảo Redis không bị dirty state nếu DB fail

---

## 3. ReservationCacheGuard — Redis gate

Mục đích: **Reject sớm nhất có thể, không tốn DB**.

### `tryReserveClaims()` — 6 bước theo thứ tự tối ưu

```java
public void tryReserveClaims(Long userId, Long spaceId, LocalDate reservationDate, BigDecimal requiredAmount) {

    // BƯỚC 1: isSpaceClaimed — 1 Redis GET
    // Sau khi 80 slots đầy, 99%+ request bị reject ở đây
    if (isSpaceClaimed(spaceId, reservationDate)) {
        throw new BusinessException("This parking slot has already been reserved", CONFLICT);
    }

    // BƯỚC 2: isUserReservationClaimed — 1 Redis GET (read-only)
    // User đã đặt rồi → reject ngay, không cần ZSET/balance check
    if (isUserReservationClaimed(userId, reservationDate)) {
        throw new BusinessException("You already have a reservation for " + reservationDate);
    }

    // BƯỚC 3: ZSET check — warm nếu cần, rồi check slot còn không
    // ZSET = set các spaceId còn available
    assertSpaceAvailableInCacheReadOnly(spaceId, reservationDate);

    // BƯỚC 4: Balance check — 1 Redis GET
    // Chỉ pre-check từ cache, không hit DB
    assertSufficientBalanceFromCache(userId, requiredAmount);

    // BƯỚC 5: Claim user reservation — Redis SETNX (atomic)
    // Side-effect bắt đầu từ đây
    if (!tryClaimUserReservation(userId, reservationDate)) {
        throw new BusinessException("You already have a reservation for " + reservationDate);
    }

    // BƯỚC 6: Claim space — Redis SETNX + ZREM
    try {
        tryClaimSpaceInRedis(spaceId, reservationDate);
    } catch (RuntimeException ex) {
        releaseUserReservationClaim(userId, reservationDate); // rollback bước 5
        throw ex;
    }
}
```

**Tại sao thứ tự này?**
- Bước 1-4: read-only, không có side-effect → reject nhanh nếu fail
- Bước 5-6: có side-effect (SETNX) → chỉ thực hiện khi chắc chắn pass

### Redis keys được dùng

```
isSpaceClaimed:          space:claim:{spaceId}:{date}     TTL: 30 phút
isUserReservationClaimed: user:reservation:{userId}:{date} TTL: 24 giờ
ZSET available slots:    parking:spaces:available          TTL: 30 phút
Balance cache:           account:balance:{userId}          TTL: 24 giờ
```

### `assertSpaceAvailableInCacheReadOnly()`

```java
private void assertSpaceAvailableInCacheReadOnly(Long spaceId, LocalDate reservationDate) {
    RScoredSortedSet<Long> availableSlots = redissonClient.getScoredSortedSet(AVAILABLE_SLOTS_KEY);

    // Nếu ZSET chưa được warm → warm 1 lần từ DB (single-flight lock)
    if (!availableSlots.isExists()) {
        warmAvailableSlotsIfMissing(availableSlots);
    }

    // Kiểm tra trong ZSET (2 Redis calls: EXISTS + ZSCORE)
    if (assertSpaceFromAvailableZset(spaceId)) return;

    // Fallback: check spaces list cache
    if (assertSpaceFromListCache(spaceId)) return;

    // Last resort: DB với single-flight lock
    assertSpaceAvailableFromDbWithLock(spaceId, reservationDate);
}
```

**Single-flight lock:** Khi ZSET empty, chỉ 1 request được phép load DB, các request khác chờ rồi dùng cache. Tránh N requests cùng hit DB.

---

## 4. ReservationCommandService — DB transaction

```java
@Transactional(timeout = 5, rollbackFor = Exception.class)
public ReservationCreatedResult createReservation(Long userId, ReserveRequest request,
        LocalDate reservationDate, String plateNumber) {

    // getReferenceById = lazy proxy, KHÔNG hit DB (chỉ dùng để set FK)
    User user = userRepository.getReferenceById(userId);
    ParkingSpace selectedSpace = parkingSpaceRepository.getReferenceById(request.getSpaceId());

    // INSERT reservation với status PENDING
    // Balance KHÔNG bị trừ ở đây — worker xử lý sau
    Reservation reservation = Reservation.builder()
            .user(user)
            .space(selectedSpace)
            .reservationDate(reservationDate)
            .status(ReservationStatus.PENDING)
            .amount(PRICE_PER_DAY)        // $10
            .expiresAt(LocalDateTime.now().plusHours(24))
            .build();

    Reservation saved = reservationRepository.save(reservation);

    // Tạo payload cho Pub/Sub
    String payload = buildPayload(saved.getId(), userId, spaceId, plateNumber, reservationDate);

    // Publish Pub/Sub SAU KHI transaction commit (tránh publish rồi DB rollback)
    schedulePublishAfterCommit(payload, spaceId, reservationDate);

    return new ReservationCreatedResult(saved.getId(), "PENDING", ...);
}
```

**Tại sao `afterCommit`?**
```
Nếu publish TRONG transaction:
  DB commit thành công
  Pub/Sub publish → worker nhận → tìm reservation trong DB
  Nhưng DB chưa visible vì transaction chưa commit hẳn → worker thấy NOT FOUND

Với afterCommit:
  DB commit xong → data visible
  Pub/Sub publish → worker nhận → tìm reservation thành công ✓
```

### `schedulePublishAfterCommit()`

```java
private void schedulePublishAfterCommit(String payload, Long spaceId, LocalDate reservationDate) {
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
            // Fire-and-forget: không block HTTP response
            reservationPubSubPublisher.publishReservationCreatedAfterCommit(payload, spaceId, reservationDate);
        }
    });
}
```

---

## 5. Worker — Xử lý bất đồng bộ

### Flow xử lý message

```java
public void process(String messageId, String payload) {

    // IDEMPOTENCY CHECK 1: Đã xử lý rồi chưa?
    String processedKey = "processed:msg:" + reservationId;
    if (processedBucket.isExists()) {
        return; // Skip — đã xử lý
    }

    // IDEMPOTENCY CHECK 2: Đang xử lý ở instance khác không?
    String processingKey = "processing:msg:" + reservationId;
    boolean claimed = processingBucket.trySet("processing", 2min);
    if (!claimed) {
        return; // Skip — instance khác đang xử lý
    }

    try {
        // Load reservation từ DB
        Reservation reservation = reservationRepository.findById(reservationId);

        // Chỉ xử lý PENDING reservations
        if (reservation.getStatus() != PENDING) return;

        // FCFS guard: đảm bảo chỉ reservation SỚM NHẤT được xử lý
        checkFCFSOrder(reservation, spaceId, reservationDate);

        // Load space với FOR UPDATE lock
        ParkingSpace space = parkingSpaceRepository.findByIdForUpdate(spaceId);

        if (space.getStatus() == AVAILABLE) {
            // Trừ tiền trong DB
            boolean balanceOk = deductAccountBalance(userId, amount);

            if (balanceOk) {
                // CONFIRM: cập nhật space + reservation
                space.setStatus(RESERVED);
                reservation.setStatus(CONFIRMED);
                updateBalanceCache(userId, newBalance); // Sync Redis cache
            } else {
                // CANCEL: không đủ tiền
                cancelReservationNoRefund(reservation, "Insufficient balance");
            }
        } else {
            // CANCEL: slot đã bị người khác lấy
            cancelReservationNoRefund(reservation, "Slot no longer available");
        }

        markProcessed(processedBucket); // Đánh dấu đã xử lý

    } finally {
        processingBucket.delete(); // Luôn release lock
    }
}
```

### `deductAccountBalance()` — Trừ tiền an toàn

```java
private boolean deductAccountBalance(Long userId, BigDecimal amount) {
    return accountRepository.findByUserIdForUpdate(userId).map(account -> {
        if (account.getBalance().compareTo(amount) < 0) {
            return false; // Không đủ tiền
        }
        BigDecimal newBalance = account.getBalance().subtract(amount);
        account.setBalance(newBalance);
        accountRepository.save(account);

        // Sync Redis cache sau khi DB update
        updateBalanceCache(userId, newBalance);
        return true;
    }).orElse(false);
}
```

**`FOR UPDATE`:** Lock row trong DB → chỉ 1 transaction được đọc/ghi account này cùng lúc → tránh double-spend.

---

## 6. FCFS — First Come First Served

Vấn đề: 1000 user cùng đặt space 1 → ai được?

### Giải pháp 3 lớp

**Lớp 1: Redis SETNX (api side)**
```java
// tryClaimSpaceInRedis()
boolean claimed = redissonClient.getBucket("space:claim:1:2026-06-03")
        .trySet("1", 30, MINUTES); // Atomic: chỉ 1 người claim được
```

**Lớp 2: Pub/Sub ordering key (message delivery)**
```java
// ReservationPubSubPublisher
String orderingKey = String.format("space:%d:%s", spaceId, reservationDate);
// Pub/Sub đảm bảo messages cùng key được giao theo thứ tự
```

**Lớp 3: DB FCFS guard (worker side)**
```java
// ReservationRepository
@Query("""
    SELECT * FROM reservations r
    WHERE r.space_id = :spaceId
      AND r.reservation_date = :reservationDate
      AND r.status = 'PENDING'
      AND r.is_deleted = false
    ORDER BY r.created_at ASC, r.id ASC  -- Sắp xếp theo thời gian tạo
    LIMIT 1
    FOR UPDATE  -- Lock row để tránh race condition
    """)
Optional<Reservation> findFirstPendingForSpaceAndDate(...);
```

**Kết hợp:**
```
User A (đến trước): created_at = 08:00:00.001
User B (đến sau):   created_at = 08:00:00.002

Worker xử lý User B trước?
→ findFirstPendingForSpaceAndDate → trả về User A (earliest)
→ User B.id ≠ User A.id → CANCEL User B
→ Worker xử lý lại User A → CONFIRM
```

---

## 7. Concurrency protection

### Idempotency (chống duplicate processing)

```
processed:msg:{reservationId}  TTL: 24h
├── Pub/Sub giao message 2 lần → check key → skip lần 2
└── Retry sau lỗi → check key → skip nếu đã done

processing:msg:{reservationId}  TTL: 2 phút
├── 2 Cloud Run instances nhận cùng message
├── Instance A: trySet → success → xử lý
└── Instance B: trySet → fail → skip
```

### Virtual Threads + Redisson

**⚠️ QUAN TRỌNG:** Virtual Threads (Java 21) KHÔNG tương thích với Redisson locks.

```
Virtual Thread acquire lock (thread-id: 508)
Virtual Thread suspend (chờ DB/Redis)
Carrier thread thay đổi
Virtual Thread resume (thread-id: 631)  ← KHÁC!
Unlock → "not locked by current thread" → Exception
```

**Fix:** Tắt Virtual Threads trong `application.yml`:
```yaml
spring:
  threads:
    virtual:
      enabled: false  # PHẢI false vì dùng Redisson locks
```

---

## Tóm tắt một request reserve đi qua gì

```
HTTP Request
    │
    ├─ JWT verify (Spring Security filter)
    │
    ├─ resolvePlateNumber (Redis cache → DB fallback)
    │
    ├─ tryReserveClaims (Redis):
    │   ├─ isSpaceClaimed?          → 409 nếu taken
    │   ├─ isUserReservationClaimed? → 400 nếu duplicate
    │   ├─ ZSET available check?    → 409 nếu no slots
    │   ├─ balance cache check?     → 400 nếu no money
    │   ├─ SETNX user claim
    │   └─ SETNX space claim + ZREM
    │
    ├─ @Transactional:
    │   ├─ INSERT reservation (PENDING)
    │   └─ afterCommit: publish Pub/Sub (async)
    │
    └─ Return 200 PENDING
         │
         │ (async, sau vài giây)
         ▼
    Worker nhận Pub/Sub message:
    ├─ Idempotency check
    ├─ FCFS guard
    ├─ findByIdForUpdate (space)
    ├─ deductAccountBalance (DB + cache)
    └─ UPDATE reservation → CONFIRMED/CANCELLED
```
