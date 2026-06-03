package com.parking.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parking.api.dto.response.ParkingSpaceResponse;
import com.parking.api.entity.ParkingSpace;
import com.parking.api.entity.ParkingSpaceStatus;
import com.parking.api.exception.BusinessException;
import com.parking.api.repository.AccountRepository;
import com.parking.api.repository.ParkingSpaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Redis-first reserve guards; on cache miss loads from DB under a single-flight lock.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationCacheGuard {

    static final String AVAILABLE_SLOTS_KEY = "parking:spaces:available";
    static final String SPACES_LIST_CACHE_KEY = "parking:spaces:all";

    static final String LOCK_SPACES_LIST = "lock:cache:parking:spaces:all";
    static final String LOCK_AVAILABLE_SLOTS = "lock:cache:parking:spaces:available";
    private static final String LOCK_SPACE_STATUS_PATTERN = "lock:cache:parking:space:%d";
    private static final String LOCK_ACCOUNT_BALANCE_PATTERN = "lock:cache:account:balance:%d";

    @Value("${app.reserve.cache-lock-wait-seconds:0}")
    private long lockWaitSeconds;

    @Value("${app.reserve.cache-lock-lease-seconds:5}")
    private long lockLeaseSeconds;

    private static final String USER_RESERVATION_KEY_PATTERN = "user:reservation:%d:%s";
    private static final String SPACE_CLAIM_KEY_PATTERN = "space:claim:%d:%s";
    private static final String ACCOUNT_BALANCE_KEY_PATTERN = "account:balance:%d";
    private static final String USER_PLATE_KEY_PATTERN = "user:plate:%d";

    private static final long USER_RESERVATION_TTL_HOURS = 24L;
    private static final long USER_PLATE_TTL_HOURS = 24L;
    private static final long AVAILABLE_SLOTS_TTL_MINUTES = 30L;
    private static final long SPACE_CLAIM_TTL_MINUTES = 30L;
    private static final long ACCOUNT_BALANCE_TTL_HOURS = 24L;

    private final RedissonClient redissonClient;
    private final ParkingSpaceRepository parkingSpaceRepository;
    private final AccountRepository accountRepository;
    private final ObjectMapper objectMapper;

    /**
     * Single-flight loader: one thread hits DB, others wait then reuse cache.
     *
     * @return supplier result, or null if lock could not be acquired (caller may retry cache / DB)
     */
    public <T> T computeWithSingleFlightLock(String lockKey, Supplier<T> loader) {
        RLock lock = redissonClient.getLock(lockKey);
        if (!acquireLoadLock(lock)) {
            return null;
        }
        try {
            return loader.get();
        } finally {
            releaseLoadLock(lock);
        }
    }

    /**
     * Atomic Redis claims + cache validation before DB transaction.
     */
    public void tryReserveClaims(Long userId, Long spaceId, LocalDate reservationDate, BigDecimal requiredAmount) {
        // 1. Space claimed check — 1 Redis GET, reject 99%+ requests after slots fill
        if (isSpaceClaimed(spaceId, reservationDate)) {
            throw new BusinessException(
                    "This parking slot has already been reserved for " + reservationDate,
                    HttpStatus.CONFLICT);
        }

        // 2. User already reserved check — read-only, fail fast before heavy ops
        if (isUserReservationClaimed(userId, reservationDate)) {
            throw new BusinessException("You already have a reservation for " + reservationDate);
        }

        // 3. ZSET check — reject if space not in available set (read-only)
        assertSpaceAvailableInCacheReadOnly(spaceId, reservationDate);

        // 4. Balance check — read-only, no side effect
        assertSufficientBalanceFromCache(userId, requiredAmount);

        // 5. Side-effect claims — only reached if all read checks pass
        if (!tryClaimUserReservation(userId, reservationDate)) {
            throw new BusinessException("You already have a reservation for " + reservationDate);
        }

        try {
            tryClaimSpaceInRedis(spaceId, reservationDate);
        } catch (RuntimeException ex) {
            releaseUserReservationClaim(userId, reservationDate);
            throw ex;
        }
    }

    public void releaseReserveClaims(Long userId, Long spaceId, LocalDate reservationDate) {
        releaseUserReservationClaim(userId, reservationDate);
        releaseSpaceClaim(spaceId, reservationDate);
    }

    public void invalidateSpacesListCache() {
        redissonClient.getBucket(SPACES_LIST_CACHE_KEY).delete();
    }

    public void cacheUserPlate(Long userId, String plateNumber) {
        if (plateNumber == null || plateNumber.isBlank()) {
            return;
        }
        redissonClient.getBucket(userPlateKey(userId))
                .set(plateNumber.trim().toUpperCase(), USER_PLATE_TTL_HOURS, TimeUnit.HOURS);
    }

    public String getUserPlateFromCache(Long userId) {
        RBucket<String> bucket = redissonClient.getBucket(userPlateKey(userId));
        String plate = bucket.get();
        return (plate != null && !plate.isBlank()) ? plate : null;
    }

    public void setBalanceCache(Long userId, BigDecimal balance) {
        redissonClient.getBucket(accountBalanceKey(userId))
                .set(balance.toPlainString(), ACCOUNT_BALANCE_TTL_HOURS, TimeUnit.HOURS);
    }

    public void deductBalanceCache(Long userId, BigDecimal amount) {
        // Single GET — skip separate isExists() call
        RBucket<String> bucket = redissonClient.getBucket(accountBalanceKey(userId));
        String current = bucket.get();
        if (current == null) {
            return; // cache miss — skip
        }
        try {
            BigDecimal newBalance = new BigDecimal(current).subtract(amount);
            bucket.set(newBalance.toPlainString(), ACCOUNT_BALANCE_TTL_HOURS, TimeUnit.HOURS);
        } catch (NumberFormatException ex) {
            log.warn("Invalid balance cache for userId={}, deleting key", userId);
            bucket.delete();
        }
    }

    public void warmAvailableSlotsIfMissing(RScoredSortedSet<Long> availableSlots) {
        if (availableSlots.isExists() && !availableSlots.isEmpty()) {
            touchAvailableSlotsTtl(availableSlots);
            return;
        }

        Boolean warmed = computeWithSingleFlightLock(LOCK_AVAILABLE_SLOTS, () -> {
            if (availableSlots.isExists() && !availableSlots.isEmpty()) {
                touchAvailableSlotsTtl(availableSlots);
                return true;
            }
            loadAvailableSlotsFromDb(availableSlots);
            return true;
        });

        if (warmed == null && availableSlots.isExists() && !availableSlots.isEmpty()) {
            touchAvailableSlotsTtl(availableSlots);
        }
    }

    private void loadAvailableSlotsFromDb(RScoredSortedSet<Long> availableSlots) {
        List<Long> availableIds = parkingSpaceRepository.findAvailableSpaceIds();
        if (availableIds.isEmpty()) {
            return;
        }
        Set<Long> uniqueOrderedIds = new LinkedHashSet<>(availableIds);
        for (Long id : uniqueOrderedIds) {
            availableSlots.add(id.doubleValue(), id);
        }
        touchAvailableSlotsTtl(availableSlots);
        log.debug("Warmed available-slots cache with {} entries from DB (TTL {} min)",
                uniqueOrderedIds.size(), AVAILABLE_SLOTS_TTL_MINUTES);
    }

    private void assertSufficientBalanceFromCache(Long userId, BigDecimal requiredAmount) {
        // Single GET — no separate isExists() call
        String balanceRaw = redissonClient.<String>getBucket(accountBalanceKey(userId)).get();
        if (balanceRaw != null) {
            assertBalanceMeetsRequired(balanceRaw, requiredAmount);
        }
        // Cache miss → skip pre-check, worker enforces correctness on CONFIRM
    }

    private void assertBalanceMeetsRequired(String balanceRaw, BigDecimal requiredAmount) {
        try {
            BigDecimal balance = new BigDecimal(balanceRaw);
            if (balance.compareTo(requiredAmount) < 0) {
                throw new BusinessException(
                        "Insufficient balance. Required: $" + requiredAmount + ", Available: $" + balance);
            }
        } catch (NumberFormatException ex) {
            log.warn("Invalid balance value in cache, ignoring pre-check");
        }
    }

    private boolean isUserReservationClaimed(Long userId, LocalDate reservationDate) {
        return redissonClient.getBucket(userReservationKey(userId, reservationDate)).isExists();
    }

    private boolean tryClaimUserReservation(Long userId, LocalDate reservationDate) {
        return redissonClient.getBucket(userReservationKey(userId, reservationDate))
                .trySet("1", USER_RESERVATION_TTL_HOURS, TimeUnit.HOURS);
    }

    private void releaseUserReservationClaim(Long userId, LocalDate reservationDate) {
        redissonClient.getBucket(userReservationKey(userId, reservationDate)).delete();
    }

    /**
     * Read-only space availability check — no claims, no side effects.
     * isSpaceClaimed already done before this call.
     */
    private void assertSpaceAvailableInCacheReadOnly(Long spaceId, LocalDate reservationDate) {
        // Warm ZSET if missing — single-flight, only one instance hits DB
        RScoredSortedSet<Long> availableSlots = redissonClient.getScoredSortedSet(AVAILABLE_SLOTS_KEY);
        if (!availableSlots.isExists()) {
            warmAvailableSlotsIfMissing(availableSlots);
        }

        if (assertSpaceFromAvailableZset(spaceId)) {
            return;
        }
        if (assertSpaceFromListCache(spaceId)) {
            return;
        }

        assertSpaceAvailableFromDbWithLock(spaceId, reservationDate);
    }

    private boolean assertSpaceFromListCache(Long spaceId) {
        RBucket<String> listBucket = redissonClient.getBucket(SPACES_LIST_CACHE_KEY);
        String cachedList = listBucket.get();
        if (cachedList == null || cachedList.isBlank()) {
            return false;
        }
        try {
            List<ParkingSpaceResponse> spaces = objectMapper.readValue(
                    cachedList, new TypeReference<List<ParkingSpaceResponse>>() {});
            for (ParkingSpaceResponse space : spaces) {
                if (space.getId() != null && space.getId().equals(spaceId)) {
                    if ("RESERVED".equalsIgnoreCase(space.getStatus())) {
                        throw new BusinessException(
                                "Parking slot is no longer available",
                                HttpStatus.CONFLICT);
                    }
                    return true;
                }
            }
            throw new BusinessException("Parking space not found: " + spaceId, HttpStatus.NOT_FOUND);
        } catch (JsonProcessingException ex) {
            log.warn("Failed to parse spaces list cache, falling back to zset/DB", ex);
            return false;
        }
    }

    private boolean assertSpaceFromAvailableZset(Long spaceId) {
        RScoredSortedSet<Long> availableSlots = redissonClient.getScoredSortedSet(AVAILABLE_SLOTS_KEY);
        // isExists() = single EXISTS call
        if (!availableSlots.isExists()) {
            return false; // ZSET not warmed yet — fall through to DB
        }
        // contains() = single ZSCORE call — skip separate isEmpty() check
        if (!availableSlots.contains(spaceId)) {
            throw new BusinessException("Parking slot is no longer available", HttpStatus.CONFLICT);
        }
        return true;
    }

    private void assertSpaceAvailableFromDbWithLock(Long spaceId, LocalDate reservationDate) {
        RLock lock = redissonClient.getLock(String.format(LOCK_SPACE_STATUS_PATTERN, spaceId));
        if (!acquireLoadLock(lock)) {
            if (assertSpaceFromAvailableZset(spaceId) || assertSpaceFromListCache(spaceId)) {
                return;
            }
            throw new BusinessException("System busy, please retry", HttpStatus.TOO_MANY_REQUESTS);
        }
        try {
            if (isSpaceClaimed(spaceId, reservationDate)) {
                throw new BusinessException(
                        "This parking slot has already been reserved for " + reservationDate,
                        HttpStatus.CONFLICT);
            }
            if (assertSpaceFromAvailableZset(spaceId) || assertSpaceFromListCache(spaceId)) {
                return;
            }

            ParkingSpace space = parkingSpaceRepository.findById(spaceId)
                    .orElseThrow(() -> new BusinessException(
                            "Parking space not found: " + spaceId, HttpStatus.NOT_FOUND));
            if (space.getStatus() == ParkingSpaceStatus.RESERVED) {
                throw new BusinessException(
                        "Parking slot is no longer available",
                        HttpStatus.CONFLICT);
            }
            log.debug("Validated spaceId={} from DB under single-flight lock", spaceId);
        } finally {
            releaseLoadLock(lock);
        }
    }

    private boolean acquireLoadLock(RLock lock) {
        try {
            if (lock.tryLock(0, lockLeaseSeconds, TimeUnit.SECONDS)) {
                return true;
            }
            if (lockWaitSeconds <= 0) {
                return false;
            }
            return lock.tryLock(lockWaitSeconds, lockLeaseSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void releaseLoadLock(RLock lock) {
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    private void tryClaimSpaceInRedis(Long spaceId, LocalDate reservationDate) {
        // trySet is atomic SETNX — no need for isSpaceClaimed() check before (already done in assertSpaceAvailableInCache)
        String claimKey = spaceClaimKey(spaceId, reservationDate);
        boolean claimed = redissonClient.getBucket(claimKey)
                .trySet("1", SPACE_CLAIM_TTL_MINUTES, TimeUnit.MINUTES);
        if (!claimed) {
            throw new BusinessException(
                    "This parking slot has already been reserved for " + reservationDate,
                    HttpStatus.CONFLICT);
        }

        RScoredSortedSet<Long> availableSlots = redissonClient.getScoredSortedSet(AVAILABLE_SLOTS_KEY);
        availableSlots.remove(spaceId);
        log.debug("Claimed spaceId={} for date={} (Redis only)", spaceId, reservationDate);
    }

    public void releaseSpaceClaim(Long spaceId, LocalDate reservationDate) {
        redissonClient.getBucket(spaceClaimKey(spaceId, reservationDate)).delete();
        RScoredSortedSet<Long> availableSlots = redissonClient.getScoredSortedSet(AVAILABLE_SLOTS_KEY);
        availableSlots.add(spaceId.doubleValue(), spaceId);
        touchAvailableSlotsTtl(availableSlots);
        log.debug("Released space claim spaceId={} for date={}", spaceId, reservationDate);
    }

    private boolean isSpaceClaimed(Long spaceId, LocalDate reservationDate) {
        return redissonClient.getBucket(spaceClaimKey(spaceId, reservationDate)).isExists();
    }

    private void touchAvailableSlotsTtl(RScoredSortedSet<Long> availableSlots) {
        availableSlots.expire(AVAILABLE_SLOTS_TTL_MINUTES, TimeUnit.MINUTES);
    }

    private String userReservationKey(Long userId, LocalDate reservationDate) {
        return String.format(USER_RESERVATION_KEY_PATTERN, userId, reservationDate);
    }

    private String spaceClaimKey(Long spaceId, LocalDate reservationDate) {
        return String.format(SPACE_CLAIM_KEY_PATTERN, spaceId, reservationDate);
    }

    private String accountBalanceKey(Long userId) {
        return String.format(ACCOUNT_BALANCE_KEY_PATTERN, userId);
    }

    private String userPlateKey(Long userId) {
        return String.format(USER_PLATE_KEY_PATTERN, userId);
    }
}
