# Parking System — Code Flow Guide

## Table of Contents
1. [Architecture Overview](#1-architecture-overview)
2. [Reserve Flow — End to End](#2-reserve-flow--end-to-end)
3. [ReservationCacheGuard — Redis Gate](#3-reservationcacheguard--redis-gate)
4. [ReservationCommandService — DB Transaction](#4-reservationcommandservice--db-transaction)
5. [Worker — Async Processing](#5-worker--async-processing)
6. [First Come First Served (FCFS)](#6-first-come-first-served-fcfs)
7. [Concurrency Protection](#7-concurrency-protection)
8. [Key Technical Decisions](#8-key-technical-decisions)

---

## 1. Architecture Overview

```
User browser
    │ HTTPS
    ▼
Google Frontend (GFE) — TLS termination, DDoS protection
    │
    ▼
Cloud Run Load Balancer
    │
    ▼
parking-api (Cloud Run, min=3, max=6, concurrency=300)
    │
    ├── Redis (Memorystore) — cache gate, claims, balance
    │   Private IP: 10.239.31.4
    │
    ├── Cloud SQL PostgreSQL — source of truth
    │   Private IP: 10.20.1.2
    │
    └── Pub/Sub (PUSH mode, ordering enabled)
              │ HTTP POST
              ▼
        parking-worker (Cloud Run, min=1, max=2)
              │
              ├── Redis — update cache after confirm
              └── Cloud SQL — deduct balance, update reservation
```

**VPC Connector** bridges Cloud Run (serverless) to private VPC resources.
All DB and Redis traffic stays within the private network.

---

## 2. Reserve Flow — End to End

### Entry point: `ParkingController.reserve()`

```java
@PostMapping("/reserve")
public ResponseEntity<ApiResponse<ReserveResponse>> reserve(
        @Valid @RequestBody ReserveRequest request,
        @AuthenticationPrincipal UserDetails userDetails) {
    Long userId = resolveUserId(userDetails); // extract from JWT
    ReserveResponse response = parkingService.reserve(userId, request);
    return ResponseEntity.ok(ApiResponse.success("Reservation request accepted", response));
}
```

Returns **200 immediately** — does NOT wait for slot confirmation.

### `ParkingService.reserve()`

```java
public ReserveResponse reserve(Long userId, ReserveRequest request) {
    // Step 1: Resolve plate number OUTSIDE transaction (avoids holding DB connection)
    String plateNumber = reservationCommandService.resolvePlateNumber(userId, request);

    // Step 2: Redis gate — fast reject before touching DB
    reservationCacheGuard.tryReserveClaims(userId, spaceId, reservationDate, RESERVE_PRICE);

    // Step 3: DB transaction — lightweight INSERT only
    ReservationCreatedResult result;
    try {
        result = reservationCommandService.createReservation(userId, request, reservationDate, plateNumber);
    } catch (RuntimeException ex) {
        reservationCacheGuard.releaseReserveClaims(userId, spaceId, reservationDate); // rollback Redis
        throw ex;
    }
    return ReserveResponse.builder().status("PENDING")...build();
}
```

**Key design**: `releaseReserveClaims` in catch block ensures Redis doesn't get into dirty state if DB fails.

---

## 3. ReservationCacheGuard — Redis Gate

Purpose: **reject requests as early as possible without touching DB**.

### Validation order (optimized for fast-fail)

```java
public void tryReserveClaims(Long userId, Long spaceId, LocalDate reservationDate, BigDecimal requiredAmount) {

    // STEP 1: isSpaceClaimed — 1 Redis GET
    // After 80 slots fill, 99%+ requests rejected here
    if (isSpaceClaimed(spaceId, reservationDate)) {
        throw new BusinessException("This parking slot has already been reserved", CONFLICT);
    }

    // STEP 2: isUserReservationClaimed — 1 Redis GET (read-only)
    // Prevents double-booking by same user
    if (isUserReservationClaimed(userId, reservationDate)) {
        throw new BusinessException("You already have a reservation for " + reservationDate);
    }

    // STEP 3: ZSET check — warm if needed, then check availability
    assertSpaceAvailableInCacheReadOnly(spaceId, reservationDate);

    // STEP 4: Balance check — 1 Redis GET
    assertSufficientBalanceFromCache(userId, requiredAmount);

    // STEP 5: Claim user slot — Redis SETNX (side-effect begins)
    if (!tryClaimUserReservation(userId, reservationDate)) {
        throw new BusinessException("You already have a reservation for " + reservationDate);
    }

    // STEP 6: Claim space — Redis SETNX + ZREM
    try {
        tryClaimSpaceInRedis(spaceId, reservationDate);
    } catch (RuntimeException ex) {
        releaseUserReservationClaim(userId, reservationDate); // rollback step 5
        throw ex;
    }
}
```

**Why this order?** Steps 1-4 are read-only — fail fast without side effects.
Steps 5-6 have side effects (SETNX) — only executed when all reads pass.

### Redis keys used

| Key Pattern | Type | TTL | Purpose |
|-------------|------|-----|---------|
| `space:claim:{spaceId}:{date}` | String | 30 min | Space claimed |
| `user:reservation:{userId}:{date}` | String | 24h | User already reserved |
| `parking:spaces:available` | ZSET | 30 min | Available space IDs |
| `account:balance:{userId}` | String | 24h | Balance pre-check |
| `user:plate:{userId}` | String | 24h | Plate number cache |

### SETNX atomic claim

```
1000 concurrent requests fire simultaneously:
  Request 47:  SETNX space:claim:1 → key not exist → SET → return true  ✅
  Request 1:   SETNX space:claim:1 → key exists    → skip → return false ❌
  Request 312: SETNX space:claim:1 → key exists    → skip → return false ❌
  ...999 others rejected instantly
```

Redis is single-threaded → SETNX is atomic → no race condition at Redis level.

### Single-flight lock for cache warm-up

When ZSET is empty (cold start), only 1 request loads from DB:

```java
public <T> T computeWithSingleFlightLock(String lockKey, Supplier<T> loader) {
    RLock lock = redissonClient.getLock(lockKey);
    if (!acquireLoadLock(lock)) {  // tryLock(waitTime=0) — fail fast
        return null;  // caller retries from cache
    }
    try {
        return loader.get();  // only 1 request hits DB
    } finally {
        releaseLoadLock(lock);
    }
}
```

Prevents thundering herd — 1000 concurrent requests don't all query DB on cache miss.

---

## 4. ReservationCommandService — DB Transaction

```java
@Transactional(timeout = 5, rollbackFor = Exception.class)
public ReservationCreatedResult createReservation(Long userId, ReserveRequest request,
        LocalDate reservationDate, String plateNumber) {

    // getReferenceById = JPA proxy — NO DB query (just sets FK)
    User user = userRepository.getReferenceById(userId);
    ParkingSpace selectedSpace = parkingSpaceRepository.getReferenceById(request.getSpaceId());

    // Lightweight INSERT — no SELECT FOR UPDATE on account
    // Balance deduction moved to worker on CONFIRM
    Reservation reservation = Reservation.builder()
            .user(user).space(selectedSpace)
            .reservationDate(reservationDate)
            .status(ReservationStatus.PENDING)
            .amount(PRICE_PER_DAY)
            .expiresAt(LocalDateTime.now().plusHours(24))
            .build();
    Reservation saved = reservationRepository.save(reservation);

    // Publish to Pub/Sub AFTER commit (prevent publish before data is visible)
    schedulePublishAfterCommit(payload, spaceId, reservationDate);
    return new ReservationCreatedResult(saved.getId(), "PENDING", ...);
}
```

**Why `afterCommit`?**
```
Without afterCommit:
  DB commit → Pub/Sub publish → worker queries DB
  But data might not be visible yet (transaction isolation) → worker sees NOT FOUND

With afterCommit:
  DB commit → data visible → Pub/Sub publish → worker queries → SUCCESS ✓
```

---

## 5. Worker — Async Processing

### PUSH mode flow

```
Pub/Sub → HTTP POST /internal/pubsub/push → PubSubPushController
    │
    ▼
SpaceExecutorRegistry.getOrCreate(spaceId)  // single Platform Thread per space
    │
    ▼
future.get(55s)  // HTTP thread waits for result → ACK/NACK
    │
    ▼
ReservationWorkerService.process(messageId, payload)
```

**Platform Thread** (not Virtual Thread) — required because Redisson locks track ownership
by thread-id. Virtual Threads change carrier thread on suspension → `IllegalMonitorStateException`.

### Processing logic

```java
public void process(String messageId, String payload) {
    // 1. Idempotency check — skip if already processed
    if (processedBucket.isExists()) return;

    // 2. Distributed lock — prevent duplicate processing across instances
    if (!processingBucket.trySet("processing", 2min)) return;

    try {
        // 3. Load reservation — skip if not PENDING
        Reservation reservation = reservationRepository.findById(reservationId);
        if (reservation.getStatus() != PENDING) return;

        // 4. FCFS guard — only earliest PENDING wins
        checkFCFSOrder(reservation, spaceId, reservationDate);

        // 5. Load space with FOR UPDATE lock
        ParkingSpace space = parkingSpaceRepository.findByIdForUpdate(spaceId);

        if (space.getStatus() == AVAILABLE) {
            // 6. Deduct balance in DB (API no longer does this)
            boolean balanceOk = deductAccountBalance(userId, amount);
            if (balanceOk) {
                // CONFIRM: update space + reservation + Redis cache
                space.setStatus(RESERVED);
                reservation.setStatus(CONFIRMED);
                updateBalanceCache(userId, newBalance);
                removeFromAvailableCache(spaceId);
                updateSpacesCacheAsReserved(spaceId, plateNumber);
            } else {
                // CANCEL: insufficient balance — no refund (API didn't deduct)
                cancelReservationNoRefund(reservation, "Insufficient balance");
            }
        } else {
            // CANCEL: slot taken — no refund (API didn't deduct)
            cancelReservationNoRefund(reservation, "Slot no longer available");
        }

        markProcessed(processedBucket);
    } finally {
        processingBucket.delete(); // always release
    }
}
```

---

## 6. First Come First Served (FCFS)

### Three layers of protection

**Layer 1 — Redis atomic claim (API side)**
```
SETNX space:claim:1:2026-06-14 → only 1 of 1000 concurrent requests wins
```

**Layer 2 — Pub/Sub ordering key**
```
orderingKey = "space:{spaceId}:{date}"
All messages for space 1 delivered sequentially:
  msg1 (User A, 20:00:00.001) → ACK → msg2 (User B, 20:00:00.002) → ...
```

**Layer 3 — DB FCFS guard (worker side)**
```sql
SELECT * FROM reservations r
WHERE r.space_id = :spaceId
  AND r.reservation_date = :reservationDate
  AND r.status = 'PENDING'
ORDER BY r.created_at ASC, r.id ASC  -- earliest wins
LIMIT 1
FOR UPDATE                            -- row lock prevents race condition
```

If the message being processed is NOT the earliest PENDING → throw `OutOfOrderReservationException` → NACK → retry later.

---

## 7. Concurrency Protection

### Idempotency

```
processed:msg:{reservationId}   TTL: 24h  → skip if already done
processing:msg:{reservationId}  TTL: 2min → skip if another instance is processing
```

If Pub/Sub delivers the same message twice → idempotency key blocks second processing.
If two Cloud Run instances receive the same message → processing lock blocks one.

### Why NOT Virtual Threads with Redisson

```
Virtual Thread acquires lock (thread-id: 508)
Virtual Thread suspends (waiting for DB/Redis)
JVM assigns different carrier thread
Virtual Thread resumes (thread-id: 631) ← DIFFERENT
Unlock → "not locked by current thread" → IllegalMonitorStateException → 500
```

**Fix**: Use Platform Thread factory in `SpaceExecutorRegistry`:
```java
Thread.ofPlatform().name("space-" + id + "-worker-", 0).factory()
```

### Balance deduction safety

Worker runs with `@Transactional`. If anything fails after `deductAccountBalance()`,
the entire transaction rolls back — including the balance deduction.
No manual refund needed for technical failures.

---

## 8. Key Technical Decisions

| Decision | Reason |
|----------|--------|
| PUSH over PULL for Pub/Sub | Cloud Run is request-driven — PUSH enables autoscale, PULL requires persistent connection |
| Balance deduction in worker, not API | Removes `SELECT FOR UPDATE` from hot path, eliminates DB connection pressure at 1500 TPS |
| Platform Thread over Virtual Thread | Redisson lock ownership tied to thread-id — Virtual Threads break this |
| Redis gate before DB | Rejects 99%+ requests without DB connection → pool stays healthy |
| Single-flight lock for cache warm | Prevents thundering herd when cache expires |
| `afterCommit` for Pub/Sub publish | Ensures DB data is visible before worker receives message |
| `created_at` for FCFS ordering | Timestamp-based ordering is reliable even if Pub/Sub delivers out of order |
