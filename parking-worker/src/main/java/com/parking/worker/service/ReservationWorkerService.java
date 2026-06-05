package com.parking.worker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.parking.worker.entity.ParkingSpace;
import com.parking.worker.entity.ParkingSpaceStatus;
import com.parking.worker.entity.Reservation;
import com.parking.worker.entity.ReservationStatus;
import com.parking.worker.entity.Account;
import com.parking.worker.repository.AccountRepository;
import com.parking.worker.repository.ParkingSpaceRepository;
import com.parking.worker.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class ReservationWorkerService {

    private static final String PROCESSED_KEY_PREFIX = "processed:msg:";
    private static final String PROCESSING_KEY_PREFIX = "processing:msg:";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final Duration PROCESSING_LOCK_TTL = Duration.ofMinutes(2);
    private static final String SPACES_CACHE_KEY = "parking:spaces:all";
    private static final String AVAILABLE_SLOTS_KEY = "parking:spaces:available";
    private static final String SPACE_CLAIM_KEY_PATTERN = "space:claim:%d:%s";
    private static final String ACCOUNT_BALANCE_KEY_PATTERN = "account:balance:%d";
    private static final long SPACES_CACHE_TTL_SECONDS = 30L;
    private static final long AVAILABLE_SLOTS_TTL_MINUTES = 30L;
    private static final long ACCOUNT_BALANCE_TTL_HOURS = 24L;

    private final ParkingSpaceRepository parkingSpaceRepository;
    private final ReservationRepository reservationRepository;
    private final AccountRepository accountRepository;
    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;

    /**
     * Processes a decoded Pub/Sub message payload.
     *
     * @param messageId unique Pub/Sub message ID used for idempotency
     * @param payload   JSON string, e.g. {"reservationId":1,"spaceId":4}
     */
    public void process(String messageId, String payload) {
        log.info("Processing message id={} payload={}", messageId, payload);

        String idempotencyKey = resolveIdempotencyKey(payload, messageId);

        // 1. Idempotency check via Redis (already processed)
        String processedKey = PROCESSED_KEY_PREFIX + idempotencyKey;
        RBucket<String> processedBucket = redissonClient.getBucket(processedKey);
        if (processedBucket.isExists()) {
            log.info("Idempotency key={} already processed (idempotency hit). Skipping.", idempotencyKey);
            return;
        }

        // 2. Atomic claim for in-flight processing across instances
        String processingKey = PROCESSING_KEY_PREFIX + idempotencyKey;
        RBucket<String> processingBucket = redissonClient.getBucket(processingKey);
        boolean claimed = processingBucket.trySet(
                "processing",
                PROCESSING_LOCK_TTL.toSeconds(),
                TimeUnit.SECONDS
        );
        if (!claimed) {
            log.info("Idempotency key={} is being processed by another instance. Skipping.", idempotencyKey);
            return;
        }

        boolean completed = false;

        try {
            // 3. Parse payload
            Long reservationId;
            Long spaceId;
            String plateNumber;
            JsonNode node = objectMapper.readTree(payload);
            reservationId = node.path("reservationId").asLong();
            spaceId = node.path("spaceId").asLong();
            plateNumber = node.path("plateNumber").asText("");
            if (reservationId <= 0 || spaceId <= 0) {
                throw new IllegalArgumentException("reservationId/spaceId must be positive");
            }

            // 4. Load reservation; skip if not PENDING
            Optional<Reservation> optReservation = reservationRepository.findById(reservationId);
            if (optReservation.isEmpty()) {
                log.warn("Reservation id={} not found. Skipping message id={}.", reservationId, messageId);
                markProcessed(processedBucket);
                completed = true;
                return;
            }
            Reservation reservation = optReservation.get();
            if (reservation.getStatus() != ReservationStatus.PENDING) {
                log.info("Reservation id={} status={} (not PENDING). Skipping message id={}.",
                        reservationId, reservation.getStatus(), messageId);
                markProcessed(processedBucket);
                completed = true;
                return;
            }

            // 4.1 FCFS guard: only the earliest pending reservation for this slot/date may compete for winning.
            Optional<Reservation> earliestPending = reservationRepository.findFirstPendingForSpaceAndDate(
                    spaceId,
                    reservation.getReservationDate(),
                    ReservationStatus.PENDING.name()
            );
            if (earliestPending.isPresent() && !earliestPending.get().getId().equals(reservationId)) {
                Long oldestPendingId = earliestPending.get().getId();
                // Do not cancel out-of-order items; defer by throwing so subscriber nacks and retries later.
                throw new OutOfOrderReservationException(
                        "Out-of-order message. Older pending reservation id=" + oldestPendingId + " exists"
                );
            }

            // 5. FCFS via queue: first PENDING wins the slot, others are rejected
            Optional<ParkingSpace> optSpace = parkingSpaceRepository.findByIdForUpdate(spaceId);
            if (optSpace.isEmpty()) {
                // API did not deduct DB balance — cancel without refund
                cancelReservationNoRefund(reservation, "Space not found: " + spaceId);
                markProcessed(processedBucket);
                completed = true;
                invalidateSpacesCache();
                return;
            }

            ParkingSpace space = optSpace.get();

            if (space.getStatus() == ParkingSpaceStatus.AVAILABLE) {
                // Deduct balance in DB here (API no longer deducts synchronously)
                boolean balanceOk = deductAccountBalance(reservation.getUserId(), reservation.getAmount());
                if (!balanceOk) {
                    cancelReservationNoRefund(reservation, "Insufficient balance at confirmation time");
                    log.info("Reservation id={} CANCELLED — insufficient balance for userId={}", reservationId, reservation.getUserId());
                } else {
                    space.setStatus(ParkingSpaceStatus.RESERVED);
                    parkingSpaceRepository.save(space);
                    reservation.setSpaceId(space.getId());
                    reservation.setStatus(ReservationStatus.CONFIRMED);
                    reservationRepository.save(reservation);
                    removeFromAvailableCache(space.getId());
                    updateSpacesCacheAsReserved(space.getId(), plateNumber);
                    log.info("Reservation id={} CONFIRMED with space id={} (FCFS winner)", reservationId, space.getId());
                }
            } else {
                // Slot taken — no refund needed since API did not deduct DB balance
                cancelReservationNoRefund(reservation, "Slot is no longer available");
                log.info("Reservation id={} CANCELLED — slot id={} already taken", reservationId, spaceId);
            }

            markProcessed(processedBucket);
            completed = true;
        } catch (OutOfOrderReservationException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to process message id={}: {}", messageId, e.getMessage(), e);
            throw new RuntimeException("Failed to process message: " + e.getMessage(), e);
        } finally {
            // Allow retries to claim and re-process on failures.
            processingBucket.delete();
        }
    }

    private void markProcessed(RBucket<String> bucket) {
        bucket.set("done", IDEMPOTENCY_TTL);
    }

    /**
     * Cancel reservation WITHOUT refunding — used when API has not deducted DB balance.
     * Redis balance cache is restored by releasing the space claim in ReservationCacheGuard.
     */
    private void cancelReservationNoRefund(Reservation reservation, String reason) {
        log.warn("Cancelling reservation id={} (no refund): {}", reservation.getId(), reason);
        reservation.setStatus(ReservationStatus.CANCELLED);
        reservationRepository.save(reservation);
        maybeReleaseSpaceClaim(reservation);
        invalidateSpacesCache();
    }

    /**
     * Cancel reservation WITH refund — used when balance was already deducted from DB
     * (e.g. max-retry compensation path where deduction happened in worker).
     */
    private void rejectReservation(Reservation reservation, String reason) {
        log.warn("Rejecting reservation id={}: {}", reservation.getId(), reason);
        refundAccount(reservation.getUserId(), reservation.getAmount());
        reservation.setStatus(ReservationStatus.CANCELLED);
        reservationRepository.save(reservation);
        maybeReleaseSpaceClaim(reservation);
        invalidateSpacesCache();
    }

    private void compensateAndCancelReservation(Reservation reservation, String reason) {
        restoreSpaceToAvailableOnCompensation(reservation);
        cancelReservationNoRefund(reservation, reason);
    }

    /**
     * Max-retry compensation: set DB space back to AVAILABLE when safe,
     * then release Redis claim and refresh availability caches.
     */
    private void restoreSpaceToAvailableOnCompensation(Reservation reservation) {
        Long spaceId = reservation.getSpaceId();
        if (spaceId == null || reservation.getReservationDate() == null) {
            return;
        }

        Optional<ParkingSpace> optSpace = parkingSpaceRepository.findByIdForUpdate(spaceId);
        if (optSpace.isEmpty()) {
            log.warn("Compensation skip DB restore — space id={} not found", spaceId);
            releaseSpaceClaimAndCaches(spaceId, reservation.getReservationDate());
            return;
        }

        if (reservationRepository.existsConfirmedForSpaceExcluding(spaceId, reservation.getId())) {
            log.info("Compensation skip DB restore — space id={} held by another CONFIRMED reservation", spaceId);
            releaseSpaceClaimAndCaches(spaceId, reservation.getReservationDate());
            return;
        }

        ParkingSpace space = optSpace.get();
        if (space.getStatus() != ParkingSpaceStatus.AVAILABLE) {
            space.setStatus(ParkingSpaceStatus.AVAILABLE);
            parkingSpaceRepository.save(space);
            log.warn("Compensation restored space id={} to AVAILABLE in DB", spaceId);
        }

        releaseSpaceClaimAndCaches(spaceId, reservation.getReservationDate());
        updateSpacesCacheAsAvailable(spaceId);
    }

    private void releaseSpaceClaimAndCaches(Long spaceId, java.time.LocalDate reservationDate) {
        redissonClient.getBucket(spaceClaimKey(spaceId, reservationDate)).delete();
        addSpaceBackToAvailableCache(spaceId);
        log.debug("Released space claim and refreshed available cache for spaceId={} date={}",
                spaceId, reservationDate);
    }

    /**
     * Deduct balance from DB. Returns true if successful, false if insufficient funds.
     */
    private boolean deductAccountBalance(Long userId, BigDecimal amount) {
        if (userId == null || amount == null) {
            return false;
        }
        return accountRepository.findByUserIdForUpdate(userId).map(account -> {
            if (account.getBalance().compareTo(amount) < 0) {
                return false;
            }
            BigDecimal newBalance = account.getBalance().subtract(amount);
            account.setBalance(newBalance);
            accountRepository.save(account);
            // Update Redis balance cache after DB deduction
            updateBalanceCache(userId, newBalance);
            return true;
        }).orElse(false);
    }

    private void updateBalanceCache(Long userId, BigDecimal balance) {
        redissonClient.getBucket(String.format(ACCOUNT_BALANCE_KEY_PATTERN, userId))
                .set(balance.toPlainString(), ACCOUNT_BALANCE_TTL_HOURS, TimeUnit.HOURS);
    }

    private void refundAccount(Long userId, BigDecimal amount) {
        if (userId == null || amount == null) {
            return;
        }
        accountRepository.findByUserIdForUpdate(userId).ifPresent(account -> {
            BigDecimal newBalance = account.getBalance().add(amount);
            account.setBalance(newBalance);
            accountRepository.save(account);
            updateBalanceCache(userId, newBalance);
        });
    }

    private void invalidateSpacesCache() {
        redissonClient.getBucket(SPACES_CACHE_KEY).delete();
    }

    private void removeFromAvailableCache(Long spaceId) {
        RScoredSortedSet<Long> availableSlots = redissonClient.getScoredSortedSet(AVAILABLE_SLOTS_KEY);
        availableSlots.remove(spaceId);
        availableSlots.expire(AVAILABLE_SLOTS_TTL_MINUTES, TimeUnit.MINUTES);
    }

    private void addSpaceBackToAvailableCache(Long spaceId) {
        RScoredSortedSet<Long> availableSlots = redissonClient.getScoredSortedSet(AVAILABLE_SLOTS_KEY);
        availableSlots.add(spaceId.doubleValue(), spaceId);
        availableSlots.expire(AVAILABLE_SLOTS_TTL_MINUTES, TimeUnit.MINUTES);
    }

    /**
     * Release API Redis slot claim when reservation is cancelled and space is still AVAILABLE in DB.
     */
    private void maybeReleaseSpaceClaim(Reservation reservation) {
        Long spaceId = reservation.getSpaceId();
        if (spaceId == null || reservation.getReservationDate() == null) {
            return;
        }
        parkingSpaceRepository.findById(spaceId).ifPresent(space -> {
            if (space.getStatus() == ParkingSpaceStatus.AVAILABLE) {
                redissonClient.getBucket(spaceClaimKey(spaceId, reservation.getReservationDate())).delete();
                addSpaceBackToAvailableCache(spaceId);
                log.debug("Released space claim for spaceId={} date={}", spaceId, reservation.getReservationDate());
            }
        });
    }

    private String spaceClaimKey(Long spaceId, java.time.LocalDate reservationDate) {
        return String.format(SPACE_CLAIM_KEY_PATTERN, spaceId, reservationDate);
    }

    private void updateSpacesCacheAsReserved(Long spaceId, String plateNumber) {
        updateSpacesCache(spaceId, ParkingSpaceStatus.RESERVED.name(), maskPlateNumber(plateNumber));
    }

    private void updateSpacesCacheAsAvailable(Long spaceId) {
        updateSpacesCache(spaceId, ParkingSpaceStatus.AVAILABLE.name(), null);
    }

    private void updateSpacesCache(Long spaceId, String status, String occupantPlate) {
        if (spaceId == null) {
            return;
        }
        RBucket<String> bucket = redissonClient.getBucket(SPACES_CACHE_KEY);
        String cachedPayload = bucket.get();
        if (cachedPayload == null || cachedPayload.isBlank()) {
            return;
        }

        try {
            JsonNode root = objectMapper.readTree(cachedPayload);
            if (!(root instanceof ArrayNode arrayNode)) {
                return;
            }

            for (JsonNode node : arrayNode) {
                if (node.path("id").asLong() == spaceId && node instanceof ObjectNode objectNode) {
                    objectNode.put("status", status);
                    if (occupantPlate == null) {
                        objectNode.putNull("occupantPlate");
                    } else {
                        objectNode.put("occupantPlate", occupantPlate);
                    }
                    break;
                }
            }
            bucket.set(objectMapper.writeValueAsString(arrayNode), SPACES_CACHE_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception ex) {
            log.warn("Failed to update spaces cache for spaceId={}", spaceId, ex);
            invalidateSpacesCache();
        }
    }

    private String maskPlateNumber(String plateNumber) {
        if (plateNumber == null || plateNumber.isBlank()) {
            return "*****";
        }
        String normalized = plateNumber.trim().toUpperCase();
        if (normalized.length() <= 3) {
            return "*****" + normalized;
        }
        return "*****" + normalized.substring(normalized.length() - 3);
    }

    private String resolveIdempotencyKey(String payload, String fallbackMessageId) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            long reservationId = node.path("reservationId").asLong(0L);
            if (reservationId > 0) {
                return "reservation:" + reservationId;
            }
        } catch (Exception ignored) {
            // fallback to Pub/Sub message id
        }
        return "message:" + fallbackMessageId;
    }

    public void compensateOnMaxRetry(String payload, String reason) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            long reservationId = node.path("reservationId").asLong(0L);
            if (reservationId <= 0) {
                log.error("Skip max-retry compensation due to invalid reservationId in payload. reason={}", reason);
                return;
            }

            Optional<Reservation> optReservation = reservationRepository.findById(reservationId);
            if (optReservation.isEmpty()) {
                log.warn("Skip max-retry compensation because reservation id={} was not found.", reservationId);
                return;
            }

            Reservation reservation = optReservation.get();
            if (reservation.getStatus() != ReservationStatus.PENDING) {
                log.info("Skip max-retry compensation for reservation id={} because status={} (not PENDING).",
                        reservationId, reservation.getStatus());
                return;
            }

            compensateAndCancelReservation(reservation, "Max retry reached: " + reason);
            invalidateSpacesCache();
            log.warn("Max-retry compensation completed for reservation id={}", reservationId);
        } catch (Exception ex) {
            throw new RuntimeException("Failed max-retry compensation: " + ex.getMessage(), ex);
        }
    }
}
